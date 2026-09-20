package net.townymap.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.townymap.TownyMapConfig;
import net.townymap.TownyMapMod;

/**
 * Shared flat dark-button styling for the on-map UI, used when the "Dark Buttons" setting is on so the
 * toggle/settings/route/discord buttons all match instead of the vanilla textured widgets. A soft slate
 * body with a 1px top highlight + bottom shade reads as a proper button rather than a flat black box.
 */
public final class DarkButtons {

    private DarkButtons() {}

    public static boolean enabled() {
        TownyMapConfig c = TownyMapMod.getConfig();
        return c != null && c.darkButtons;
    }

    public static void draw(DrawContext ctx, int x, int y, int w, int h, String label,
                            boolean active, int textColor, int mouseX, int mouseY) {
        boolean hover = active && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int body = !active ? 0xFF1E2024 : hover ? 0xFF34373F : 0xFF26282E;
        ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF101114);    // outer border
        ctx.fill(x, y, x + w, y + h, body);                          // body
        ctx.fill(x, y, x + w, y + 1, 0x1AFFFFFF);                    // top highlight
        ctx.fill(x, y + h - 1, x + w, y + h, 0x22000000);            // bottom shade
        MinecraftClient mc = MinecraftClient.getInstance();
        drawLabel(ctx, mc, x, y, w, h, label, active ? textColor : 0xFF7A7A7A);
    }

    // Vanilla's own scroll timing, so a dark button reads the same as a textured one next to it.
    private static final double PERIOD_PER_SCROLLED_PIXEL = 0.5;
    private static final double MIN_SCROLL_PERIOD = 3.0;
    private static final int LABEL_PAD = 2;

    /**
     * Centres the label, or scrolls it inside the button when it is too wide.
     *
     * <p>Vanilla buttons have always done this -- a label that does not fit is clipped to the widget
     * and panned back and forth. Ours just centred it, which was invisible while every label was short
     * and started spilling out of both ends once "World" began reporting the world it resolved to
     * ("World: Auto: Terra Nostra"). Same easing and period as vanilla so the two styles match.
     */
    private static void drawLabel(DrawContext ctx, MinecraftClient mc,
                                  int x, int y, int w, int h, String label, int color) {
        int textY = y + (h - 8) / 2;
        int width = mc.textRenderer.getWidth(label);
        int left = x + LABEL_PAD;
        int available = w - LABEL_PAD * 2;
        if (width <= available) {
            ctx.drawText(mc.textRenderer, label, x + (w - width) / 2, textY, color, false);
            return;
        }
        int overflow = width - available;
        double period = Math.max(overflow * PERIOD_PER_SCROLLED_PIXEL, MIN_SCROLL_PERIOD);
        double phase = Math.sin((Math.PI / 2) * Math.cos((Math.PI * 2) * (Util.getMeasuringTimeMs() / 1000.0) / period)) / 2.0 + 0.5;
        int offset = (int) Math.round(MathHelper.lerp(phase, 0.0, overflow));
        // Clip to the body, not the border, so the text never touches the frame.
        ctx.enableScissor(left, y, x + w - LABEL_PAD, y + h);
        try {
            ctx.drawText(mc.textRenderer, label, left - offset, textY, color, false);
        } finally {
            ctx.disableScissor();
        }
    }
}
