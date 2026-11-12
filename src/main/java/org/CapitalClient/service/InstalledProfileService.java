
package org.CapitalClient.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public class InstalledProfileService {

    private final Path gameDirectory;
    private final Path versionsDirectory;
    private final Path configFile;
    private final Path namesFile;

    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    public record InstalledProfile(
            String id,
            String name,
            String minecraftVersion,
            String loader,
            String loaderVersion,
            Path versionDirectory
    ) {}

    public InstalledProfileService() {

        String appData = System.getenv("APPDATA");

        Path root = Paths.get(
                appData != null && !appData.isBlank()
                        ? appData
                        : System.getProperty("user.home")
        );

        Path capitalClientDirectory = root.resolve("CapitalClient");

        this.gameDirectory = capitalClientDirectory.resolve("game");
        this.versionsDirectory = gameDirectory.resolve("versions");

        this.configFile = capitalClientDirectory.resolve(
                "selected-installed-profile.json"
        );

        this.namesFile = capitalClientDirectory
                .resolve("config")
                .resolve("installed-profile-names.json");
    }

    public Path getGameDirectory() {
        return gameDirectory;
    }

    public List<InstalledProfile> scanInstalledProfiles()
            throws IOException {

        List<InstalledProfile> profiles = new ArrayList<>();
        Map<String, String> savedNames = loadProfileNames();

        if (!Files.isDirectory(versionsDirectory)) {
            return profiles;
        }

        try (Stream<Path> directories = Files.list(versionsDirectory)) {

            directories
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(
                            path -> path.getFileName().toString()
                    ))
                    .forEach(directory -> {

                        String folderName =
                                directory.getFileName().toString();

                        Path jsonFile =
                                directory.resolve(folderName + ".json");

                        if (!Files.isRegularFile(jsonFile)) {
                            return;
                        }

                        try {
                            String json = Files.readString(jsonFile);

                            var object = gson.fromJson(
                                    json,
                                    com.google.gson.JsonObject.class
                            );

                            if (object == null) return;

                            String id = object.has("id")
                                    ? object.get("id").getAsString()
                                    : folderName;

                            String inherits = object.has("inheritsFrom")
                                    ? object.get("inheritsFrom").getAsString()
                                    : "";

                            String minecraftVersion = inherits.isBlank()
                                    ? id
                                    : inherits;

                            String loader = detectLoader(id);

                            String loaderVersion =
                                    detectLoaderVersion(id, loader);

                            String displayName = savedNames.getOrDefault(
                                    id,
                                    id
                            );

                            profiles.add(new InstalledProfile(
                                    id,
                                    displayName,
                                    minecraftVersion,
                                    loader,
                                    loaderVersion,
                                    directory
                            ));

                        } catch (Exception exception) {
                            System.err.println(
                                    "Unable to read profile: "
                                            + folderName
                                            + " - "
                                            + exception.getMessage()
                            );
                        }
                    });
        }

        return profiles;
    }

    private String detectLoader(String id) {

        String lower = id.toLowerCase();

        if (lower.contains("neoforge")) return "NeoForge";
        if (lower.contains("fabric")) return "Fabric";
        if (lower.contains("quilt")) return "Quilt";
        if (lower.contains("forge")) return "Forge";
        if (lower.contains("optifine")) return "OptiFine";

        return "Vanilla";
    }

    private String detectLoaderVersion(
            String id,
            String loader
    ) {

        if (loader.equals("Vanilla")) {
            return "";
        }

        String[] parts = id.split("-");

        if (parts.length >= 3) {
            return parts[parts.length - 1];
        }

        return "";
    }

    public Optional<String> loadSelectedProfileId()
            throws IOException {

        if (!Files.exists(configFile)) {
            return Optional.empty();
        }

        String id = Files.readString(configFile).trim();

        if (id.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(id);
    }

    public void saveSelectedProfileId(String id)
            throws IOException {

        Files.createDirectories(configFile.getParent());

        Files.writeString(
                configFile,
                id,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    // Load saved custom profile names
    private Map<String, String> loadProfileNames()
            throws IOException {

        if (!Files.exists(namesFile)) {
            return new HashMap<>();
        }

        Type type = new TypeToken<Map<String, String>>() {}.getType();

        Map<String, String> names = gson.fromJson(
                Files.readString(namesFile),
                type
        );

        return names != null ? names : new HashMap<>();
    }

    // Save custom profile names
    private void saveProfileNames(Map<String, String> names)
            throws IOException {

        Files.createDirectories(namesFile.getParent());

        Files.writeString(
                namesFile,
                gson.toJson(names),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    // Rename profile
    public void renameProfile(String profileId, String newName)
            throws IOException {

        if (profileId == null || profileId.isBlank()) {
            throw new IOException("Invalid profile ID.");
        }

        if (newName == null || newName.isBlank()) {
            throw new IOException("Profile name cannot be empty.");
        }

        boolean exists = scanInstalledProfiles()
                .stream()
                .anyMatch(profile -> profile.id().equals(profileId));

        if (!exists) {
            throw new IOException("Profile not found: " + profileId);
        }

        Map<String, String> names = loadProfileNames();

        names.put(profileId, newName.trim());

        saveProfileNames(names);
    }

    // Delete profile using profile ID
    public void deleteProfile(String profileId)
            throws IOException {

        if (profileId == null || profileId.isBlank()) {
            throw new IOException("Invalid profile ID.");
        }

        InstalledProfile profile = scanInstalledProfiles()
                .stream()
                .filter(p -> p.id().equals(profileId))
                .findFirst()
                .orElseThrow(() ->
                        new IOException("Profile not found: " + profileId)
                );

        deleteProfile(profile);
    }

    // Delete profile using InstalledProfile object
    public void deleteProfile(InstalledProfile profile)
            throws IOException {

        Path directory = profile.versionDirectory()
                .toAbsolutePath()
                .normalize();

        Path root = versionsDirectory.toAbsolutePath()
                .normalize();

        if (!directory.startsWith(root)
                || directory.equals(root)) {
            throw new IOException("Invalid profile directory.");
        }

        deleteDirectory(directory);

        // Remove saved custom name
        Map<String, String> names = loadProfileNames();
        names.remove(profile.id());
        saveProfileNames(names);

        // Clear selected profile if it was deleted
        if (loadSelectedProfileId()
                .orElse("")
                .equals(profile.id())) {

            Files.deleteIfExists(configFile);
        }
    }

    private void deleteDirectory(Path directory)
            throws IOException {

        if (!Files.exists(directory)) return;

        try (Stream<Path> paths = Files.walk(directory)) {

            for (Path path : paths
                    .sorted(Comparator.reverseOrder())
                    .toList()) {

                Files.deleteIfExists(path);
            }
        }
    }
}
