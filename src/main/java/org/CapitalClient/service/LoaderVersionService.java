
package org.CapitalClient.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class LoaderVersionService {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public CompletableFuture<List<String>> loadVersions(
            String minecraftVersion,
            String loader
    ) {

        return switch (loader) {

            case "Fabric" -> loadJsonVersions(
                    "https://meta.fabricmc.net/v2/versions/loader/"
                            + encode(minecraftVersion)
            );

            case "Quilt" -> loadJsonVersions(
                    "https://meta.quiltmc.org/v3/versions/loader/"
                            + encode(minecraftVersion)
            );

            case "Forge" -> loadForgeVersions(minecraftVersion);

            case "NeoForge" -> loadNeoForgeVersions(minecraftVersion);

            default -> CompletableFuture.completedFuture(List.of());
        };
    }

    private CompletableFuture<List<String>> loadJsonVersions(
            String url
    ) {

        return get(url).thenApply(body -> {

            JsonArray array = JsonParser.parseString(body)
                    .getAsJsonArray();

            List<String> versions = new ArrayList<>();

            for (JsonElement element : array) {

                JsonObject object = element.getAsJsonObject();

                JsonObject loader = object.has("loader")
                        && object.get("loader").isJsonObject()
                        ? object.getAsJsonObject("loader")
                        : object;

                if (loader.has("version")
                        && !loader.get("version").isJsonNull()) {

                    versions.add(
                            loader.get("version").getAsString()
                    );
                }
            }

            return versions.stream().distinct().toList();
        });
    }

    private CompletableFuture<List<String>> loadForgeVersions(
            String minecraftVersion
    ) {

        String url =
                "https://maven.minecraftforge.net/net/minecraftforge/"
                        + "forge/maven-metadata.xml";

        return get(url).thenApply(xml -> {

            List<String> versions = parseMavenVersions(xml);

            String prefix = minecraftVersion + "-";

            return versions.stream()
                    .filter(v -> v.startsWith(prefix))
                    .sorted(Comparator.reverseOrder())
                    .toList();
        });
    }

    private CompletableFuture<List<String>> loadNeoForgeVersions(
            String minecraftVersion
    ) {

        String url =
                "https://maven.neoforged.net/releases/net/neoforged/"
                        + "neoforge/maven-metadata.xml";

        String prefix = neoForgePrefix(minecraftVersion);

        if (prefix == null) {
            return CompletableFuture.completedFuture(List.of());
        }

        return get(url).thenApply(xml -> {

            List<String> versions = parseMavenVersions(xml);

            return versions.stream()
                    .filter(v -> v.startsWith(prefix))
                    .sorted(Comparator.reverseOrder())
                    .toList();
        });
    }

    private String neoForgePrefix(String mc) {

        String[] parts = mc.split("\\.");

        if (parts.length < 3 || !parts[0].equals("1")) {
            return null;
        }

        try {

            int minor = Integer.parseInt(parts[1]);
            int patch = Integer.parseInt(parts[2]);

            if (minor < 20 || (minor == 20 && patch < 2)) {
                return null;
            }

            // Minecraft 1.20.1 uses the legacy 47.x NeoForge line.
            if (minor == 20 && patch == 1) {
                return "47.";
            }

            return minor + "." + patch + ".";

        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<String> parseMavenVersions(String xml) {

        try {

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

            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            Document document = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(
                            xml.getBytes(StandardCharsets.UTF_8)
                    )
            );

            NodeList nodes =
                    document.getElementsByTagName("version");

            List<String> versions = new ArrayList<>();

            for (int i = 0; i < nodes.getLength(); i++) {

                String version = nodes.item(i)
                        .getTextContent()
                        .trim();

                if (!version.isEmpty()) {
                    versions.add(version);
                }
            }

            return versions;

        } catch (Exception e) {

            throw new RuntimeException(
                    "Could not parse loader metadata: " + e.getMessage(),
                    e
            );
        }
    }

    private CompletableFuture<String> get(String url) {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
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
                        "Metadata server returned HTTP "
                                + response.statusCode()
                );
            }

            return response.body();
        });
    }

    private String encode(String value) {

        return URLEncoder.encode(
                value,
                StandardCharsets.UTF_8
        ).replace("+", "%20");
    }
}
