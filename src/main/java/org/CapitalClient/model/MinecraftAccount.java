
package org.CapitalClient.model;

public record MinecraftAccount(
        String username,
        String uuid,
        String accessToken,
        String userType,
        String xuid
) {
    public MinecraftAccount {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Minecraft username is required.");
        }

        if (uuid == null || uuid.isBlank()) {
            throw new IllegalArgumentException("Minecraft UUID is required.");
        }

        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("Minecraft access token is required.");
        }

        if (userType == null || userType.isBlank()) {
            userType = "msa";
        }

        if (xuid == null) {
            xuid = "";
        }
    }
}
