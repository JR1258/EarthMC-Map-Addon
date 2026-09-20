package net.townymap.input;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.resources.Identifier;
import net.townymap.TownyMapMod;

public final class TownyMapKeybinds {

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("townymapaddon", "keybinds"));

    private static KeyMapping mapScreenshot;
    private static KeyMapping refreshTowns;
    private static KeyMapping openStats;

    private TownyMapKeybinds() {
    }

    public static void register() {
        // Everything the removed binds covered has an on-map button. These two do not: the screenshot
        // needs a frame drawn without our chrome, and a claim refresh is worth having under a key when
        // the map is closed. Refresh ships unbound so it cannot collide with another mod's default.
        mapScreenshot = register("map_screenshot", InputConstants.KEY_P);
        refreshTowns = register("refresh_towns", InputConstants.KEY_R);
        openStats = register("open_stats", InputConstants.KEY_J);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Blocked access turns every bind into a no-op, but the presses are still consumed so they
            // do not fall through to something else.
            TownyMapMod.tickAccessNotice();   // shows the blocked notice once a world is loaded
            TownyMapMod.tickWorldChange();    // Terra Nostra <-> Moon, on the main thread
            if (TownyMapMod.isAccessBlocked()) {
                while (mapScreenshot.consumeClick()) { /* discard */ }
                while (refreshTowns.consumeClick()) { /* discard */ }
                while (openStats.consumeClick()) { /* discard */ }
                return;
            }
            // Only meaningful with the world map open — the countdown advances in its render. Arming with
            // it closed used to leave the "hide our UI" flag set indefinitely.
            while (mapScreenshot.consumeClick()) {
                if (TownyMapMod.isWorldMapOpen()) TownyMapMod.armMapScreenshot();
            }
            // The capture itself waits for a frame drawn without our chrome; see TownyMapMod.
            TownyMapMod.captureMapScreenshotIfReady();

            // Same entry point as the settings button: forces a squaremap reload and confirms in chat.
            // There is no cooldown, so the chat line is the only thing stopping a held key going unnoticed.
            while (refreshTowns.consumeClick()) TownyMapMod.refreshTownClaimsFromSettings();

            // Stats are built from data already in memory, so this opens straight from gameplay with no
            // map screen in between and nothing to wait for.
            while (openStats.consumeClick()) TownyMapMod.openStatsPanel();
        });
    }

    /** True if this press matches the refresh bind — map screens swallow keys, so they ask explicitly. */
    public static boolean isRefreshKey(net.minecraft.client.input.KeyEvent input) {
        return refreshTowns != null && !refreshTowns.isUnbound() && refreshTowns.matches(input);
    }

    /** True if this press matches the info-panel bind, for use inside map screens. */
    public static boolean isOpenStatsKey(net.minecraft.client.input.KeyEvent input) {
        return openStats != null && !openStats.isUnbound() && openStats.matches(input);
    }

    /** True if this key press matches the (rebindable) clean-screenshot bind, for use inside map screens. */
    public static boolean isMapScreenshotKey(net.minecraft.client.input.KeyEvent input) {
        return mapScreenshot != null && !mapScreenshot.isUnbound() && mapScreenshot.matches(input);
    }

    private static KeyMapping register(String id, int defaultKey) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.townymapaddon." + id,
                InputConstants.Type.KEYBOARD,
                defaultKey,
                CATEGORY
        ));
    }

    /** The screenshot bind's current key, for the settings row. */
    public static KeyMapping mapScreenshotBinding() {
        return mapScreenshot;
    }

    /** The stats bind's current key, for the settings row. */
    public static String openStatsKeyName() {
        if (openStats == null) return "\u2014";
        return openStats.isUnbound() ? "Not bound" : openStats.getTranslatedKeyMessage().getString();
    }

    /** Rebinds the stats key from our settings screen; same write-through as the others. */
    public static void setOpenStatsKey(int keyCode) {
        if (openStats == null) return;
        openStats.setKey(keyCode == InputConstants.UNKNOWN.getValue()
                ? InputConstants.UNKNOWN
                : InputConstants.Type.KEYBOARD.getOrCreate(keyCode));
        KeyMapping.resetMapping();
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.options != null) mc.options.save();
    }

    /** The refresh bind's current key, for the settings row. */
    public static KeyMapping refreshTownsBinding() {
        return refreshTowns;
    }

    /** Display name of the refresh bind's key, e.g. "R" or "Not bound". */
    public static String refreshTownsKeyName() {
        if (refreshTowns == null) return "\u2014";
        return refreshTowns.isUnbound()
                ? "Not bound"
                : refreshTowns.getTranslatedKeyMessage().getString();
    }

    /** Rebinds the refresh key from our settings screen; same write-through as the screenshot bind. */
    public static void setRefreshTownsKey(int keyCode) {
        if (refreshTowns == null) return;
        refreshTowns.setKey(keyCode == InputConstants.UNKNOWN.getValue()
                ? InputConstants.UNKNOWN
                : InputConstants.Type.KEYBOARD.getOrCreate(keyCode));
        KeyMapping.resetMapping();
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.options != null) mc.options.save();
    }

    /** Display name of the bound key, e.g. "P" or "Not bound". */
    public static String mapScreenshotKeyName() {
        if (mapScreenshot == null) return "—";
        return mapScreenshot.isUnbound()
                ? "Not bound"
                : mapScreenshot.getTranslatedKeyMessage().getString();
    }

    /**
     * Rebinds the screenshot key from our own settings screen.
     *
     * <p>Writes through to the same KeyMapping vanilla's Controls screen edits and saves options, so the two
     * always agree — this is a second door onto one setting, not a copy of it.
     */
    public static void setMapScreenshotKey(int keyCode) {
        if (mapScreenshot == null) return;
        mapScreenshot.setKey(keyCode == InputConstants.UNKNOWN.getValue()
                ? InputConstants.UNKNOWN
                : InputConstants.Type.KEYBOARD.getOrCreate(keyCode));
        KeyMapping.resetMapping();
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.options != null) mc.options.save();
    }
}
