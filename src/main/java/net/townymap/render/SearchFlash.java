package net.townymap.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
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
    /** How far past the claim's own box a halo ring travels, as a share of the box's half-size. */
    private static final double SWEEP_REACH = 0.55;
    /** Halo rings in flight at once, evenly offset through the sweep. */
    private static final int SWEEP_RINGS = 2;
    /** Chrome: a near-white core over a cool steel halo. */
    private static final int CORE_RGB = 0xF2F6FF;
    private static final int HALO_RGB = 0x9FC2E8;

    /** The box one flashed claim's halo expands out of. The outline comes from the renderer. */
    private record Shape(int minX, int maxX, int minZ, int maxZ) {}

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
            nextShapes.add(new Shape(town.minX(), town.maxX(), town.minZ(), town.maxZ()));
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

    /** Arms the flash on a bare point -- a player, who has no claim of their own to take over. */
    public static void armPoint(int x, int z, int span) {
        int half = Math.max(8, span / 2);
        shapes = List.of(new Shape(x - half, x + half, z - half, z + half));
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

    public static void render(GuiGraphicsExtractor ctx, double cameraX, double cameraZ,
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

        for (Shape shape : snapshot) {
            drawHalo(ctx, shape, age, life, cameraX, cameraZ, blockScale, sw, sh);
            // A player has no claim for the renderer to take over, so the box itself is the marker.
            if (pointOnly) drawPointMark(ctx, shape, life, beat, cameraX, cameraZ, blockScale, sw, sh);
        }
    }

    /** Rings expanding outwards from the claim's box, offset through the sweep so one is always leaving. */
    private static void drawHalo(GuiGraphicsExtractor ctx, Shape shape, long age, double life,
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

    private static void drawPointMark(GuiGraphicsExtractor ctx, Shape shape, double life, double beat,
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
    private static void box(GuiGraphicsExtractor ctx, double wx1, double wz1, double wx2, double wz2,
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
