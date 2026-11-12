
package org.CapitalClient.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.CapitalClient.model.MinecraftAccount;
import org.CapitalClient.model.ProfileSelection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MinecraftLaunchService {

    private final GameDirectoryService directories;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    public MinecraftLaunchService() {
        this(new GameDirectoryService());
    }

    public MinecraftLaunchService(GameDirectoryService directories) {
        this.directories = directories;
    }

    public CompletableFuture<Process> launch(
            ProfileSelection profile,
            MinecraftAccount account
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return launchBlocking(profile, account);
            } catch (Exception exception) {
                throw new RuntimeException(
                        "Minecraft launch failed: " + exception.getMessage(),
                        exception
                );
            }
        }, executor);
    }

    private Process launchBlocking(
            ProfileSelection profile,
            MinecraftAccount account
    ) throws Exception {

        if (profile == null) {
            throw new IllegalArgumentException("No Minecraft profile selected.");
        }

        if (account == null) {
            throw new IllegalArgumentException(
                    "Please sign in with your Microsoft account first."
            );
        }

        directories.createDirectories();

        String minecraftVersion = profile.minecraftVersion();

        Path versionDirectory = findInstalledVersion(profile);

        String versionId = versionDirectory.getFileName().toString();

        Path versionJsonPath =
                versionDirectory.resolve(versionId + ".json");

        if (!Files.exists(versionJsonPath)) {
            throw new IOException("Installed version JSON is missing.");
        }

        JsonObject versionJson =
                resolveVersionJson(versionJsonPath, new HashSet<>());

        String mainClass = getString(versionJson, "mainClass");

        if (mainClass == null || mainClass.isBlank()) {
            throw new IOException("Minecraft mainClass is missing.");
        }

        Path clientJar = versionDirectory.resolve(versionId + ".jar");

        if (!Files.exists(clientJar)) {
            // Some inherited loader installations use the base version JAR.
            clientJar = directories.getClientJar(minecraftVersion);
        }

        if (!Files.exists(clientJar)) {
            throw new IOException("Minecraft client JAR is missing.");
        }

        Path nativesDirectory =
                directories.getNativeVersionDirectory(versionId);

        Files.createDirectories(nativesDirectory);

        Path assetsDirectory = directories.getAssetsDirectory();

        String assetIndex = "legacy";

        JsonObject assetIndexObject =
                getObject(versionJson, "assetIndex");

        if (assetIndexObject != null) {
            assetIndex = getString(assetIndexObject, "id");

            if (assetIndex == null) {
                assetIndex = "legacy";
            }
        }

        String runtimeComponent =
                getRuntimeComponent(versionJson);

        Path javaExecutable =
                directories.getRuntimeDirectory()
                        .resolve(runtimeComponent)
                        .resolve("windows-x64")
                        .resolve("bin")
                        .resolve("javaw.exe");

        if (!Files.exists(javaExecutable)) {
            javaExecutable = javaExecutable.resolveSibling("java.exe");
        }

        if (!Files.exists(javaExecutable)) {
            throw new IOException(
                    "Required Java runtime not found: "
                            + runtimeComponent
                            + ". Install the profile again."
            );
        }

        List<Path> libraries = collectLibraries(versionJson);

        libraries.add(clientJar);

        String classpath = buildClasspath(libraries);

        Map<String, String> placeholders = new HashMap<>();

        placeholders.put("${auth_player_name}", account.username());
        placeholders.put("${version_name}", versionId);
        placeholders.put("${game_directory}",
                directories.getGameDirectory().toAbsolutePath().toString());
        placeholders.put("${assets_root}", assetsDirectory.toAbsolutePath().toString());
        placeholders.put("${assets_index_name}", assetIndex);
        placeholders.put("${auth_uuid}", normalizeUuid(account.uuid()));
        placeholders.put("${auth_access_token}", account.accessToken());
        placeholders.put("${user_type}", account.userType());
        placeholders.put("${version_type}",
                getString(versionJson, "type") == null
                        ? "release"
                        : getString(versionJson, "type"));
        placeholders.put("${natives_directory}",
                nativesDirectory.toAbsolutePath().toString());
        placeholders.put("${launcher_name}", "CapitalClient");
        placeholders.put("${launcher_version}", "1.0.0");
        placeholders.put("${classpath}", classpath);
        placeholders.put("${classpath_separator}", ";");
        placeholders.put("${clientid}", "");
        placeholders.put("${auth_xuid}", account.xuid());
        placeholders.put("${user_properties}", "{}");

        List<String> command = new ArrayList<>();

        command.add(javaExecutable.toAbsolutePath().toString());

        JsonObject arguments = getObject(versionJson, "arguments");

        if (arguments != null && arguments.has("jvm")) {

            command.addAll(
                    parseArguments(
                            arguments.getAsJsonArray("jvm"),
                            placeholders
                    )
            );

        } else {
            command.add("-Djava.library.path="
                    + nativesDirectory.toAbsolutePath());

            command.add("-cp");
            command.add(classpath);
        }

        command.add(mainClass);

        if (arguments != null && arguments.has("game")) {

            command.addAll(
                    parseArguments(
                            arguments.getAsJsonArray("game"),
                            placeholders
                    )
            );

        } else {

            String legacyArguments =
                    getString(versionJson, "minecraftArguments");

            if (legacyArguments == null) {
                throw new IOException(
                        "Minecraft launch arguments are missing."
                );
            }

            for (String argument : splitLegacyArguments(legacyArguments)) {
                command.add(replacePlaceholders(argument, placeholders));
            }
        }

        Path logFile = directories.getLogsDirectory()
                .resolve("minecraft-" + versionId + ".log");

        ProcessBuilder builder = new ProcessBuilder(command);

        builder.directory(directories.getGameDirectory().toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(
                ProcessBuilder.Redirect.appendTo(logFile.toFile())
        );

        return builder.start();
    }

    private Path findInstalledVersion(
            ProfileSelection profile
    ) throws IOException {

        String minecraftVersion = profile.minecraftVersion();
        String loader = profile.loader();

        if (loader == null || loader.equalsIgnoreCase("Vanilla")) {
            Path vanilla = directories.getVersionDirectory(minecraftVersion);

            if (Files.exists(vanilla.resolve(minecraftVersion + ".json"))) {
                return vanilla;
            }

            throw new IOException("Vanilla version is not installed.");
        }

        String loaderName = loader.toLowerCase(Locale.ROOT);
        String loaderVersion = profile.loaderVersion();

        try (var paths = Files.list(directories.getVersionsDirectory())) {

            return paths
                    .filter(Files::isDirectory)
                    .filter(path -> {
                        String name = path.getFileName()
                                .toString()
                                .toLowerCase(Locale.ROOT);

                        boolean loaderMatch = switch (loaderName) {
                            case "fabric" -> name.startsWith("fabric-loader-");
                            case "forge" -> name.contains("forge");
                            case "neoforge" -> name.contains("neoforge");
                            case "quilt" -> name.startsWith("quilt-loader-");
                            default -> false;
                        };

                        if (!loaderMatch) {
                            return false;
                        }

                        if (loaderVersion != null
                                && !loaderVersion.isBlank()
                                && !name.contains(loaderVersion.toLowerCase(Locale.ROOT))) {
                            return false;
                        }

                        Path jsonPath = path.resolve(
                                path.getFileName() + ".json"
                        );

                        if (!Files.exists(jsonPath)) {
                            return false;
                        }

                        try {
                            JsonObject json = JsonParser.parseString(
                                    Files.readString(jsonPath)
                            ).getAsJsonObject();

                            return minecraftVersion.equals(
                                    getString(json, "inheritsFrom")
                            );
                        } catch (Exception ignored) {
                            return false;
                        }
                    })
                    .findFirst()
                    .orElseThrow(() -> new IOException(
                            loader + " installation was not found for "
                                    + minecraftVersion
                    ));
        }
    }

    private JsonObject resolveVersionJson(
            Path jsonPath,
            Set<String> visited
    ) throws Exception {

        Path normalized = jsonPath.toAbsolutePath().normalize();

        if (!visited.add(normalized.toString())) {
            throw new IOException("Circular Minecraft version inheritance.");
        }

        JsonObject child = JsonParser.parseString(
                Files.readString(normalized)
        ).getAsJsonObject();

        String parentId = getString(child, "inheritsFrom");

        if (parentId == null || parentId.isBlank()) {
            return child;
        }

        Path parentPath =
                directories.getVersionJson(parentId);

        if (!Files.exists(parentPath)) {
            throw new IOException(
                    "Parent Minecraft version metadata is missing: "
                            + parentId
            );
        }

        JsonObject parent = resolveVersionJson(parentPath, visited);

        JsonObject merged = parent.deepCopy();

        for (Map.Entry<String, JsonElement> entry : child.entrySet()) {

            String key = entry.getKey();

            if ((key.equals("libraries")
                    || key.equals("arguments"))
                    && entry.getValue().isJsonObject()
                    && merged.has(key)
                    && merged.get(key).isJsonObject()) {

                merged.add(key, mergeArgumentObjects(
                        merged.getAsJsonObject(key),
                        entry.getValue().getAsJsonObject()
                ));

            } else if (key.equals("libraries")
                    && entry.getValue().isJsonArray()
                    && merged.has(key)
                    && merged.get(key).isJsonArray()) {

                JsonArray combined = merged.getAsJsonArray(key).deepCopy();

                for (JsonElement element :
                        entry.getValue().getAsJsonArray()) {
                    combined.add(element);
                }

                merged.add(key, combined);

            } else {
                merged.add(key, entry.getValue());
            }
        }

        return merged;
    }

    private JsonObject mergeArgumentObjects(
            JsonObject parent,
            JsonObject child
    ) {

        JsonObject merged = parent.deepCopy();

        for (Map.Entry<String, JsonElement> entry : child.entrySet()) {

            String key = entry.getKey();

            if (entry.getValue().isJsonArray()
                    && merged.has(key)
                    && merged.get(key).isJsonArray()) {

                JsonArray combined =
                        merged.getAsJsonArray(key).deepCopy();

                for (JsonElement element :
                        entry.getValue().getAsJsonArray()) {
                    combined.add(element);
                }

                merged.add(key, combined);

            } else {
                merged.add(key, entry.getValue());
            }
        }

        return merged;
    }

    private List<Path> collectLibraries(
            JsonObject versionJson
    ) throws IOException {

        List<Path> result = new ArrayList<>();

        JsonArray libraries =
                getArray(versionJson, "libraries");

        if (libraries == null) {
            return result;
        }

        for (JsonElement element : libraries) {

            JsonObject library = element.getAsJsonObject();

            if (!isAllowed(library)) {
                continue;
            }

            JsonObject downloads = getObject(library, "downloads");

            if (downloads == null) {
                continue;
            }

            JsonObject artifact = getObject(downloads, "artifact");

            if (artifact == null) {
                continue;
            }

            String relativePath = getString(artifact, "path");

            if (relativePath == null) {
                continue;
            }

            Path file = directories.getLibrariesDirectory()
                    .resolve(relativePath)
                    .normalize();

            if (!file.startsWith(
                    directories.getLibrariesDirectory().normalize())) {
                throw new IOException("Unsafe library path.");
            }

            if (Files.exists(file)) {
                result.add(file);
            }
        }

        return result;
    }

    private String buildClasspath(List<Path> libraries) {

        StringBuilder classpath = new StringBuilder();

        for (Path library : libraries) {

            if (!classpath.isEmpty()) {
                classpath.append(";");
            }

            classpath.append(library.toAbsolutePath());
        }

        return classpath.toString();
    }

    private List<String> parseArguments(
            JsonArray arguments,
            Map<String, String> placeholders
    ) {

        List<String> result = new ArrayList<>();

        for (JsonElement element : arguments) {

            if (element.isJsonPrimitive()) {

                result.add(
                        replacePlaceholders(
                                element.getAsString(),
                                placeholders
                        )
                );

            } else if (element.isJsonObject()) {

                JsonObject argument = element.getAsJsonObject();

                if (!isAllowed(argument)) {
                    continue;
                }

                JsonElement value = argument.get("value");

                if (value == null) {
                    continue;
                }

                if (value.isJsonArray()) {

                    for (JsonElement item : value.getAsJsonArray()) {
                        result.add(
                                replacePlaceholders(
                                        item.getAsString(),
                                        placeholders
                                )
                        );
                    }

                } else if (value.isJsonPrimitive()) {

                    result.add(
                            replacePlaceholders(
                                    value.getAsString(),
                                    placeholders
                            )
                    );
                }
            }
        }

        return result;
    }

    private boolean isAllowed(JsonObject object) {

        JsonArray rules = getArray(object, "rules");

        if (rules == null || rules.isEmpty()) {
            return true;
        }

        boolean allowed = false;

        for (JsonElement element : rules) {

            JsonObject rule = element.getAsJsonObject();

            if (!matchesWindowsRule(rule)) {
                continue;
            }

            String action = getString(rule, "action");

            allowed = "allow".equals(action);
        }

        return allowed;
    }

    private boolean matchesWindowsRule(JsonObject rule) {

        JsonObject os = getObject(rule, "os");

        if (os == null) {
            return true;
        }

        String name = getString(os, "name");

        if (name != null && !name.equals("windows")) {
            return false;
        }

        String arch = getString(os, "arch");

        if (arch != null && arch.equals("x86")) {
            return System.getProperty("os.arch").contains("86")
                    && !System.getProperty("os.arch").contains("64");
        }

        return true;
    }

    private String getRuntimeComponent(JsonObject versionJson) {

        JsonObject javaVersion = getObject(versionJson, "javaVersion");

        if (javaVersion != null) {
            String component = getString(javaVersion, "component");

            if (component != null) {
                return component;
            }
        }

        return "jre-legacy";
    }

    private String replacePlaceholders(
            String argument,
            Map<String, String> placeholders
    ) {

        String result = argument;

        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(
                    entry.getKey(),
                    entry.getValue()
            );
        }

        return result;
    }

    private List<String> splitLegacyArguments(String input) {

        List<String> result = new ArrayList<>();

        java.util.regex.Matcher matcher =
                java.util.regex.Pattern
                        .compile("\"([^\"]*)\"|(\\S+)")
                        .matcher(input);

        while (matcher.find()) {
            result.add(
                    matcher.group(1) != null
                            ? matcher.group(1)
                            : matcher.group(2)
            );
        }

        return result;
    }

    private String normalizeUuid(String uuid) {
        return uuid.replace("-", "");
    }

    private JsonObject getObject(JsonObject object, String key) {

        if (object == null || !object.has(key)
                || !object.get(key).isJsonObject()) {
            return null;
        }

        return object.getAsJsonObject(key);
    }

    private JsonArray getArray(JsonObject object, String key) {

        if (object == null || !object.has(key)
                || !object.get(key).isJsonArray()) {
            return null;
        }

        return object.getAsJsonArray(key);
    }

    private String getString(JsonObject object, String key) {

        if (object == null || !object.has(key)
                || object.get(key).isJsonNull()) {
            return null;
        }

        return object.get(key).getAsString();
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
