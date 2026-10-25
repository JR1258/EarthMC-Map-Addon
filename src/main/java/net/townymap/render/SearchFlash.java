package net.townymap.render;

import net.minecraft.client.gui.DrawContext;
import net.townymap.model.TownData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The "here it is" flash that follows a search jump.
 *
 * <p>Centring on a result put the right place under the crosshair and nothing more: on a map where every
 * neighbour is also an outlined polygon, the town you asked for looked exactly like the forty around it.
 *
 * <p>This class owns the TIMING and the halo only. The outline itself is drawn by
 * {@link WorldMapRenderer}, which swaps the claim onto its highlight layer for the duration -- so the
 * white line IS the town's border, on the renderer's own geometry, rather than a second line traced
 * beside it. Tracing the raw rings here instead was the bug: those are the unsimplified chunk corners,
 * rounded differently and stroked at a different width than the border actually on screen, so the white
 * sat next to the real outline instead of replacing it.
 */
public final class SearchFlash {

    private SearchFlash() {}

    /** Long enough to find on a busy map, short enough not to become part of the furniture. */
    private static final long DURATION_MS = 4_000L;
    /** One halo sweep from the claim edge outwards. */
    private static final long SWEEP_MS = 1_150L;
    /** How far past its own border a halo ring travels, in blocks. A constant distance rather than a
     *  share of the claim's size, so a hamlet and a metropolis get the same visual sweep. */
    private static final double SWEEP_REACH_BLOCKS = 80.0;
    /** Above this many flashed claims the halo is dropped -- a whole nation's worth of offset outlines is
     *  thousands of segments a frame, and by then the white borders are doing the job on their own. */
    private static final int HALO_MAX_CLAIMS = 12;
    /** Segments per halo ring. A claim's raw outline can carry hundreds of corners; stepping over them
     *  keeps the cost flat, and a glow outside the border does not need every one. */
    private static final int HALO_MAX_SEGMENTS = 120;
    /** Halo rings in flight at once, evenly offset through the sweep. */
    private static final int SWEEP_RINGS = 2;
    /** Chrome: a near-white core over a cool steel halo. */
    private static final int CORE_RGB = 0xF2F6FF;
    private static final int HALO_RGB = 0x9FC2E8;

    /**
     * One flashed claim: the outer ring its halo is offset from, and the box used instead when there is
     * no ring (a player's position). The BORDER is the renderer's business -- these rings are only ever
     * pushed outwards into open ground, so being a refresh behind cannot misplace anything.
     */
    private record Shape(int[] ringX, int[] ringZ, int minX, int maxX, int minZ, int maxZ) {
        boolean hasRing() { return ringX != null && ringX.length >= 3; }
        double centreX() { return (minX + maxX) / 2.0; }
        double centreZ() { return (minZ + maxZ) / 2.0; }
    }

    private static volatile List<Shape> shapes = List.of();
    /** Lower-cased names of the claims whose outline the renderer should take over. */
    private static volatile Set<String> townKeys = Set.of();
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
        List<Shape> nextShapes = new ArrayList<>(wanted.size());
        java.util.Set<String> nextKeys = new java.util.HashSet<>(Math.max(4, wanted.size() * 2));
        for (TownData town : towns) {
            if (!wanted.contains(town.key())) continue;
            nextShapes.add(outerRing(town));
            nextKeys.add(town.key());
        }
        if (nextShapes.isEmpty()) {
            clear();
            return;
        }
        shapes = List.copyOf(nextShapes);
        townKeys = Set.copyOf(nextKeys);
        startedMs = System.currentTimeMillis();
    }

    /** The claim's outer boundary, which is the first ring by GeoJSON convention -- later rings are
     *  unclaimed pockets inside it, and a halo has no business tracing those. */
    private static Shape outerRing(TownData town) {
        List<int[][]> rings = town.polygonRings();
        if (rings.isEmpty()) {
            return new Shape(null, null, town.minX(), town.maxX(), town.minZ(), town.maxZ());
        }
        int[][] ring = rings.get(0);
        int[] xs = new int[ring.length];
        int[] zs = new int[ring.length];
        int n = 0;
        for (int[] point : ring) {
            if (point.length < 2) continue;
            xs[n] = point[0];
            zs[n] = point[1];
            n++;
        }
        if (n < 3) {
            return new Shape(null, null, town.minX(), town.maxX(), town.minZ(), town.maxZ());
        }
        return new Shape(java.util.Arrays.copyOf(xs, n), java.util.Arrays.copyOf(zs, n),
                town.minX(), town.maxX(), town.minZ(), town.maxZ());
    }

    /** Arms the flash on a bare point -- a player, who has no claim of their own to take over. */
    public static void armPoint(int x, int z, int span) {
        int half = Math.max(8, span / 2);
        shapes = List.of(new Shape(null, null, x - half, x + half, z - half, z + half));
        townKeys = Set.of();
        startedMs = System.currentTimeMillis();
    }

    public static void clear() {
        shapes = List.of();
        townKeys = Set.of();
        startedMs = 0L;
    }

    public static boolean active() {
        return startedMs != 0L && System.currentTimeMillis() - startedMs < DURATION_MS;
    }

    /**
     * The claims whose border the renderer should draw in the flash colour, or empty when nothing is
     * flashing. Read once per frame: the renderer re-bakes its outline tiles when this changes, which is
     * what makes the swap a replacement rather than a second line on top.
     */
    public static Set<String> activeTownKeys() {
        return active() ? townKeys : Set.of();
    }

    /**
     * The colour the flashed border is drawn in. Opaque, so it fully replaces what was there, and it
     * pulses in BRIGHTNESS rather than alpha: the tile path applies this as a blit tint, so dropping the
     * alpha would fade the whole line out instead of making it breathe. Free either way -- the tint is
     * read per frame and never re-bakes.
     */
    public static int tint() {
        long started = startedMs;
        if (started == 0L) return 0xFF000000 | CORE_RGB;
        double beat = 0.5 + 0.5 * Math.sin((System.currentTimeMillis() - started) / 150.0);
        double mix = 0.72 + 0.28 * beat;   // steel at the trough, near-white at the peak
        int r = (int) Math.round(((CORE_RGB >> 16) & 0xFF) * mix + ((HALO_RGB >> 16) & 0xFF) * (1 - mix));
        int g = (int) Math.round(((CORE_RGB >> 8) & 0xFF) * mix + ((HALO_RGB >> 8) & 0xFF) * (1 - mix));
        int b = (int) Math.round((CORE_RGB & 0xFF) * mix + (HALO_RGB & 0xFF) * (1 - mix));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
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
        boolean pointOnly = townKeys.isEmpty();
        double beat = 0.5 + 0.5 * Math.sin(age / 150.0);

        boolean halo = snapshot.size() <= HALO_MAX_CLAIMS;
        for (Shape shape : snapshot) {
            if (halo) drawHalo(ctx, shape, age, life, cameraX, cameraZ, blockScale, sw, sh);
            // A player has no claim for the renderer to take over, so the box itself is the marker.
            if (pointOnly) drawPointMark(ctx, shape, life, beat, cameraX, cameraZ, blockScale, sw, sh);
        }
    }

    /**
     * Copies of the claim's own outline pushed outwards, offset through the sweep so one is always
     * leaving. Each vertex moves a constant distance along its own radius from the claim's centre, so the
     * halo keeps the claim's shape -- an L-shaped town gets an L-shaped glow -- instead of the bounding
     * box it used to grow, which sat in open ground on any claim that was not a rectangle.
     */
    private static void drawHalo(DrawContext ctx, Shape shape, long age, double life,
                                 double cameraX, double cameraZ, double blockScale, int sw, int sh) {
        for (int i = 0; i < SWEEP_RINGS; i++) {
            double phase = ((age + (long) (SWEEP_MS * i / (double) SWEEP_RINGS)) % SWEEP_MS) / (double) SWEEP_MS;
            // Brightest as it leaves the border, gone by the time it reaches full reach.
            int alpha = (int) Math.round(150 * life * (1.0 - phase) * (1.0 - phase));
            if (alpha < 6) continue;
            int argb = (alpha << 24) | HALO_RGB;
            double out = SWEEP_REACH_BLOCKS * phase;
            if (shape.hasRing()) {
                offsetRing(ctx, shape, out, argb, cameraX, cameraZ, blockScale, sw, sh);
            } else {
                // No claim to follow (a player): the box is all there is.
                double halfX = (shape.maxX() - shape.minX()) / 2.0 + out;
                double halfZ = (shape.maxZ() - shape.minZ()) / 2.0 + out;
                double cx = shape.centreX(), cz = shape.centreZ();
                box(ctx, cx - halfX, cz - halfZ, cx + halfX, cz + halfZ, argb,
                        cameraX, cameraZ, blockScale, sw, sh);
            }
        }
    }

    /** The claim's outline with every vertex pushed {@code out} blocks further from its centre. */
    private static void offsetRing(DrawContext ctx, Shape shape, double out, int argb,
                                   double cameraX, double cameraZ, double blockScale, int sw, int sh) {
        int[] xs = shape.ringX();
        int[] zs = shape.ringZ();
        int n = xs.length;
        int stride = Math.max(1, n / HALO_MAX_SEGMENTS);
        double cx = shape.centreX(), cz = shape.centreZ();
        // One pass, no buffers: carry the previous point and the first point, then close the loop at the
        // end. Each vertex moves out along its own radius, which is what preserves the claim's shape.
        double firstX = 0, firstY = 0, px = 0, py = 0;
        int drawn = 0;
        for (int i = 0; i < n; i += stride) {
            double dx = xs[i] - cx, dz = zs[i] - cz;
            double r = Math.hypot(dx, dz);
            double f = r < 1e-6 ? 0.0 : (r + out) / r;
            double x = (cx + dx * f - cameraX) * blockScale + sw / 2.0;
            double y = (cz + dz * f - cameraZ) * blockScale + sh / 2.0;
            if (drawn == 0) {
                firstX = x;
                firstY = y;
            } else {
                WorldMapRenderer.drawThinSegment(ctx, px, py, x, y, argb, sw, sh);
            }
            px = x;
            py = y;
            drawn++;
        }
        if (drawn >= 3) WorldMapRenderer.drawThinSegment(ctx, px, py, firstX, firstY, argb, sw, sh);
    }

    private static void drawPointMark(DrawContext ctx, Shape shape, double life, double beat,
                                      double cameraX, double cameraZ, double blockScale, int sw, int sh) {
        int alpha = (int) Math.round((110 + 90 * beat) * life);
        if (alpha < 6) return;
        double cx = (shape.minX() + shape.maxX()) / 2.0;
        double cz = (shape.minZ() + shape.maxZ()) / 2.0;
        double r = Math.max(shape.maxX() - cx, shape.maxZ() - cz) * 0.35;
        box(ctx, cx - r, cz - r, cx + r, cz + r, (Math.min(255, alpha) << 24) | CORE_RGB,
                cameraX, cameraZ, blockScale, sw, sh);
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
