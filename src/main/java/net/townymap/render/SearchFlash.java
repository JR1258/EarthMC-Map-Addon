package net.townymap.render;

import net.townymap.model.TownData;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The "here it is" flash that follows a search jump to a town.
 *
 * <p>Centring on a result put the right place under the crosshair and nothing more: on a map where every
 * neighbour is also an outlined polygon, the town you asked for looked exactly like the forty around it.
 *
 * <p>This class draws nothing. It owns the timing, and {@link WorldMapRenderer} does the rest: the claim
 * stays baked normally and the renderer's highlight layer carries a white copy of its border over the
 * top, which {@link #tint()} blinks by driving that layer's blit alpha. Nothing is re-baked, so the
 * blink is free, and the border alternates between white and the town's own colour.
 *
 * <p>Only claims flash. A player has no border to take over, and a marker invented for one was a second
 * thing to look at next to the thing you were looking for -- the jump and its zoom are the answer there.
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
        townKeys = Set.copyOf(nextKeys);
        startedMs = System.currentTimeMillis();
    }

    public static void clear() {
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
     * flashing. Read once per frame: the renderer rebuilds its highlight tiles when this changes, which
     * is what puts the white border there and takes it away again. Also where an expired flash is let
     * go of, since nothing else looks at it.
     */
    public static Set<String> activeTownKeys() {
        if (startedMs != 0L && !active()) clear();
        return townKeys;
    }

    /**
     * The colour the flashed border is drawn in, blinking through its ALPHA.
     *
     * <p>Alpha is what makes this a blink rather than a pulse. The renderer applies this as a blit tint
     * over the town's own border, so alpha 0 shows the border in its real colour and full alpha covers
     * it in white. The direct path lands in the same place for free: it takes this colour only while the
     * blink is on and falls through to the town's own colour off-beat.
     */
    public static int tint() {
        long started = startedMs;
        if (started == 0L) return 0xFF000000 | CORE_RGB;
        long age = System.currentTimeMillis() - started;
        if (age < 0) return 0xFF000000 | CORE_RGB;
        // Fade the blink out over the last stretch so the border comes back without a snap.
        double life = 1.0 - Math.max(0.0, (age - DURATION_MS * 0.6) / (DURATION_MS * 0.4));
        int alpha = (int) Math.round(255 * blink(age) * Math.max(0.0, Math.min(1.0, life)));
        return (Math.max(0, Math.min(255, alpha)) << 24) | CORE_RGB;
    }

    /** 0 while the border shows its own colour, 1 at full white, with short ramps between. */
    private static double blink(long age) {
        double t = (age % BLINK_MS) / (double) BLINK_MS;
        if (t < BLINK_RAMP) return t / BLINK_RAMP;
        if (t < 0.5) return 1.0;
        if (t < 0.5 + BLINK_RAMP) return 1.0 - (t - 0.5) / BLINK_RAMP;
        return 0.0;
    }
}
