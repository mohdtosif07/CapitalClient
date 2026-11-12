
package org.CapitalClient.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class GameDirectoryService {

    private final Path root;
    private final Path gameDirectory;

    public GameDirectoryService() {
        String appData = System.getenv("APPDATA");

        if (appData == null || appData.isBlank()) {
            appData = System.getProperty("user.home");
        }

        this.root = Path.of(appData, "CapitalClient");
        this.gameDirectory = root.resolve("game");
    }

    public Path getRoot() {
        return root;
    }

    public Path getGameDirectory() {
        return gameDirectory;
    }

    public Path getVersionsDirectory() {
        return gameDirectory.resolve("versions");
    }

    public Path getLibrariesDirectory() {
        return gameDirectory.resolve("libraries");
    }

    public Path getAssetsDirectory() {
        return gameDirectory.resolve("assets");
    }

    public Path getNativesDirectory() {
        return gameDirectory.resolve("natives");
    }

    public Path getRuntimeDirectory() {
        return gameDirectory.resolve("runtime");
    }

    public Path getLogsDirectory() {
        return root.resolve("logs");
    }

    public Path getVersionDirectory(String version) {
        return getVersionsDirectory().resolve(version);
    }

    public Path getVersionJson(String version) {
        return getVersionDirectory(version).resolve(version + ".json");
    }

    public Path getClientJar(String version) {
        return getVersionDirectory(version).resolve(version + ".jar");
    }

    public Path getAssetIndexesDirectory() {
        return getAssetsDirectory().resolve("indexes");
    }

    public Path getAssetObjectsDirectory() {
        return getAssetsDirectory().resolve("objects");
    }

    public Path getNativeVersionDirectory(String version) {
        return getNativesDirectory().resolve(version);
    }

    public void createDirectories() throws IOException {
        Files.createDirectories(root);
        Files.createDirectories(gameDirectory);
        Files.createDirectories(getVersionsDirectory());
        Files.createDirectories(getLibrariesDirectory());
        Files.createDirectories(getAssetsDirectory());
        Files.createDirectories(getAssetIndexesDirectory());
        Files.createDirectories(getAssetObjectsDirectory());
        Files.createDirectories(getNativesDirectory());
        Files.createDirectories(getRuntimeDirectory());
        Files.createDirectories(getLogsDirectory());
    }
}
