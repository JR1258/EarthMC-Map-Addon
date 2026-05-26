package net.townymap.model;

public record OptimisticClaimChunk(
        int chunkX,
        int chunkZ,
        String townName,
        int fillColor,
        int outlineColor,
        long expiresAtMs
) {
    public int blockX() {
        return chunkX * 16;
    }

    public int blockZ() {
        return chunkZ * 16;
    }

    public boolean expired(long nowMs) {
        return nowMs >= expiresAtMs;
    }
}
