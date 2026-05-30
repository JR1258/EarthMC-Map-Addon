package net.townymap.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.townymap.model.EarthMcNationData;
import net.townymap.model.TownPopupData;
import net.townymap.util.DiscordUrl;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Statically-held popup shown when the player right-clicks a town on the WorldMap.
 *
 * Uses plain String + Minecraft formatting codes (§) for text rather than Text objects,
 * because the String path of DrawContext.drawText() is guaranteed to work in the
 * same GL state as border/player-dot rendering (renderPreDropdown HEAD).
 */
public final class TownInfoOverlay {

    private static final int PADDING     = 16;
    private static final int LINE_HEIGHT = 15;
    private static final int BUTTON_HEIGHT = 20;
    private static final int STAR_HITBOX = 18;
    private static final long DISPLAY_MS = 12_000;

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

    public static void render(DrawContext ctx, int sw, int sh, boolean favorite,
                              Map<String, EarthMcNationData> nationDetails) {
        infoLinks.clear();
        if (!loading && currentData == null) return;
        if (System.currentTimeMillis() > showUntil) {
            dismiss();
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;

        List<InfoRow> lines = buildLines(nationDetails);
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

        // Keep right-click town details in the same right-side area as search result details.
        int bx = Math.max(8, sw - boxW - 12);
        int by = Math.max(36, Math.min(sh - boxH - 36, sh / 2 - boxH / 2));

        // Background + border
        ctx.fill(bx - 1, by - 1, bx + boxW + 1, by + boxH + 1, BORDER_COLOR);
        ctx.fill(bx,     by,     bx + boxW,     by + boxH,     BG_COLOR);
        if (showButtons) {
            drawFavoriteStar(ctx, tr, bx, by, boxW, favorite);
        }

        // Text — use the String overload (same path as player name labels).
        // Link rows draw the label prefix, then the clickable name (yellow on
        // hover), then any suffix; the name's bounds are recorded for handleClick.
        int mx = scaledMouseX();
        int my = scaledMouseY();
        int ty = by + PADDING;
        for (InfoRow row : lines) {
            int px = bx + horizontalPadding;
            ctx.drawText(tr, row.prefix(), px, ty, 0xFFFFFFFF, true);
            if (row.hasLink()) {
                int nameX = px + tr.getWidth(row.prefix());
                int nameW = tr.getWidth(row.name());
                boolean hover = mx >= nameX && mx <= nameX + nameW && my >= ty - 1 && my <= ty + 11;
                ctx.drawText(tr, row.name(), nameX, ty, hover ? LINK_HOVER_COLOR : LINK_COLOR, true);
                infoLinks.add(new InfoLink(nameX, ty - 1, nameW, 12, row.linkType(), row.name()));
                if (!row.suffix().isEmpty()) {
                    ctx.drawText(tr, row.suffix(), nameX + nameW, ty, 0xFFFFFFFF, true);
                }
            }
            ty += LINE_HEIGHT;
        }

        hasButtons = false;
        if (showButtons) {
            drawButtons(ctx, tr, bx, by + boxH - PADDING - BUTTON_HEIGHT + 2, boxW);
        }
    }

    private static int rowWidth(TextRenderer tr, InfoRow row) {
        int w = tr.getWidth(row.prefix());
        if (row.hasLink()) w += tr.getWidth(row.name()) + tr.getWidth(row.suffix());
        return w;
    }

    public static ActionResult handleClick(double mouseX, double mouseY) {
        // Clickable names work whenever a real town is shown (even before buttons).
        if (currentData != null && currentData != TownPopupData.WILDERNESS) {
            for (InfoLink link : infoLinks) {
                if (link.contains(mouseX, mouseY)) {
                    return ActionResult.search(link.type(), link.name());
                }
            }
        }
        if (!hasButtons || currentData == null || currentData == TownPopupData.WILDERNESS) return ActionResult.none();
        String town = currentData.townName();
        if (inside(mouseX, mouseY, favoriteX1, favoriteY1, favoriteX2, favoriteY2)) return ActionResult.favorite(town);
        if (inside(mouseX, mouseY, discordX1, discordY1, discordX2, discordY2)) {
            String url = normalizeDiscordUrl(currentData.discord());
            return url.isBlank() ? ActionResult.none() : ActionResult.discord(town, url);
        }
        if (inside(mouseX, mouseY, routeX1, routeY1, routeX2, routeY2)) return ActionResult.route(town);
        return ActionResult.none();
    }

    private static List<InfoRow> buildLines(Map<String, EarthMcNationData> nationDetails) {
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
        if (d.nationName().isEmpty()) {
            lines.add(InfoRow.text("§f§l" + d.townName()));
        } else {
            lines.add(InfoRow.link("§f§l" + d.townName() + " §7§l(", d.nationName(), "§7§l)", "nation"));
        }

        // Board
        if (hasBoard(d.board())) {
            lines.add(InfoRow.text("§7§o" + d.board()));
        }

        // Spacer
        lines.add(InfoRow.text(""));

        // Mayor name → clickable player search.
        lines.add(InfoRow.link("§7Mayor: §f§l", d.mayor(), "", "player"));
        int possibleChunks = possibleTownChunks(d, nationDetails);
        boolean overLimit = d.isOverClaimed() || d.numChunks() > possibleChunks;
        String sizeColor = overLimit ? "§c§l" : "§f§l";
        lines.add(InfoRow.text("§7Size: " + sizeColor + d.numChunks() + " / " + possibleChunks + " chunks"));
        if (!d.founded().isEmpty()) {
            lines.add(InfoRow.text("§7Founded: §f§l" + d.founded()));
        }
        lines.add(InfoRow.text("§7Open: §f§l"      + (d.isOpen()   ? "Yes" : "No")));
        lines.add(InfoRow.text("§7Public: §f§l"    + (d.isPublic() ? "Yes" : "No")));
        lines.add(InfoRow.text("§7Residents: §f§l" + d.residentCount()));
        lines.add(InfoRow.text("§7Gold: §f§l"      + formatGold(d.balance())));

        return lines;
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

    private static int nationBonus(int residents) {
        if (residents >= 200) return 100;
        if (residents >= 120) return 80;
        if (residents >= 80) return 60;
        if (residents >= 60) return 50;
        if (residents >= 40) return 30;
        if (residents >= 20) return 10;
        return 0;
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

    private static void drawFavoriteStar(DrawContext ctx, TextRenderer tr, int bx, int by, int boxW, boolean favorite) {
        favoriteX1 = bx + boxW - PADDING - STAR_HITBOX + 2;
        favoriteY1 = by + PADDING - 4;
        favoriteX2 = favoriteX1 + STAR_HITBOX;
        favoriteY2 = favoriteY1 + STAR_HITBOX;

        String star = favorite ? "★" : "☆";
        int color = favorite ? 0xFFFFE066 : 0xFFE5E7EB;
        int textX = favoriteX1 + (STAR_HITBOX - tr.getWidth(star)) / 2;
        int textY = favoriteY1 + 4;
        ctx.drawText(tr, star, textX + 1, textY + 1, 0xCC000000, false);
        ctx.drawText(tr, star, textX, textY, color, false);
    }

    private static void drawButtons(DrawContext ctx, TextRenderer tr, int bx, int by, int boxW) {
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

        boolean hasDiscord = !normalizeDiscordUrl(currentData.discord()).isBlank();
        drawButton(ctx, tr, discordX1, discordY1, discordX2, discordY2, "Discord", hasDiscord);
        drawButton(ctx, tr, routeX1, routeY1, routeX2, routeY2, "Route", true);
    }

    private static void drawButton(DrawContext ctx, TextRenderer tr, int x1, int y1, int x2, int y2,
                                   String label, boolean active) {
        ButtonWidget button = ButtonWidget.builder(coloredText(label, active ? 0xFFFFFF : 0x777777), ignored -> {})
                .dimensions(x1, y1, x2 - x1, y2 - y1)
                .build();
        button.active = active;
        button.render(ctx, scaledMouseX(), scaledMouseY(), 0.0F);
    }

    private static String normalizeDiscordUrl(String discord) {
        return DiscordUrl.normalize(discord);
    }

    public static void openDiscord(String url) {
        if (url == null || url.isBlank()) return;
        try {
            Util.getOperatingSystem().open(URI.create(url));
        } catch (Exception ignored) {
        }
    }

    private static Text coloredText(String label, int textColor) {
        return Text.literal(label).setStyle(Style.EMPTY.withColor(textColor & 0xFFFFFF));
    }

    private static int scaledMouseX() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return (int) (mc.mouse.getX() * mc.getWindow().getScaledWidth() / mc.getWindow().getWidth());
    }

    private static int scaledMouseY() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return (int) (mc.mouse.getY() * mc.getWindow().getScaledHeight() / mc.getWindow().getHeight());
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
