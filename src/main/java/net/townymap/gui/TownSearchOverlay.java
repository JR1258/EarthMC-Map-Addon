package net.townymap.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.townymap.TownyMapMod;
import net.townymap.model.EarthMcNationData;
import net.townymap.model.EarthMcPlayerData;
import net.townymap.model.MapJumpTarget;
import net.townymap.model.NationBonusProjection;
import net.townymap.model.PlayerHistoryEntry;
import net.townymap.model.PlayerMarker;
import net.townymap.model.TownData;
import net.townymap.model.TownPopupData;
import net.townymap.util.DiscordUrl;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.Set;

public final class TownSearchOverlay {

    private static final int WIDTH = 180;          // minimum; the bar grows to fit its contents
    private static final int MAX_WIDTH = 460;
    /** Current bar width, recomputed each frame from the query and results so nothing is cut off. */
    private static volatile int panelWidth = WIDTH;
    /** What an empty bar advertises. The short forms are the ones worth teaching, so they come first. */
    private static final List<String> HINTS = List.of(
            "§7Filter: §fnationless§7, §fn:germany,france",
            "§7Filter: §fr>30,<60§7, §fchunks>500",
            "§7Type §fdd/mm/yyyy §7= archive");
    private static final int FAVORITES_WIDTH = 74;
    private static final int ROW_HEIGHT = 20;
    private static final int MAX_RESULTS = 7;
    private static final int MAX_PER_TYPE = 3;
    private static final int MAX_INFO_LINES = 11;
    private static final int BG = 0xD8101010;
    private static final int BORDER = 0xFF333333;
    private static final int ACTIVE_BORDER = 0xFFDDDDDD;
    private static final int HOVER = 0x553BFF3B;
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    private static boolean focused;

    /** True while the search bar has keyboard focus (typing goes to it, not to map shortcuts). */
    public static boolean isFocused() { return focused; }
    private static String query = "";
    private static int caret = 0;             // cursor position within query (0..length)
    private static int selAnchor = -1;        // other end of a selection; -1 = none. Selection = [lo, hi]
    private static boolean textDragging = false;   // a left-drag started in the bar is extending the selection
    private static int selected;
    private static String selectedType = "";
    private static String selectedName = "";
    private static boolean favoritesOpen;

    /** The nation currently selected in the panel (via a capital-star click or a nation search), else null. */
    public static String selectedNationName() {
        return "nation".equals(selectedType) && selectedName != null && !selectedName.isBlank()
                ? selectedName : null;
    }

    // The nation whose join-range zone is being shown, toggled by the ◯ button in the panel's top-right.
    // Explicit rather than "whatever is selected", so the zone stays up while you look around the map.
    private static String rangeNation = null;

    /** The nation whose join range should be drawn, or null when the overlay is off. */
    public static String rangeNationName() { return rangeNation; }

    /** Turns the join-range overlay on for {@code nation} (null/blank clears it). */
    public static void showNationRange(String nation) {
        rangeNation = nation == null || nation.isBlank() ? null : nation;
    }

    /**
     * Entering Planning puts the bar straight into typing mode as a nation picker — unless a nation is
     * already on screen (e.g. you came from the info panel's range button), in which case it adopts that one
     * and skips the search entirely. Leaving the mode throws the plan away.
     */
    public static void onStatusModeChanged(int before, int after) {
        if (after == PlanningOverlay.MODE) {
            String preset = selectedNationName();
            if (preset == null) preset = rangeNation;
            if (preset != null && !preset.isBlank()) {
                PlanningOverlay.setNation(preset);   // already know the nation: no search step needed
                return;
            }
            query = "";
            clearFilterHighlight();
            focused = true;                          // straight into typing
            selected = 0;
        } else if (before == PlanningOverlay.MODE) {
            PlanningOverlay.reset();
            rangeNation = null;
        }
    }

    private static void toggleNationRange(String nation) {
        rangeNation = nation.equalsIgnoreCase(rangeNation == null ? "" : rangeNation) ? null : nation;
    }
    private static String cachedNeedle = null;
    private static int cachedTownCount = -1;
    private static int cachedTownDetailCount = -1;
    private static int cachedMarkerCount = -1;
    private static int cachedApiPlayerCount = -1;
    private static int cachedPlayerDetailCount = -1;
    private static int cachedPlayerHistoryCount = -1;
    private static int cachedNationCount = -1;
    private static int cachedNationDetailCount = -1;
    private static List<Result> cachedResults = List.of();
    private static int infoDiscordX, infoDiscordY, infoDiscordW, infoDiscordH;
    private static int infoExpandX, infoExpandY, infoExpandW, infoExpandH;
    private static boolean infoExpandVisible;
    private static int infoRangeX, infoRangeY, infoRangeW, infoRangeH;
    private static int infoStarX, infoStarY, infoStarW, infoStarH;
    private static boolean infoStarVisible;
    // Hover labels are drawn after everything else in the panel: the info rows are drawn last and were
    // painting over them (the player's name sat straight across "Favourite").
    private static String pendingTipText;
    private static int pendingTipX, pendingTipY;
    private static boolean infoRangeVisible;
    private static int infoAnchorX, infoAnchorY;   // the right-side panel's top-left, for UI-Scale hit-testing
    private static boolean infoDiscordVisible;
    private static String infoDiscordUrl = "";
    // Clickable name spans inside the selected-info panel (Mayor/King → player,
    // Nation/Capital → nation/town).  Rebuilt every render; consumed on click.
    private static final List<InfoLink> infoLinks = new ArrayList<>();
    private static final int LINK_COLOR = 0xFF8FB7FF;
    private static final int LINK_HOVER_COLOR = 0xFFFFE066;

    private TownSearchOverlay() {}

    public static void render(GuiGraphicsExtractor ctx, int sw, int sh,
                              List<TownData> towns, List<PlayerMarker> players,
                              Map<String, TownPopupData> townDetails,
                              List<EarthMcPlayerData> apiPlayers,
                              Map<String, EarthMcPlayerData> playerDetails,
                              Map<String, PlayerHistoryEntry> playerHistory,
                              List<EarthMcNationData> apiNations,
                              Map<String, EarthMcNationData> nationDetails,
                              List<String> favoriteTowns) {
        Minecraft mc = Minecraft.getInstance();
        Font tr = mc.font;
        TownyMapMod.setTextInputActive(focused);   // SDL only sends characters while text input is on
        tickTextDrag(mc, sw);   // follow a left-drag over the bar (updates the caret/selection)

        // Results are needed before layout: the bar widens to fit the longest row, so nothing is ellipsised.
        List<Result> results = focused
                ? results(towns, players, townDetails, apiPlayers, playerDetails, playerHistory,
                          apiNations, nationDetails)
                : List.of();
        panelWidth = computePanelWidth(tr, results, sw);
        final int W = panelWidth;

        int x = left(sw);
        int y = top();
        boolean scaled = UiScale.active();   // shrink around the bar's CENTRE so a centred bar stays centred
        if (scaled) UiScale.push(ctx, x + W / 2f, y + ROW_HEIGHT / 2f);
        int border = focused ? ACTIVE_BORDER : BORDER;

        ctx.fill(x - 1, y - 1, x + W + 1, y + ROW_HEIGHT + 1, border);
        ctx.fill(x, y, x + W, y + ROW_HEIGHT, BG);

        // Expand button, same frame and fill as the bar so the two read as one control. Two diagonal
        // arrows pointing out of opposite corners -- the usual "open this bigger" glyph.
        int ex = x + W + 4;
        expandX1 = ex; expandY1 = y; expandX2 = ex + ROW_HEIGHT; expandY2 = y + ROW_HEIGHT;
        // Cursor comes from the mouse handler rather than a render parameter: threading mouseX/mouseY
        // through this signature would touch every caller for one highlight.
        boolean eh = false;
        net.minecraft.client.Minecraft hmc = net.minecraft.client.Minecraft.getInstance();
        if (hmc != null && hmc.getWindow() != null && hmc.getWindow().getScreenWidth() > 0) {
            double hmx = hmc.mouseHandler.xpos() * hmc.getWindow().getGuiScaledWidth()
                    / (double) hmc.getWindow().getScreenWidth();
            double hmy = hmc.mouseHandler.ypos() * hmc.getWindow().getGuiScaledHeight()
                    / (double) hmc.getWindow().getScreenHeight();
            eh = hmx >= ex && hmx <= expandX2 && hmy >= y && hmy <= expandY2;
        }
        ctx.fill(ex - 1, y - 1, expandX2 + 1, expandY2 + 1, eh ? ACTIVE_BORDER : border);
        ctx.fill(ex, y, expandX2, expandY2, eh ? 0xFF2A2E33 : BG);
        int gc = eh ? 0xFFFFFFFF : 0xFFCCCCCC;
        int pad = 4, arm = 4;
        int lx = ex + pad, ty = y + pad, rx = expandX2 - pad - 1, by = expandY2 - pad - 1;
        for (int i = 0; i < arm; i++) {                       // top-left and bottom-right diagonals
            ctx.fill(lx + i, ty + i, lx + i + 1, ty + i + 1, gc);
            ctx.fill(rx - i, by - i, rx - i + 1, by - i + 1, gc);
        }
        for (int i = 0; i < 3; i++) {                         // the little corner ticks that read as arrows
            ctx.fill(lx + i, ty, lx + i + 1, ty + 1, gc);
            ctx.fill(lx, ty + i, lx + 1, ty + i + 1, gc);
            ctx.fill(rx - i, by, rx - i + 1, by + 1, gc);
            ctx.fill(rx, by - i, rx + 1, by - i + 1, gc);
        }

        String display = query.isEmpty() && !focused ? "Search towns/nations/players" : query;
        int color = query.isEmpty() && !focused ? 0xFFAAAAAA : 0xFFFFFFFF;
        int textLeft = x + 7;
        if (focused && hasSelection()) {   // drag / Ctrl+A selection highlight behind the text
            int a = textLeft + tr.width(query.substring(0, selLo()));
            int b = textLeft + tr.width(query.substring(0, selHi()));
            ctx.fill(a, y + 3, b, y + ROW_HEIGHT - 2, 0x993B6FE0);
        }
        ctx.text(tr, display, textLeft, y + 5, color, true);
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {   // blinking caret, only as tall as the text
            int cx = textLeft + tr.width(query.substring(0, Math.min(caret, query.length())));
            ctx.fill(cx, y + 5, cx + 1, y + 4 + tr.lineHeight, 0xFFFFFFFF);
        }
        renderFavorites(ctx, tr, favoritesX(x), y, sw, towns, favoriteTowns);

        // The results dropdown shows only while the bar is focused (actively
        // searching); once a result is chosen the info panel takes over.  The two
        // are mutually exclusive — focusing clears the selection, selecting unfocuses
        // — so they are never on screen at the same time.
        if (focused) {
            for (int i = 0; i < results.size(); i++) {
                Result result = results.get(i);
                int rowY = resultRowY(y, i);
                ctx.fill(x - 1, rowY - 1, x + W + 1, rowY + ROW_HEIGHT + 1, BORDER);
                ctx.fill(x, rowY, x + W, rowY + ROW_HEIGHT, i == selected ? HOVER : BG);
                ctx.text(tr, trimToWidth(tr, result.label(), W - 14), x + 7, rowY + 5, 0xFFFFFFFF, true);
            }
            // Empty bar: advertise what the bar can do beyond names — filters first, archive last, since
            // the archive row was the only hint before and stays where players already expect it.
            if (query.isEmpty()) {
                boolean planningPrompt = PlanningOverlay.isActive() && !PlanningOverlay.hasNation();
                List<String> hints = planningPrompt
                        ? List.of("§fPlease enter a nation")
                        : HINTS;
                for (int i = 0; i < hints.size(); i++) {
                    int rowY = resultRowY(y, i);
                    ctx.fill(x - 1, rowY - 1, x + W + 1, rowY + ROW_HEIGHT + 1, BORDER);
                    ctx.fill(x, rowY, x + W, rowY + ROW_HEIGHT, BG);
                    ctx.text(tr, trimToWidth(tr, hints.get(i), W - 14), x + 7, rowY + 5, 0xFFFFFFFF, true);
                }
            }
        }
        // The selected info panel is tied to the search bar: an empty bar means no lingering right-clicked
        // or searched selection. (A real selection always keeps the bar populated — openSearch/select set
        // the query — so this only fires once the bar has actually been cleared.)
        if (scaled) UiScale.pop(ctx);   // end the bar/dropdown scale; the right-side info panel scales itself
        if (query.isEmpty()) clearSelection();
        renderSelectedInfo(ctx, tr, sw, sh,
                towns, players, townDetails, playerDetails, playerHistory, nationDetails);
    }

    public static ClickResult click(double mouseX, double mouseY, int sw,
                                    List<TownData> towns, List<PlayerMarker> players,
                                    Map<String, TownPopupData> townDetails,
                                    List<EarthMcPlayerData> apiPlayers,
                                    Map<String, EarthMcPlayerData> playerDetails,
                                    Map<String, PlayerHistoryEntry> playerHistory,
                                    List<EarthMcNationData> apiNations,
                                    Map<String, EarthMcNationData> nationDetails,
                                    List<String> favoriteTowns) {
        int x = left(sw);
        int y = top();
        // The bar/dropdown and the right-side info panel scale around different anchors, so un-scale the
        // mouse for each separately. The caret uses the RAW mouse (charIndexAtX un-scales it itself).
        double barMx = mouseX, barMy = mouseY, infoMx = mouseX, infoMy = mouseY;
        if (UiScale.active()) {
            barMx = UiScale.unscale(mouseX, x + panelWidth / 2.0); barMy = UiScale.unscale(mouseY, y + ROW_HEIGHT / 2.0);
            infoMx = UiScale.unscale(mouseX, infoAnchorX);    infoMy = UiScale.unscale(mouseY, infoAnchorY);
        }
        ClickResult favoriteClick = favoriteClick(barMx, barMy, favoritesX(x), y, towns, favoriteTowns);
        if (favoriteClick.consumed()) return favoriteClick;
        if (infoDiscordVisible && inside(infoMx, infoMy, infoDiscordX, infoDiscordY, infoDiscordW, infoDiscordH)) {
            TownInfoOverlay.openDiscord(infoDiscordUrl);
            return ClickResult.consumedResult();
        }
        if (infoStarVisible && inside(infoMx, infoMy, infoStarX, infoStarY, infoStarW, infoStarH)) {
            TownyMapMod.toggleFavoriteEntity(selectedType, selectedName);
            return ClickResult.consumedResult();
        }
        if (infoRangeVisible && inside(infoMx, infoMy, infoRangeX, infoRangeY, infoRangeW, infoRangeH)) {
            toggleNationRange(selectedName);
            return ClickResult.consumedResult();
        }
        if (infoExpandVisible && inside(infoMx, infoMy, infoExpandX, infoExpandY, infoExpandW, infoExpandH)) {
            DetailScreen.Kind kind = switch (selectedType) {
                case "nation" -> DetailScreen.Kind.NATION;
                case "player" -> DetailScreen.Kind.PLAYER;
                default -> DetailScreen.Kind.TOWN;
            };
            TownyMapMod.openDetail(kind, selectedName, null);
            return ClickResult.consumedResult();
        }
        // Clicking a name inside the info panel re-searches for that entity.
        for (InfoLink link : infoLinks) {
            if (link.contains(infoMx, infoMy)) {
                activateLink(link);
                return ClickResult.consumedResult();
            }
        }

        if (inside(barMx, barMy, x, y, panelWidth, ROW_HEIGHT)) {
            focused = true;
            selected = 0;
            caret = charIndexAtX(sw, mouseX);   // place the cursor where they clicked (raw mouse)
            selAnchor = caret;                  // anchor a potential drag-select
            textDragging = true;
            clearSelection();                   // hide the info panel while typing a new search
            return ClickResult.consumedResult();
        }

        if (focused) {
            List<Result> results = results(towns, players, townDetails, apiPlayers,
                    playerDetails, playerHistory, apiNations, nationDetails);
            for (int i = 0; i < results.size(); i++) {
                int rowY = resultRowY(y, i);
                if (inside(barMx, barMy, x, rowY, panelWidth, ROW_HEIGHT)) {
                    selected = i;
                    Result result = results.get(i);
                    focused = false;
                    select(result);
                    return ClickResult.jump(result.target());
                }
            }
        }

        // Click landed outside the search UI: just close the dropdown. Do NOT clear the selected info here —
        // that's deferred to the map click-away dismiss (armMapClickDismiss), so panning keeps the result up.
        focused = false;
        textDragging = false;
        selAnchor = -1;
        return ClickResult.none();
    }

    /**
     * While a text-drag is active, follow the live mouse each frame (Xaero's GuiMap doesn't forward
     * mouseDragged to us, and the map can't pan because our click cancelled its press handler). Ends the drag
     * when the button is released — a press with no movement leaves just a caret.
     */
    private static void tickTextDrag(Minecraft mc, int sw) {
        if (!textDragging) return;
        boolean held = focused && mc.mouseHandler != null && mc.mouseHandler.isLeftPressed();
        if (held) {
            caret = charIndexAtX(sw, mc.mouseHandler.xpos() * sw / (double) mc.getWindow().getWidth());
        } else {
            textDragging = false;
            if (selAnchor == caret) selAnchor = -1;
        }
    }

    private static boolean hasSelection() { return selAnchor >= 0 && selAnchor != caret; }
    private static int selLo() { return Math.min(caret, selAnchor); }
    private static int selHi() { return Math.max(caret, selAnchor); }

    /** The caret index nearest a screen-space x within the bar (0..length). */
    private static int charIndexAtX(int sw, double mouseX) {
        Font tr = Minecraft.getInstance().font;
        if (UiScale.active()) mouseX = UiScale.unscale(mouseX, left(sw) + panelWidth / 2.0);   // scaled around the bar centre
        double target = mouseX - (left(sw) + 7);
        if (target <= 0) return 0;
        for (int i = 1; i <= query.length(); i++) {
            double mid = (tr.width(query.substring(0, i - 1)) + tr.width(query.substring(0, i))) / 2.0;
            if (target < mid) return i - 1;
        }
        return query.length();
    }

    private static void deleteSelection() {
        int lo = selLo(), hi = selHi();
        query = query.substring(0, lo) + query.substring(hi);
        caret = lo;
        selAnchor = -1;
    }

    /** Inserts text at the caret (replacing any selection), respecting the length cap. */
    private static void insertText(String s) {
        if (hasSelection()) deleteSelection();
        int room = MAX_QUERY - query.length();
        if (room <= 0 || s.isEmpty()) return;
        if (s.length() > room) s = s.substring(0, room);
        query = query.substring(0, caret) + s + query.substring(caret);
        caret += s.length();
        selAnchor = -1;
    }

    public static ClickResult keyPressed(int keyCode, List<TownData> towns, List<PlayerMarker> players,
                                         Map<String, TownPopupData> townDetails,
                                         List<EarthMcPlayerData> apiPlayers,
                                         Map<String, EarthMcPlayerData> playerDetails,
                                         Map<String, PlayerHistoryEntry> playerHistory,
                                         List<EarthMcNationData> apiNations,
                                         Map<String, EarthMcNationData> nationDetails) {
        if (!focused) return ClickResult.none();
        List<Result> results = results(towns, players, townDetails, apiPlayers,
                playerDetails, playerHistory, apiNations, nationDetails);

        if (keyCode == InputConstants.KEY_ESCAPE) {
            focused = false;
            selAnchor = -1;
            return ClickResult.consumedResult();
        }
        // Ctrl+A selects the whole query.
        if (keyCode == InputConstants.KEY_A && ctrlDown()) {
            selAnchor = 0;
            caret = query.length();
            return ClickResult.consumedResult();
        }
        // Clipboard: Ctrl+C copy, Ctrl+X cut, Ctrl+V paste — on the selection if there is one, else the whole query.
        if (keyCode == InputConstants.KEY_C && ctrlDown()) {
            setClipboard(hasSelection() ? query.substring(selLo(), selHi()) : query);
            return ClickResult.consumedResult();
        }
        if (keyCode == InputConstants.KEY_X && ctrlDown()) {
            setClipboard(hasSelection() ? query.substring(selLo(), selHi()) : query);
            if (hasSelection()) deleteSelection(); else { query = ""; caret = 0; selAnchor = -1; }
            afterEdit();
            return ClickResult.consumedResult();
        }
        if (keyCode == InputConstants.KEY_V && ctrlDown()) {
            insertText(sanitizePaste(getClipboard()));
            afterEdit();
            return ClickResult.consumedResult();
        }
        if (keyCode == InputConstants.KEY_BACKSPACE) {
            if (hasSelection()) deleteSelection();
            else if (ctrlDown()) deleteWordBackward();
            else if (caret > 0) { query = query.substring(0, caret - 1) + query.substring(caret); caret--; }
            selAnchor = -1;
            afterEdit();
            return ClickResult.consumedResult();
        }
        if (keyCode == InputConstants.KEY_DELETE) {
            if (hasSelection()) deleteSelection();
            else if (caret < query.length()) query = query.substring(0, caret) + query.substring(caret + 1);
            selAnchor = -1;
            afterEdit();
            return ClickResult.consumedResult();
        }
        if (keyCode == InputConstants.KEY_LEFT || keyCode == InputConstants.KEY_RIGHT) {
            int to = keyCode == InputConstants.KEY_LEFT ? caret - 1 : caret + 1;
            moveCaret(Math.max(0, Math.min(query.length(), to)), shiftDown());
            return ClickResult.consumedResult();
        }
        if (keyCode == InputConstants.KEY_HOME) { moveCaret(0, shiftDown()); return ClickResult.consumedResult(); }
        if (keyCode == InputConstants.KEY_END)  { moveCaret(query.length(), shiftDown()); return ClickResult.consumedResult(); }
        if (keyCode == InputConstants.KEY_DOWN) {
            if (!results.isEmpty()) selected = Math.min(results.size() - 1, selected + 1);
            return ClickResult.consumedResult();
        }
        if (keyCode == InputConstants.KEY_UP) {
            selected = Math.max(0, selected - 1);
            return ClickResult.consumedResult();
        }
        if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
            if (!results.isEmpty()) {
                Result result = results.get(Math.min(selected, results.size() - 1));
                focused = false;
                select(result);
                return ClickResult.jump(result.target());
            }
            return ClickResult.consumedResult();
        }
        return ClickResult.consumedResult();
    }

    public static boolean charTyped(char chr) {
        if (!focused) return false;
        if (Character.isISOControl(chr)) return true;
        insertText(String.valueOf(chr));   // replaces any selection, inserts at the caret
        afterEdit();
        return true;
    }

    /** Post-edit housekeeping: reset the result cursor and rebuild the dropdown. */
    private static void afterEdit() {
        selected = 0;
        invalidateResults();
        clearSelection();
    }

    private static void moveCaret(int to, boolean extend) {
        if (extend) { if (selAnchor < 0) selAnchor = caret; }
        else selAnchor = -1;
        caret = Math.max(0, Math.min(query.length(), to));
    }

    /** Ctrl+Backspace: deletes the word (and any whitespace right before it) just before the caret. */
    private static void deleteWordBackward() {
        int i = caret;
        while (i > 0 && Character.isWhitespace(query.charAt(i - 1))) i--;
        while (i > 0 && !Character.isWhitespace(query.charAt(i - 1))) i--;
        query = query.substring(0, i) + query.substring(caret);
        caret = i;
    }

    private static boolean shiftDown() {
        return InputConstants.isKeyDown(InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT);
    }

    private static final int MAX_QUERY = 60;

    private static void setClipboard(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.keyboardHandler != null && s != null) mc.keyboardHandler.setClipboard(s);
    }

    private static String getClipboard() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.keyboardHandler != null ? mc.keyboardHandler.getClipboard() : "";
    }

    /** Strips control characters (newlines, tabs…) so a multi-line paste stays a single search line. */
    private static String sanitizePaste(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isISOControl(c)) b.append(c);
        }
        return b.toString();
    }

    /** True while Ctrl (or Cmd on macOS) is held, matching how this mod reads Shift elsewhere. */
    private static boolean ctrlDown() {
        return InputConstants.isKeyDown(InputConstants.KEY_LCONTROL)
                || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL)
                || InputConstants.isKeyDown(InputConstants.KEY_LGUI)
                || InputConstants.isKeyDown(InputConstants.KEY_RGUI);
    }


    public static String query() {
        return query;
    }

    public static String exactPlayerQuery() {
        String trimmed = query.trim();
        if (trimmed.length() < 3) return "";
        if (!trimmed.matches("[A-Za-z0-9_]{3,16}")) return "";
        return trimmed;
    }

    public static List<String> visibleApiPlayerMatches(List<EarthMcPlayerData> apiPlayers) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.length() < 2) return List.of();

        ArrayList<EarthMcPlayerData> matches = new ArrayList<>();
        for (EarthMcPlayerData player : apiPlayers) {
            if (player.name().toLowerCase(Locale.ROOT).contains(needle)) matches.add(player);
        }
        matches.sort(Comparator
                .comparingInt((EarthMcPlayerData p) -> score(p.name(), needle))
                .thenComparing(EarthMcPlayerData::name, String.CASE_INSENSITIVE_ORDER));

        ArrayList<String> names = new ArrayList<>();
        int limit = Math.min(MAX_RESULTS, matches.size());
        for (int i = 0; i < limit; i++) names.add(matches.get(i).name());
        return List.copyOf(names);
    }

    public static List<String> visibleTownMatches(List<TownData> towns) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.length() < 2) return List.of();

        ArrayList<TownData> matches = new ArrayList<>();
        for (TownData town : towns) {
            String lowerName = town.name().toLowerCase(Locale.ROOT);
            if (lowerName.contains(needle)) matches.add(town);
        }
        matches.sort(Comparator
                .comparingInt((TownData t) -> score(t.name(), needle))
                .thenComparing(TownData::name, String.CASE_INSENSITIVE_ORDER));

        ArrayList<String> names = new ArrayList<>();
        int limit = Math.min(MAX_RESULTS, matches.size());
        for (int i = 0; i < limit; i++) names.add(matches.get(i).name());
        return List.copyOf(names);
    }

    public static List<String> visibleNationMatches(List<EarthMcNationData> apiNations) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.length() < 2) return List.of();

        ArrayList<EarthMcNationData> matches = new ArrayList<>();
        for (EarthMcNationData nation : apiNations) {
            if (nation.name().toLowerCase(Locale.ROOT).contains(needle)) matches.add(nation);
        }
        matches.sort(Comparator
                .comparingInt((EarthMcNationData n) -> score(n.name(), needle))
                .thenComparing(EarthMcNationData::name, String.CASE_INSENSITIVE_ORDER));

        ArrayList<String> names = new ArrayList<>();
        int limit = Math.min(MAX_RESULTS, matches.size());
        for (int i = 0; i < limit; i++) names.add(matches.get(i).name());
        return List.copyOf(names);
    }

    private static List<Result> results(List<TownData> towns, List<PlayerMarker> players,
                                        Map<String, TownPopupData> townDetails,
                                        List<EarthMcPlayerData> apiPlayers,
                                        Map<String, EarthMcPlayerData> playerDetails,
                                        Map<String, PlayerHistoryEntry> playerHistory,
                                        List<EarthMcNationData> apiNations,
                                        Map<String, EarthMcNationData> nationDetails) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return List.of();

        // While Planning waits for a nation the bar is a nation picker: no archive shortcut, no other types.
        boolean planningPick = PlanningOverlay.isActive() && !PlanningOverlay.hasNation();
        if (planningPick) {
            List<Result> out = new ArrayList<>();
            for (EarthMcNationData n : apiNations) {
                if (n.name() == null || !n.name().toLowerCase(Locale.ROOT).contains(needle)) continue;
                out.add(new Result(n.name(), null, 0, "nation", n.name()));
                if (out.size() >= MAX_RESULTS) break;
            }
            return out;
        }

        // A dd/mm/yyyy query is an archive request: load the Wayback snapshot nearest that date.
        int archiveDate = parseArchiveDate(query.trim());
        if (archiveDate > 0) {
            return List.of(new Result("View archive: " + archiveDateLabel(archiveDate),
                    null, 0, "archive", String.valueOf(archiveDate)));
        }
        // Filter query ("nationless", "residents>20", "chunks<10", "nation:Germany") — searches by property
        // rather than by name. Everything it needs is already parsed from the squaremap markers, so filtering
        // all 5,600 towns costs nothing extra.
        Filters filters = Filters.parse(query);
        if (filters != null) return filterTowns(towns, filters);
        publishFilterMatches(false, java.util.Set.of());

        if (needle.equals(cachedNeedle)
                && cachedTownCount == towns.size()
                && cachedTownDetailCount == townDetails.size()
                && cachedMarkerCount == players.size()
                && cachedApiPlayerCount == apiPlayers.size()
                && cachedPlayerDetailCount == playerDetails.size()
                && cachedPlayerHistoryCount == playerHistory.size()
                && cachedNationCount == apiNations.size()
                && cachedNationDetailCount == nationDetails.size()) {
            return cachedResults;
        }

        Comparator<Result> byScore = Comparator
                .comparingInt(Result::score)
                .thenComparing(Result::label, String.CASE_INSENSITIVE_ORDER);
        Map<String, TownData> townIndex = new HashMap<>(Math.max(16, towns.size() * 2));
        for (TownData town : towns) {
            townIndex.put(town.name().toLowerCase(Locale.ROOT), town);
        }
        Map<String, PlayerMarker> markerIndex = new HashMap<>(Math.max(16, players.size() * 2));
        for (PlayerMarker player : players) {
            markerIndex.put(player.name().toLowerCase(Locale.ROOT), player);
        }
        Set<String> apiPlayerNames = new HashSet<>(Math.max(16, apiPlayers.size() * 2));
        for (EarthMcPlayerData player : apiPlayers) {
            apiPlayerNames.add(player.name().toLowerCase(Locale.ROOT));
        }
        Set<String> playerDetailNames = new HashSet<>(Math.max(16, playerDetails.size() * 2));
        for (EarthMcPlayerData player : playerDetails.values()) {
            playerDetailNames.add(player.name().toLowerCase(Locale.ROOT));
        }

        // ── Towns ────────────────────────────────────────────────────────────
        ArrayList<Result> townMatches = new ArrayList<>();
        for (TownData town : towns) {
            String lowerName = town.name().toLowerCase(Locale.ROOT);
            if (lowerName.contains(needle)) {
                townMatches.add(new Result("Town: " + town.name(),
                        new MapJumpTarget(town.name(), town.centerX(), town.centerZ()),
                        score(town.name(), needle), "town", town.name()));
            }
        }
        townMatches.sort(byScore);
        if (townMatches.size() > MAX_PER_TYPE) townMatches.subList(MAX_PER_TYPE, townMatches.size()).clear();

        // ── Nations ──────────────────────────────────────────────────────────
        ArrayList<Result> nationMatches = new ArrayList<>();
        for (EarthMcNationData nation : apiNations) {
            String lowerName = nation.name().toLowerCase(Locale.ROOT);
            if (!lowerName.contains(needle)) continue;

            EarthMcNationData details = nationDetails.get(lowerName);
            String suffix = details == null ? "Checking" : capitalLabel(details);
            String allianceTag = TownyMapMod.allianceTagForNation(nation.name());
            if (!allianceTag.isEmpty()) suffix += " · " + allianceTag;
            nationMatches.add(new Result("Nation: " + nation.name() + " (" + suffix + ")",
                    nationTarget(nation.name(), details, townIndex),
                    score(nation.name(), needle), "nation", nation.name()));
        }
        nationMatches.sort(byScore);
        if (nationMatches.size() > MAX_PER_TYPE) nationMatches.subList(MAX_PER_TYPE, nationMatches.size()).clear();

        // ── Players ──────────────────────────────────────────────────────────
        ArrayList<Result> playerMatches = new ArrayList<>();
        for (EarthMcPlayerData player : apiPlayers) {
            String lowerName = player.name().toLowerCase(Locale.ROOT);
            if (!lowerName.contains(needle)) continue;

            PlayerMarker marker = markerIndex.get(lowerName);
            EarthMcPlayerData details = playerDetails.get(lowerName);
            String status = playerStatus(details, marker);
            PlayerHistoryEntry history = playerHistory.get(lowerName);
            MapJumpTarget target = playerTarget(player.name(), marker, details, history, townIndex);
            playerMatches.add(new Result("Player: " + player.name() + " (" + status + ")",
                    target, score(player.name(), needle), "player", player.name()));
        }
        for (PlayerMarker player : players) {
            String lowerName = player.name().toLowerCase(Locale.ROOT);
            if (lowerName.contains(needle)
                    && !apiPlayerNames.contains(lowerName)) {
                playerMatches.add(new Result("Player: " + player.name() + " (Online)",
                        new MapJumpTarget(player.name(), player.x(), player.z()),
                        score(player.name(), needle), "player", player.name()));
            }
        }
        for (EarthMcPlayerData player : playerDetails.values()) {
            String lowerName = player.name().toLowerCase(Locale.ROOT);
            if (!lowerName.contains(needle)) continue;
            if (apiPlayerNames.contains(lowerName)) continue;
            if (markerIndex.containsKey(lowerName)) continue;
            PlayerMarker marker = markerIndex.get(lowerName);
            PlayerHistoryEntry history = playerHistory.get(lowerName);
            playerMatches.add(new Result("Player: " + player.name() + " (" + playerStatus(player, marker) + ")",
                    playerTarget(player.name(), marker, player, history, townIndex),
                    score(player.name(), needle), "player", player.name()));
        }
        for (PlayerHistoryEntry history : playerHistory.values()) {
            String lowerName = history.name().toLowerCase(Locale.ROOT);
            if (!lowerName.contains(needle)) continue;
            if (apiPlayerNames.contains(lowerName)) continue;
            if (markerIndex.containsKey(lowerName)) continue;
            if (playerDetailNames.contains(lowerName)) continue;
            playerMatches.add(new Result("Player: " + history.name() + " (Last seen)",
                    new MapJumpTarget(history.name(), history.x(), history.z()),
                    score(history.name(), needle), "player", history.name()));
        }
        String exact = exactPlayerQuery();
        String exactKey = exact.toLowerCase(Locale.ROOT);
        if (!exact.isBlank()
                && playerMatches.stream().noneMatch(r -> r.name().equalsIgnoreCase(exact))
                && !apiPlayerNames.contains(exactKey)
                && !markerIndex.containsKey(exactKey)) {
            playerMatches.add(new Result("Player: " + exact + " (Checking)",
                    null, score(exact, needle), "player", exact));
        }
        playerMatches.sort(byScore);
        if (playerMatches.size() > MAX_PER_TYPE) playerMatches.subList(MAX_PER_TYPE, playerMatches.size()).clear();

        // ── Combine: interleave by rank so each type gets fair representation ─
        ArrayList<Result> combined = new ArrayList<>(townMatches.size() + nationMatches.size() + playerMatches.size());
        int ti = 0, ni = 0, pi = 0;
        while (combined.size() < MAX_RESULTS) {
            boolean added = false;
            if (ti < townMatches.size())   { combined.add(townMatches.get(ti++));   added = true; }
            if (combined.size() < MAX_RESULTS && ni < nationMatches.size())  { combined.add(nationMatches.get(ni++));  added = true; }
            if (combined.size() < MAX_RESULTS && pi < playerMatches.size())  { combined.add(playerMatches.get(pi++));  added = true; }
            if (!added) break;
        }
        List<Result> result = List.copyOf(combined);
        cachedNeedle = needle;
        cachedTownCount = towns.size();
        cachedTownDetailCount = townDetails.size();
        cachedMarkerCount = players.size();
        cachedApiPlayerCount = apiPlayers.size();
        cachedPlayerDetailCount = playerDetails.size();
        cachedPlayerHistoryCount = playerHistory.size();
        cachedNationCount = apiNations.size();
        cachedNationDetailCount = nationDetails.size();
        cachedResults = result;
        return result;
    }

    /** Public so external data loads (e.g. the alliance roster) can force the result labels to rebuild. */
    public static void invalidateResults() {
        cachedNeedle = null;
        cachedResults = List.of();
    }

    private static int score(String name, String needle) {
        if (needle.isEmpty()) return 0;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals(needle)) return 0;
        if (lower.startsWith(needle)) return 1;
        return 2 + lower.indexOf(needle);
    }

    private static PlayerMarker visibleMarker(String name, List<PlayerMarker> players) {
        for (PlayerMarker marker : players) {
            if (marker.name().equalsIgnoreCase(name)) return marker;
        }
        return null;
    }

    private static String playerStatus(EarthMcPlayerData details, PlayerMarker marker) {
        if (marker != null) return "Online";
        if (details == null) return "Checking";
        if (details.online()) return "Hidden";
        return "Offline";
    }

    private static MapJumpTarget playerTarget(String name, PlayerMarker marker,
                                              EarthMcPlayerData details, PlayerHistoryEntry history,
                                              Map<String, TownData> townIndex) {
        if (marker != null) return new MapJumpTarget(name, marker.x(), marker.z());
        if (history != null) return new MapJumpTarget(name, history.x(), history.z());
        if (details != null && !details.townName().isBlank()) {
            TownData town = townIndex.get(details.townName().toLowerCase(Locale.ROOT));
            if (town != null) {
                return new MapJumpTarget(name, town.centerX(), town.centerZ());
            }
        }
        return null;
    }

    private static MapJumpTarget nationTarget(String name, EarthMcNationData details, Map<String, TownData> townIndex) {
        if (details == null) return null;
        if (!details.capitalName().isBlank()) {
            TownData town = townIndex.get(details.capitalName().toLowerCase(Locale.ROOT));
            if (town != null) {
                return new MapJumpTarget(name, town.centerX(), town.centerZ());
            }
        }
        // Only on Earth: the API spawn is an Earth coordinate, so jumping to it while viewing the Moon
        // would land somewhere unrelated rather than at the nation's outpost.
        if (details.hasSpawn() && TownyMapMod.viewingEarth()) {
            return new MapJumpTarget(name, details.spawnX(), details.spawnZ());
        }
        return null;
    }

    private static String capitalLabel(EarthMcNationData details) {
        if (details == null || details.capitalName().isBlank()) return "No capital";
        return "Capital: " + details.capitalName();
    }

    /** Parses a dd/mm/yyyy date (lenient) to yyyymmdd, or 0 if invalid / before MIN_DATE. Public so the
     *  settings screen can offer the same entry point. */
    public static int parseArchiveDate(String s) {
        // Lenient dd/mm/yyyy: no leading zero needed (17/4/2026); the separator may be / . , - OR a space
        // (17 4 2026); and the year may be 2 or 4 digits (17 4 26 → 2026).
        String sep = "(?:\\s*[/.,-]\\s*|\\s+)";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^\\s*(\\d{1,2})" + sep + "(\\d{1,2})" + sep + "(\\d{4}|\\d{2})\\s*$").matcher(s);
        if (!m.matches()) return 0;
        int d = Integer.parseInt(m.group(1)), mo = Integer.parseInt(m.group(2)), y = Integer.parseInt(m.group(3));
        if (y < 100) y += 2000;   // two-digit year → 20xx
        if (d < 1 || d > 31 || mo < 1 || mo > 12) return 0;
        int yyyymmdd = y * 10000 + mo * 100 + d;
        // Per world: the Moon launched on 2026-08-30, so Terra Nostra's floor would accept dates it has
        // no captures for and send the user to an empty snapshot.
        int floor = net.townymap.api.ArchiveClient.minDateFor(net.townymap.TownyMapMod.activeWorldKey());
        return yyyymmdd >= floor ? yyyymmdd : 0;
    }

    private static String archiveDateLabel(int yyyymmdd) {
        int y = yyyymmdd / 10000, mo = (yyyymmdd / 100) % 100, d = yyyymmdd % 100;
        String[] mon = {"", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        return d + " " + mon[mo] + " " + y;
    }

    private static void select(Result result) {
        if ("archive".equals(result.type())) {
            clearSelection();
            query = "";
            clearFilterHighlight();
            caret = 0;
            selAnchor = -1;
            invalidateResults();
            TownyMapMod.enterArchive(Integer.parseInt(result.name()));
            return;
        }
        if ("town".equals(result.type())) {
            // Towns use the same rich popup as right-clicking the map (Route button + full details),
            // so the search bar and the map share one town GUI instead of a separate inline panel.
            clearSelection();
            TownyMapMod.openTownPopupFromSearch(result.name());
            return;
        }
        selectedType = result.type();
        selectedName = result.name();
        if ("nation".equals(result.type()) && PlanningOverlay.isActive()) {
            PlanningOverlay.setNation(result.name());   // the prompt clears once a nation is picked
        }
        TownInfoOverlay.dismiss();
    }

    private static void clearSelection() {
        // The join-range zone belongs to the panel that switched it on, so it goes when that panel does:
        // dismissing the panel, opening the right-click popup, selecting something else, or reopening the
        // map (reset() routes through here) all leave the map clean instead of stranding a zone on screen.
        rangeNation = null;
        selectedType = "";
        selectedName = "";
        infoDiscordVisible = false;
        infoExpandVisible = false;
        infoDiscordUrl = "";
        infoLinks.clear();
    }

    /** Fully clears the search bar — query, focus, and any selected info panel. Called when the world
     *  map is reopened or panned, so a stale search/selection doesn't linger. */
    public static void reset() {
        query = "";
        clearFilterHighlight();
        focused = false;
        selected = 0;
        caret = 0;
        selAnchor = -1;
        textDragging = false;
        favoritesOpen = false;
        cachedNeedle = null;
        clearSelection();
    }

    /**
     * Hides the search result info panel.  Called when the right-click town popup
     * (TownInfoOverlay) opens, so the two info panels are never shown at once.
     */
    public static void dismissSelection() {
        clearSelection();
    }

    /**
     * Switches the info panel to a name clicked inside it (Mayor/King → that
     * player, Nation/Capital → that nation/town), mirroring a search-bar lookup:
     * the query is updated so detail fetching kicks in, and the panel re-renders
     * for the clicked entity.  The camera is left where it is.
     */
    private static void activateLink(InfoLink link) {
        // A bloc has no search entry of its own — go straight to its expanded panel.
        if ("alliance".equals(link.type())) {
            TownyMapMod.openDetail(DetailScreen.Kind.ALLIANCE, link.name(), null);
            return;
        }
        openSearch(link.type(), link.name());
    }

    /**
     * Opens the search info panel for a given entity, as if it had been typed and
     * selected in the search bar.  Used by the in-panel name links and by the
     * right-click town popup's clickable names.
     */
    public static void openSearch(String type, String name) {
        if (name == null || name.isBlank()) return;
        query = name;
        selected = 0;
        focused = false;
        caret = query.length();
        selAnchor = -1;
        textDragging = false;
        invalidateResults();
        if ("town".equals(type)) {
            // Town links resolve to the shared rich popup, matching a search selection / right-click.
            clearSelection();
            TownyMapMod.openTownPopupFromSearch(name);
            return;
        }
        selectedType = type;
        selectedName = name;
        infoLinks.clear();
    }

    /** A smooth 1px ring, drawn as short rotated quads so it reads as a circle rather than a stair-stepped box. */
    private static void drawRing(GuiGraphicsExtractor ctx, double cx, double cy, double r, int color) {
        if (r < 1.0) return;
        int segments = Math.max(16, Math.min(64, (int) Math.round(r * 6)));
        org.joml.Matrix3x2fStack m = ctx.pose();
        double px = cx + r, py = cy;
        for (int i = 1; i <= segments; i++) {
            double a = Math.PI * 2 * i / segments;
            double nx = cx + Math.cos(a) * r, ny = cy + Math.sin(a) * r;
            double dx = nx - px, dy = ny - py;
            double len = Math.hypot(dx, dy);
            if (len >= 0.01) {
                m.pushMatrix();
                try {
                    m.translate((float) px, (float) py);
                    m.rotate((float) Math.atan2(dy, dx));
                    m.scale((float) len, 1f);
                    ctx.fill(0, 0, 1, 1, color);
                } finally {
                    m.popMatrix();
                }
            }
            px = nx; py = ny;
        }
    }

    private static void renderSelectedInfo(GuiGraphicsExtractor ctx, Font tr, int sw, int sh,
                                           List<TownData> towns, List<PlayerMarker> players,
                                           Map<String, TownPopupData> townDetails,
                                           Map<String, EarthMcPlayerData> playerDetails,
                                           Map<String, PlayerHistoryEntry> playerHistory,
                                           Map<String, EarthMcNationData> nationDetails) {
        infoDiscordVisible = false;
        infoExpandVisible = false;
        infoDiscordUrl = "";
        infoLinks.clear();
        if (selectedName.isBlank()) return;
        List<InfoRow> lines = selectedInfo(towns, players, townDetails, playerDetails,
                playerHistory, nationDetails);
        if (lines.isEmpty()) return;
        String discordUrl = selectedDiscordUrl(townDetails, nationDetails);
        boolean showDiscordButton = !discordUrl.isBlank();
        // Every entity the search panel can show has a full panel behind it, so Expand is always offered.
        boolean showExpand = !selectedType.isBlank();
        if (lines.size() > MAX_INFO_LINES) {
            lines = new ArrayList<>(lines.subList(0, MAX_INFO_LINES));
        }

        int maxW = 0;
        for (InfoRow row : lines) maxW = Math.max(maxW, rowWidth(tr, row));
        int boxW = Math.min(Math.max(WIDTH, maxW + 16), Math.max(WIDTH, sw - 24));
        int buttonRowHeight = (showDiscordButton || showExpand) ? ROW_HEIGHT + 7 : 0;
        int boxH = lines.size() * 12 + 14 + buttonRowHeight;
        int x = Math.max(8, sw - boxW - 12);
        int y = Math.max(36, Math.min(sh - boxH - 36, sh / 2 - boxH / 2));
        // Scale around the panel's RIGHT-MIDDLE so it stays pinned to the right edge and vertically centred.
        infoAnchorX = x + boxW; infoAnchorY = y + boxH / 2;
        boolean scaled = UiScale.active();
        if (scaled) UiScale.push(ctx, infoAnchorX, infoAnchorY);

        ctx.fill(x - 1, y - 1, x + boxW + 1, y + boxH + 1, BORDER);
        ctx.fill(x, y, x + boxW, y + boxH, BG);
        if ("player".equals(selectedType)) {   // player head in the top-right corner
            int hs = 16;
            net.townymap.render.PlayerHeadRenderer.drawMenuHead(ctx, selectedName, x + boxW - 5 - hs / 2, y + 5 + hs / 2, hs);
        }

        int mx = scaled ? (int) Math.round(UiScale.unscale(scaledMouseX(), infoAnchorX)) : scaledMouseX();
        int my = scaled ? (int) Math.round(UiScale.unscale(scaledMouseY(), infoAnchorY)) : scaledMouseY();

        // Star (favourite). Worked out before the range ring so the ring's hover label knows to clear it.
        // On players it sits left of the head rather than over it.
        pendingTipText = null;
        infoStarVisible = false;
        int starD = 13;
        if ("nation".equals(selectedType) || "player".equals(selectedType)) {
            infoStarVisible = true;
            infoStarW = starD;
            infoStarH = starD;
            infoStarY = y + 5;
            boolean nationRing = "nation".equals(selectedType)
                    && TownyMapMod.getConfig().nationRangeEnabled;
            if (nationRing) {
                infoStarX = x + boxW - 6 - 13 - 4 - starD;      // left of the range ring
            } else if ("player".equals(selectedType)) {
                infoStarX = x + boxW - 5 - 16 - 4 - starD;      // left of the 16px head
            } else {
                infoStarX = x + boxW - 6 - starD;
            }
        }

        // Join-range toggle: a small ring in the top-right of a nation's panel. Labels itself on hover so the
        // icon doesn't need explaining, and stays lit while its zone is on the map.
        infoRangeVisible = false;
        if ("nation".equals(selectedType) && TownyMapMod.getConfig().nationRangeEnabled) {
            int d = 13;
            infoRangeX = x + boxW - 6 - d;
            infoRangeY = y + 5;
            infoRangeW = d;
            infoRangeH = d;
            infoRangeVisible = true;
            boolean on = selectedName.equalsIgnoreCase(rangeNation == null ? "" : rangeNation);
            boolean hov = inside(mx, my, infoRangeX, infoRangeY, d, d);
            int ring = on ? 0xFF6FD3A0 : hov ? 0xFFFFFFFF : 0xFF9AA0A8;
            drawRing(ctx, infoRangeX + d / 2.0, infoRangeY + d / 2.0, d / 2.0 - 1.0, ring);
            if (on) ctx.fill(infoRangeX + d / 2 - 1, infoRangeY + d / 2 - 1,
                             infoRangeX + d / 2 + 1, infoRangeY + d / 2 + 1, ring);
            if (hov) {   // left of the ring, so it never runs off the screen edge
                pendingTipText = "Nation Range";
                pendingTipX = (infoStarVisible ? infoStarX : infoRangeX) - 4;
                pendingTipY = infoRangeY + 3;
            }
        }
        // Star: nations and players can be favourited too, not just towns. Sits left of the range ring
        // when there is one, otherwise takes the top-right corner itself.
        if (infoStarVisible) {
            boolean on = TownyMapMod.isFavoriteEntity(selectedType, selectedName);
            boolean hov = inside(mx, my, infoStarX, infoStarY, starD, starD);
            int col = on ? 0xFFFFD24A : hov ? 0xFFFFFFFF : 0xFF9AA0A8;
            String star = on ? "\u2605" : "\u2606";
            int swid = tr.width(star);
            ctx.text(tr, star, infoStarX + (starD - swid) / 2, infoStarY + 3, col, false);
            if (hov) {
                pendingTipText = on ? "Unfavourite" : "Favourite";
                pendingTipX = infoStarX - 4;
                pendingTipY = infoStarY + 3;
            }
        }

        int ty = y + 7;
        for (InfoRow row : lines) {
            if (row.hasLink()) {
                int prefixW = tr.width(row.prefix());
                ctx.text(tr, row.prefix(), x + 7, ty, 0xFFFFFFFF, true);
                int nameX = x + 7 + prefixW;
                int nameW = tr.width(row.name());
                boolean hovered = mx >= nameX && mx <= nameX + nameW && my >= ty - 1 && my <= ty + 9;
                ctx.text(tr, row.name(), nameX, ty, hovered ? LINK_HOVER_COLOR : LINK_COLOR, true);
                infoLinks.add(new InfoLink(nameX, ty - 1, nameW, 10, row.linkType(), row.name()));
            } else {
                ctx.text(tr, trimToWidth(tr, row.prefix(), boxW - 14), x + 7, ty, 0xFFFFFFFF, true);
            }
            ty += 12;
        }
        if (showDiscordButton || showExpand) {
            int btnY = y + boxH - ROW_HEIGHT - 7;
            int avail = boxW - 14;
            int gap = 5;
            int btnW = (showDiscordButton && showExpand)
                    ? Math.min(82, (avail - gap) / 2) : Math.min(82, avail);
            int bx = x + 7;
            if (showDiscordButton) {
                infoDiscordX = bx;
                infoDiscordY = btnY;
                infoDiscordW = btnW;
                infoDiscordH = ROW_HEIGHT;
                infoDiscordVisible = true;
                infoDiscordUrl = discordUrl;
                drawPanelButton(ctx, infoDiscordX, infoDiscordY, infoDiscordW, infoDiscordH, "Discord");
                bx += btnW + gap;
            }
            if (showExpand) {
                infoExpandX = bx;
                infoExpandY = btnY;
                infoExpandW = btnW;
                infoExpandH = ROW_HEIGHT;
                infoExpandVisible = true;
                drawPanelButton(ctx, infoExpandX, infoExpandY, infoExpandW, infoExpandH, "Expand");
            }
        }
        // Hover labels last, so nothing in the panel can paint over them.
        if (pendingTipText != null) {
            int lw = tr.width(pendingTipText);
            int lx = pendingTipX - lw;
            int ly = pendingTipY;
            ctx.fill(lx - 3, ly - 3, lx + lw + 3, ly + 10, 0xF0101114);
            ctx.text(tr, pendingTipText, lx, ly, 0xFFFFFFFF, false);
        }
        if (scaled) UiScale.pop(ctx);
    }

    private static void drawPanelButton(GuiGraphicsExtractor ctx, int bx, int by, int bw, int bh, String label) {
        if (DarkButtons.enabled()) {
            DarkButtons.draw(ctx, bx, by, bw, bh, label, true, 0xFFFFFFFF, scaledMouseX(), scaledMouseY());
        } else {
            Button button = Button.builder(coloredText(label, 0xFFFFFF), ignored -> {})
                    .bounds(bx, by, bw, bh).build();
            button.extractRenderState(ctx, scaledMouseX(), scaledMouseY(), 0.0F);
        }
    }

    private static String selectedDiscordUrl(Map<String, TownPopupData> townDetails,
                                             Map<String, EarthMcNationData> nationDetails) {
        String key = selectedName.toLowerCase(Locale.ROOT);
        if ("town".equals(selectedType)) {
            TownPopupData details = townDetails.get(key);
            return details == null ? "" : normalizeDiscordUrl(details.discord());
        }
        if ("nation".equals(selectedType)) {
            EarthMcNationData details = nationDetails.get(key);
            return details == null ? "" : normalizeDiscordUrl(details.discord());
        }
        return "";
    }

    private static List<InfoRow> selectedInfo(List<TownData> towns, List<PlayerMarker> players,
                                              Map<String, TownPopupData> townDetails,
                                              Map<String, EarthMcPlayerData> playerDetails,
                                              Map<String, PlayerHistoryEntry> playerHistory,
                                              Map<String, EarthMcNationData> nationDetails) {
        if ("town".equals(selectedType)) {
            for (TownData town : towns) {
                if (town.name().equalsIgnoreCase(selectedName)) {
                    TownPopupData details = townDetails.get(selectedName.toLowerCase(Locale.ROOT));
                    return townInfo(town, details, nationDetails);
                }
            }
            return List.of();
        }
        if ("nation".equals(selectedType)) {
            EarthMcNationData details = nationDetails.get(selectedName.toLowerCase(Locale.ROOT));
            ArrayList<InfoRow> lines = new ArrayList<>();
            lines.add(InfoRow.text("§f§lNation: " + selectedName));
            if (details == null) {
                lines.add(InfoRow.text("§7Details: §fChecking..."));
                return List.copyOf(lines);
            }
            boolean archive = TownyMapMod.isArchiveMode();
            if (!details.capitalName().isBlank()) lines.add(InfoRow.link("§7Capital: §f", details.capitalName(), "town"));
            if (!archive) {   // alliance/meganation membership is only known live, not for the archived date
                // One row per bloc, each a link straight into that bloc's own panel.
                for (String mega : TownyMapMod.meganationsForNation(selectedName)) {
                    lines.add(InfoRow.link("§7Meganation: §f", mega, "alliance"));
                }
                for (String alli : TownyMapMod.alliancesForNation(selectedName)) {
                    lines.add(InfoRow.link("§7Alliance: §f", alli, "alliance"));
                }
            }
            if (!details.kingName().isBlank()) lines.add(InfoRow.link("§7King: §f", details.kingName(), "player"));
            if (!details.founded().isBlank()) lines.add(InfoRow.text("§7Founded: §f" + details.founded()));
            if (details.townCount() > 0) lines.add(InfoRow.text("§7Towns: §f" + details.townCount()));
            if (details.residentCount() > 0) {
                String inactive = details.activeResidentCount() >= 0
                        && details.activeResidentCount() < details.residentCount()
                        ? " §8(" + (details.residentCount() - details.activeResidentCount()) + " Inactive)" : "";
                lines.add(InfoRow.text("§7Residents: §f" + details.residentCount() + inactive));
                // Nation bonus on its own row, with a projection of the next level drop when known.
                // EarthMC computes the bonus on ACTIVE residents (inactive members are still counted in
                // residentCount but don't earn bonus), so use its authoritative stats.nationBonus — the
                // local tier-of-total-residents is only a fallback when the API didn't send it. Omitted in
                // archive mode: it's a formula on the total, not a value the snapshot actually recorded.
                if (!archive) {
                    int bonusValue = details.nationBonus() >= 0
                            ? details.nationBonus() : nationBonus(details.residentCount());
                    String bonusLine = "§7Bonus: §f" + bonusValue;
                    NationBonusProjection proj = TownyMapMod.nationBonusProjection(selectedName);
                    if (proj != null && proj.daysUntilDrop() >= 0) {
                        // Cascade the countdown: days → hours (<24h) → minutes (<1h). Sub-day units come from
                        // the absolute instant, so they're already offset-correct vs the ~noon-Berlin purge.
                        String when;
                        if (proj.minutesUntilDrop() < 60) when = proj.minutesUntilDrop() + "m";
                        else if (proj.minutesUntilDrop() < 1440) when = proj.hoursUntilDrop() + "h";
                        else when = proj.daysUntilDrop() + "d";
                        bonusLine += " §8(→" + proj.nextBonus() + " in " + when + ", " + proj.dropDate() + ")";
                    }
                    lines.add(InfoRow.text(bonusLine));
                }
            }
            if (details.chunkCount() > 0) lines.add(InfoRow.text("§7Chunks: §f" + details.chunkCount()));
            if (!archive) lines.add(InfoRow.text("§7Gold: §f" + formatGold(details.balance())));
            if (details.outlawCount() > 0) lines.add(InfoRow.text("§7Outlaws: §f" + details.outlawCount()));
            if (details.enemyCount() > 0) lines.add(InfoRow.text("§7Enemies: §f" + details.enemyCount()));
            return List.copyOf(lines);
        }
        if (!"player".equals(selectedType)) return List.of();

        // Archive mode: a player is only what the snapshot reveals — their town, its nation, and their rank.
        // Nothing live (status, last seen, gold, last online…) is shown.
        if (TownyMapMod.isArchiveMode()) {
            ArrayList<InfoRow> lines = new ArrayList<>();
            lines.add(InfoRow.text("§f§lPlayer: " + selectedName));
            TownyMapMod.ArchivePlayerInfo info = TownyMapMod.archivePlayerInfo(selectedName);
            if (info == null) {
                lines.add(InfoRow.text("§7Not in this snapshot"));
            } else {
                lines.add(InfoRow.link("§7Town: §f", info.town(), "town"));
                if (!info.nation().isBlank()) lines.add(InfoRow.link("§7Nation: §f", info.nation(), "nation"));
                lines.add(InfoRow.text("§7Rank: §f" + info.role()));
            }
            return List.copyOf(lines);
        }

        PlayerMarker marker = visibleMarker(selectedName, players);
        EarthMcPlayerData details = playerDetails.get(selectedName.toLowerCase(Locale.ROOT));
        PlayerHistoryEntry history = playerHistory.get(selectedName.toLowerCase(Locale.ROOT));
        String status = playerStatus(details, marker);

        ArrayList<InfoRow> lines = new ArrayList<>();
        lines.add(InfoRow.text("§f§lPlayer: " + selectedName));
        lines.add(InfoRow.text("§7Status: §f" + status));
        if (marker != null) lines.add(InfoRow.text("§7Location: §f" + marker.x() + ", " + marker.z()));
        else if (history != null) {
            lines.add(InfoRow.text("§7Last seen: §f" + history.x() + ", " + history.z()));
            lines.add(InfoRow.text("§7On map: §f" + dateWithAgo(history.lastSeenMs())));
        }
        if (details == null) {
            // The EarthMC API has an opt-out, and for those players the lookup never returns -- the card
            // used to sit on "Checking..." forever. Town rosters in markers.json are TOWN data with no
            // opt-out, so fall back to those: it gives the town, and the nation through it. Labelled as
            // map data, because someone who opted out deserves to see where this came from.
            String rosterTown = null;
            net.townymap.api.SquaremapApiClient api = TownyMapMod.getApiClient();
            if (api != null) rosterTown = api.townOfResident(selectedName);
            if (rosterTown != null) {
                lines.add(InfoRow.link("§7Town: §f", rosterTown, "town"));
                String rosterNation = api.getTownNation(rosterTown.toLowerCase(Locale.ROOT));
                if (rosterNation != null && !rosterNation.isBlank()) {
                    lines.add(InfoRow.link("§7Nation: §f", rosterNation, "nation"));
                }
                lines.add(InfoRow.text("§8From map data (not on the API)"));
            } else {
                // Still say something terminal. An endless "Checking..." reads as broken.
                lines.add(InfoRow.text("§7Details: §fNot available"));
            }
            return List.copyOf(lines);
        }
        if (!details.townName().isBlank()) lines.add(InfoRow.link("§7Town: §f", details.townName(), "town"));
        if (!details.nationName().isBlank()) lines.add(InfoRow.link("§7Nation: §f", details.nationName(), "nation"));
        if (details.king()) {
            lines.add(InfoRow.text("§7Role: §fKing"));
        } else if (details.mayor()) {
            lines.add(InfoRow.text("§7Role: §fMayor"));
        }
        lines.add(InfoRow.text("§7Gold: §f" + formatGold(details.balance())));
        if (!details.registered().isBlank()) lines.add(InfoRow.text("§7Registered: §f" + details.registered()));
        // Always say something about presence. This used to be skipped entirely for a player visible on the
        // map, so the one case where the answer is simply "right now" showed nothing at all.
        if (marker != null || details.online()) {
            lines.add(InfoRow.text("§7Online: §anow"));
        } else if (details.lastOnlineMs() > 0) {
            lines.add(InfoRow.text("§7Last online: §f" + ageLabel(details.lastOnlineMs())
                    + " §7(" + details.lastOnline() + ")"));
        } else if (!details.lastOnline().isBlank()) {
            lines.add(InfoRow.text("§7Last online: §f" + details.lastOnline()));
        }
        return List.copyOf(lines);
    }

    private static boolean isNationCapital(String townName, String nationName,
                                           Map<String, EarthMcNationData> nationDetails) {
        if (nationName == null || nationName.isBlank() || nationDetails == null) return false;
        EarthMcNationData nation = nationDetails.get(nationName.toLowerCase(Locale.ROOT));
        return nation != null && nation.capitalName() != null
                && nation.capitalName().equalsIgnoreCase(townName);
    }

    private static List<InfoRow> townInfo(TownData town, TownPopupData details,
                                          Map<String, EarthMcNationData> nationDetails) {
        TownyMapMod.requestTownActive(town.name());   // active count on-demand for the opened town only
        ArrayList<InfoRow> lines = new ArrayList<>();
        lines.add(InfoRow.text("§f§lTown: " + town.name()));
        if (details == null) {
            lines.add(InfoRow.text("§7Chunks: §f" + town.approximateChunks()));
            lines.add(InfoRow.text("§7Details: §fChecking..."));
            return List.copyOf(lines);
        }
        if (!details.nationName().isBlank()) {
            boolean capital = isNationCapital(town.name(), details.nationName(), nationDetails);
            String label = capital ? "§7Capital of: §f" : "§7Nation: §f";
            lines.add(InfoRow.link(label, details.nationName(), "nation"));
        }
        if (!details.mayor().isBlank()) lines.add(InfoRow.link("§7Mayor: §f", details.mayor(), "player"));
        boolean overLimit = details.isOverClaimed()
                || (details.maxChunks() > 0 && details.numChunks() > details.maxChunks());
        String sizeColor = overLimit ? "§c" : "§f";
        String maxStr = details.maxChunks() > 0 ? " / " + details.maxChunks() : "";
        if (!TownyMapMod.viewingEarth()) {
            // maxTownBlocks is a town-wide allowance shared with the Earth claim, so pairing it with
            // an outpost-only count would read as a limit that is nowhere near being reached.
            lines.add(InfoRow.text("§7Outpost: §f" + town.approximateChunks() + " chunks"));
            lines.add(InfoRow.text("§8Town total: " + details.numChunks() + maxStr));
        } else {
            lines.add(InfoRow.text("§7Chunks: " + sizeColor + details.numChunks() + maxStr));
        }
        if (!details.founded().isBlank()) lines.add(InfoRow.text("§7Founded: §f" + details.founded()));
        String townInactive = details.activeResidentCount() >= 0
                && details.activeResidentCount() < details.residentCount()
                ? " §8(" + (details.residentCount() - details.activeResidentCount()) + " Inactive)" : "";
        lines.add(InfoRow.text("§7Residents: §f" + details.residentCount() + townInactive));
        lines.add(InfoRow.text("§7Gold: §f" + formatGold(details.balance())));
        lines.add(InfoRow.text("§7Open: §f" + yesNo(details.isOpen())));
        lines.add(InfoRow.text("§7Public: §f" + yesNo(details.isPublic())));
        return List.copyOf(lines);
    }

    /** "MMM d, yyyy (5h ago)" — absolute date plus relative age for a timestamp. */
    private static String dateWithAgo(long timestampMs) {
        if (timestampMs <= 0) return "Unknown";
        String date = Instant.ofEpochMilli(timestampMs).atZone(ZoneOffset.UTC).toLocalDate().format(DATE_FMT);
        return date + " §7(" + ageLabel(timestampMs) + ")";
    }

    private static int rowWidth(Font tr, InfoRow row) {
        int w = tr.width(row.prefix());
        if (row.hasLink()) w += tr.width(row.name());
        return w;
    }

    private static String yesNo(boolean value) {
        return value ? "Yes" : "No";
    }

    private static String formatGold(double gold) {
        return Long.toString(Math.round(gold));
    }

    private static String normalizeDiscordUrl(String discord) {
        return DiscordUrl.normalize(discord);
    }

    private static int nationBonus(int residents) {
        if (residents >= 200) return 100;
        if (residents >= 120) return 80;
        if (residents >= 80) return 60;
        if (residents >= 60) return 50;
        if (residents >= 40) return 30;
        if (residents >= 20) return 10;
        return 0;
    }

    private static int possibleTownChunks(TownPopupData town, Map<String, EarthMcNationData> nationDetails) {
        int residentChunks = Math.max(0, town.residentCount()) * 12;
        if (town.nationName().isBlank()) return residentChunks;
        EarthMcNationData nation = nationDetails.get(town.nationName().toLowerCase(Locale.ROOT));
        return nation == null ? residentChunks : residentChunks + nationBonus(nation.residentCount());
    }

    private static String ageLabel(long timestampMs) {
        long seconds = Math.max(0, (System.currentTimeMillis() - timestampMs) / 1000);
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 48) return hours + "h ago";
        return (hours / 24) + "d ago";
    }

    private static void renderFavorites(GuiGraphicsExtractor ctx, Font tr, int x, int y, int sw,
                                        List<TownData> towns, List<String> favoriteTowns) {
        String label = "Favorites";
        if (DarkButtons.enabled()) {
            DarkButtons.draw(ctx, x, y, FAVORITES_WIDTH, ROW_HEIGHT, label, true, 0xFFFFFFFF,
                    scaledMouseX(), scaledMouseY());
        } else {
            Button button = Button.builder(coloredText(label, 0xFFFFFF), ignored -> {})
                    .bounds(x, y, FAVORITES_WIDTH, ROW_HEIGHT)
                    .build();
            button.extractRenderState(ctx, scaledMouseX(), scaledMouseY(), 0.0F);
        }
        if (!favoritesOpen) return;

        List<FavEntry> favorites = favoriteEntries(towns, favoriteTowns);
        int rows = Math.min(MAX_RESULTS, favorites.size());
        for (int i = 0; i < rows; i++) {
            FavEntry fav = favorites.get(i);
            int ty = resultRowY(y, i);
            ctx.fill(x - 1, ty - 1, x + FAVORITES_WIDTH + 1, ty + ROW_HEIGHT + 1, BORDER);
            ctx.fill(x, ty, x + FAVORITES_WIDTH, ty + ROW_HEIGHT, BG);
            // A one-letter tag keeps the narrow row readable when the three kinds share a name.
            int tag = switch (fav.type()) {
                case "nation" -> 0xFF7FB2FF;
                case "player" -> 0xFFFFD24A;
                default -> 0xFF9AA0A8;
            };
            String letter = fav.type().substring(0, 1).toUpperCase(Locale.ROOT);
            ctx.text(tr, letter, x + 5, ty + 5, tag, true);
            ctx.text(tr, trimToWidth(tr, fav.name(), FAVORITES_WIDTH - 22), x + 14, ty + 5, 0xFFFFFFFF, true);
        }
        if (favorites.isEmpty()) {
            int rowY = resultRowY(y, 0);
            ctx.fill(x - 1, rowY - 1, x + FAVORITES_WIDTH + 1, rowY + ROW_HEIGHT + 1, BORDER);
            ctx.fill(x, rowY, x + FAVORITES_WIDTH, rowY + ROW_HEIGHT, BG);
            ctx.text(tr, trimToWidth(tr, "No favorites", FAVORITES_WIDTH - 14), x + 7, rowY + 5, 0xFFAAAAAA, true);
        }
    }

    private static ClickResult favoriteClick(double mouseX, double mouseY, int x, int y,
                                             List<TownData> towns, List<String> favoriteTowns) {
        if (inside(mouseX, mouseY, x, y, FAVORITES_WIDTH, ROW_HEIGHT)) {
            favoritesOpen = !favoritesOpen;
            focused = false;
            return ClickResult.consumedResult();
        }

        if (favoritesOpen) {
            List<FavEntry> favorites = favoriteEntries(towns, favoriteTowns);
            int rows = Math.min(MAX_RESULTS, favorites.size());
            for (int i = 0; i < rows; i++) {
                int ty = resultRowY(y, i);
                if (inside(mouseX, mouseY, x, ty, FAVORITES_WIDTH, ROW_HEIGHT)) {
                    FavEntry fav = favorites.get(i);
                    favoritesOpen = false;
                    focused = false;
                    if (fav.town() != null) return ClickResult.jump(fav.town());
                    // Nations go to their capital, players to where they are — a favourite should take you
                    // somewhere, the same way a favourite town does.
                    MapJumpTarget target = favoriteTarget(fav, towns);
                    if (target != null) {
                        openSearch(fav.type(), fav.name());   // also show the panel for what we jumped to
                        return ClickResult.jump(target);
                    }
                    openSearch(fav.type(), fav.name());
                    return ClickResult.consumedResult();
                }
            }
        }
        return ClickResult.none();
    }

    /** One row of the favourites dropdown. Towns jump straight to their claim; the other kinds open a panel. */
    private record FavEntry(String type, String name, TownData town) {}

    /**
     * Where a favourited nation or player lives on the map: a nation's capital, a player's current position.
     * Falls back to their last-seen spot, so a player who has just gone offline (or hidden) still leads
     * somewhere rather than doing nothing.
     */
    private static MapJumpTarget favoriteTarget(FavEntry fav, List<TownData> towns) {
        if ("nation".equals(fav.type())) {
            EarthMcNationData nd = TownyMapMod.nationDetails(fav.name());
            if (nd != null && !nd.capitalName().isBlank()) {
                TownData capital = townByName(towns, nd.capitalName());
                if (capital != null) {
                    return new MapJumpTarget(fav.name(), capital.centerX(), capital.centerZ());
                }
            }
            if (nd != null && nd.hasSpawn() && TownyMapMod.viewingEarth()) {
                return new MapJumpTarget(fav.name(), nd.spawnX(), nd.spawnZ());
            }
            return null;
        }
        if ("player".equals(fav.type())) {
            return TownyMapMod.playerJumpTarget(fav.name());
        }
        return null;
    }

    /** Towns, then nations, then players — each alphabetical, so the list is stable as it grows. */
    private static List<FavEntry> favoriteEntries(List<TownData> towns, List<String> favoriteNames) {
        List<FavEntry> out = new ArrayList<>();
        for (TownData t : favoriteTowns(towns, favoriteNames)) out.add(new FavEntry("town", t.name(), t));
        List<String> nations = new ArrayList<>(TownyMapMod.favoriteNations());
        nations.sort(String.CASE_INSENSITIVE_ORDER);
        for (String n : nations) out.add(new FavEntry("nation", n, null));
        List<String> players = new ArrayList<>(TownyMapMod.favoritePlayers());
        players.sort(String.CASE_INSENSITIVE_ORDER);
        for (String pl : players) out.add(new FavEntry("player", pl, null));
        return out;
    }

    private static List<TownData> favoriteTowns(List<TownData> towns, List<String> favoriteNames) {
        ArrayList<TownData> favorites = new ArrayList<>();
        for (String name : favoriteNames) {
            TownData town = townByName(towns, name);
            if (town != null) favorites.add(town);
        }
        favorites.sort(Comparator.comparing(TownData::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(favorites);
    }

    private static TownData townByName(List<TownData> towns, String name) {
        for (TownData town : towns) {
            if (town.name().equalsIgnoreCase(name)) return town;
        }
        return null;
    }

    private static String trimToWidth(Font tr, String text, int width) {
        if (tr.width(text) <= width) return text;
        String ellipsis = "...";
        int max = Math.max(1, width - tr.width(ellipsis));
        return tr.plainSubstrByWidth(text, max) + ellipsis;
    }

    private static Component coloredText(String label, int textColor) {
        return Component.literal(label).setStyle(Style.EMPTY.withColor(textColor & 0xFFFFFF));
    }

    private static int scaledMouseX() {
        Minecraft mc = Minecraft.getInstance();
        return (int) (mc.mouseHandler.getScaledXPos(mc.getWindow()));
    }

    private static int scaledMouseY() {
        Minecraft mc = Minecraft.getInstance();
        return (int) (mc.mouseHandler.getScaledYPos(mc.getWindow()));
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
    }

    private static int expandX1, expandY1, expandX2, expandY2;

    /** True if the click landed on the expand button; the caller opens the info panel. */
    public static boolean isExpandClick(double mx, double my) {
        return expandX2 > expandX1 && mx >= expandX1 && mx <= expandX2 && my >= expandY1 && my <= expandY2;
    }

    private static int left(int sw) {
        return Math.max(8, sw / 2 - panelWidth / 2);
    }

    /** Widens the bar to fit whichever is longest: the typed query or a result row. Capped, and grows only. */
    private static int computePanelWidth(Font tr, List<Result> results, int sw) {
        int needed = tr.width(query) + 20;
        for (Result r : results) needed = Math.max(needed, tr.width(r.label()) + 20);
        // An empty focused bar shows the hint rows, which are wider than the default bar.
        if (focused && query.isEmpty()) {
            for (String h : HINTS) needed = Math.max(needed, tr.width(h) + 20);
        }
        return Math.max(WIDTH, Math.min(Math.min(MAX_WIDTH, Math.max(WIDTH, sw - 24)), needed));
    }

    private static int top() {
        Minecraft mc = Minecraft.getInstance();
        int sh = mc == null ? 360 : mc.getWindow().getGuiScaledHeight();
        return Math.max(36, sh - ROW_HEIGHT - 18);
    }

    private static int favoritesX(int searchX) {
        return Math.max(8, searchX - FAVORITES_WIDTH - 7);
    }

    private static int resultRowY(int searchY, int index) {
        return searchY - 3 - (index + 1) * ROW_HEIGHT;
    }

    private record Result(String label, MapJumpTarget target, int score, String type, String name) {}

    // ── Property filters ─────────────────────────────────────────────────────

    /**
     * A property search over towns, e.g. {@code nationless}, {@code residents>20 nation:Germany}.
     *
     * <p>Every field here comes from the squaremap markers the map already downloads — the resident count is
     * parsed out of the town popup — so a filter never triggers an API call, however many towns it scans.
     */
    private record Filters(boolean nationless, List<String> nations,
                           List<int[]> residents, List<int[]> chunks, String text) {

        // "residents>30", and "residents>30,<60" for a range — each comma-separated clause must hold.
        // "residents>30" or the short "r>30" / "r:>30"; chunks keeps its full name.
        private static final Pattern NUMERIC =
                Pattern.compile("^(residents|r|chunks):?((?:(?:>=|<=|>|<|=)\\d+)(?:,(?:>=|<=|>|<|=)?\\d+)*)$",
                        Pattern.CASE_INSENSITIVE);
        private static final Pattern CLAUSE = Pattern.compile("(>=|<=|>|<|=)?(\\d+)");

        static final int OP_GT = 0, OP_LT = 1, OP_GE = 2, OP_LE = 3, OP_EQ = 4;

        /** Parses a query, or returns null if it holds no filter terms (so the normal name search runs). */
        static Filters parse(String raw) {
            if (raw == null || raw.isBlank()) return null;
            boolean nationless = false;
            List<String> nations = new ArrayList<>();
            List<int[]> residents = new ArrayList<>();
            List<int[]> chunks = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            boolean any = false;

            for (String tok : raw.trim().split("\\s+")) {
                String low = tok.toLowerCase(Locale.ROOT);
                if (low.equals("nationless")) { nationless = true; any = true; continue; }
                if (low.startsWith("nation:") || low.startsWith("nations:") || low.startsWith("n:")) {
                    String list = tok.substring(tok.indexOf(':') + 1);
                    for (String n : list.split(",")) {          // nation:germany,france,egypt
                        String t = n.trim();
                        if (!t.isEmpty()) nations.add(t.toLowerCase(Locale.ROOT));
                    }
                    if (!nations.isEmpty()) any = true;
                    continue;
                }
                Matcher m = NUMERIC.matcher(tok);
                if (m.matches()) {
                    String field = m.group(1).toLowerCase(Locale.ROOT);
                    List<int[]> target = field.equals("chunks") ? chunks : residents;   // "r" = residents
                    Matcher c = CLAUSE.matcher(m.group(2));
                    while (c.find()) {
                        int op = opOf(c.group(1));
                        try { target.add(new int[]{op, Integer.parseInt(c.group(2))}); }
                        catch (NumberFormatException ignored) { /* skip a clause we can't read */ }
                    }
                    any = true;
                    continue;
                }
                if (!text.isEmpty()) text.append(' ');
                text.append(low);
            }
            return any ? new Filters(nationless, List.copyOf(nations), List.copyOf(residents),
                    List.copyOf(chunks), text.toString()) : null;
        }

        private static int opOf(String op) {
            if (op == null) return OP_EQ;
            return switch (op) {
                case ">"  -> OP_GT;
                case "<"  -> OP_LT;
                case ">=" -> OP_GE;
                case "<=" -> OP_LE;
                default   -> OP_EQ;
            };
        }

        /** Every clause must hold, so ">30,<60" reads as a range rather than as alternatives. */
        static boolean passes(int actual, List<int[]> clauses) {
            for (int[] c : clauses) {
                boolean ok = switch (c[0]) {
                    case OP_GT -> actual > c[1];
                    case OP_LT -> actual < c[1];
                    case OP_GE -> actual >= c[1];
                    case OP_LE -> actual <= c[1];
                    default    -> actual == c[1];
                };
                if (!ok) return false;
            }
            return true;
        }
    }

    /** Runs a property filter over every town, newest-largest first, and labels each hit with why it matched. */
    // Towns matching the active property filter, published so the map can black out everything else.
    private static volatile java.util.Set<String> filterMatchKeys = java.util.Set.of();
    private static volatile int filterVersion = 0;
    private static volatile boolean filterActive = false;

    /** True while the search bar holds a property filter (so the map should dim non-matching towns). */
    public static boolean isFilterActive() { return filterActive; }

    /** Lower-case keys of the towns matching the active filter. */
    public static java.util.Set<String> filterMatches() { return filterMatchKeys; }

    /** Bumps whenever the filter or its matches change, so the map's recolour memo rebuilds. */
    public static int filterVersion() { return filterVersion; }

    /** Closes the dropdown but keeps the query and the map dimming, so a filter survives panning. */
    public static void unfocusKeepingFilter() {
        focused = false;
        selAnchor = -1;
        textDragging = false;
        clearSelection();
    }

    /** Drops the filter highlight — called whenever the bar is cleared or dismissed. */
    public static void clearFilterHighlight() {
        publishFilterMatches(false, java.util.Set.of());
    }

    private static void publishFilterMatches(boolean active, java.util.Set<String> keys) {
        if (active == filterActive && keys.equals(filterMatchKeys)) return;
        filterActive = active;
        filterMatchKeys = keys;
        filterVersion++;
    }

    /** Runs a property filter over every town, biggest first, labelling each hit with why it matched. */
    private static List<Result> filterTowns(List<TownData> towns, Filters f) {
        record Hit(Result result, int sortKey) {}
        List<Hit> hits = new ArrayList<>();
        java.util.Set<String> matched = new java.util.HashSet<>();

        for (TownData town : towns) {
            String key = town.key();
            String nation = TownyMapMod.townNationOf(key);
            if (f.nationless() && nation != null) continue;
            if (!f.nations().isEmpty()) {
                if (nation == null) continue;
                String low = nation.toLowerCase(Locale.ROOT);
                boolean hit = false;
                for (String want : f.nations()) if (low.contains(want)) { hit = true; break; }
                if (!hit) continue;                       // nation:a,b,c matches any of them
            }
            if (!f.text().isEmpty() && !town.name().toLowerCase(Locale.ROOT).contains(f.text())) continue;

            int residents = TownyMapMod.townResidentsOf(key);
            if (!f.residents().isEmpty()
                    && (residents < 0 || !Filters.passes(residents, f.residents()))) continue;

            int chunks = town.approximateChunks();
            if (!f.chunks().isEmpty() && !Filters.passes(chunks, f.chunks())) continue;

            StringBuilder detail = new StringBuilder();
            if (residents >= 0) {
                detail.append(residents).append(residents == 1 ? " resident" : " residents");
            }
            if (!f.chunks().isEmpty()) {
                if (!detail.isEmpty()) detail.append(", ");
                detail.append(chunks).append(" chunks");
            }
            if (nation == null) {
                if (!detail.isEmpty()) detail.append(", ");
                detail.append("nationless");
            }

            String label = "Town: " + town.name() + (detail.isEmpty() ? "" : "  (" + detail + ")");
            matched.add(key);
            hits.add(new Hit(new Result(label,
                    new MapJumpTarget(town.name(), town.centerX(), town.centerZ()),
                    0, "town", town.name()),
                    !f.chunks().isEmpty() ? chunks : Math.max(residents, 0)));
        }

        // The map dims everything that didn't match — all of them, not just the ones that fit the list.
        publishFilterMatches(true, java.util.Set.copyOf(matched));

        // Biggest first — when you filter by a number, the extremes are what you were looking for.
        hits.sort(Comparator.comparingInt(Hit::sortKey).reversed()
                .thenComparing(h -> h.result().name(), String.CASE_INSENSITIVE_ORDER));
        List<Result> out = new ArrayList<>(Math.min(MAX_RESULTS, hits.size()));
        for (Hit h : hits) {
            if (out.size() >= MAX_RESULTS) break;
            out.add(h.result());
        }
        return out;
    }

    /** A clickable name span in the info panel: bounds + what to search for. */
    private record InfoLink(int x, int y, int w, int h, String type, String name) {
        boolean contains(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    /**
     * One line of the selected-info panel.  {@code prefix} is the formatted label
     * text; if {@code linkType} is non-empty, {@code name} is drawn after it as a
     * clickable span that re-searches for that town/nation/player.
     */
    private record InfoRow(String prefix, String name, String linkType) {
        static InfoRow text(String formatted) { return new InfoRow(formatted, "", ""); }
        static InfoRow link(String prefix, String name, String linkType) {
            return new InfoRow(prefix, name, linkType);
        }
        boolean hasLink() { return !linkType.isEmpty() && !name.isEmpty(); }
    }

    public record ClickResult(boolean consumed, MapJumpTarget target) {
        public static ClickResult none() {
            return new ClickResult(false, null);
        }

        public static ClickResult consumedResult() {
            return new ClickResult(true, null);
        }

        public static ClickResult jump(TownData town) {
            return new ClickResult(true, new MapJumpTarget(town.name(), town.centerX(), town.centerZ()));
        }

        public static ClickResult jump(MapJumpTarget target) {
            return new ClickResult(true, target);
        }
    }
}
