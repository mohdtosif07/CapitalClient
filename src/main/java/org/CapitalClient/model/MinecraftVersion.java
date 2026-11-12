
package org.CapitalClient.model;

public record MinecraftVersion(
        String id,
        String type,
        String url,
        String releaseTime,
        boolean latestRelease,
        boolean latestSnapshot
) {

    public String getDisplayType() {

        return switch (type) {
            case "release" -> "Release";
            case "snapshot" -> "Snapshot";
            case "old_beta" -> "Old Beta";
            case "old_alpha" -> "Old Alpha";
            default -> type;
        };
    }

    public boolean isLatest() {
        return latestRelease || latestSnapshot;
    }
}
