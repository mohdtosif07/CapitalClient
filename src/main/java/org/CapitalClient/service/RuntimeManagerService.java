
package org.CapitalClient.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class RuntimeManagerService {

    private static final String RUNTIME_INDEX_URL =
            "https://piston-meta.mojang.com/v1/products/java-runtime/"
                    + "2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

    private static final String PLATFORM = "windows-x64";

    private final GameDirectoryService directories;

    private final HttpClient httpClient;

    private final ExecutorService executor =
            Executors.newFixedThreadPool(8);

    public RuntimeManagerService() {
        this(new GameDirectoryService());
    }

    public RuntimeManagerService(GameDirectoryService directories) {
        this.directories = directories;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public CompletableFuture<RuntimeResult> installForVersion(
            String minecraftVersion,
            ProgressListener listener
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return installBlocking(minecraftVersion, listener);
            } catch (Exception exception) {
                throw new RuntimeException(
                        "Java runtime installation failed: "
                                + exception.getMessage(),
                        exception
                );
            }
        }, executor);
    }

    private RuntimeResult installBlocking(
            String minecraftVersion,
            ProgressListener listener
    ) throws Exception {

        notify(listener, 0, 0, "Reading Minecraft Java requirements...");

        Path versionJsonPath =
                directories.getVersionJson(minecraftVersion);

        if (!Files.exists(versionJsonPath)) {
            throw new IOException(
                    "Minecraft version metadata not found. Install Minecraft first."
            );
        }

        JsonObject versionJson = JsonParser.parseString(
                Files.readString(versionJsonPath)
        ).getAsJsonObject();

        String component = "jre-legacy";

        if (versionJson.has("javaVersion")
                && versionJson.get("javaVersion").isJsonObject()) {

            JsonObject javaVersion =
                    versionJson.getAsJsonObject("javaVersion");

            if (javaVersion.has("component")) {
                component = javaVersion.get("component").getAsString();
            }
        }

        notify(
                listener,
                0,
                0,
                "Required Java runtime: " + component
        );

        JsonObject runtimeIndex = fetchJson(RUNTIME_INDEX_URL);

        if (!runtimeIndex.has(PLATFORM)) {
            throw new IOException(
                    "Mojang runtime index does not contain Windows x64."
            );
        }

        JsonObject platformRuntimes =
                runtimeIndex.getAsJsonObject(PLATFORM);

        if (!platformRuntimes.has(component)) {
            throw new IOException(
                    "Java runtime component is unavailable: " + component
            );
        }

        var releases =
                platformRuntimes.getAsJsonArray(component);

        if (releases == null || releases.isEmpty()) {
            throw new IOException(
                    "No runtime release found for " + component
            );
        }

        JsonObject selectedRelease = null;

        for (JsonElement element : releases) {
            JsonObject release = element.getAsJsonObject();

            if (release.has("manifest")
                    && release.get("manifest").isJsonObject()) {
                selectedRelease = release;
                break;
            }
        }

        if (selectedRelease == null) {
            throw new IOException(
                    "No usable runtime manifest found for " + component
            );
        }

        JsonObject manifestInfo =
                selectedRelease.getAsJsonObject("manifest");

        String manifestUrl =
                manifestInfo.get("url").getAsString();

        String manifestSha1 =
                manifestInfo.has("sha1")
                        ? manifestInfo.get("sha1").getAsString()
                        : null;

        notify(listener, 0, 0, "Fetching Java runtime manifest...");

        JsonObject runtimeManifest = fetchJson(manifestUrl);

        Path runtimeDirectory =
                directories.getRuntimeDirectory()
                        .resolve(component)
                        .resolve(PLATFORM);

        Files.createDirectories(runtimeDirectory);

        List<RuntimeFileTask> tasks = new ArrayList<>();

        if (!runtimeManifest.has("files")) {
            throw new IOException("Runtime manifest has no files section.");
        }

        JsonObject files = runtimeManifest.getAsJsonObject("files");

        for (Map.Entry<String, JsonElement> entry : files.entrySet()) {

            String relativePath = entry.getKey();

            JsonObject fileInfo = entry.getValue().getAsJsonObject();

            String type = getString(fileInfo, "type");

            Path destination = safeResolve(
                    runtimeDirectory,
                    relativePath
            );

            if ("directory".equals(type)) {
                Files.createDirectories(destination);
                continue;
            }

            if (!"file".equals(type)) {
                continue;
            }

            JsonObject downloads = getObject(fileInfo, "downloads");

            if (downloads == null) {
                throw new IOException(
                        "Runtime file has no download information: "
                                + relativePath
                );
            }

            JsonObject raw = getObject(downloads, "raw");

            if (raw == null) {
                throw new IOException(
                        "Runtime raw download is missing: " + relativePath
                );
            }

            String url = getString(raw, "url");
            String sha1 = getString(raw, "sha1");

            if (url == null) {
                throw new IOException(
                        "Runtime file URL is missing: " + relativePath
                );
            }

            long size = raw.has("size")
                    ? raw.get("size").getAsLong()
                    : -1;

            tasks.add(new RuntimeFileTask(
                    url,
                    destination,
                    sha1,
                    size
            ));
        }

        if (tasks.isEmpty()) {
            throw new IOException("Runtime manifest contains no files.");
        }

        downloadTasks(tasks, listener);

        Path javaExecutable =
                runtimeDirectory.resolve("bin").resolve("javaw.exe");

        if (!Files.exists(javaExecutable)) {
            javaExecutable =
                    runtimeDirectory.resolve("bin").resolve("java.exe");
        }

        if (!Files.exists(javaExecutable)) {
            throw new IOException(
                    "Java executable was not found in the installed runtime."
            );
        }

        notify(
                listener,
                1,
                1,
                "Java runtime " + component + " installed successfully."
        );

        return new RuntimeResult(
                component,
                javaExecutable,
                runtimeDirectory
        );
    }

    private void downloadTasks(
            List<RuntimeFileTask> tasks,
            ProgressListener listener
    ) throws Exception {

        AtomicInteger completed = new AtomicInteger();
        int total = tasks.size();

        notify(
                listener,
                0,
                total,
                "Downloading Java runtime files..."
        );

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (RuntimeFileTask task : tasks) {

            CompletableFuture<Void> future =
                    CompletableFuture.runAsync(() -> {
                        try {
                            downloadFile(task);

                            int done = completed.incrementAndGet();

                            notify(
                                    listener,
                                    done,
                                    total,
                                    "Downloading Java: " + done + "/" + total
                            );

                        } catch (Exception exception) {
                            throw new RuntimeException(exception);
                        }
                    }, executor);

            futures.add(future);
        }

        try {
            CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0])
            ).join();

        } catch (Exception exception) {
            futures.forEach(future -> future.cancel(true));

            throw new IOException(
                    "One or more Java runtime files failed to download.",
                    exception
            );
        }
    }

    private void downloadFile(RuntimeFileTask task) throws Exception {

        Files.createDirectories(task.destination().getParent());

        if (isValid(
                task.destination(),
                task.sha1(),
                task.size()
        )) {
            return;
        }

        Path temporary = task.destination().resolveSibling(
                task.destination().getFileName() + ".download"
        );

        Files.deleteIfExists(temporary);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(task.url()))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "CapitalClient/1.0")
                .GET()
                .build();

        HttpResponse<Path> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofFile(temporary)
        );

        if (response.statusCode() != 200) {
            Files.deleteIfExists(temporary);

            throw new IOException(
                    "Java download failed. HTTP "
                            + response.statusCode()
                            + " URL: " + task.url()
            );
        }

        if (!isValid(temporary, task.sha1(), task.size())) {
            Files.deleteIfExists(temporary);

            throw new IOException(
                    "Java runtime verification failed: "
                            + task.destination().getFileName()
            );
        }

        Files.move(
                temporary,
                task.destination(),
                StandardCopyOption.REPLACE_EXISTING
        );
    }

    private boolean isValid(
            Path file,
            String expectedSha1,
            long expectedSize
    ) throws Exception {

        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            return false;
        }

        if (expectedSize >= 0 && Files.size(file) != expectedSize) {
            return false;
        }

        if (expectedSha1 == null || expectedSha1.isBlank()) {
            return true;
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-1");

        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;

            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        String actual = HexFormat.of().formatHex(digest.digest());

        return actual.equalsIgnoreCase(expectedSha1);
    }

    private JsonObject fetchJson(String url) throws Exception {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", "CapitalClient/1.0")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200) {
            throw new IOException(
                    "Metadata request failed. HTTP "
                            + response.statusCode()
            );
        }

        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private Path safeResolve(
            Path base,
            String relativePath
    ) throws IOException {

        Path normalizedBase = base.toAbsolutePath().normalize();

        Path resolved = normalizedBase
                .resolve(relativePath)
                .normalize();

        if (!resolved.startsWith(normalizedBase)) {
            throw new IOException(
                    "Unsafe runtime path: " + relativePath
            );
        }

        return resolved;
    }

    private JsonObject getObject(
            JsonObject object,
            String key
    ) {
        if (object == null || !object.has(key)
                || !object.get(key).isJsonObject()) {
            return null;
        }

        return object.getAsJsonObject(key);
    }

    private String getString(
            JsonObject object,
            String key
    ) {
        if (object == null || !object.has(key)
                || object.get(key).isJsonNull()) {
            return null;
        }

        return object.get(key).getAsString();
    }

    private void notify(
            ProgressListener listener,
            int completed,
            int total,
            String message
    ) {
        if (listener != null) {
            listener.onProgress(completed, total, message);
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private record RuntimeFileTask(
            String url,
            Path destination,
            String sha1,
            long size
    ) {}

    public record RuntimeResult(
            String component,
            Path javaExecutable,
            Path runtimeDirectory
    ) {}

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(int completed, int total, String message);
    }
}
