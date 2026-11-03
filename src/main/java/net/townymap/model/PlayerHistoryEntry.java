package net.townymap.model;

/**
 * A player's last known position from EarthMC's map player feed.
 *
 * <p>{@code world} is squaremap's world name for where they were. Without it the store was a flat
 * name -> X/Z map, so lunar positions could only have been drawn back on Terra Nostra -- which is why
 * history used to record Earth alone, and why the Moon had no last-seen markers at all. Entries saved
 * before this field existed load with it null and are read as the overworld, which is what they were.
 */
public record PlayerHistoryEntry(String name, String uuid, int x, int z, long lastSeenMs, String world) {
    public PlayerHistoryEntry(String name, String uuid, int x, int z, long lastSeenMs) {
        this(name, uuid, x, z, lastSeenMs, null);
    }

    /** The world this position belongs to; entries from before the field default to the overworld. */
    public String worldOrDefault() {
        return world == null || world.isEmpty() ? "minecraft_overworld" : world;
    }
}
