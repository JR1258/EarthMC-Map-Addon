package net.townymap.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.townymap.model.TownData;

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
 * <p>For a town, this class owns only the TIMING. {@link WorldMapRenderer} draws the border: the claim
 * moves onto its highlight layer for the duration, so the white line IS the town's border on the
 * renderer's own geometry, and {@link #tint()} blinks it by driving that layer's blit alpha -- which
 * costs no re-bake, so the blink is free however fast it runs.
 *
 * <p>A player has no claim to take over, so the one thing drawn here is a blinking mark on the spot.
 */
public final class SearchFlash {

    private SearchFlash() {}

    /** Long enough to find on a busy map, short enough not to become part of the furniture. */
    private static final long DURATION_MS = 4_000L;
    /** One on-off cycle of the border. Just under two blinks a second: quick enough to catch the eye,
     *  slow enough not to strobe. */
    private static final long BLINK_MS = 550L;
    /** Share of a cycle spent fading in or out. A hard square wave at this rate reads as a strobe. */
    private static final double BLINK_RAMP = 0.12;
    /** Chrome: a near-white border. */
    private static final int CORE_RGB = 0xF2F6FF;

    /** Where a point flash sits. Towns need no geometry here -- the renderer owns their border. */
    private record Shape(int minX, int maxX, int minZ, int maxZ) {}

    /** Point flashes only (a player). Empty for a town flash. */
    private static volatile List<Shape> shapes = List.of();
    /** Lower-cased names of the claims whose border the renderer should take over. */
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
        java.util.Set<String> nextKeys = new java.util.HashSet<>(Math.max(4, wanted.size() * 2));
        for (TownData town : towns) {
            if (wanted.contains(town.key())) nextKeys.add(town.key());
        }
        if (nextKeys.isEmpty()) {
            clear();
            return;
        }
        shapes = List.of();            // the renderer draws the borders; nothing to draw here
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
        long started = startedMs;
        if (started == 0L) return false;
        long age = System.currentTimeMillis() - started;
        return age >= 0 && age < DURATION_MS;
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
     * The colour the flashed border is drawn in, blinking through its ALPHA.
     *
     * <p>Alpha is what makes this a blink rather than a pulse. The tile path applies this as a blit tint
     * and a flashing claim's border is not on the main layer, so alpha 0 leaves the town showing its own
     * fill with no border at all and full alpha puts a clean white border back. The direct path lands in
     * the same place for free: it already skips the outline when the border colour is transparent, and it
     * does so after drawing the fill.
     */
    public static int tint() {
        long started = startedMs;
        if (started == 0L) return 0xFF000000 | CORE_RGB;
        long age = System.currentTimeMillis() - started;
        if (age < 0) return 0xFF000000 | CORE_RGB;
        // Fade the blink out over the last stretch so the border comes back without a snap.
        double life = 1.0 - Math.max(0.0, (age - DURATION_MS * 0.6) / (DURATION_MS * 0.4));
        double strength = blink(age) * Math.max(0.0, Math.min(1.0, life));
        int alpha = (int) Math.round(255 * strength);
        return (Math.max(0, Math.min(255, alpha)) << 24) | CORE_RGB;
    }

    /** 0 while the border is hidden, 1 at full strength, with short ramps between. */
    private static double blink(long age) {
        double t = (age % BLINK_MS) / (double) BLINK_MS;
        if (t < BLINK_RAMP) return t / BLINK_RAMP;
        if (t < 0.5) return 1.0;
        if (t < 0.5 + BLINK_RAMP) return 1.0 - (t - 0.5) / BLINK_RAMP;
        return 0.0;
    }

    public static void render(GuiGraphicsExtractor ctx, double cameraX, double cameraZ,
                              double blockScale, int sw, int sh) {
        // A town flash draws nothing here, but this is still where an expired one is let go of.
        if (startedMs != 0L && !active()) clear();

        List<Shape> snapshot = shapes;
        if (snapshot.isEmpty() || blockScale <= 0) return;

        int argb = tint();
        if ((argb >>> 24) < 6) return;   // mid-blink, or faded out
        for (Shape shape : snapshot) {
            drawPointMark(ctx, shape, argb, cameraX, cameraZ, blockScale, sw, sh);
        }
    }

    /** A player's position: no claim for the renderer to take over, so mark the spot and blink that. */
    private static void drawPointMark(GuiGraphicsExtractor ctx, Shape shape, int argb,
                                      double cameraX, double cameraZ, double blockScale, int sw, int sh) {
        double cx = (shape.minX() + shape.maxX()) / 2.0;
        double cz = (shape.minZ() + shape.maxZ()) / 2.0;
        double r = Math.max(shape.maxX() - cx, shape.maxZ() - cz) * 0.35;
        box(ctx, cx - r, cz - r, cx + r, cz + r, argb, cameraX, cameraZ, blockScale, sw, sh);
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
