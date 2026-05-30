package net.townymap.model;

import java.util.Locale;

/**
 * One online player's map marker.
 *
 * {@code key} is the lowercase name, precomputed once at construction so the
 * per-frame render loop can look players up in the details cache without
 * allocating a new lowercase string for every player every frame.
 */
public record PlayerMarker(String name, String uuid, int x, int z, String key) {
    public PlayerMarker(String name, String uuid, int x, int z) {
        this(name, uuid, x, z, name == null ? "" : name.toLowerCase(Locale.ROOT));
    }
}
