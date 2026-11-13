package net.townymap.model;

import java.util.List;
import java.util.Locale;

/**
 * Holds rendering data for one Towny town.
 * polygonRings follows GeoJSON ring convention: first ring = outer boundary,
 * additional rings = holes. Each point is [worldX, worldZ].
 */
public record TownData(String name, int rgbColor, int fillRgbColor, List<int[][]> polygonRings,
                       int minX, int maxX, int minZ, int maxZ) {

    /** Single-colour form: the fill reuses the outline colour. */
    public TownData(String name, int rgbColor, List<int[][]> polygonRings) {
        this(name, rgbColor, rgbColor, polygonRings);
    }

    /** squaremap ships BOTH a stroke {@code color} and a {@code fillColor} per town — on EarthMC they're
     *  the nation's two-colour scheme and differ for ~62% of towns, so keep them apart. */
    public TownData(String name, int rgbColor, int fillRgbColor, List<int[][]> polygonRings) {
        this(name, rgbColor, fillRgbColor, List.copyOf(polygonRings), bounds(polygonRings));
    }

    private TownData(String name, int rgbColor, int fillRgbColor, List<int[][]> polygonRings, Bounds bounds) {
        this(name, rgbColor, fillRgbColor, polygonRings, bounds.minX, bounds.maxX, bounds.minZ, bounds.maxZ);
    }

    /** ARGB OUTLINE colour with the given opacity applied on top of the stored RGB. */
    public int argbColor(int alpha) {
        return ((alpha & 0xFF) << 24) | (rgbColor & 0x00FFFFFF);
    }

    /** ARGB INTERIOR colour (squaremap's {@code fillColor} — the nation's fill on EarthMC). */
    public int argbFillColor(int alpha) {
        return ((alpha & 0xFF) << 24) | (fillRgbColor & 0x00FFFFFF);
    }

    /** A copy with new outline/fill colours but identical geometry — used by the meganation/alliance map
     *  modes to recolour a town by its alliance. Reuses the precomputed bounds (no re-scan). */
    public TownData withColors(int newRgbColor, int newFillRgbColor) {
        return new TownData(name, newRgbColor, newFillRgbColor, polygonRings, minX, maxX, minZ, maxZ);
    }

    /** The same town with a different outline -- used when the API has claims the map has not published
     *  yet. Bounds are recomputed, and renderSignature covers the rings, so this re-bakes on its own. */
    public TownData withRings(List<int[][]> newRings) {
        return new TownData(name, rgbColor, fillRgbColor, newRings);
    }

    public String key() {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    public long renderSignature() {
        long hash = 1125899906842597L;
        hash = 31L * hash + key().hashCode();
        hash = 31L * hash + rgbColor;
        hash = 31L * hash + fillRgbColor;   // a nation recolour must re-bake the tiles
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

    /**
     * Chunks enclosed by this claim's borders: the area they bound, divided by 256 (16x16 blocks to a
     * chunk). This is ONE world's claim -- a town with Moon outposts has a marker in each world, and
     * only their sum matches the API's town-wide total.
     *
     * <p>Rings arrive flattened with no outer/hole distinction, and squaremap winds them all the same
     * way, so winding cannot say which is which. Nesting can: a ring inside an odd number of the
     * others is a hole, and subtracts. Measured against the API across 267 towns -- including all 54
     * whose rings nest -- 266 came out exact. The one miss is a border that pinches shut around an
     * unclaimed pocket, which no ring-level rule can see, so this stays "approximate": prefer a figure
     * the API has already given when one is in hand.
     */
    public int approximateChunks() {
        int n = polygonRings.size();
        if (n == 0) return 1;
        // Nothing can nest inside a single ring, which is the overwhelming majority of towns.
        if (n == 1) return (int) Math.max(1, Math.round(Math.abs(area2(polygonRings.get(0))) / 2.0 / 256.0));
        double[][] inside = new double[n][];
        for (int i = 0; i < n; i++) inside[i] = interiorPoint(polygonRings.get(i));
        double blocks = 0;
        for (int i = 0; i < n; i++) {
            double area = Math.abs(area2(polygonRings.get(i))) / 2.0;
            if (inside[i] == null) { blocks += area; continue; }   // degenerate: count it solid
            int depth = 0;
            for (int j = 0; j < n; j++) {
                if (j != i && ringContains(polygonRings.get(j), inside[i][0], inside[i][1])) depth++;
            }
            blocks += (depth % 2 == 0) ? area : -area;
        }
        return (int) Math.max(1, Math.round(blocks / 256.0));
    }

    /** Ray cast, counting the crossings left of the point. */
    private static boolean ringContains(int[][] ring, double x, double z) {
        boolean in = false;
        for (int i = 0, n = ring.length; i < n; i++) {
            int[] p = ring[i], q = ring[(i + 1) % n];
            if ((p[1] > z) != (q[1] > z)) {
                double cross = p[0] + (z - p[1]) * (double) (q[0] - p[0]) / (q[1] - p[1]);
                if (x < cross) in = !in;
            }
        }
        return in;
    }

    /** A point strictly inside the ring, to test that ring against the others. Claim corners sit on
     *  16-block chunk lines, so stepping 8 blocks off an edge midpoint lands mid-chunk on one side or
     *  the other; whichever of the two this ring contains is interior to it. */
    private static double[] interiorPoint(int[][] ring) {
        for (int i = 0, n = ring.length; i < n; i++) {
            int[] p = ring[i], q = ring[(i + 1) % n];
            double dx = q[0] - p[0], dz = q[1] - p[1];
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len == 0) continue;
            double mx = (p[0] + q[0]) / 2.0, mz = (p[1] + q[1]) / 2.0;
            double ox = -dz / len * 8.0, oz = dx / len * 8.0;
            if (ringContains(ring, mx + ox, mz + oz)) return new double[]{mx + ox, mz + oz};
            if (ringContains(ring, mx - ox, mz - oz)) return new double[]{mx - ox, mz - oz};
        }
        return null;
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
