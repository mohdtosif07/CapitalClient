
package org.CapitalClient.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class MinecraftInstallerService {

    private static final String MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private final GameDirectoryService directories;
    private final HttpClient httpClient;

    private final ExecutorService downloadExecutor =
            Executors.newFixedThreadPool(8);

    public MinecraftInstallerService() {
        this(new GameDirectoryService());
    }

    public MinecraftInstallerService(GameDirectoryService directories) {
        this.directories = directories;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public CompletableFuture<InstallResult> install(
            String minecraftVersion,
            ProgressListener listener
    ) {
        if (minecraftVersion == null || minecraftVersion.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Minecraft version is required.")
            );
        }

        ProgressListener progressListener =
                listener == null ? (done, total, message) -> {} : listener;

        return CompletableFuture.supplyAsync(() -> {
            try {
                return installBlocking(minecraftVersion, progressListener);
            } catch (Exception exception) {
                throw new RuntimeException(
                        "Minecraft installation failed: " + exception.getMessage(),
                        exception
                );
            }
        });
    }

    private InstallResult installBlocking(
            String minecraftVersion,
            ProgressListener listener
    ) throws Exception {

        directories.createDirectories();

        listener.onProgress(0, 0, "Fetching Minecraft version information...");

        JsonObject manifest = fetchJson(MANIFEST_URL);
        JsonArray versions = manifest.getAsJsonArray("versions");

        String versionUrl = null;

        for (JsonElement element : versions) {
            JsonObject version = element.getAsJsonObject();

            if (version.get("id").getAsString().equals(minecraftVersion)) {
                versionUrl = version.get("url").getAsString();
                break;
            }
        }

        if (versionUrl == null) {
            throw new IOException(
                    "Minecraft version not found: " + minecraftVersion
            );
        }

        listener.onProgress(0, 0, "Downloading version metadata...");

        Path versionJsonPath =
                directories.getVersionJson(minecraftVersion);

        downloadFile(versionUrl, versionJsonPath, null);

        JsonObject versionJson =
                JsonParser.parseString(
                        Files.readString(versionJsonPath)
                ).getAsJsonObject();

        List<DownloadTask> tasks = new ArrayList<>();

        // Minecraft client JAR
        JsonObject downloads = getObject(versionJson, "downloads");

        if (downloads == null || !downloads.has("client")) {
            throw new IOException("Client download information is missing.");
        }

        JsonObject client = downloads.getAsJsonObject("client");

        tasks.add(new DownloadTask(
                client.get("url").getAsString(),
                directories.getClientJar(minecraftVersion),
                getString(client, "sha1"),
                false
        ));

        // Libraries and Windows natives
        JsonArray libraries = getArray(versionJson, "libraries");

        if (libraries != null) {
            for (JsonElement element : libraries) {
                JsonObject library = element.getAsJsonObject();

                if (!isAllowed(library)) {
                    continue;
                }

                JsonObject libraryDownloads =
                        getObject(library, "downloads");

                if (libraryDownloads == null) {
                    continue;
                }

                JsonObject artifact =
                        getObject(libraryDownloads, "artifact");

                if (artifact != null) {
                    addDownloadTask(tasks, artifact, false);
                }

                JsonObject natives =
                        getObject(library, "natives");

                if (natives != null && natives.has("windows")) {
                    String classifierName =
                            natives.get("windows").getAsString()
                                    .replace("${arch}", "64");

                    JsonObject classifiers =
                            getObject(libraryDownloads, "classifiers");

                    if (classifiers != null
                            && classifiers.has(classifierName)) {

                        JsonObject nativeArtifact =
                                classifiers.getAsJsonObject(classifierName);

                        addDownloadTask(tasks, nativeArtifact, true);
                    }
                }
            }
        }

        // Asset index
        JsonObject assetIndex =
                getObject(versionJson, "assetIndex");

        if (assetIndex == null) {
            throw new IOException("Asset index information is missing.");
        }

        String assetIndexId =
                assetIndex.get("id").getAsString();

        Path assetIndexPath =
                directories.getAssetIndexesDirectory()
                        .resolve(assetIndexId + ".json");

        tasks.add(new DownloadTask(
                assetIndex.get("url").getAsString(),
                assetIndexPath,
                getString(assetIndex, "sha1"),
                false
        ));

        listener.onProgress(0, tasks.size(), "Preparing downloads...");

        downloadTasks(tasks, listener);

        // Read downloaded asset index
        JsonObject assetIndexJson =
                JsonParser.parseString(
                        Files.readString(assetIndexPath)
                ).getAsJsonObject();

        JsonObject objects =
                assetIndexJson.getAsJsonObject("objects");

        List<DownloadTask> assetTasks = new ArrayList<>();

        for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
            JsonObject object = entry.getValue().getAsJsonObject();

            String hash = object.get("hash").getAsString();

            String assetUrl =
                    "https://resources.download.minecraft.net/"
                            + hash.substring(0, 2)
                            + "/"
                            + hash;

            Path assetPath =
                    directories.getAssetObjectsDirectory()
                            .resolve(hash.substring(0, 2))
                            .resolve(hash);

            assetTasks.add(new DownloadTask(
                    assetUrl,
                    assetPath,
                    hash,
                    false
            ));
        }

        listener.onProgress(
                0,
                assetTasks.size(),
                "Downloading Minecraft assets..."
        );

        downloadTasks(assetTasks, listener);

        // Extract Windows native libraries
        listener.onProgress(
                0,
                0,
                "Preparing native libraries..."
        );

        Path nativeDirectory =
                directories.getNativeVersionDirectory(minecraftVersion);

        Files.createDirectories(nativeDirectory);

        for (DownloadTask task : tasks) {
            if (task.nativeLibrary()) {
                extractNative(task.destination(), nativeDirectory);
            }
        }

        listener.onProgress(
                1,
                1,
                "Minecraft " + minecraftVersion + " installed successfully."
        );

        return new InstallResult(
                minecraftVersion,
                directories.getVersionDirectory(minecraftVersion),
                directories.getClientJar(minecraftVersion)
        );
    }

    private void addDownloadTask(
            List<DownloadTask> tasks,
            JsonObject artifact,
            boolean nativeLibrary
    ) throws IOException {

        String url = getString(artifact, "url");
        String relativePath = getString(artifact, "path");

        if (url == null || relativePath == null) {
            return;
        }

        Path destination = safeResolve(
                directories.getLibrariesDirectory(),
                relativePath
        );

        tasks.add(new DownloadTask(
                url,
                destination,
                getString(artifact, "sha1"),
                nativeLibrary
        ));
    }

    private void downloadTasks(
            List<DownloadTask> tasks,
            ProgressListener listener
    ) throws Exception {

        AtomicInteger completed = new AtomicInteger(0);
        int total = tasks.size();

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (DownloadTask task : tasks) {
            CompletableFuture<Void> future =
                    CompletableFuture.runAsync(() -> {
                        try {
                            downloadFile(
                                    task.url(),
                                    task.destination(),
                                    task.sha1()
                            );

                            int done = completed.incrementAndGet();

                            listener.onProgress(
                                    done,
                                    total,
                                    "Downloading files: " + done + "/" + total
                            );

                        } catch (Exception exception) {
                            throw new RuntimeException(exception);
                        }
                    }, downloadExecutor);

            futures.add(future);
        }

        try {
            CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0])
            ).join();
        } catch (Exception exception) {
            futures.forEach(future -> future.cancel(true));
            throw new IOException(
                    "One or more files could not be downloaded.",
                    exception
            );
        }
    }

    private void downloadFile(
            String url,
            Path destination,
            String expectedSha1
    ) throws Exception {

        Files.createDirectories(destination.getParent());

        if (Files.exists(destination)
                && isValidFile(destination, expectedSha1)) {
            return;
        }

        Path temporaryFile =
                destination.resolveSibling(
                        destination.getFileName() + ".download"
                );

        Files.deleteIfExists(temporaryFile);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "CapitalClient/1.0")
                .GET()
                .build();

        HttpResponse<Path> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofFile(temporaryFile)
        );

        if (response.statusCode() != 200) {
            Files.deleteIfExists(temporaryFile);

            throw new IOException(
                    "Download failed with HTTP "
                            + response.statusCode() + ": " + url
            );
        }

        if (!isValidFile(temporaryFile, expectedSha1)) {
            Files.deleteIfExists(temporaryFile);

            throw new IOException(
                    "SHA-1 verification failed: " + destination.getFileName()
            );
        }

        try {
            Files.move(
                    temporaryFile,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    temporaryFile,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private boolean isValidFile(
            Path file,
            String expectedSha1
    ) throws Exception {

        if (!Files.exists(file) || Files.size(file) == 0) {
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

        String actualSha1 =
                HexFormat.of().formatHex(digest.digest());

        return actualSha1.equalsIgnoreCase(expectedSha1);
    }

    private void extractNative(
            Path archive,
            Path destination
    ) throws IOException {

        Files.createDirectories(destination);

        try (ZipInputStream zip = new ZipInputStream(
                Files.newInputStream(archive))) {

            ZipEntry entry;

            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();

                if (name.startsWith("META-INF/")) {
                    continue;
                }

                Path output = safeResolve(destination, name);

                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());

                    Files.copy(
                            zip,
                            output,
                            StandardCopyOption.REPLACE_EXISTING
                    );
                }

                zip.closeEntry();
            }
        }
    }

    private Path safeResolve(
            Path base,
            String relativePath
    ) throws IOException {

        Path resolved = base.resolve(relativePath).normalize();

        if (!resolved.startsWith(base.normalize())) {
            throw new IOException("Unsafe file path: " + relativePath);
        }

        return resolved;
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
                    "Metadata request failed: HTTP " + response.statusCode()
            );
        }

        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private boolean isAllowed(JsonObject library) {
        JsonArray rules = getArray(library, "rules");

        if (rules == null || rules.isEmpty()) {
            return true;
        }

        boolean allowed = false;

        for (JsonElement element : rules) {
            JsonObject rule = element.getAsJsonObject();

            if (!matchesRule(rule)) {
                continue;
            }

            String action = getString(rule, "action");
            allowed = "allow".equals(action);
        }

        return allowed;
    }

    private boolean matchesRule(JsonObject rule) {
        JsonObject os = getObject(rule, "os");

        if (os == null) {
            return true;
        }

        String name = getString(os, "name");

        if (name != null && !"windows".equals(name)) {
            return false;
        }

        String arch = getString(os, "arch");

        if (arch != null) {
            String systemArch =
                    System.getProperty("os.arch", "")
                            .toLowerCase(Locale.ROOT);

            if (arch.equals("x86")) {
                return systemArch.contains("86")
                        && !systemArch.contains("64");
            }

            if (arch.equals("x86_64")) {
                return systemArch.contains("64")
                        || systemArch.equals("amd64");
            }
        }

        return true;
    }

    private JsonObject getObject(JsonObject parent, String key) {
        if (parent == null || !parent.has(key)
                || !parent.get(key).isJsonObject()) {
            return null;
        }

        return parent.getAsJsonObject(key);
    }

    private JsonArray getArray(JsonObject parent, String key) {
        if (parent == null || !parent.has(key)
                || !parent.get(key).isJsonArray()) {
            return null;
        }

        return parent.getAsJsonArray(key);
    }

    private String getString(JsonObject object, String key) {
        if (object == null || !object.has(key)
                || object.get(key).isJsonNull()) {
            return null;
        }

        return object.get(key).getAsString();
    }

    public void shutdown() {
        downloadExecutor.shutdownNow();
    }

    private record DownloadTask(
            String url,
            Path destination,
            String sha1,
            boolean nativeLibrary
    ) {}

    public record InstallResult(
            String minecraftVersion,
            Path versionDirectory,
            Path clientJar
    ) {}

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(int completed, int total, String message);
    }
}
