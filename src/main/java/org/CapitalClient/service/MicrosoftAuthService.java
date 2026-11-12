package org.CapitalClient.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.CapitalClient.model.MinecraftAccount;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Microsoft OAuth Authorization Code + PKCE authentication for a desktop app.
 * Opens the user's default browser and receives the OAuth redirect on localhost.
 */
public class MicrosoftAuthService {

    private static final String CLIENT_ID =
            "5beb79b3-6cf8-4e64-8ab9-faafa10a816a";

    private static final String AUTHORITY =
            "https://login.microsoftonline.com/consumers";

    private static final String SCOPE = "XboxLive.signin offline_access";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(25))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "CapitalClient-Microsoft-Auth");
                thread.setDaemon(true);
                return thread;
            });

    public CompletableFuture<MinecraftAccount> authenticate() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return authenticateBlocking();
            } catch (Exception exception) {
                throw new RuntimeException(
                        exception.getMessage() == null
                                ? "Microsoft authentication failed."
                                : exception.getMessage(),
                        exception
                );
            }
        }, executor);
    }

    private MinecraftAccount authenticateBlocking() throws Exception {
        String verifier = randomUrlSafe(64);
        String challenge = sha256UrlSafe(verifier);
        String state = randomUrlSafe(32);

        HttpServer callbackServer = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0
        );
        CompletableFuture<String> authorizationCode = new CompletableFuture<>();
        String redirectUri = "http://localhost:" + callbackServer.getAddress().getPort() + "/";

        callbackServer.createContext("/", exchange -> handleCallback(
                exchange, state, authorizationCode
        ));
        callbackServer.start();

        try {
            String authorizationUrl = AUTHORITY + "/oauth2/v2.0/authorize?" + formEncode(Map.of(
                    "client_id", CLIENT_ID,
                    "response_type", "code",
                    "redirect_uri", redirectUri,
                    "response_mode", "query",
                    "scope", SCOPE,
                    "state", state,
                    "code_challenge", challenge,
                    "code_challenge_method", "S256"
            ));

            openBrowser(authorizationUrl);

            String code;
            try {
                code = authorizationCode.get(5, TimeUnit.MINUTES);
            } catch (java.util.concurrent.TimeoutException exception) {
                throw new IOException("Microsoft login timed out. Please try again.");
            }

            JsonObject microsoftToken = postForm(
                    AUTHORITY + "/oauth2/v2.0/token",
                    Map.of(
                            "client_id", CLIENT_ID,
                            "grant_type", "authorization_code",
                            "code", code,
                            "redirect_uri", redirectUri,
                            "code_verifier", verifier,
                            "scope", SCOPE
                    )
            );

            String accessToken = requiredString(microsoftToken, "access_token");
            JsonObject xbox = authenticateXbox(accessToken);
            String userHash = requiredString(xbox, "uhs");
            String xstsToken = requiredString(xbox, "xstsToken");

            JsonObject minecraftLogin = postJson(
                    "https://api.minecraftservices.com/authentication/login_with_xbox",
                    jsonObject("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken)
            );

            String minecraftAccessToken = requiredString(minecraftLogin, "access_token");
            checkGameOwnership(minecraftAccessToken);

            JsonObject profile = getJson(
                    "https://api.minecraftservices.com/minecraft/profile",
                    minecraftAccessToken
            );

            String username = requiredString(profile, "name");
            String uuid = requiredString(profile, "id");

            return new MinecraftAccount(
                    username,
                    uuid,
                    minecraftAccessToken,
                    "msa",
                    userHash
            );
        } finally {
            callbackServer.stop(0);
        }
    }

    private void handleCallback(
            HttpExchange exchange,
            String expectedState,
            CompletableFuture<String> result
    ) throws IOException {
        try {
            if (!"127.0.0.1".equals(exchange.getRemoteAddress().getAddress().getHostAddress())
                    && !"0:0:0:0:0:0:0:1".equals(
                    exchange.getRemoteAddress().getAddress().getHostAddress())) {
                sendHtml(exchange, 403, "Request rejected", "This callback is only available on this computer.");
                return;
            }

            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            String returnedState = query.get("state");
            if (returnedState == null || !constantTimeEquals(expectedState, returnedState)) {
                sendHtml(exchange, 400, "Sign-in could not be verified", "The sign-in response did not match this login request. Close this page and try again.");
                result.completeExceptionally(new IOException("Microsoft sign-in state validation failed."));
                return;
            }

            if (query.containsKey("error")) {
                String description = query.getOrDefault("error_description", query.get("error"));
                sendHtml(exchange, 400, "Microsoft sign-in cancelled", "You can close this tab and return to CapitalClient.");
                result.completeExceptionally(new IOException("Microsoft sign-in was not completed: " + description));
                return;
            }

            String code = query.get("code");
            if (code == null || code.isBlank()) {
                sendHtml(exchange, 400, "Authorization code missing", "Return to CapitalClient and try signing in again.");
                result.completeExceptionally(new IOException("Microsoft did not return an authorization code."));
                return;
            }

            sendHtml(exchange, 200, "Sign-in received", "Microsoft sign-in was received. You can close this tab and return to CapitalClient.");
            result.complete(code);
        } catch (Exception exception) {
            result.completeExceptionally(exception);
            sendHtml(exchange, 500, "Sign-in error", "An error occurred. Return to CapitalClient and try again.");
        }
    }

    private JsonObject authenticateXbox(String microsoftAccessToken) throws Exception {
        JsonObject properties = new JsonObject();
        properties.addProperty("AuthMethod", "RPS");
        properties.addProperty("SiteName", "user.auth.xboxlive.com");
        properties.addProperty("RpsTicket", "d=" + microsoftAccessToken);

        JsonObject body = new JsonObject();
        body.add("Properties", properties);
        body.addProperty("RelyingParty", "http://auth.xboxlive.com");
        body.addProperty("TokenType", "JWT");

        JsonObject userAuth = postJson(
                "https://user.auth.xboxlive.com/user/authenticate", body
        );
        String userToken = requiredString(userAuth, "Token");
        String userHash = userAuth.getAsJsonObject("DisplayClaims")
                .getAsJsonArray("xui").get(0).getAsJsonObject()
                .get("uhs").getAsString();

        JsonObject xstsProperties = new JsonObject();
        xstsProperties.addProperty("SandboxId", "RETAIL");
        JsonArray userTokens = new JsonArray();
        userTokens.add(userToken);
        xstsProperties.add("UserTokens", userTokens);

        JsonObject xstsBody = new JsonObject();
        xstsBody.add("Properties", xstsProperties);
        xstsBody.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        xstsBody.addProperty("TokenType", "JWT");

        JsonObject xsts = postJson(
                "https://xsts.auth.xboxlive.com/xsts/authorize", xstsBody
        );
        String xstsHash = xsts.getAsJsonObject("DisplayClaims")
                .getAsJsonArray("xui").get(0).getAsJsonObject()
                .get("uhs").getAsString();
        return jsonObject("uhs", xstsHash, "xstsToken", requiredString(xsts, "Token"));
    }

    private void checkGameOwnership(String accessToken) throws Exception {
        JsonObject entitlements = getJson(
                "https://api.minecraftservices.com/entitlements/mcstore", accessToken
        );
        JsonArray items = entitlements.has("items") && entitlements.get("items").isJsonArray()
                ? entitlements.getAsJsonArray("items") : new JsonArray();

        boolean ownsGame = false;
        for (JsonElement element : items) {
            if (!element.isJsonObject()) continue;
            String name = element.getAsJsonObject().has("name")
                    ? element.getAsJsonObject().get("name").getAsString() : "";
            if (name.equalsIgnoreCase("game_minecraft")
                    || name.equalsIgnoreCase("product_minecraft")) {
                ownsGame = true;
                break;
            }
        }
        if (!ownsGame) {
            throw new IOException("This Microsoft account does not appear to own Minecraft Java Edition.");
        }
    }

    private JsonObject postForm(String url, Map<String, String> values) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(35))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(values)))
                .build();
        return sendJson(request);
    }

    private JsonObject postJson(String url, JsonObject body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(35))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return sendJson(request);
    }

    private JsonObject getJson(String url, String bearerToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(35))
                .header("Authorization", "Bearer " + bearerToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        return sendJson(request);
    }

    private JsonObject sendJson(HttpRequest request) throws Exception {
        HttpResponse<String> response = HTTP.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        String body = response.body() == null ? "" : response.body().trim();
        JsonObject json = null;

        try {
            if (!body.isBlank()) {
                JsonElement parsed = JsonParser.parseString(body);
                if (parsed.isJsonObject()) {
                    json = parsed.getAsJsonObject();
                }
            }
        } catch (Exception ignored) {
            // Some services return plain-text or HTML error pages.
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String detail = "No error details were returned.";

            if (json != null) {
                if (json.has("error_description") && !json.get("error_description").isJsonNull()) {
                    detail = json.get("error_description").getAsString();
                } else if (json.has("Message") && !json.get("Message").isJsonNull()) {
                    detail = json.get("Message").getAsString();
                } else if (json.has("message") && !json.get("message").isJsonNull()) {
                    detail = json.get("message").getAsString();
                } else if (json.has("error") && !json.get("error").isJsonNull()) {
                    detail = json.get("error").getAsString();
                } else {
                    detail = json.toString();
                }
            } else if (!body.isBlank()) {
                detail = body.replaceAll("<[^>]*>", " ")
                        .replaceAll("\\s+", " ").trim();
                if (detail.length() > 500) {
                    detail = detail.substring(0, 500) + "...";
                }
            }

            String endpoint = request.uri().getHost()
                    + request.uri().getPath();

            throw new IOException(
                    "Authentication failed at " + endpoint
                            + " (HTTP " + response.statusCode() + ").\\n"
                            + detail
            );
        }

        if (json == null) {
            throw new IOException(
                    "Unexpected non-JSON response from "
                            + request.uri().getHost() + request.uri().getPath()
            );
        }

        return json;
    }

    private void openBrowser(String url) throws Exception {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            throw new IOException("A default browser could not be opened on this computer.");
        }
        Desktop.getDesktop().browse(URI.create(url));
    }

    private static String formEncode(Map<String, String> values) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!result.isEmpty()) result.append('&');
            result.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            result.append('=');
            result.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return result.toString();
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return result;
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length > 1
                    ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            result.put(key, value);
        }
        return result;
    }

    private static void sendHtml(HttpExchange exchange, int status,
                                 String title, String message) throws IOException {
        String html = "<!doctype html><html><head><meta charset='utf-8'><title>CapitalClient</title>"
                + "<style>body{font-family:Segoe UI,Arial;background:#101014;color:#eee;display:grid;"
                + "place-items:center;min-height:90vh}.box{background:#1b1b23;padding:36px;border-radius:16px;"
                + "max-width:520px;text-align:center}h2{color:#a78bfa}p{color:#bbb;line-height:1.6}</style>"
                + "</head><body><div class='box'><h2>" + escapeHtml(title)
                + "</h2><p>" + escapeHtml(message) + "</p></div></body></html>";
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String randomUrlSafe(int bytes) {
        byte[] random = new byte[bytes];
        new SecureRandom().nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static String sha256UrlSafe(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static boolean constantTimeEquals(String first, String second) {
        return MessageDigest.isEqual(
                first.getBytes(StandardCharsets.UTF_8),
                second.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String requiredString(JsonObject object, String key) throws IOException {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()
                || object.get(key).getAsString().isBlank()) {
            throw new IOException("Authentication response is missing '" + key + "'.");
        }
        return object.get(key).getAsString();
    }

    private static JsonObject jsonObject(String key1, String value1) {
        JsonObject object = new JsonObject();
        object.addProperty(key1, value1);
        return object;
    }

    private static JsonObject jsonObject(String key1, String value1,
                                         String key2, String value2) {
        JsonObject object = jsonObject(key1, value1);
        object.addProperty(key2, value2);
        return object;
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}

