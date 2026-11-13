package net.townymap.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Shown once, on the first launch where no EarthMC API key is set.
 *
 * <p>Built to inform, not to block: the addon is fully usable without a key and the panel says so
 * before it says anything else. Everything drawn on the map -- borders, town names, nation colours,
 * players, the imagery layer, hovering a town, searching by name -- reads the live map and needs
 * nothing. A key only adds what EarthMC keeps in its API alone.
 *
 * <p>The panel measures itself: width comes from the longest line it actually holds and height from
 * the rows it actually draws, both at the font in use. Nothing is positioned by a hand-tuned
 * constant, because the first version was, and its last line was clipped off the bottom edge.
 *
 * <p>A sibling of {@link BlockedScreen} rather than a generalisation of it: that screen carries a
 * message from the developer and an appeal route, this one carries instructions and a way into the
 * settings, and the only thing they genuinely share is how they look.
 */
public final class ApiKeyNoticeScreen extends Screen {

    private static final int PANEL_BG     = 0xF00E1013;
    private static final int PANEL_BORDER = 0xCC3A3D42;
    private static final int ACCENT       = 0xFF57A8FF;
    private static final int TITLE        = 0xFFFFFFFF;
    private static final int SUBTITLE     = 0xFF9AA2AD;
    private static final int LABEL        = 0xFF6F9FD8;
    private static final int BODY         = 0xFFC9CDD3;
    private static final int DIM          = 0xFF7D838C;
    private static final int COMMAND      = 0xFFFFD866;
    private static final int BULLET       = 0xFF4D5560;

    private static final int PAD = 14;
    private static final int LINE = 11;
    private static final int GAP = 7;
    private static final int BTN_H = 20;

    /** One drawn row. {@code tail} is appended straight after {@code text} in its own colour, which
     *  is how a command sits inside a sentence without splitting the row into two. */
    private record Row(String text, int color, String tail, int tailColor, int indent, int gapBefore) {
        static Row of(String t, int c)              { return new Row(t, c, "", 0, 0, 0); }
        static Row gap(String t, int c)             { return new Row(t, c, "", 0, 0, GAP); }
        static Row bullet(String t)                 { return new Row(t, BODY, "", 0, 10, 0); }
        static Row step(String n, String t, String cmd) { return new Row(n + "  " + t, BODY, cmd, COMMAND, 10, 0); }
    }

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();

    public ApiKeyNoticeScreen(Screen parent) {
        super(Component.literal("EarthMC Map Addon"));
        this.parent = parent;

        rows.add(Row.of("Add your EarthMC API key", TITLE));
        rows.add(Row.of("Optional - everything on the map already works without one.", SUBTITLE));

        rows.add(Row.gap("ALREADY WORKING", LABEL));
        rows.add(Row.bullet("Town borders, nation colours and town names"));
        rows.add(Row.bullet("Players, the EarthMC imagery layer and search"));
        rows.add(Row.bullet("Hovering a town for its mayor, nation and size"));

        rows.add(Row.gap("A KEY ALSO SHOWS", LABEL));
        rows.add(Row.bullet("Bank balances, and open or for-sale status"));
        rows.add(Row.bullet("Claim limits and active resident counts"));
        rows.add(Row.bullet("The overclaim and outsider-spawn overlays"));
        rows.add(Row.bullet("Player cards and nation pages"));

        rows.add(Row.gap("TO ADD ONE", LABEL));
        rows.add(Row.step("1", "Run ", "/api key create"));
        rows.add(Row.step("2", "Paste it into ", "Settings > Advanced > EarthMC API Key"));

        rows.add(Row.gap("EarthMC requires a key on its API from 19 November.", DIM));
        rows.add(Row.of("The map side of this addon is unaffected either way.", DIM));
    }

    private int contentHeight() {
        int h = 0;
        for (Row r : rows) h += r.gapBefore() + LINE;
        return h;
    }

    private int panelWidth() {
        int longest = 0;
        for (Row r : rows) {
            longest = Math.max(longest, r.indent() + this.font.width(r.text()) + this.font.width(r.tail()));
        }
        return Math.min(this.width - 40, longest + PAD * 2);
    }

    private int panelHeight() {
        return PAD + contentHeight() + GAP + BTN_H + PAD;
    }

    @Override
    protected void init() {
        int w = panelWidth();
        int left = (this.width - w) / 2;
        int top = (this.height - panelHeight()) / 2;
        int btnY = top + PAD + contentHeight() + GAP;

        // Sized to their labels so neither crowds the other, and centred as a pair.
        int openW = this.font.width("Open settings") + 22;
        int skipW = this.font.width("Continue without") + 22;
        int totalW = openW + 8 + skipW;
        int x = left + (w - totalW) / 2;

        this.addRenderableWidget(Button.builder(Component.literal("Open settings"),
                        b -> this.minecraft.gui.setScreen(new TownyMapConfigScreen(parent)))
                .bounds(x, btnY, openW, BTN_H).build());
        this.addRenderableWidget(Button.builder(Component.literal("Continue without"),
                        b -> this.onClose())
                .bounds(x + openW + 8, btnY, skipW, BTN_H).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(ctx, mouseX, mouseY, delta);

        int w = panelWidth();
        int h = panelHeight();
        int left = (this.width - w) / 2;
        int top = (this.height - h) / 2;

        ctx.fill(left - 1, top - 1, left + w + 1, top + h + 1, PANEL_BORDER);
        ctx.fill(left, top, left + w, top + h, PANEL_BG);
        ctx.fill(left, top, left + w, top + 2, ACCENT);

        int y = top + PAD;
        for (Row r : rows) {
            y += r.gapBefore();
            int x = left + PAD + r.indent();
            if (r.indent() > 0) ctx.text(this.font, "-", left + PAD, y, BULLET, false);
            ctx.text(this.font, r.text(), x, y, r.color(), false);
            if (!r.tail().isEmpty()) {
                ctx.text(this.font, r.tail(), x + this.font.width(r.text()), y, r.tailColor(), false);
            }
            y += LINE;
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
