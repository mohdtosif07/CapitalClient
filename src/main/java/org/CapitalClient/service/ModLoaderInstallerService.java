
package org.CapitalClient.service;

import org.CapitalClient.model.ProfileSelection;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import org.w3c.dom.Document;

public class ModLoaderInstallerService {

    private static final String FABRIC_METADATA =
            "https://maven.fabricmc.net/net/fabricmc/fabric-installer/maven-metadata.xml";

    private static final String FABRIC_BASE =
            "https://maven.fabricmc.net/net/fabricmc/fabric-installer/";

    private static final String FORGE_BASE =
            "https://maven.minecraftforge.net/net/minecraftforge/forge/";

    private static final String NEOFORGE_BASE =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/";

    private static final Pattern SAFE_VERSION =
            Pattern.compile("[a-zA-Z0-9._+\\-]+");

    private final GameDirectoryService directories;
    private final HttpClient httpClient;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    public ModLoaderInstallerService() {
        this(new GameDirectoryService());
    }

    public ModLoaderInstallerService(GameDirectoryService directories) {
        this.directories = directories;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public CompletableFuture<Void> install(
            ProfileSelection profile,
            ProgressListener listener
    ) {
        return CompletableFuture.runAsync(() -> {
            try {
                installBlocking(profile, listener);
            } catch (Exception exception) {
                throw new RuntimeException(
                        "Loader installation failed: "
                                + exception.getMessage(),
                        exception
                );
            }
        }, executor);
    }

    private void installBlocking(
            ProfileSelection profile,
            ProgressListener listener
    ) throws Exception {

        if (profile == null) {
            throw new IllegalArgumentException("Profile is missing.");
        }

        String loader = profile.loader();
        String minecraftVersion = profile.minecraftVersion();
        String loaderVersion = profile.loaderVersion();

        if (loader == null || loader.isBlank()
                || loader.equalsIgnoreCase("Vanilla")) {
            notify(listener, "Vanilla selected. No mod loader required.");
            return;
        }

        validateVersion(minecraftVersion);

        if (loaderVersion == null || loaderVersion.isBlank()
                || loaderVersion.equalsIgnoreCase("Not required")) {
            throw new IllegalArgumentException(
                    "A valid loader version must be selected."
            );
        }

        validateVersion(loaderVersion);
        directories.createDirectories();

        switch (loader.toLowerCase(Locale.ROOT)) {

            case "fabric" -> installFabric(
                    minecraftVersion, loaderVersion, listener
            );

            case "forge" -> installForge(
                    minecraftVersion, loaderVersion, listener
            );

            case "neoforge" -> installNeoForge(
                    minecraftVersion, loaderVersion, listener
            );

            case "quilt" -> throw new UnsupportedOperationException(
                    "Quilt installation is not implemented yet."
            );

            case "optifine" -> throw new UnsupportedOperationException(
                    "OptiFine requires a separate installation workflow."
            );

            default -> throw new UnsupportedOperationException(
                    "Unsupported loader: " + loader
            );
        }
    }

    // ---------------- FABRIC ----------------

    private void installFabric(
            String minecraftVersion,
            String loaderVersion,
            ProgressListener listener
    ) throws Exception {

        notify(listener, "Finding official Fabric installer version...");

        String installerVersion = getLatestFabricInstallerVersion();

        String installerUrl = FABRIC_BASE
                + installerVersion
                + "/fabric-installer-"
                + installerVersion
                + ".jar";

        Path installerJar = getInstallerPath(
                "fabric-installer-" + installerVersion + ".jar"
        );

        notify(listener, "Downloading Fabric installer...");
        download(installerUrl, installerJar);

        runInstaller(
                installerJar,
                new String[]{
                        "client",
                        "-dir",
                        directories.getGameDirectory().toString(),
                        "-mcversion",
                        minecraftVersion,
                        "-loader",
                        loaderVersion,
                        "-noprofile"
                },
                listener,
                "Fabric"
        );

        verifyInstalledVersion(
                minecraftVersion,
                "fabric",
                loaderVersion,
                listener
        );
    }

    private String getLatestFabricInstallerVersion() throws Exception {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(FABRIC_METADATA))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "CapitalClient/1.0")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200) {
            throw new IOException(
                    "Fabric metadata request failed. HTTP "
                            + response.statusCode()
            );
        }

        DocumentBuilderFactory factory =
                DocumentBuilderFactory.newInstance();

        factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl",
                true
        );

        factory.setFeature(
                "http://xml.org/sax/features/external-general-entities",
                false
        );

        factory.setFeature(
                "http://xml.org/sax/features/external-parameter-entities",
                false
        );

        Document document = factory.newDocumentBuilder().parse(
                new ByteArrayInputStream(
                        response.body().getBytes(StandardCharsets.UTF_8)
                )
        );

        var latest = document.getElementsByTagName("latest");

        if (latest.getLength() > 0) {
            return latest.item(0).getTextContent().trim();
        }

        var release = document.getElementsByTagName("release");

        if (release.getLength() > 0) {
            return release.item(0).getTextContent().trim();
        }

        throw new IOException("Fabric installer version not found.");
    }

    // ---------------- FORGE ----------------

    private void installForge(
            String minecraftVersion,
            String loaderVersion,
            ProgressListener listener
    ) throws Exception {

        String combinedVersion =
                minecraftVersion + "-" + loaderVersion;

        String installerName =
                "forge-" + combinedVersion + "-installer.jar";

        String installerUrl =
                FORGE_BASE + combinedVersion + "/" + installerName;

        Path installerJar = getInstallerPath(installerName);

        notify(listener, "Downloading official Forge installer...");
        download(installerUrl, installerJar);

        runInstaller(
                installerJar,
                new String[]{
                        "--installClient",
                        directories.getGameDirectory().toString()
                },
                listener,
                "Forge"
        );

        verifyInstalledVersion(
                minecraftVersion,
                "forge",
                loaderVersion,
                listener
        );
    }

    // ---------------- NEOFORGE ----------------

    private void installNeoForge(
            String minecraftVersion,
            String loaderVersion,
            ProgressListener listener
    ) throws Exception {

        String installerName =
                "neoforge-" + loaderVersion + "-installer.jar";

        String installerUrl =
                NEOFORGE_BASE
                        + loaderVersion
                        + "/"
                        + installerName;

        Path installerJar = getInstallerPath(installerName);

        notify(listener, "Downloading official NeoForge installer...");
        download(installerUrl, installerJar);

        runInstaller(
                installerJar,
                new String[]{
                        "--install-client",
                        directories.getGameDirectory().toString()
                },
                listener,
                "NeoForge"
        );

        verifyInstalledVersion(
                minecraftVersion,
                "neoforge",
                loaderVersion,
                listener
        );
    }

    // ---------------- RUN INSTALLER ----------------

    private void runInstaller(
            Path installerJar,
            String[] arguments,
            ProgressListener listener,
            String loaderName
    ) throws Exception {

        notify(listener, "Running " + loaderName + " installer...");

        Path logFile = directories.getLogsDirectory()
                .resolve(loaderName.toLowerCase(Locale.ROOT)
                        + "-installer.log");

        String javaExecutable = getJavaExecutable();

        List<String> command = new ArrayList<>();

        command.add(javaExecutable);
        command.add("-jar");
        command.add(installerJar.toAbsolutePath().toString());
        command.addAll(Arrays.asList(arguments));

        ProcessBuilder builder = new ProcessBuilder(command);

        builder.directory(directories.getRoot().toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(logFile.toFile());

        Process process = builder.start();
        int exitCode = process.waitFor();

        if (exitCode != 0) {

            String log = Files.exists(logFile)
                    ? Files.readString(logFile)
                    : "Installer log not available.";

            throw new IOException(
                    loaderName + " installer failed. Exit code: "
                            + exitCode + "\n" + log
            );
        }

        notify(listener, loaderName + " installer process completed.");
    }

    // ---------------- VERIFY INSTALLATION ----------------

    private void verifyInstalledVersion(
            String minecraftVersion,
            String loaderName,
            String loaderVersion,
            ProgressListener listener
    ) throws IOException {

        Path versionsDirectory = directories.getVersionsDirectory();

        if (!Files.exists(versionsDirectory)) {
            throw new IOException(
                    "Minecraft versions directory was not created."
            );
        }

        String expectedVersionPart =
                loaderVersion.toLowerCase(Locale.ROOT);

        try (var paths = Files.list(versionsDirectory)) {

            boolean found = paths
                    .filter(Files::isDirectory)
                    .filter(path -> {

                        String name = path.getFileName()
                                .toString()
                                .toLowerCase(Locale.ROOT);

                        return name.startsWith(
                                minecraftVersion.toLowerCase(Locale.ROOT) + "-"
                        )
                                && name.contains(loaderName.toLowerCase(Locale.ROOT))
                                && name.contains(expectedVersionPart);
                    })
                    .anyMatch(path -> Files.exists(
                            path.resolve(
                                    path.getFileName() + ".json"
                            )
                    ));

            if (!found) {
                throw new IOException(
                        loaderName + " installer completed, but expected "
                                + "version JSON was not found. Check installer log."
                );
            }
        }

        notify(listener, loaderName + " installed and verified.");
    }

    // ---------------- DOWNLOAD ----------------

    private Path getInstallerPath(String fileName) throws IOException {

        Path installerDirectory =
                directories.getRoot().resolve("installers");

        Files.createDirectories(installerDirectory);

        return installerDirectory.resolve(fileName);
    }

    private void download(
            String url,
            Path destination
    ) throws Exception {

        Files.createDirectories(destination.getParent());

        if (Files.exists(destination) && Files.size(destination) > 0) {
            return;
        }

        Path temporary = destination.resolveSibling(
                destination.getFileName() + ".download"
        );

        Files.deleteIfExists(temporary);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
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
                    "Installer download failed. HTTP "
                            + response.statusCode()
            );
        }

        if (!Files.exists(temporary) || Files.size(temporary) == 0) {
            Files.deleteIfExists(temporary);
            throw new IOException("Downloaded installer is empty.");
        }

        Files.move(
                temporary,
                destination,
                StandardCopyOption.REPLACE_EXISTING
        );
    }

    // ---------------- UTILITIES ----------------

    private void validateVersion(String version) {

        if (!SAFE_VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException(
                    "Invalid version value: " + version
            );
        }
    }

    private String getJavaExecutable() {

        String executable = System.getProperty("os.name")
                .toLowerCase(Locale.ROOT)
                .contains("win")
                ? "java.exe"
                : "java";

        return Path.of(
                System.getProperty("java.home"),
                "bin",
                executable
        ).toString();
    }

    private void notify(
            ProgressListener listener,
            String message
    ) {
        if (listener != null) {
            listener.onMessage(message);
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onMessage(String message);
    }
}
