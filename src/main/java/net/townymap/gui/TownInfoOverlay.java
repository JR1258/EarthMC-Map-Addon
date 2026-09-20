package net.townymap.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.townymap.TownyMapMod;
import net.townymap.model.EarthMcNationData;
import net.townymap.model.TownPopupData;
import net.townymap.util.DiscordUrl;
import org.joml.Matrix3x2fStack;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Statically-held popup shown when the player right-clicks a town on the WorldMap.
 *
 * Uses plain String + Minecraft formatting codes (§) for text rather than Text objects,
 * because the String path of GuiGraphicsExtractor.text() is guaranteed to work in the
 * same GL state as border/player-dot rendering (renderPreDropdown HEAD).
 */
public final class TownInfoOverlay {

    private static final int PADDING     = 16;
    private static final int LINE_HEIGHT = 15;
    private static final int BUTTON_HEIGHT = 20;
    private static final int STAR_HITBOX = 18;
    private static final long DISPLAY_MS = 12_000;
    private static final int BOARD_MAX_WIDTH = 200;   // px (unscaled): wrap the board so it can't blow out the box
    private static final int BOARD_MAX_LINES = 4;     // and cap it so a rambling board can't make a giant popup

    private static final int BG_COLOR     = 0xD8101010;
    private static final int BORDER_COLOR = 0xFF333333;
    private static final int LINK_COLOR       = 0xFF8FB7FF;
    private static final int LINK_HOVER_COLOR = 0xFFFFE066;

    // Clickable name spans (Nation in the title → nation search, Mayor → player
    // search).  Rebuilt every render; consumed by handleClick().
    private static final List<InfoLink> infoLinks = new ArrayList<>();

    private static TownPopupData currentData;
    private static int screenX, screenY;
    private static long showUntil;
    private static boolean loading;
    private static int favoriteX1, favoriteY1, favoriteX2, favoriteY2;
    private static int discordX1, discordY1, discordX2, discordY2;
    private static int routeX1, routeY1, routeX2, routeY2;
    private static boolean hasButtons;
    // The popup is drawn in its own 0..boxW/0..boxH space via a scaled matrix; these publish that transform
    // so click/hit-tests (which get screen-space mouse coords) can map back into the popup's local space.
    private static float renderScale = 1f;
    private static int originX, originY;

    private TownInfoOverlay() {}

    public static void showLoading(int sx, int sy) {
        loading     = true;
        currentData = null;
        screenX     = sx;
        screenY     = sy;
        showUntil   = System.currentTimeMillis() + DISPLAY_MS;
        // Only one right-side info panel at a time: hide the search result panel.
        TownSearchOverlay.dismissSelection();
    }

    public static void show(TownPopupData data, int sx, int sy) {
        loading     = false;
        currentData = data;
        screenX     = sx;
        screenY     = sy;
        showUntil   = System.currentTimeMillis() + DISPLAY_MS;
        TownSearchOverlay.dismissSelection();
    }

    public static void dismiss() {
        currentData = null;
        loading     = false;
        infoLinks.clear();
    }

    public static TownPopupData currentData() {
        return loading ? null : currentData;
    }

    /** Folds an on-demand active-resident count into the open panel's town (looked up off the base
     *  fetch). No-op if the panel has since moved to a different town. */
    public static void setActiveResidentCount(String townName, int count) {
        TownPopupData d = currentData;
        if (d != null && d != TownPopupData.WILDERNESS && townName != null
                && townName.equalsIgnoreCase(d.townName())) {
            currentData = d.withActiveResidentCount(count);
        }
    }

    public static void render(GuiGraphicsExtractor ctx, int sw, int sh, boolean favorite,
                              Map<String, EarthMcNationData> nationDetails) {
        infoLinks.clear();
        if (!loading && currentData == null) return;
        if (System.currentTimeMillis() > showUntil) {
            dismiss();
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Font tr = mc.font;

        List<InfoRow> lines = buildLines(tr, nationDetails);
        if (lines.isEmpty()) return;

        // Measure rendered text width and add more breathing room for long names.
        int maxW = 0;
        int longestVisibleChars = 0;
        for (InfoRow row : lines) {
            int w = rowWidth(tr, row);
            if (w > maxW) maxW = w;
            int chars = stripFormatting(row.prefix() + row.name() + row.suffix()).length();
            if (chars > longestVisibleChars) longestVisibleChars = chars;
        }
        int horizontalPadding = PADDING + extraHorizontalPadding(longestVisibleChars);
        boolean showButtons = !loading && currentData != null && currentData != TownPopupData.WILDERNESS;
        int starReserve = showButtons ? STAR_HITBOX + 6 : 0;
        int boxW = maxW + horizontalPadding * 2 + starReserve;
        if (showButtons) {
            boxW = Math.max(boxW, PADDING * 2 + 52 * 2 + 6);
        }
        int buttonRowHeight = showButtons ? BUTTON_HEIGHT + 6 : 0;
        int boxH = lines.size() * LINE_HEIGHT + PADDING * 2 - 1 + buttonRowHeight;

        // Shrink the whole popup on small windows (and always enough to fit), then place it right-aligned and
        // vertically centred using the SCALED footprint.
        float scale = uiScale(sw, sh, boxW, boxH);
        int scaledW = Math.round(boxW * scale);
        int scaledH = Math.round(boxH * scale);
        int bx = Math.max(8, sw - scaledW - 12);
        int hi = sh - scaledH - 8;
        int by = hi < 8 ? Math.max(0, (sh - scaledH) / 2) : Math.max(8, Math.min(hi, sh / 2 - scaledH / 2));
        renderScale = scale;
        originX = bx;
        originY = by;

        // Everything below is drawn in the popup's own 0..boxW/0..boxH space; the matrix translates+scales it
        // onto the screen. The mouse is mapped into that same space so hover/hit-tests line up under the scale.
        int mx = scale > 0 ? Math.round((scaledMouseX() - bx) / scale) : scaledMouseX();
        int my = scale > 0 ? Math.round((scaledMouseY() - by) / scale) : scaledMouseY();

        Matrix3x2fStack matrices = ctx.pose();
        matrices.pushMatrix();
        matrices.translate(bx, by);
        matrices.scale(scale, scale);
        try {
            // Background + border
            ctx.fill(-1, -1, boxW + 1, boxH + 1, BORDER_COLOR);
            ctx.fill(0,  0,  boxW,     boxH,     BG_COLOR);
            if (showButtons) {
                drawFavoriteStar(ctx, tr, 0, 0, boxW, favorite);
            }

            // Component — use the String overload (same path as player name labels). Link rows draw the label
            // prefix, then the clickable name (yellow on hover), then any suffix; the name's local bounds are
            // recorded for handleClick.
            int ty = PADDING;
            for (InfoRow row : lines) {
                int px = horizontalPadding;
                ctx.text(tr, row.prefix(), px, ty, 0xFFFFFFFF, true);
                if (row.hasLink()) {
                    int nameX = px + tr.width(row.prefix());
                    int nameW = tr.width(row.name());
                    boolean hover = mx >= nameX && mx <= nameX + nameW && my >= ty - 1 && my <= ty + 11;
                    ctx.text(tr, row.name(), nameX, ty, hover ? LINK_HOVER_COLOR : LINK_COLOR, true);
                    infoLinks.add(new InfoLink(nameX, ty - 1, nameW, 12, row.linkType(), row.name()));
                    if (!row.suffix().isEmpty()) {
                        ctx.text(tr, row.suffix(), nameX + nameW, ty, 0xFFFFFFFF, true);
                    }
                }
                ty += LINE_HEIGHT;
            }

            hasButtons = false;
            if (showButtons) {
                drawButtons(ctx, tr, 0, boxH - PADDING - BUTTON_HEIGHT + 2, boxW, mx, my);
            }
        } finally {
            matrices.popMatrix();
        }
    }

    /** Popup scale. The default (Info Panel Scale = 1.0) keeps the GUI-scale-aware auto sizing; a lower slider
     *  value shrinks the whole popup — text and gaps — on top of that. Always kept small enough to fit. */
    private static float uiScale(int sw, int sh, int boxW, int boxH) {
        double area = (double) sw * sh;
        double refArea = 550.0 * 300.0;   // above this, full size; below, shrink gently
        float scale = area >= refArea ? 1.0f : Math.max(0.7f, (float) Math.sqrt(area / refArea));
        scale *= Math.max(0.7f, Math.min(1.0f, TownyMapMod.infoPanelScale()));   // user "UI Scale" slider (70% floor)
        // Hard fit: never let the popup exceed the window even after the shrink (long content, tiny window).
        if (boxW * scale > sw - 16) scale = Math.min(scale, (sw - 16f) / boxW);
        if (boxH * scale > sh - 16) scale = Math.min(scale, (sh - 16f) / boxH);
        return Math.max(0.30f, Math.min(1.0f, scale));
    }

    private static int rowWidth(Font tr, InfoRow row) {
        int w = tr.width(row.prefix());
        if (row.hasLink()) w += tr.width(row.name()) + tr.width(row.suffix());
        return w;
    }

    public static ActionResult handleClick(double mouseX, double mouseY) {
        // Map the screen-space click into the popup's own (unscaled) coordinate space, where the hitboxes live.
        double lx = renderScale > 0 ? (mouseX - originX) / renderScale : mouseX;
        double ly = renderScale > 0 ? (mouseY - originY) / renderScale : mouseY;
        // Clickable names work whenever a real town is shown (even before buttons).
        if (currentData != null && currentData != TownPopupData.WILDERNESS) {
            for (InfoLink link : infoLinks) {
                if (link.contains(lx, ly)) {
                    return ActionResult.search(link.type(), link.name());
                }
            }
        }
        if (!hasButtons || currentData == null || currentData == TownPopupData.WILDERNESS) return ActionResult.none();
        String town = currentData.townName();
        if (inside(lx, ly, favoriteX1, favoriteY1, favoriteX2, favoriteY2)) return ActionResult.favorite(town);
        if (inside(lx, ly, discordX1, discordY1, discordX2, discordY2)) {
            return ActionResult.expand(town);
        }
        if (inside(lx, ly, routeX1, routeY1, routeX2, routeY2)) return ActionResult.route(town);
        return ActionResult.none();
    }

    private static List<InfoRow> buildLines(Font tr, Map<String, EarthMcNationData> nationDetails) {
        List<InfoRow> lines = new ArrayList<>();

        if (loading) {
            lines.add(InfoRow.text("§7§oLooking up town..."));
            return lines;
        }

        TownPopupData d = currentData;
        if (d == null) return lines;

        if (d == TownPopupData.WILDERNESS) {
            lines.add(InfoRow.text("§aWilderness"));
            return lines;
        }

        // Title — the nation name (in parens) is a clickable nation search.
        // Capital towns read "(Capital of <nation>)" instead of just "(<nation>)".
        if (d.nationName().isEmpty()) {
            lines.add(InfoRow.text("§f§l" + d.townName()));
        } else {
            String capPrefix = isNationCapital(d, nationDetails) ? "Capital of " : "";
            lines.add(InfoRow.link("§f§l" + d.townName() + " §7§l(" + capPrefix, d.nationName(), "§7§l)", "nation"));
        }

        // Board — wrapped to a sane width and capped, so a long board can't blow the popup out to the whole
        // screen (as an unwrapped one-liner did).
        if (hasBoard(d.board())) {
            for (String boardLine : wrapBoard(tr, "§7§o", d.board(), BOARD_MAX_WIDTH, BOARD_MAX_LINES)) {
                lines.add(InfoRow.text(boardLine));
            }
        }

        // Spacer
        lines.add(InfoRow.text(""));

        // In archive mode only show what the Wayback snapshot actually recorded; the rest (chunk size, open,
        // gold) isn't in the archive, so it's omitted rather than shown as a misleading zero.
        boolean archive = TownyMapMod.isArchiveMode();

        // Mayor name → clickable player search.
        lines.add(InfoRow.link("§7Mayor: §f§l", d.mayor(), "", "player"));
        // "claimed / max chunks" using EarthMC's own claim max (stats.maxTownBlocks), which already
        // accounts for residents + bonus blocks + nation bonus. Over-limit is highlighted in red.
        if (!TownyMapMod.viewingEarth()) {
            // numChunks is stats.numTownBlocks - the town's TOTAL across every world, so on the Moon it
            // reported the whole Earth town for what is really a small outpost. The polygon in the world
            // being shown IS the outpost, so count that instead and say which it is.
            net.townymap.model.TownData here = TownyMapMod.townPolygon(d.townName());
            int outpost = here != null ? here.approximateChunks() : -1;
            lines.add(InfoRow.text(outpost >= 0
                    ? "§7Outpost: §f§l" + outpost + " chunks"
                    : "§7Outpost: §f§lno claim here"));
            lines.add(InfoRow.text("§8Town total: " + d.numChunks()
                    + (d.maxChunks() > 0 ? " / " + d.maxChunks() : "") + " chunks"));
        } else if (archive) {
            // The archive has no claim limit, but the claimed count is derived from the snapshot's claim polygon.
            lines.add(InfoRow.text("§7Size: §f§l" + d.numChunks() + " chunks"));
        } else if (d.maxChunks() > 0) {
            boolean overLimit = d.isOverClaimed() || d.numChunks() > d.maxChunks();
            String sizeColor = overLimit ? "§c§l" : "§f§l";
            lines.add(InfoRow.text("§7Size: " + sizeColor + d.numChunks() + " / " + d.maxChunks() + " chunks"));
        } else {
            lines.add(InfoRow.text("§7Size: §f§l" + d.numChunks() + " chunks"));
        }
        if (!d.founded().isEmpty()) {
            lines.add(InfoRow.text("§7Founded: §f§l" + d.founded()));
        }
        if (archive) {
            lines.add(InfoRow.text("§7PVP: §f§l"   + (d.pvp() ? "Yes" : "No")));   // archive records PVP, not Open
        } else {
            lines.add(InfoRow.text("§7Open: §f§l"  + (d.isOpen() ? "Yes" : "No")));
        }
        lines.add(InfoRow.text("§7Public: §f§l"    + (d.isPublic() ? "Yes" : "No")));
        String residentsLine = "§7Residents: §f§l" + d.residentCount();
        if (d.activeResidentCount() >= 0 && d.activeResidentCount() < d.residentCount()) {
            residentsLine += " §8(" + (d.residentCount() - d.activeResidentCount()) + " Inactive)";
        }
        lines.add(InfoRow.text(residentsLine));
        if (!archive) {
            lines.add(InfoRow.text("§7Gold: §f§l"  + formatGold(d.balance())));
        }

        return lines;
    }

    /** True if the town is its nation's capital (per EarthMC nation data). */
    private static boolean isNationCapital(TownPopupData d, Map<String, EarthMcNationData> nationDetails) {
        if (d.nationName() == null || d.nationName().isBlank() || nationDetails == null) return false;
        EarthMcNationData nation = nationDetails.get(d.nationName().toLowerCase(Locale.ROOT));
        return nation != null && nation.capitalName() != null
                && nation.capitalName().equalsIgnoreCase(d.townName());
    }

    private static int possibleTownChunks(TownPopupData town, Map<String, EarthMcNationData> nationDetails) {
        int residentChunks = Math.max(0, town.residentCount()) * 12;
        if (town.nationName() == null || town.nationName().isBlank() || nationDetails == null) {
            return residentChunks;
        }
        EarthMcNationData nation = nationDetails.get(town.nationName().toLowerCase(Locale.ROOT));
        if (nation == null) return residentChunks;
        return residentChunks + nationBonus(nation.residentCount());
    }

    static int nationBonus(int residents) {
        if (residents >= 200) return 100;
        if (residents >= 120) return 80;
        if (residents >= 80) return 60;
        if (residents >= 60) return 50;
        if (residents >= 40) return 30;
        if (residents >= 20) return 10;
        return 0;
    }

    /** Word-wraps a board to {@code maxWidth} px over at most {@code maxLines}, ellipsising the overflow.
     *  Each returned line is prefixed with {@code prefix} (the grey-italic style). */
    private static List<String> wrapBoard(Font tr, String prefix, String board, int maxWidth, int maxLines) {
        String text = stripFormatting(board).replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        List<String> wrapped = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String[] words = text.split(" ");
        int consumed = 0;
        for (; consumed < words.length; consumed++) {
            String word = words[consumed];
            if (word.isEmpty()) continue;
            String trial = cur.length() == 0 ? word : cur + " " + word;
            if (cur.length() == 0 || tr.width(trial) <= maxWidth) {
                cur.setLength(0);
                cur.append(trial);
            } else {
                wrapped.add(cur.toString());
                cur.setLength(0);
                cur.append(word);
                if (wrapped.size() == maxLines) break;   // 'cur' now holds overflow beyond the cap
            }
        }
        boolean overflow;
        if (wrapped.size() < maxLines) {
            if (cur.length() > 0) wrapped.add(cur.toString());
            overflow = false;
        } else {
            overflow = cur.length() > 0 || consumed < words.length - 1;   // more text didn't fit
        }
        if (overflow && !wrapped.isEmpty()) {
            String last = wrapped.get(wrapped.size() - 1);
            while (!last.isEmpty() && tr.width(last + "…") > maxWidth) last = last.substring(0, last.length() - 1);
            wrapped.set(wrapped.size() - 1, last + "…");
        }
        List<String> out = new ArrayList<>(wrapped.size());
        for (String line : wrapped) out.add(prefix + line);
        return out;
    }

    private static boolean hasBoard(String board) {
        if (board == null || board.isBlank()) return false;
        String normalized = stripFormatting(board)
                .replaceAll("<[^>]*>", "")
                .trim();
        return !normalized.isEmpty();
    }

    private static String formatGold(double g) {
        return Long.toString(Math.round(g));
    }

    private static int extraHorizontalPadding(int visibleChars) {
        if (visibleChars <= 24) return 0;
        return Math.min(28, (visibleChars - 24) / 2);
    }

    /** Strip §X codes for visible text checks and fallback width measurement. */
    private static String stripFormatting(String s) {
        return s.replaceAll("§.", "");
    }

    private static void drawFavoriteStar(GuiGraphicsExtractor ctx, Font tr, int bx, int by, int boxW, boolean favorite) {
        favoriteX1 = bx + boxW - PADDING - STAR_HITBOX + 2;
        favoriteY1 = by + PADDING - 4;
        favoriteX2 = favoriteX1 + STAR_HITBOX;
        favoriteY2 = favoriteY1 + STAR_HITBOX;

        String star = favorite ? "★" : "☆";
        int color = favorite ? 0xFFFFE066 : 0xFFE5E7EB;
        int textX = favoriteX1 + (STAR_HITBOX - tr.width(star)) / 2;
        int textY = favoriteY1 + 4;
        ctx.text(tr, star, textX + 1, textY + 1, 0xCC000000, false);
        ctx.text(tr, star, textX, textY, color, false);
    }

    private static void drawButtons(GuiGraphicsExtractor ctx, Font tr, int bx, int by, int boxW,
                                    int mouseX, int mouseY) {
        int gap = 6;
        int available = boxW - PADDING * 2;
        int buttonW = Math.max(52, Math.min(82, (available - gap) / 2));
        discordX1 = bx + PADDING;
        discordY1 = by;
        discordX2 = discordX1 + buttonW;
        discordY2 = by + BUTTON_HEIGHT;
        routeX1 = discordX2 + gap;
        routeY1 = by;
        routeX2 = routeX1 + buttonW;
        routeY2 = by + BUTTON_HEIGHT;
        hasButtons = true;

        // "Expand" replaces the old Discord button: the popup can only show a handful of fields, and the
        // full panel carries the Discord link itself. Always enabled — unlike Discord, every town has
        // something to show. Mouse coords are in the popup's local (scaled) space so hover matches.
        drawButton(ctx, tr, discordX1, discordY1, discordX2, discordY2, "Expand", true, mouseX, mouseY);
        drawButton(ctx, tr, routeX1, routeY1, routeX2, routeY2, "Route", true, mouseX, mouseY);
    }

    private static void drawButton(GuiGraphicsExtractor ctx, Font tr, int x1, int y1, int x2, int y2,
                                   String label, boolean active, int mouseX, int mouseY) {
        if (DarkButtons.enabled()) {
            DarkButtons.draw(ctx, x1, y1, x2 - x1, y2 - y1, label, active, 0xFFFFFFFF, mouseX, mouseY);
            return;
        }
        Button button = Button.builder(coloredText(label, active ? 0xFFFFFF : 0x777777), ignored -> {})
                .bounds(x1, y1, x2 - x1, y2 - y1)
                .build();
        button.active = active;
        button.extractRenderState(ctx, mouseX, mouseY, 0.0F);
    }

    private static String normalizeDiscordUrl(String discord) {
        return DiscordUrl.normalize(discord);
    }

    public static void openDiscord(String url) {
        if (url == null || url.isBlank()) return;
        try {
            com.mojang.blaze3d.Blaze3D.openUri(URI.create(url));
        } catch (Exception ignored) {
        }
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

    private static boolean inside(double mouseX, double mouseY, int x1, int y1, int x2, int y2) {
        return mouseX >= x1 && mouseX <= x2 && mouseY >= y1 && mouseY <= y2;
    }

    public record ActionResult(Action action, String townName, String url,
                               String searchType, String searchName) {
        public static ActionResult none() {
            return new ActionResult(Action.NONE, "", "", "", "");
        }
        public static ActionResult favorite(String townName) {
            return new ActionResult(Action.FAVORITE, townName, "", "", "");
        }
        public static ActionResult expand(String townName) {
            return new ActionResult(Action.EXPAND, townName, "", "", "");
        }

        public static ActionResult discord(String townName, String url) {
            return new ActionResult(Action.DISCORD, townName, url, "", "");
        }
        public static ActionResult route(String townName) {
            return new ActionResult(Action.ROUTE, townName, "", "", "");
        }
        public static ActionResult search(String searchType, String searchName) {
            return new ActionResult(Action.SEARCH, "", "", searchType, searchName);
        }
    }

    public enum Action {
        NONE,
        FAVORITE,
        EXPAND,
        DISCORD,
        ROUTE,
        SEARCH
    }

    /** One line of the popup: label prefix, optional clickable name, optional suffix. */
    private record InfoRow(String prefix, String name, String suffix, String linkType) {
        static InfoRow text(String formatted) { return new InfoRow(formatted, "", "", ""); }
        static InfoRow link(String prefix, String name, String suffix, String linkType) {
            return new InfoRow(prefix, name, suffix, linkType);
        }
        boolean hasLink() { return !linkType.isEmpty() && !name.isEmpty(); }
    }

    /** A clickable name span: bounds plus the entity to search for. */
    private record InfoLink(int x, int y, int w, int h, String type, String name) {
        boolean contains(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }
}
