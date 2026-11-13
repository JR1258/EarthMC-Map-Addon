package net.townymap.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Shown once, on the first launch where no EarthMC API key is set.
 *
 * <p>Built to inform, not to block: the addon is fully usable without a key and says so plainly.
 * Everything drawn on the map -- borders, town names, nation colours, players, the imagery layer,
 * hovering a town, searching by name -- comes from the live map and needs nothing. A key only adds
 * what EarthMC keeps in its API alone.
 *
 * <p>Deliberately a sibling of {@link BlockedScreen} rather than a generalisation of it: that one
 * carries a message from the developer and an appeal route, this one carries instructions and a way
 * into the settings, and the only thing they genuinely share is how they look.
 */
public final class ApiKeyNoticeScreen extends Screen {

    private static final int PANEL_BG = 0xE8121317;
    private static final int PANEL_BORDER = 0xCC3A3D42;
    private static final int ACCENT = 0xFF57A8FF;

    private final Screen parent;

    private static final List<String> BODY = List.of(
            "The addon works as it is. The map, town borders, nation colours, players,",
            "town names and search all read the live map and need no key at all.",
            "",
            "An EarthMC API key adds what only EarthMC holds: a town's bank balance,",
            "whether it is open or for sale, the overclaim and outsider-spawn overlays,",
            "its claim limit and active residents, player cards and nation pages.",
            "",
            "To add one: run  /api key create  in game, copy the key it gives you,",
            "then paste it into Settings -> Advanced -> EarthMC API Key.",
            "",
            "EarthMC requires a key on its API from 19 November. The map side of this",
            "addon is unaffected either way.");

    public ApiKeyNoticeScreen(Screen parent) {
        super(Component.literal("EarthMC Map Addon"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int y = this.height / 2 + 68;
        this.addRenderableWidget(Button.builder(Component.literal("Open settings"),
                        b -> this.minecraft.gui.setScreen(new TownyMapConfigScreen(parent)))
                .bounds(this.width / 2 - 108, y, 100, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Continue without"),
                        b -> this.onClose())
                .bounds(this.width / 2 + 2, y, 106, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        int w = Math.min(this.width - 60, 440);
        int left = (this.width - w) / 2;
        int top = this.height / 2 - 86;
        int bottom = this.height / 2 + 60;

        ctx.fill(left - 1, top - 1, left + w + 1, bottom + 1, PANEL_BORDER);
        ctx.fill(left, top, left + w, bottom, PANEL_BG);
        ctx.fill(left, top, left + w, top + 3, ACCENT);

        ctx.text(this.font, "Optional: add your EarthMC API key", left + 12, top + 14, 0xFFFFFFFF, false);
        int y = top + 32;
        for (String line : BODY) {
            if (!line.isEmpty()) ctx.text(this.font, line, left + 12, y, 0xFFC8C8C8, false);
            y += 10;
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
