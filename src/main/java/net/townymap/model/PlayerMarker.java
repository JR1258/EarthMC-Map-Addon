package net.townymap.model;

import java.util.Locale;

/**
 * A live player position from EarthMC's map player feed. {@code yaw} is Minecraft's facing yaw.
 *
 * <p>{@code world} is squaremap's world name for where the player is. It is kept on the marker rather
 * than filtered away at parse time because two surfaces want different answers: the world map draws the
 * world it is showing, the minimap draws the one the player is standing in. Blank means squaremap did
 * not say, in which case the marker is shown everywhere rather than nowhere.
 */
public record PlayerMarker(String name, String uuid, int x, int z, float yaw, String key, String world) {
    public PlayerMarker(String name, String uuid, int x, int z, float yaw, String key) {
        this(name, uuid, x, z, yaw, key, "");
    }
    public PlayerMarker(String name, String uuid, int x, int z, float yaw) {
        this(name, uuid, x, z, yaw, name == null ? "" : name.toLowerCase(Locale.ROOT), "");
    }
    public PlayerMarker(String name, String uuid, int x, int z) {
        this(name, uuid, x, z, 0f);
    }

    /** True if this marker belongs on a map showing {@code worldKey}. */
    public boolean inWorld(String worldKey) {
        return world == null || world.isEmpty() || world.equals(worldKey);
    }
}
