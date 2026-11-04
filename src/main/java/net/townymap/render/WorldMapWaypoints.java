package net.townymap.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.townymap.TownyMapConfig;
import net.townymap.TownyMapMod;
import org.joml.Matrix3x2fStack;
import xaero.map.mods.SupportMods;
import xaero.map.mods.SupportXaeroMinimap;
import xaero.map.mods.gui.WaypointRenderContext;
import xaero.map.mods.gui.WaypointRenderer;

import java.util.List;

/**
 * Redraws Xaero's world-map waypoints on top of our map layer.
 *
 * <p>Not a nicety, and not a choice. Xaero draws map elements through its own buffer provider, whose
 * endBatch() uploads a StagedVertexBuffer and renders it there and then, while everything we draw goes
 * through GuiGraphicsExtractor, which only RECORDS into GuiRenderState -- and that state is composited
 * after the screen's render call returns. Our layer therefore lands on top of Xaero's waypoints from any
 * injection point in extractRenderState, and the opaque fill that opens renderSquaremapBackground buries
 * them. Putting Xaero's own markers back on top would mean moving the entire squaremap tile layer into
 * Xaero's immediate pipeline; drawing them again is what fits.
 *
 * <p>So this is a copy of Xaero's marker rather than an invention, decoded from
 * WaypointRenderer.renderElement: the same sprite out of the same atlas, the same bottom-centre anchor,
 * the same tint, and -- this is the part that matters -- the same SIZE. Xaero draws map elements in raw
 * window pixels, so a sprite we blit at 30x30 GUI pixels came out guiScale times too big and no longer
 * covered the hitbox Xaero tests the cursor against. Matching its size is what makes the marker behave
 * like a waypoint: hovering it raises Xaero's own tooltip and right-clicking it opens Xaero's own menu,
 * both of which are drawn through ctx (WaypointMenuRenderer.postMapRender) and so were never hidden.
 */
public final class WorldMapWaypoints {

    private static final Identifier GUI_TEXTURES =
            Identifier.fromNamespaceAndPath("xaeroworldmap", "gui/gui.png");
    private static final int ATLAS = 256;

    // Sprite of the pin, straight out of renderElement. A symbol of more than one character uses the
    // wider pin 35px to the right, and a temporary waypoint its own variant 83px further still.
    private static final int SPRITE_U = 35;
    private static final int SPRITE_V = 34;
    private static final int SPRITE_W = 30;
    private static final int SPRITE_H = 43;
    private static final int WIDE_U_STEP = 35;
    private static final int WIDE_W_STEP = 13;
    private static final int TEMPORARY_U_STEP = 83;

    /**
     * Xaero rasterises the symbol into an icon atlas at three times the font's own size and blits that,
     * which is why renderElement reserves (font.width - 1) * 3 for it. Drawn 1:1 the character came out
     * a third of the size of the real marker's and read as a different thing entirely.
     */
    private static final int SYMBOL_SCALE = 3;
    /** Text origin inside the pin's head, in the symbol's own tripled space. */
    private static final int SYMBOL_Y = -13;
    /** Same, for the symbol drawn on its own with the pin switched off. */
    private static final int BARE_SYMBOL_Y = -4;
    /** Xaero's own dimming for a waypoint that is switched off but still shown. */
    private static final int DISABLED_ALPHA = 0x4D;
    /** Beyond this far outside the window the marker cannot be visible. */
    private static final int MARGIN = 48;

    private WorldMapWaypoints() {}

    /**
     * @param cameraX Xaero's own camera, not the overlay's -- getRenderX/Z are in that same space, so
     *                pairing them avoids re-deriving a projection that is already correct.
     * @param guiScale Xaero's scale/screenScale, for the same reason.
     */
    public static void render(GuiGraphicsExtractor ctx, double cameraX, double cameraZ,
                              double guiScale, int sw, int sh) {
        TownyMapConfig config = TownyMapMod.getConfig();
        if (config == null || !config.squaremapOnWorldMap()) return;   // nothing of ours is covering them
        // Their coordinates belong to the world the player is in; on a map of another world they would
        // mark ground nobody has stood on. Xaero's own copy is already suppressed in that case.
        if (TownyMapMod.hidePlayerWorldMarkers()) return;
        if (!SupportMods.minimap()) return;
        SupportXaeroMinimap support = SupportMods.xaeroMinimap;
        if (support == null) return;

        List<xaero.map.mods.gui.Waypoint> waypoints = support.getWaypointsSorted();
        if (waypoints == null || waypoints.isEmpty()) return;

        // Xaero's own settings for these markers, read off the renderer rather than guessed: the pin can
        // be switched off on its own, disabled waypoints can be kept on the map, and the whole marker has
        // a size slider.
        float waypointsScale = 1.0f;
        boolean pin = true;
        boolean showDisabled = false;
        WaypointRenderer renderer = support.getWaypointRenderer();
        WaypointRenderContext settings = renderer == null ? null : renderer.getContext();
        if (settings != null) {
            waypointsScale = settings.worldmapWaypointsScale;
            pin = settings.waypointBackgrounds;
            showDisabled = settings.showDisabledWaypoints;
        }

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        // Xaero draws map elements at 1 sprite pixel per WINDOW pixel, scaled by getScaleMultiplier() of
        // the smaller window dimension. Dividing by the GUI scale converts that to the GUI-scaled pixels
        // ctx works in -- without it the marker comes out guiScale times too big, which is what made it
        // miss the hitbox Xaero hit-tests the cursor against.
        int minDim = Math.min(mc.getWindow().getWidth(), mc.getWindow().getHeight());
        double screenSizeBasedScale = minDim <= 1100 ? 1.0 : minDim / 1080.0;
        double screenScale = Math.max(1, mc.getWindow().getGuiScale());
        float markerScale = (float) (screenSizeBasedScale * waypointsScale / screenScale);
        if (markerScale <= 0.0f) return;

        Matrix3x2fStack m = ctx.pose();
        for (xaero.map.mods.gui.Waypoint waypoint : waypoints) {
            if (waypoint == null) continue;
            boolean disabled = waypoint.isDisabled();
            if (disabled && !showDisabled) continue;

            float x = (float) (sw / 2.0 + (waypoint.getRenderX() - cameraX) * guiScale);
            float y = (float) (sh / 2.0 + (waypoint.getRenderZ() - cameraZ) * guiScale);
            if (x < -MARGIN || x > sw + MARGIN || y < -MARGIN || y > sh + MARGIN) continue;

            String symbol = waypoint.getSymbol();
            int u = SPRITE_U;
            int w = SPRITE_W;
            if (symbol != null && symbol.length() > 1) {
                u += WIDE_U_STEP;
                w += WIDE_W_STEP;
            }
            if (waypoint.isTemporary()) u += TEMPORARY_U_STEP;

            // getColor() is a packed RGB, not a palette index -- renderElement pulls r/g/b straight out
            // of it by shifting.
            int alpha = disabled ? DISABLED_ALPHA : 0xFF;
            int tint = (alpha << 24) | (waypoint.getColor() & 0x00FFFFFF);
            int symbolTint = (alpha << 24) | 0x00FFFFFF;

            // The tip of the pin sits on the waypoint, so everything hangs off a bottom-centre origin.
            m.pushMatrix();
            m.translate(x, y);
            m.scale(markerScale, markerScale);
            int top = -(SPRITE_H - 1);
            if (pin) {
                ctx.blit(RenderPipelines.GUI_TEXTURED, GUI_TEXTURES, -(w / 2), top, u, SPRITE_V,
                        w, SPRITE_H, ATLAS, ATLAS, tint);
            }
            if (symbol != null && !symbol.isEmpty()) {
                m.scale(SYMBOL_SCALE, SYMBOL_SCALE);
                ctx.text(font, symbol, -(font.width(symbol) / 2),
                        pin ? SYMBOL_Y : BARE_SYMBOL_Y, symbolTint, false);
            }
            m.popMatrix();
            // No name label: Xaero only fades one in while the waypoint is hovered, and on a hover its
            // tooltip says the same thing through ctx, where it is already visible over our layer.
        }
    }
}
