package net.townymap.model;

import java.util.List;
import java.util.Locale;

/**
 * Holds rendering data for one Towny town.
 * polygonRings follows GeoJSON ring convention: first ring = outer boundary,
 * additional rings = holes. Each point is [worldX, worldZ].
 */
public record TownData(String name, int rgbColor, List<int[][]> polygonRings,
                       int minX, int maxX, int minZ, int maxZ) {

    public TownData(String name, int rgbColor, List<int[][]> polygonRings) {
        this(name, rgbColor, List.copyOf(polygonRings), bounds(polygonRings));
    }

    private TownData(String name, int rgbColor, List<int[][]> polygonRings, Bounds bounds) {
        this(name, rgbColor, polygonRings, bounds.minX, bounds.maxX, bounds.minZ, bounds.maxZ);
    }

    /** ARGB colour with the given opacity applied on top of the stored RGB. */
    public int argbColor(int alpha) {
        return ((alpha & 0xFF) << 24) | (rgbColor & 0x00FFFFFF);
    }

    public String key() {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    public long renderSignature() {
        long hash = 1125899906842597L;
        hash = 31L * hash + key().hashCode();
        hash = 31L * hash + rgbColor;
        hash = 31L * hash + minX;
        hash = 31L * hash + maxX;
        hash = 31L * hash + minZ;
        hash = 31L * hash + maxZ;
        for (int[][] ring : polygonRings) {
            hash = 31L * hash + ring.length;
            for (int[] point : ring) {
                hash = 31L * hash + (point.length > 0 ? point[0] : 0);
                hash = 31L * hash + (point.length > 1 ? point[1] : 0);
            }
        }
        return hash;
    }

    public boolean intersectsWorld(double left, double right, double top, double bottom) {
        return maxX >= left && minX <= right && maxZ >= top && minZ <= bottom;
    }

    public int centerX() {
        return minX + (maxX - minX) / 2;
    }

    public int centerZ() {
        return minZ + (maxZ - minZ) / 2;
    }

    public int approximateChunks() {
        long blocks = 0;
        for (int[][] ring : polygonRings) {
            blocks += Math.abs(area2(ring)) / 2;
        }
        return (int) Math.max(1, Math.round(blocks / 256.0));
    }

    /** Parse "#rrggbb" or "#rrggbbaa" into packed 0x00RRGGBB. Returns fallback on error. */
    public static int parseHexColor(String hex, int fallback) {
        if (hex == null) return fallback;
        String s = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            return (int) (Long.parseLong(s, 16) & 0x00FFFFFF);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Bounds bounds(List<int[][]> rings) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (int[][] ring : rings) {
            for (int[] point : ring) {
                if (point.length < 2) continue;
                minX = Math.min(minX, point[0]);
                maxX = Math.max(maxX, point[0]);
                minZ = Math.min(minZ, point[1]);
                maxZ = Math.max(maxZ, point[1]);
            }
        }

        if (minX == Integer.MAX_VALUE) {
            return new Bounds(0, 0, 0, 0);
        }
        return new Bounds(minX, maxX, minZ, maxZ);
    }

    private static long area2(int[][] ring) {
        if (ring.length < 3) return 0;
        long sum = 0;
        for (int i = 0; i < ring.length; i++) {
            int[] a = ring[i];
            int[] b = ring[(i + 1) % ring.length];
            if (a.length < 2 || b.length < 2) continue;
            sum += (long) a[0] * b[1] - (long) b[0] * a[1];
        }
        return sum;
    }

    private record Bounds(int minX, int maxX, int minZ, int maxZ) {}
}
