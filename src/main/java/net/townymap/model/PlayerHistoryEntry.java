package net.townymap.model;

public record PlayerHistoryEntry(String name, String uuid, int x, int z, long lastSeenMs) {
}
