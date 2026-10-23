package net.townymap.render;

import net.minecraft.client.gui.DrawContext;
import net.townymap.model.TownData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * The "here it is" flash that follows a search jump.
 *
 * <p>Centring on a result put the right place under the crosshair and nothing more: on a map where every
 * neighbour is also an outlined polygon, the town you asked for looked exactly like the forty around it.
 * This traces the claim in bright silver for a few seconds and sends two rings out past its edge, so the
 * eye lands on it before the rings have finished.
 *
 * <p>Geometry is snapshotted at jump time -- the live town list is replaced wholesale on every claim
 * refresh, so holding names and looking them up per frame would make the flash blink out mid-animation.
 */
public final class SearchFlash {

    private SearchFlash() {}

    /** Long enough to find on a busy map, short enough not to become part of the furniture. */
    private static final long DURATION_MS = 4_000L;
    /** One halo sweep from the claim edge outwards. */
    private static final long SWEEP_MS = 1_150L;
    /** How far past the claim's own box a halo ring travels, as a share of the box's half-size. */
    private static final double SWEEP_REACH = 0.55;
    /** Halo rings in flight at once, evenly offset through the sweep. */
    private static final int SWEEP_RINGS = 2;
    /** Chrome: a near-white core over a cool steel halo. */
    private static final int CORE_RGB = 0xF2F6FF;
    private static final int HALO_RGB = 0x9FC2E8;

    /** One flashed claim: its rings, plus the box the halo expands out of. */
    private record Shape(List<int[][]> rings, int minX, int maxX, int minZ, int maxZ) {}

    private static volatile List<Shape> shapes = List.of();
    private static volatile long startedMs = 0L;

    /**
     * Arms the flash on a set of towns. {@code names} are matched against {@code towns} case-insensitively;
     * any that don't resolve are simply not flashed.
     */
    public static void arm(Collection<String> names, List<TownData> towns) {
        if (names == null || names.isEmpty() || towns == null || towns.isEmpty()) {
            clear();
            return;
        }
        java.util.Set<String> wanted = new java.util.HashSet<>(Math.max(4, names.size() * 2));
        for (String name : names) {
            if (name != null && !name.isBlank()) wanted.add(name.toLowerCase(Locale.ROOT));
        }
        if (wanted.isEmpty()) {
            clear();
            return;
        }
        List<Shape> next = new ArrayList<>(wanted.size());
        for (TownData town : towns) {
            if (!wanted.contains(town.key())) continue;
            next.add(new Shape(town.polygonRings(), town.minX(), town.maxX(), town.minZ(), town.maxZ()));
        }
        if (next.isEmpty()) {
            clear();
            return;
        }
        shapes = List.copyOf(next);
        startedMs = System.currentTimeMillis();
    }

    /** Arms the flash on a bare point -- a player, who has no claim of their own to trace. */
    public static void armPoint(int x, int z, int span) {
        int half = Math.max(8, span / 2);
        shapes = List.of(new Shape(List.of(), x - half, x + half, z - half, z + half));
        startedMs = System.currentTimeMillis();
    }

    public static void clear() {
        shapes = List.of();
        startedMs = 0L;
    }

    public static boolean active() {
        return startedMs != 0L && System.currentTimeMillis() - startedMs < DURATION_MS;
    }

    public static void render(DrawContext ctx, double cameraX, double cameraZ,
                              double blockScale, int sw, int sh) {
        List<Shape> snapshot = shapes;
        long started = startedMs;
        if (snapshot.isEmpty() || started == 0L || blockScale <= 0) return;

        long age = System.currentTimeMillis() - started;
        if (age < 0 || age >= DURATION_MS) {
            clear();
            return;
        }

        // Full strength for the first two thirds, then out. Nothing here is interactive, so it leaving
        // on its own is the whole point -- an outline that stayed would be a second favourites colour.
        double life = 1.0 - Math.max(0.0, (age - DURATION_MS * 0.6) / (DURATION_MS * 0.4));
        double beat = 0.5 + 0.5 * Math.sin(age / 150.0);

        for (Shape shape : snapshot) {
            drawHalo(ctx, shape, age, life, cameraX, cameraZ, blockScale, sw, sh);
            drawOutline(ctx, shape, life, beat, cameraX, cameraZ, blockScale, sw, sh);
        }
    }

    /** Rings expanding outwards from the claim's box, offset through the sweep so one is always leaving. */
    private static void drawHalo(DrawContext ctx, Shape shape, long age, double life,
                                 double cameraX, double cameraZ, double blockScale, int sw, int sh) {
        double cx = (shape.minX() + shape.maxX()) / 2.0;
        double cz = (shape.minZ() + shape.maxZ()) / 2.0;
        // A one-chunk town has almost no box to grow out of, so give every claim a floor to work with.
        double halfX = Math.max(48.0, (shape.maxX() - shape.minX()) / 2.0);
        double halfZ = Math.max(48.0, (shape.maxZ() - shape.minZ()) / 2.0);

        for (int i = 0; i < SWEEP_RINGS; i++) {
            double phase = ((age + (long) (SWEEP_MS * i / (double) SWEEP_RINGS)) % SWEEP_MS) / (double) SWEEP_MS;
            double grow = 1.0 + SWEEP_REACH * phase;
            // Brightest as it leaves the edge, gone by the time it reaches full reach.
            int alpha = (int) Math.round(150 * life * (1.0 - phase) * (1.0 - phase));
            if (alpha < 6) continue;
            double x1 = cx - halfX * grow, x2 = cx + halfX * grow;
            double z1 = cz - halfZ * grow, z2 = cz + halfZ * grow;
            box(ctx, x1, z1, x2, z2, (alpha << 24) | HALO_RGB, cameraX, cameraZ, blockScale, sw, sh);
        }
    }

    /** The claim's own outline, traced bright and 3px wide so it reads over any nation colour. */
    private static void drawOutline(DrawContext ctx, Shape shape, double life, double beat,
                                    double cameraX, double cameraZ, double blockScale, int sw, int sh) {
        if (shape.rings().isEmpty()) {
            // A point flash: no claim to trace, so the box itself is the marker.
            int alpha = (int) Math.round((110 + 90 * beat) * life);
            if (alpha > 6) {
                double cx = (shape.minX() + shape.maxX()) / 2.0;
                double cz = (shape.minZ() + shape.maxZ()) / 2.0;
                double r = Math.max(shape.maxX() - cx, shape.maxZ() - cz) * 0.35;
                box(ctx, cx - r, cz - r, cx + r, cz + r, (alpha << 24) | CORE_RGB,
                        cameraX, cameraZ, blockScale, sw, sh);
            }
            return;
        }
        int coreAlpha = (int) Math.round((165 + 90 * beat) * life);
        if (coreAlpha < 6) return;
        int core = (Math.min(255, coreAlpha) << 24) | CORE_RGB;
        int edge = (Math.min(255, coreAlpha / 2) << 24) | HALO_RGB;

        for (int[][] ring : shape.rings()) {
            if (ring.length < 2) continue;
            // Three passes: a dim pass either side of a bright centre line, which is how you get a wide,
            // soft stroke out of a renderer that can only fill 1px quads.
            traceRing(ctx, ring, cameraX, cameraZ, blockScale, sw, sh, -1, edge);
            traceRing(ctx, ring, cameraX, cameraZ, blockScale, sw, sh, 1, edge);
            traceRing(ctx, ring, cameraX, cameraZ, blockScale, sw, sh, 0, core);
        }
    }

    private static void traceRing(DrawContext ctx, int[][] ring,
                                  double cameraX, double cameraZ, double blockScale, int sw, int sh,
                                  int pixelOffset, int argb) {
        int n = ring.length;
        for (int i = 0; i < n; i++) {
            int[] a = ring[i];
            int[] b = ring[(i + 1) % n];
            if (a.length < 2 || b.length < 2) continue;
            double x1 = (a[0] - cameraX) * blockScale + sw / 2.0 + pixelOffset;
            double y1 = (a[1] - cameraZ) * blockScale + sh / 2.0 + pixelOffset;
            double x2 = (b[0] - cameraX) * blockScale + sw / 2.0 + pixelOffset;
            double y2 = (b[1] - cameraZ) * blockScale + sh / 2.0 + pixelOffset;
            WorldMapRenderer.drawThinSegment(ctx, x1, y1, x2, y2, argb, sw, sh);
        }
    }

    /** An axis-aligned world-space rectangle outline, projected and drawn as four thin segments. */
    private static void box(DrawContext ctx, double wx1, double wz1, double wx2, double wz2,
                            int argb, double cameraX, double cameraZ, double blockScale, int sw, int sh) {
        double x1 = (wx1 - cameraX) * blockScale + sw / 2.0;
        double y1 = (wz1 - cameraZ) * blockScale + sh / 2.0;
        double x2 = (wx2 - cameraX) * blockScale + sw / 2.0;
        double y2 = (wz2 - cameraZ) * blockScale + sh / 2.0;
        if (x2 < 0 || x1 > sw || y2 < 0 || y1 > sh) return;
        WorldMapRenderer.drawThinSegment(ctx, x1, y1, x2, y1, argb, sw, sh);
        WorldMapRenderer.drawThinSegment(ctx, x1, y2, x2, y2, argb, sw, sh);
        WorldMapRenderer.drawThinSegment(ctx, x1, y1, x1, y2, argb, sw, sh);
        WorldMapRenderer.drawThinSegment(ctx, x2, y1, x2, y2, argb, sw, sh);
    }
}
