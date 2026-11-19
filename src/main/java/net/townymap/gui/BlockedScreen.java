package net.townymap.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.Util;
import net.minecraft.text.Text;

import java.net.URI;

/**
 * Shown once when the player's UUID or nation is on the access blocklist.
 *
 * <p>Deliberately only informs: the game and every other mod carry on normally, and dismissing it
 * returns the player to whatever they were doing. It never blocks play — only this mod's features.
 */
public final class BlockedScreen extends Screen {

    private static final int PANEL_BG = 0xE8121317;
    private static final int PANEL_BORDER = 0xCC3A3D42;
    private static final int ACCENT = 0xFFE2603B;

    private final Screen parent;
    private final String message;

    public BlockedScreen(Screen parent, String message) {
        super(Text.literal("EarthMC Map Addon"));
        this.parent = parent;
        this.message = message == null || message.isBlank()
                ? "Your access to the EarthMC Map Addon has been removed. If you believe this is a "
                  + "mistake, please contact the developer of the mod."
                : message;
    }

    /** The developer's Discord profile, so an appeal has somewhere to go. */
    private static final String DISCORD_URL = "https://discord.com/users/730747321238945803";

    @Override
    protected void init() {
        int y = this.height / 2 + 52;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Message the developer"),
                        b -> Util.getOperatingSystem().open(URI.create(DISCORD_URL)))
                .dimensions(this.width / 2 - 108, y, 130, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Acknowledge"), b -> this.close())
                .dimensions(this.width / 2 + 28, y, 80, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int w = Math.min(this.width - 60, 380);
        int left = (this.width - w) / 2;
        int top = this.height / 2 - 70;
        int bottom = this.height / 2 + 44;

        ctx.fill(left - 1, top - 1, left + w + 1, bottom + 1, PANEL_BORDER);
        ctx.fill(left, top, left + w, bottom, PANEL_BG);
        ctx.fill(left, top, left + w, top + 3, ACCENT);

        ctx.drawText(this.textRenderer, "Access removed", left + 12, top + 14, 0xFFFFFFFF, false);
        int y = top + 34;
        for (var line : this.textRenderer.wrapLines(Text.literal(message), w - 24)) {
            ctx.drawText(this.textRenderer, line, left + 12, y, 0xFFC8C8C8, false);
            y += 11;
        }

        // Widgets last; see ApiKeyNoticeScreen. "Message the developer" and "Acknowledge" were
        // buried under this panel exactly the same way.
        super.render(ctx, mouseX, mouseY, delta);
    }

    /** Esc dismisses it like the button; this is a notice, not a wall. */
    @Override
    public void close() {
        this.client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
