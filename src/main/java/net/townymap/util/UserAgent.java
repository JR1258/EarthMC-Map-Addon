package net.townymap.util;

import net.fabricmc.loader.api.FabricLoader;

/**
 * The identity we present to EarthMC on every request.
 *
 * <p>This used to be the literal string {@code "TownyMapAddon/1.0 (Fabric Mod)"}, hardcoded at four
 * call sites and stuck at 1.0 since the first release. Server access logs group by user agent, so
 * every build the mod has ever shipped arrived looking identical: EarthMC could see how much traffic
 * came from the mod, but not which versions caused it, and neither could we.
 *
 * <p>That matters whenever the mod's footprint changes. A build that asks for gzip and one that does
 * not differ several-fold on the same work, and with a fixed agent string the improvement is
 * invisible in the only logs that actually measure it -- theirs.
 *
 * <p>Read from the mod metadata so it cannot drift again, and through FabricLoader rather than the
 * game's own version constant so the same source compiles on every branch.
 */
public final class UserAgent {

    private static volatile String cached;

    private UserAgent() {}

    /** e.g. {@code EMCMapAddon/1.4.9 (Fabric; MC 26.2)}. */
    public static String get() {
        String ua = cached;
        if (ua == null) {
            ua = "EMCMapAddon/" + version("townymapaddon") + " (Fabric; MC " + version("minecraft") + ")";
            cached = ua;
        }
        return ua;
    }

    private static String version(String modId) {
        try {
            return FabricLoader.getInstance().getModContainer(modId)
                    .map(m -> m.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown");
        } catch (Throwable t) {
            return "unknown";   // never let identifying ourselves break a request
        }
    }
}
