package net.townymap.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;
import net.minecraft.network.chat.Component;

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
        super(Component.literal("EarthMC Map Addon"));
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
        this.addRenderableWidget(Button.builder(Component.literal("Message the developer"),
                        b -> com.mojang.blaze3d.Blaze3D.openUri(URI.create(DISCORD_URL)))
                .bounds(this.width / 2 - 108, y, 130, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Acknowledge"), b -> this.onClose())
                .bounds(this.width / 2 + 28, y, 80, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        int w = Math.min(this.width - 60, 380);
        int left = (this.width - w) / 2;
        int top = this.height / 2 - 70;
        int bottom = this.height / 2 + 44;

        ctx.fill(left - 1, top - 1, left + w + 1, bottom + 1, PANEL_BORDER);
        ctx.fill(left, top, left + w, bottom, PANEL_BG);
        ctx.fill(left, top, left + w, top + 3, ACCENT);

        ctx.text(this.font, "Access removed", left + 12, top + 14, 0xFFFFFFFF, false);
        int y = top + 34;
        for (var line : this.font.split(Component.literal(message), w - 24)) {
            ctx.text(this.font, line, left + 12, y, 0xFFC8C8C8, false);
            y += 11;
        }
    }

    /** Esc dismisses it like the button; this is a notice, not a wall. */
    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
