package net.townymap.model;

import java.util.Collection;
import java.util.List;

/**
 * Where a search result (or a popup link) sends the world map.
 *
 * <p>{@code x}/{@code z} is what the camera centres on. The {@code min}/{@code max} box is the area the
 * jump should FRAME: centring alone left you looking at the right spot from whatever zoom you happened
 * to be at, which at full zoom-out is a claim a few pixels across. The box lets the jump pick a zoom
 * that fits the whole claim with room around it.
 *
 * <p>{@code highlightTowns} names the claims to flash once you arrive, so the thing you searched for is
 * findable among its neighbours instead of being one more outline in a wall of them.
 */
public record MapJumpTarget(String label, int x, int z,
                            int minX, int maxX, int minZ, int maxZ,
                            List<String> highlightTowns) {

    public MapJumpTarget {
        highlightTowns = List.copyOf(highlightTowns);
    }

    /** A bare point: centre there and leave the zoom alone. */
    public MapJumpTarget(String label, int x, int z) {
        this(label, x, z, x, x, z, z, List.of());
    }

    /**
     * A point worth leaning in on -- a player's position -- framed to {@code span} blocks across. There
     * is no outline to fit, but arriving at whole-map zoom is no more useful for a player than for a
     * town, so the jump still picks a readable zoom.
     */
    public static MapJumpTarget ofPoint(String label, int x, int z, int span) {
        int half = Math.max(1, span / 2);
        // No claim to name: the flash falls back to a ring on the spot itself.
        return new MapJumpTarget(label, x, z, x - half, x + half, z - half, z + half, List.of());
    }

    /** Frames one town's claim and flashes its outline. */
    public static MapJumpTarget ofTown(TownData town) {
        return ofTown(town.name(), town);
    }

    /** Frames {@code town}'s claim under a label of its own -- a player framed on their home town. */
    public static MapJumpTarget ofTown(String label, TownData town) {
        return new MapJumpTarget(label, town.centerX(), town.centerZ(),
                town.minX(), town.maxX(), town.minZ(), town.maxZ(), List.of(town.name()));
    }

    /**
     * Frames every claim in a group -- a nation's member towns. Centres on the middle of their combined
     * box rather than on the capital, so the whole nation is in view and not just its corner.
     * Falls back to {@code fallback} when the group holds no towns we have geometry for.
     */
    public static MapJumpTarget ofTowns(String label, Collection<TownData> group, MapJumpTarget fallback) {
        if (group == null || group.isEmpty()) return fallback;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        List<String> names = new java.util.ArrayList<>(group.size());
        for (TownData town : group) {
            minX = Math.min(minX, town.minX());
            maxX = Math.max(maxX, town.maxX());
            minZ = Math.min(minZ, town.minZ());
            maxZ = Math.max(maxZ, town.maxZ());
            names.add(town.name());
        }
        return new MapJumpTarget(label, minX + (maxX - minX) / 2, minZ + (maxZ - minZ) / 2,
                minX, maxX, minZ, maxZ, names);
    }

    /** The same jump, flashing a different set of claims than the one it frames. */
    public MapJumpTarget withHighlights(List<String> names) {
        return new MapJumpTarget(label, x, z, minX, maxX, minZ, maxZ, names);
    }

    /** Whether there is an area to fit, as opposed to a single point. */
    public boolean hasExtent() {
        return maxX > minX || maxZ > minZ;
    }

    public int spanX() {
        return Math.max(0, maxX - minX);
    }

    public int spanZ() {
        return Math.max(0, maxZ - minZ);
    }
}
