
package org.CapitalClient.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.CapitalClient.model.MinecraftVersion;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class VersionManifestService {

    private static final String MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private final HttpClient client;

    public VersionManifestService() {

        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public CompletableFuture<List<MinecraftVersion>> loadVersions() {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MANIFEST_URL))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "CapitalClient/1.0")
                .GET()
                .build();

        return client.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString()
        ).thenApply(response -> {

            if (response.statusCode() != 200) {
                throw new RuntimeException(
                        "Minecraft manifest HTTP error: "
                                + response.statusCode()
                );
            }

            return parseManifest(response.body());
        });
    }

    private List<MinecraftVersion> parseManifest(String json) {

        JsonObject root = JsonParser.parseString(json)
                .getAsJsonObject();

        JsonObject latest = root.getAsJsonObject("latest");

        String latestRelease =
                latest.get("release").getAsString();

        String latestSnapshot =
                latest.get("snapshot").getAsString();

        JsonArray versions = root.getAsJsonArray("versions");

        List<MinecraftVersion> result = new ArrayList<>();

        for (JsonElement element : versions) {

            JsonObject item = element.getAsJsonObject();

            String id = item.get("id").getAsString();
            String type = item.get("type").getAsString();
            String url = item.get("url").getAsString();

            String releaseTime =
                    item.get("releaseTime").getAsString();

            result.add(new MinecraftVersion(
                    id,
                    type,
                    url,
                    releaseTime,
                    id.equals(latestRelease),
                    id.equals(latestSnapshot)
            ));
        }

        return List.copyOf(result);
    }
}
