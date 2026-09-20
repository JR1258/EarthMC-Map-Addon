package net.townymap.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.townymap.TownyMapMod;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The expanded information panel for a town, nation or player.
 *
 * <p>One screen renders all three: a page is just a list of typed blocks, so the town/nation/player
 * pages differ only in what {@link DetailPages} builds, not in layout code. Names inside list blocks are
 * clickable and push another panel with this screen as its parent, so Close walks back the way you came.
 *
 * <p>Styled to match {@link TownyMapConfigScreen} — translucent panel, accent rule, one scrolling body.
 * Long lists are collapsed by default; a 250-resident nation would otherwise bury everything else.
 */
public class DetailScreen extends Screen {

    public enum Kind { TOWN, NATION, PLAYER, ALLIANCE, STATS }

    /** A clickable entity reference. */
    public record Ref(Kind kind, String name) {}

    public sealed interface Block permits Cols, Wide, LegacyLine, Chips, Rule, NameList, LineList, RankList, RefLineList {}

    /**
     * One labelled value, optionally a link. {@code live} recomputes the text every frame, which is how
     * a value that is not known when the page is built — or that counts down — stays current: the blocks
     * are built once when the panel opens, so a plain string would freeze whatever was true then.
     */
    public record Col(String label, String value, Ref link, java.util.function.Supplier<String> live) {
        public Col(String label, String value) { this(label, value, null, null); }
        public Col(String label, String value, Ref link) { this(label, value, link, null); }
        public static Col live(String label, java.util.function.Supplier<String> supplier) {
            return new Col(label, "", null, supplier);
        }
        public String text() { return live != null ? live.get() : value; }
    }
    /** Up to three values side by side. */
    public record Cols(List<Col> cols) implements Block {}
    /** One value spanning the panel (board text, about, formatted name). */
    public record Wide(String label, String value) implements Block {}

    /** A one-line value rendered as a styled {@link Component} (for the colour-formatted player name). */
    public record LegacyLine(String label, Component value) implements Block {}
    /** Flag chips: filled when on, outlined when off. */
    public record Chips(List<String> labels, List<Boolean> states) implements Block {}
    public record Rule() implements Block {}
    /** Collapsible list of clickable names. */
    public record NameList(String title, List<Ref> refs) implements Block {}
    /** Collapsible list of plain text lines (warps, pacts). */
    public record LineList(String title, List<String> lines) implements Block {}
    /** One rank and the players holding it. */
    public record RankGroup(String rank, List<Ref> refs) {}
    /** Collapsible rank listing whose holders are clickable, unlike a plain LineList. */
    public record RankList(String title, List<RankGroup> groups) implements Block {}
    /** A clickable entity followed by descriptive text (pacts: the other nation, then its terms). */
    public record RefLine(Ref ref, String suffix) {}
    /** {@code ranked} draws the list as numbered cards; plain lists (pacts) keep the original look. */
    public record RefLineList(String title, List<RefLine> lines, boolean ranked) implements Block {
        public RefLineList(String title, List<RefLine> lines) { this(title, lines, false); }
    }

    /** A page: header plus blocks plus optional external links for the footer. */
    public record Page(Kind kind, String title, String subtitle, List<Block> blocks,
                       String discordUrl, String wikiUrl) {}

    private static final int PANEL_TOP = 26;
    private static final int PAD = 14;
    private static final int FOOTER_H = 34;
    private static final int LINE = 11;
    private static final int GAP = 8;

    private static final int PANEL_BG = 0xB80E0F12;
    private static final int PANEL_BORDER = 0xCC3A3D42;
    private static final int ACCENT = 0xFF4FA37A;
    private static final int LABEL = 0xFF7F868F;
    private static final int VALUE = 0xFFE5E7EB;
    private static final int MUTED = 0xFF9CA3AF;
    private static final int LINK = 0xFF7FB2E8;
    private static final int ON_FG = 0xFF7FE0B0;
    private static final int OFF_FG = 0xFF6B7280;
    /** Hover colour for anything clickable — the same gold the favourite star uses. */
    private static final int HOVER = 0xFFFFE066;

    private final Screen parent;
    private Page page;                 // null while the fetch is in flight
    private final String pendingTitle;
    private boolean failed;
    private final Set<String> open = new LinkedHashSet<>();
    /** Hit boxes recorded during draw, consumed by the next click. */
    private final List<Hit> hits = new ArrayList<>();
    private record Hit(int x1, int y1, int x2, int y2, Ref ref, String collapseKey) {}

    private int panelLeft, panelWidth, cLeft, cRight;
    private int scroll, contentHeight;

    private Button backButton, discordButton, wikiButton;
    /** Panel bottom, from the previous frame's measured content — the panel hugs its content instead of
     *  always filling the screen, which left the footer stranded far below a short page. */
    private int panelBottom;

    public DetailScreen(Screen parent, Page page) {
        super(Component.literal(page.title()));
        this.parent = parent;
        this.page = page;
        this.pendingTitle = page.title();
    }

    /** Opens immediately in a loading state; {@link #setPage} fills it in when the fetch returns. */
    public DetailScreen(Screen parent, String pendingTitle) {
        super(Component.literal(pendingTitle));
        this.parent = parent;
        this.page = null;
        this.pendingTitle = pendingTitle;
    }

    public void setPage(Page p) {
        this.page = p;
        this.failed = false;
        if (this.minecraft != null) this.rebuildWidgets();
    }

    /** No such entity, or the lookup failed — say so rather than leaving a blank panel. */
    public void markFailed() {
        this.failed = true;
    }

    @Override
    protected void init() {
        panelWidth = Math.max(380, Math.min(this.width - 40, 540));
        panelLeft = (this.width - panelWidth) / 2;
        cLeft = panelLeft + PAD;
        cRight = panelLeft + panelWidth - PAD;

        // Footer: external links from the left, Close pinned right, evenly gapped.
        if (panelBottom <= 0) panelBottom = this.height - 8;

        int bx = cLeft;
        discordButton = null;
        wikiButton = null;
        if (page != null && page.discordUrl() != null && !page.discordUrl().isBlank()) {
            discordButton = Button.builder(Component.literal("Discord"),
                            x -> TownInfoOverlay.openDiscord(page.discordUrl()))
                    .bounds(bx, footerButtonY(), 64, 20).build();
            discordButton.setTooltip(Tooltip.create(Component.literal(page.discordUrl())));
            this.addRenderableWidget(discordButton);
            bx += 64 + GAP;
        }
        if (page != null && page.wikiUrl() != null && !page.wikiUrl().isBlank()) {
            wikiButton = Button.builder(Component.literal("Wiki"),
                            x -> TownInfoOverlay.openDiscord(page.wikiUrl()))
                    .bounds(bx, footerButtonY(), 52, 20).build();
            this.addRenderableWidget(wikiButton);
        }
        backButton = Button.builder(
                        Component.literal(parent == null ? "Close" : "Back"), x -> this.onClose())
                .bounds(cRight - 64, footerButtonY(), 64, 20).build();
        this.addRenderableWidget(backButton);

    }

    /** Height of the tab strip drawn under the title. */
    private static final int TAB_H = 16;
    private static final int TAB_GAP = 4;
    private static final int TAB_ACTIVE_BG = 0x664FA37A;
    private static final int TAB_HOVER_BG = 0x33FFFFFF;
    private static final String[] TABS = { "Dashboard", "Statistics" };
    // "Players" is deliberately absent: its leaderboards need a ~600-request sweep of the whole roster
    // for data no index exposes, which was never fast enough to justify. Everything behind it is intact
    // -- DetailPages.stats(2, ...), filtersFor(2), allPlayerStats(), outlawTrustedCounts() -- so putting
    // it back is this one string plus the warm call in TownyMapMod.warmInfoPanelData().
    private static final String[] SUBTABS = { "Towns", "Nations" };
    private final int[][] subRects = new int[SUBTABS.length][4];
    /** Hit rects for the sort options. Sized to the longest filter row (Nations has seven) -- the render
     *  loop is bounded by this array, so anything beyond it silently never drew or responded. */
    private final int[][] filterRects = new int[10][4];
    /** Hit rects for the tabs and the settings cog, filled while rendering, read on click. */
    private final int[][] tabRects = new int[TABS.length][4];
    private final int[] cogRect = new int[4];

    private int tabTop() { return PANEL_TOP + 20; }
    /** The sub-tab strip only exists on the Statistics tab, so the body starts lower only there. */
    private boolean hasSubTabs() { return page != null && page.kind() == Kind.STATS && activeTab == 1; }
    private int subTop() { return tabTop() + TAB_H + 2; }
    private int filterTop() { return subTop() + TAB_H + 5; }
    private int bodyTop() {
        return PANEL_TOP + 22 + TAB_H + TAB_GAP + (hasSubTabs() ? (TAB_H + 2) + (TAB_H + 7) : 0);
    }
    /** True when the dashboard's search bar is showing, which reserves a strip at the body's foot. */
    private boolean hasSearch() { return page != null && page.kind() == Kind.STATS && activeTab == 0; }
    private static final int SEARCH_STRIP = 28;
    private int bodyBottom() {
        return panelBottom - FOOTER_H + 8 - (hasSearch() ? SEARCH_STRIP : 0);
    }
    /** Y of the search box: inside the panel body, clear of the footer. Sitting it level with the footer
     *  buttons put it where a click counted as outside the panel, which closed the screen instead of
     *  focusing the field. */
    private int searchBoxY() { return bodyBottom() + 4; }

    /** Which tab is selected. Only meaningful on the info panel; entity pages leave it alone. */
    private int activeTab = 0;
    /** Whether the dashboard on screen was built with your own player record available. */
    private boolean dashboardHasSelf = false;
    /** Last stats-data fingerprint the page was built from; -1 until a stats page is drawn. */
    private int lastStatsSig = -1;
    private String searchQuery = "";
    private boolean searchFocused = false;
    private final List<Ref> searchHits = new ArrayList<>();
    private final int[][] searchRects = new int[6][4];
    private int activeSub = 0;
    private int activeFilter = 0;

    /** Result rows to offer at once. Six keeps it a hint, not a second list to scroll. */
    private static final int SEARCH_ROWS = 6;

    /** A scored candidate: lower score is a better match, shorter name breaks ties. */
    private record Cand(Ref ref, int score, int len) {}

    /**
     * Ranks towns, nations and players together against the query.
     *
     * <p>Exact match first, then prefix, then substring, shorter names winning ties -- so "ber" puts
     * Berlin above Bergamo_Nuovo rather than whichever the underlying list happened to hold first.
     * Towns edge out nations, which edge out players, at equal quality.
     */
    private void recomputeSearch() {
        searchHits.clear();
        String q = searchQuery.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.length() < 2) return;

        List<Cand> found = new ArrayList<>();
        var api = TownyMapMod.getApiClient();
        if (api != null) for (var t : api.getTowns()) addCand(found, t.name(), q, Kind.TOWN, 0);
        for (var n : TownyMapMod.apiNationIndex()) addCand(found, n.name(), q, Kind.NATION, 1);
        for (var pl : TownyMapMod.apiPlayerIndex()) addCand(found, pl.name(), q, Kind.PLAYER, 2);

        found.sort((a, b) -> a.score() != b.score() ? a.score() - b.score() : a.len() - b.len());
        for (int i = 0; i < found.size() && i < SEARCH_ROWS; i++) searchHits.add(found.get(i).ref());
    }

    private static void addCand(List<Cand> out, String name, String q, Kind kind, int kindRank) {
        if (name == null || name.isBlank()) return;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        int quality = lower.equals(q) ? 0 : lower.startsWith(q) ? 10 : lower.contains(q) ? 20 : -1;
        if (quality < 0) return;
        out.add(new Cand(new Ref(kind, name), quality + kindRank, name.length()));
    }

    /** Draws the result rows just above the search box, and records their hit rects. */
    private void renderSearchResults(GuiGraphicsExtractor ctx, int mouseX, int mouseY) {
        // Drawn entirely by us, like the world map's search bar. No vanilla widget is involved, so a
        // UI-theming pack cannot restyle it into a button or make it vanish -- which is what happened
        // through three attempts at taming an EditBox.
        if (hasSearch()) {
            int fy = searchBoxY();
            ctx.fill(cLeft - 1, fy - 1, cRight + 1, fy + 19, searchFocused ? ACCENT : PANEL_BORDER);
            ctx.fill(cLeft, fy, cRight, fy + 18, 0xFF101216);
            boolean empty = searchQuery.isEmpty();
            String shown = empty ? "Search towns, nations, players" : searchQuery;
            ctx.text(this.font, this.font.plainSubstrByWidth(shown, cRight - cLeft - 12),
                    cLeft + 6, fy + 5, empty ? 0xFF7A7A7A : 0xFFFFFFFF, false);
            if (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0) {
                int cx = cLeft + 6 + this.font.width(searchQuery);
                ctx.fill(cx, fy + 4, cx + 1, fy + 14, 0xFFFFFFFF);   // blinking caret
            }
        }

        for (int[] r : searchRects) r[2] = 0;
        if (!hasSearch() || searchHits.isEmpty()) return;
        int rowH = 12;
        int top = searchBoxY() - 4 - searchHits.size() * rowH;
        ctx.fill(cLeft - 2, top - 3, cRight + 2, searchBoxY() - 2, 0xEE0E0F12);
        ctx.fill(cLeft - 2, top - 3, cRight + 2, top - 2, ACCENT);
        for (int i = 0; i < searchHits.size(); i++) {
            Ref ref = searchHits.get(i);
            int ry = top + i * rowH;
            boolean hot = mouseX >= cLeft - 2 && mouseX <= cRight + 2 && mouseY >= ry && mouseY < ry + rowH;
            if (hot) ctx.fill(cLeft - 2, ry, cRight + 2, ry + rowH, 0x24FFFFFF);
            ctx.text(this.font, ref.name(), cLeft + 4, ry + 2, hot ? HOVER : LINK, false);
            String kindLabel = ref.kind().name().charAt(0) + ref.kind().name().substring(1).toLowerCase(java.util.Locale.ROOT);
            ctx.text(this.font, kindLabel, cRight - this.font.width(kindLabel) - 4, ry + 2, MUTED, false);
            searchRects[i][0] = cLeft - 2; searchRects[i][1] = ry;
            searchRects[i][2] = cRight + 2; searchRects[i][3] = ry + rowH;
        }
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent input) {
        // Enter opens the top result, so a search can be finished without reaching for the mouse.
        if (searchFocused && hasSearch()) {
            int k = input.key();
            if (k == InputConstants.KEY_BACKSPACE) {
                if (!searchQuery.isEmpty()) {
                    searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                    recomputeSearch();
                }
                return true;
            }
            if (k == InputConstants.KEY_ESCAPE) {   // unfocus first, do not close the panel
                searchFocused = false;
                searchHits.clear();
                return true;
            }
            if ((k == InputConstants.KEY_RETURN || k == InputConstants.KEY_NUMPADENTER)
                    && !searchHits.isEmpty()) {
                Ref best = searchHits.get(0);
                TownyMapMod.openDetail(best.kind(), best.name(), this);
                return true;
            }
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent input) {
        if (!searchFocused || !hasSearch() || !input.isAllowedChatCharacter()) {
            return super.charTyped(input);
        }
        String text = input.codepointAsString();
        if (searchQuery.length() + text.length() <= 64) {
            searchQuery += text;
            recomputeSearch();
        }
        return true;
    }

    private static boolean within(int[] r, int x, int y) {
        return r[2] > r[0] && x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3];
    }
    /** Y that vertically centres a 20px button inside the footer strip below the divider. */
    private int footerButtonY() {
        // With the search bar present the footer strip is shared, and centring a 20px button in it put
        // Close straight through the field. Sit it on its own row underneath instead.
        if (hasSearch()) return searchBoxY() + 18 + 6;
        return bodyBottom() + ((panelBottom - bodyBottom()) - 20) / 2;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        net.townymap.TownyMapMod.setTextInputActive(searchFocused && hasSearch());
        // UI Scale: shrink the whole panel around the screen centre; the mouse is un-scaled to match.
        if (!UiScale.active()) { drawContent(ctx, mouseX, mouseY, delta); return; }
        float cx = this.width / 2f, cy = this.height / 2f;
        int mx = (int) Math.round(UiScale.unscale(mouseX, cx));
        int my = (int) Math.round(UiScale.unscale(mouseY, cy));
        UiScale.push(ctx, cx, cy);
        try { drawContent(ctx, mx, my, delta); }
        finally { UiScale.pop(ctx); }
    }

    private void drawContent(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        int right = panelLeft + panelWidth;
        // Hug the content: measured last frame, clamped to the screen. A short page no longer leaves the
        // footer stranded at the bottom of a mostly-empty full-height panel.
        // Entity pages hug their content so a short page does not strand the footer at the bottom of a
        // mostly-empty panel. The dashboard is the exception: its search bar belongs at the bottom of the
        // screen, and hugging left the bar floating mid-panel with dead background beneath it.
        int wanted = bodyTop() + Math.max(40, contentHeight) + FOOTER_H;
        int bottom = hasSearch() ? this.height - 8 : Math.min(this.height - 8, wanted);
        if (bottom != panelBottom) {
            panelBottom = bottom;
            int by = footerButtonY();
            if (backButton != null) backButton.setY(by);
            if (discordButton != null) discordButton.setY(by);
            if (wikiButton != null) wikiButton.setY(by);
        }
        ctx.fill(panelLeft - 4, PANEL_TOP + 4, right + 4, bottom + 4, 0x66000000);
        ctx.fill(panelLeft - 1, PANEL_TOP - 1, right + 1, bottom + 1, PANEL_BORDER);
        ctx.fill(panelLeft, PANEL_TOP, right, bottom, PANEL_BG);
        ctx.fill(panelLeft, PANEL_TOP, right, PANEL_TOP + 3, ACCENT);

        String title = page != null ? page.title() : pendingTitle;
        ctx.text(this.font, title, cLeft, PANEL_TOP + 9, 0xFFFFFFFF, false);
        if (page != null && page.kind() == Kind.PLAYER) {   // player head in the top-right corner
            int hs = 18;
            net.townymap.render.PlayerHeadRenderer.drawMenuHead(ctx, page.title(),
                    right - 10 - hs / 2, PANEL_TOP + 2 + hs / 2, hs);
        }
        String sub = page != null ? page.subtitle() : (failed ? "not found" : "loading…");
        if (sub != null && !sub.isBlank()) {
            ctx.text(this.font, sub,
                    cLeft + this.font.width(title) + 6, PANEL_TOP + 9, MUTED, false);
        }
        // Tab strip. Drawn by hand rather than with vanilla Buttons: the panel has its own flat look
        // (PANEL_BG / PANEL_BORDER / ACCENT) and a stock button would read as a foreign widget in it.
        int tx = cLeft;
        int ty = tabTop();
        for (int i = 0; i < TABS.length; i++) {
            int w = this.font.width(TABS[i]) + 12;
            boolean active = i == activeTab;
            boolean hover = mouseX >= tx && mouseX <= tx + w && mouseY >= ty && mouseY <= ty + TAB_H;
            if (active) ctx.fill(tx, ty, tx + w, ty + TAB_H, TAB_ACTIVE_BG);
            else if (hover) ctx.fill(tx, ty, tx + w, ty + TAB_H, TAB_HOVER_BG);
            if (active) ctx.fill(tx, ty + TAB_H - 1, tx + w, ty + TAB_H, ACCENT);
            ctx.text(this.font, TABS[i], tx + 6, ty + 4, active ? 0xFFFFFFFF : MUTED, false);
            tabRects[i][0] = tx; tabRects[i][1] = ty; tabRects[i][2] = tx + w; tabRects[i][3] = ty + TAB_H;
            tx += w + TAB_GAP;
        }
        // Settings cog, far right of the strip. Three bars read better than a gear at this size.
        int cw = 18;
        int cx0 = right - 10 - cw;
        boolean cogHover = mouseX >= cx0 && mouseX <= cx0 + cw && mouseY >= ty && mouseY <= ty + TAB_H;
        if (cogHover) ctx.fill(cx0, ty, cx0 + cw, ty + TAB_H, TAB_HOVER_BG);
        for (int i = 0; i < 3; i++) {
            int ly = ty + 4 + i * 3;
            ctx.fill(cx0 + 4, ly, cx0 + cw - 4, ly + 1, cogHover ? 0xFFFFFFFF : MUTED);
        }
        cogRect[0] = cx0; cogRect[1] = ty; cogRect[2] = cx0 + cw; cogRect[3] = ty + TAB_H;

        for (int[] r : subRects) r[2] = 0;   // collapse stale hit rects when the strip is hidden
        if (hasSubTabs()) {
            int sx = cLeft;
            int sy = subTop();
            for (int i = 0; i < SUBTABS.length; i++) {
                int w = this.font.width(SUBTABS[i]) + 10;
                boolean act = i == activeSub;
                boolean hov = mouseX >= sx && mouseX <= sx + w && mouseY >= sy && mouseY <= sy + TAB_H;
                if (act || hov) ctx.fill(sx, sy, sx + w, sy + TAB_H, act ? TAB_ACTIVE_BG : TAB_HOVER_BG);
                ctx.text(this.font, SUBTABS[i], sx + 5, sy + 4, act ? 0xFFFFFFFF : MUTED, false);
                subRects[i][0] = sx; subRects[i][1] = sy;
                subRects[i][2] = sx + w; subRects[i][3] = sy + TAB_H;
                sx += w + TAB_GAP;
            }
        }

        for (int[] r : filterRects) r[2] = 0;
        if (hasSubTabs()) {
            // Separator, then the sort options in the same flat style as the strips above it.
            int sepY = subTop() + TAB_H + 2;
            ctx.fill(cLeft, sepY, right - 10, sepY + 1, 0x553A3D42);

            String[] filters = DetailPages.filtersFor(activeSub);
            int fx = cLeft;
            int fy = filterTop();
            for (int i = 0; i < filters.length && i < filterRects.length; i++) {
                int w = this.font.width(filters[i]) + 10;
                boolean act = i == activeFilter;
                boolean hov = mouseX >= fx && mouseX <= fx + w && mouseY >= fy && mouseY <= fy + TAB_H;
                if (act || hov) ctx.fill(fx, fy, fx + w, fy + TAB_H, act ? TAB_ACTIVE_BG : TAB_HOVER_BG);
                ctx.text(this.font, filters[i], fx + 5, fy + 4, act ? 0xFFFFFFFF : MUTED, false);
                filterRects[i][0] = fx; filterRects[i][1] = fy;
                filterRects[i][2] = fx + w; filterRects[i][3] = fy + TAB_H;
                fx += w + TAB_GAP;
            }
        }

        ctx.fill(panelLeft, bodyTop() - 1, right, bodyTop(), 0x663A3D42);
        // Your town/nation card needs a record that is usually still being fetched when the dashboard is
        // first built, so the page was rendering without it and only picked it up when a tab switch
        // happened to rebuild the page. Rebuild once, the moment it arrives.
        // Rebuild once when your own data lands. Two things arrive separately -- the player record, then
        // the town record it points at -- so wait for the town too, unless you are townless and it will
        // never come.
        if (hasSearch() && !dashboardHasSelf) {
            var self = TownyMapMod.selfPlayer();
            boolean townless = self != null && (self.townName() == null || self.townName().isBlank());
            if (self != null && (townless || TownyMapMod.selfTownFull() != null)) {
                dashboardHasSelf = true;
                setPage(DetailPages.dashboard());
            }
        }
        // Rebuild a Statistics page when its data arrives. Gold, Outlaws and Founded all open before
        // their sweep finishes, and without this they sat on "sweeping..." until you changed filter.
        if (page != null && page.kind() == Kind.STATS && activeTab == 1) {
            int sig = TownyMapMod.statsCacheSignature();
            if (lastStatsSig != sig) {
                lastStatsSig = sig;
                setPage(DetailPages.stats(activeSub, activeFilter));
            }
        }
        if (!hasSearch() && (!searchQuery.isEmpty() || searchFocused)) {
            searchQuery = "";
            searchFocused = false;
            searchHits.clear();
        }
        renderSearchResults(ctx, mouseX, mouseY);

        hits.clear();
        if (page == null) {
            String msg = failed ? "Nothing found for \"" + pendingTitle + "\"." : "Loading…";
            ctx.text(this.font, msg, cLeft, bodyTop() + 10, MUTED, false);
            contentHeight = 30;
        } else {
            ctx.enableScissor(panelLeft, bodyTop(), right, bodyBottom());
            int y = drawBlocks(ctx, mouseX, mouseY);
            ctx.disableScissor();
            contentHeight = y + scroll - (bodyTop() + 5) + 6;
            scroll = Math.max(0, Math.min(scroll, maxScroll()));
        }

        ctx.fill(panelLeft, bodyBottom(), right, bodyBottom() + 1, 0x663A3D42);
        ctx.fill(panelLeft, bodyBottom() + 1, right, bottom, 0xAA14161A);
        drawScrollbar(ctx);
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        // The vanilla widgets self-render in the light textured style, so with "Dark Buttons" on we paint
        // the flat dark style over them. The real widgets stay underneath and keep handling clicks.
        if (DarkButtons.enabled()) {
            for (var child : this.children()) {
                if (child instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible) {
                    DarkButtons.draw(ctx, w.getX(), w.getY(), w.getWidth(), w.getHeight(),
                            w.getMessage().getString(), w.active, 0xFFFFFFFF, mouseX, mouseY);
                }
            }
        }
    }

    private int maxScroll() { return Math.max(0, contentHeight - (bodyBottom() - bodyTop())); }

    private void drawScrollbar(GuiGraphicsExtractor ctx) {
        int max = maxScroll();
        if (max <= 0) return;
        int top = bodyTop(), bot = bodyBottom(), h = bot - top;
        int thumb = Math.max(20, h * h / Math.max(1, contentHeight));
        int ty = top + (h - thumb) * scroll / max;
        int x = panelLeft + panelWidth - 5;
        ctx.fill(x, top + 2, x + 2, bot - 2, 0x553A3D42);
        ctx.fill(x, ty, x + 2, ty + thumb, 0xFF9CA3AF);
    }

    private int drawBlocks(GuiGraphicsExtractor ctx, int mouseX, int mouseY) {
        int y = bodyTop() + 5 - scroll;
        for (Block b : page.blocks()) {
            if (b instanceof Rule) {
                y += 2;
                ctx.fill(cLeft, y, cRight, y + 1, 0x443A3D42);
                y += 6;
            } else if (b instanceof Wide w) {
                ctx.text(this.font, w.label(), cLeft, y, LABEL, false);
                y += LINE;
                for (var l : this.font.split(Component.literal(w.value()), cRight - cLeft)) {
                    ctx.text(this.font, l, cLeft, y, VALUE, false);
                    y += LINE;
                }
                y += 3;
            } else if (b instanceof LegacyLine ll) {
                ctx.text(this.font, ll.label(), cLeft, y, LABEL, false);
                y += LINE;
                ctx.text(this.font, ll.value(), cLeft, y, VALUE, false);   // styled Component (real colours)
                y += LINE + 3;
            } else if (b instanceof Cols c) {
                int n = Math.max(1, c.cols().size());
                int colW = (cRight - cLeft - GAP * (n - 1)) / n;
                for (int i = 0; i < c.cols().size(); i++) {
                    Col col = c.cols().get(i);
                    int x = cLeft + i * (colW + GAP);
                    ctx.text(this.font, col.label(), x, y, LABEL, false);
                    boolean link = col.link() != null;
                    String shown = this.font.plainSubstrByWidth(col.text(), colW);
                    int w = this.font.width(shown);
                    boolean hot = link && mouseX >= x && mouseX < x + w
                            && mouseY >= y + LINE - 1 && mouseY < y + LINE + 9
                            && mouseY >= bodyTop() && mouseY < bodyBottom();
                    ctx.text(this.font, shown, x, y + LINE,
                            hot ? HOVER : link ? LINK : VALUE, false);
                    if (link) hits.add(new Hit(x, y + LINE - 1, x + w, y + LINE + 9, col.link(), null));
                }
                y += LINE * 2 + 4;
            } else if (b instanceof Chips ch) {
                int x = cLeft;
                for (int i = 0; i < ch.labels().size(); i++) {
                    String lab = ch.labels().get(i);
                    boolean on = ch.states().get(i);
                    int w = this.font.width(lab) + 10;
                    if (x + w > cRight) { x = cLeft; y += 13; }
                    ctx.fill(x, y - 1, x + w, y + 10, on ? 0x557FE0B0 : 0x1AFFFFFF);
                    ctx.text(this.font, lab, x + 5, y + 1, on ? ON_FG : OFF_FG, false);
                    x += w + 4;
                }
                y += 17;
            } else if (b instanceof NameList nl) {
                y = drawCollapsible(ctx, mouseX, mouseY, y, nl.title(), nl.refs().size(), nl);
            } else if (b instanceof LineList ll) {
                y = drawCollapsible(ctx, mouseX, mouseY, y, ll.title(), ll.lines().size(), ll);
            } else if (b instanceof RankList rl) {
                y = drawCollapsible(ctx, mouseX, mouseY, y, rl.title(), rl.groups().size(), rl);
            } else if (b instanceof RefLineList rll) {
                y = drawCollapsible(ctx, mouseX, mouseY, y, rll.title(), rll.lines().size(), rll);
            }
        }
        return y;
    }

    /** Header row for a collapsible block, then its contents when open. */
    private int drawCollapsible(GuiGraphicsExtractor ctx, int mouseX, int mouseY, int y,
                                String title, int count, Block body) {
        // A ranked leaderboard is the whole point of the tab it sits on -- there is nothing to collapse
        // it away from, and a fold just adds a click before you can read it. So it renders bare: no
        // header, no arrow, always open. Every other list keeps the collapsible treatment, which is what
        // stops a 250-resident nation burying the rest of its page.
        boolean bare = body instanceof RefLineList rlBare && rlBare.ranked();
        boolean isOpen = bare || open.contains(title);
        if (!bare) {
            boolean hover = mouseY >= y - 2 && mouseY < y + 11 && mouseX >= cLeft && mouseX <= cRight
                    && mouseY >= bodyTop() && mouseY < bodyBottom();
            if (hover) ctx.fill(cLeft - 5, y - 2, cRight + 3, y + 11, 0x18FFFFFF);
            ctx.fill(cLeft, y, cLeft + 1, y + 9, ACCENT);
            ctx.text(this.font, (isOpen ? "\u25be " : "\u25b8 ") + title, cLeft + 6, y,
                    isOpen ? 0xFFFFFFFF : VALUE, false);
            String c = String.valueOf(count);
            ctx.text(this.font, c, cRight - this.font.width(c), y, LABEL, false);
            hits.add(new Hit(cLeft - 5, y - 2, cRight + 3, y + 11, null, title));
            y += 14;
            if (!isOpen) return y;
        }

        if (body instanceof NameList nl) {
            // Names flow inline and wrap, each with its own hit box so it can be clicked through.
            int x = cLeft + 10;
            for (int i = 0; i < nl.refs().size(); i++) {
                Ref ref = nl.refs().get(i);
                String txt = ref.name() + (i < nl.refs().size() - 1 ? "," : "");
                int w = this.font.width(txt);
                if (x + w > cRight) { x = cLeft + 10; y += LINE; }
                boolean hot = mouseX >= x && mouseX < x + w && mouseY >= y - 1 && mouseY < y + 9
                        && mouseY >= bodyTop() && mouseY < bodyBottom();
                ctx.text(this.font, txt, x, y, hot ? HOVER : LINK, false);
                hits.add(new Hit(x, y - 1, x + w, y + 9, ref, null));
                x += w + 4;
            }
            y += LINE + 3;
        } else if (body instanceof RankList rl) {
            for (RankGroup g : rl.groups()) {
                int x = cLeft + 10;
                String head = g.rank() + ": ";
                ctx.text(this.font, head, x, y, LABEL, false);
                x += this.font.width(head);
                for (int i = 0; i < g.refs().size(); i++) {
                    Ref ref = g.refs().get(i);
                    String txt = ref.name() + (i < g.refs().size() - 1 ? "," : "");
                    int w = this.font.width(txt);
                    if (x + w > cRight) { x = cLeft + 18; y += LINE; }
                    boolean hot = mouseX >= x && mouseX < x + w && mouseY >= y - 1 && mouseY < y + 9
                            && mouseY >= bodyTop() && mouseY < bodyBottom();
                    ctx.text(this.font, txt, x, y, hot ? HOVER : LINK, false);
                    hits.add(new Hit(x, y - 1, x + w, y + 9, ref, null));
                    x += w + 4;
                }
                y += LINE;
            }
            y += 3;
        } else if (body instanceof RefLineList rll) {
            int rank = 0;
            for (RefLine rl : rll.lines()) {
                rank++;
                String nm = rl.ref().name();
                if (rll.ranked()) {
                    // Card row: a banded background, the rank set dim on the left, and the value pushed
                    // out to the right edge so the numbers line up into a column you can read down.
                    int rowTop = y - 2, rowBot = y + LINE - 3;
                    boolean rowHot = mouseX >= cLeft && mouseX < cRight
                            && mouseY >= rowTop && mouseY < rowBot
                            && mouseY >= bodyTop() && mouseY < bodyBottom();
                    ctx.fill(cLeft + 4, rowTop, cRight, rowBot,
                            rowHot ? 0x24FFFFFF : (rank % 2 == 0 ? 0x10FFFFFF : 0x1A000000));
                    if (rowHot) ctx.fill(cLeft + 4, rowTop, cLeft + 6, rowBot, ACCENT);

                    String num = rank + ".";
                    ctx.text(this.font, num, cLeft + 12, y, MUTED, false);
                    int nx = cLeft + 12 + 22;
                    int nw = this.font.width(nm);
                    ctx.text(this.font, nm, nx, y, rowHot ? HOVER : LINK, false);
                    hits.add(new Hit(cLeft + 4, rowTop, cRight, rowBot, rl.ref(), null));
                    String val = rl.suffix();
                    if (val != null && !val.isBlank()) {
                        int vw = this.font.width(val);
                        int vx = Math.max(nx + nw + 8, cRight - 8 - vw);
                        ctx.text(this.font, this.font.plainSubstrByWidth(val, cRight - 8 - vx),
                                vx, y, MUTED, false);
                    }
                } else {
                    int x = cLeft + 10;
                    int w = this.font.width(nm);
                    boolean hot = mouseX >= x && mouseX < x + w && mouseY >= y - 1 && mouseY < y + 9
                            && mouseY >= bodyTop() && mouseY < bodyBottom();
                    ctx.text(this.font, nm, x, y, hot ? HOVER : LINK, false);
                    hits.add(new Hit(x, y - 1, x + w, y + 9, rl.ref(), null));
                    ctx.text(this.font,
                            this.font.plainSubstrByWidth(rl.suffix(), cRight - (x + w + 5)),
                            x + w + 5, y, MUTED, false);
                }
                y += LINE;
            }
            y += 3;
        } else if (body instanceof LineList ll) {
            for (String line : ll.lines()) {
                for (var l : this.font.split(Component.literal(line), cRight - cLeft - 10)) {
                    ctx.text(this.font, l, cLeft + 10, y, MUTED, false);
                    y += LINE;
                }
            }
            y += 3;
        }
        return y;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        int tabMx = (int) click.x(), tabMy = (int) click.y();
        for (int i = 0; i < searchHits.size() && i < searchRects.length; i++) {
            if (!within(searchRects[i], tabMx, tabMy)) continue;
            Ref ref = searchHits.get(i);
            TownyMapMod.openDetail(ref.kind(), ref.name(), this);
            return true;
        }
        if (within(cogRect, tabMx, tabMy)) {
            net.minecraft.client.Minecraft.getInstance().gui.setScreen(
                    new TownyMapConfigScreen(this));
            return true;
        }
        for (int i = 0; i < filterRects.length; i++) {
            if (!within(filterRects[i], tabMx, tabMy)) continue;
            if (i != activeFilter) {
                activeFilter = i;
                setPage(DetailPages.stats(activeSub, activeFilter));
            }
            return true;
        }
        for (int i = 0; i < SUBTABS.length; i++) {
            if (!within(subRects[i], tabMx, tabMy)) continue;
            if (i != activeSub) {
                activeSub = i;
                activeFilter = 0;   // filters differ per sub-tab, so start at the first one
                setPage(DetailPages.stats(activeSub, activeFilter));
            }
            return true;
        }
        for (int i = 0; i < TABS.length; i++) {
            if (!within(tabRects[i], tabMx, tabMy)) continue;
            if (i != activeTab) {
                activeTab = i;
                setPage(i == 0 ? DetailPages.dashboard() : DetailPages.stats(activeSub, activeFilter));
            }
            return true;
        }
        // Clicking anywhere in the panel that is not the search field drops focus and clears the
        // suggestions -- otherwise the results stayed up and keystrokes kept going into a box you had
        // visually moved on from.
        if (hasSearch()) {
            int fy = searchBoxY();
            boolean onBox = tabMx >= cLeft && tabMx <= cRight && tabMy >= fy && tabMy <= fy + 18;
            searchFocused = onBox;
            if (onBox) return true;
        }
        if (UiScale.active()) click = UiScale.unscaleClick(click, this.width / 2.0, this.height / 2.0);
        double mx = click.x(), my = click.y();

        // Click-away dismiss. Anything outside the panel closes it — and closes the whole stack, not one
        // level: after following names a few panels deep, clicking the map means "get out of this", not
        // "go back one". Buttons and content sit inside the rect, so they are unaffected.
        if (mx < panelLeft || mx > panelLeft + panelWidth || my < PANEL_TOP || my > panelBottom) {
            this.minecraft.gui.setScreen(rootParent());
            return true;
        }

        if (my >= bodyTop() && my < bodyBottom()) {
            for (Hit h : hits) {
                if (mx < h.x1() || mx > h.x2() || my < h.y1() || my > h.y2()) continue;
                if (h.collapseKey() != null) {
                    if (!open.remove(h.collapseKey())) open.add(h.collapseKey());
                    return true;
                }
                if (h.ref() != null) {
                    TownyMapMod.openDetail(h.ref().kind(), h.ref().name(), this);
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (UiScale.active()) {
            mouseX = UiScale.unscale(mouseX, this.width / 2.0);
            mouseY = UiScale.unscale(mouseY, this.height / 2.0);
        }
        int max = maxScroll();
        if (max <= 0) return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.round(vertical * 18.0)));
        return true;
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    /** The first ancestor that is not one of these panels — where a click-away should land. */
    private Screen rootParent() {
        Screen s = parent;
        while (s instanceof DetailScreen d) s = d.parent;
        return s;
    }
}
