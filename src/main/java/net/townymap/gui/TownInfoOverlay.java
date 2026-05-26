package net.townymap.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.townymap.model.EarthMcNationData;
import net.townymap.model.TownPopupData;

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
    private static final long DISPLAY_MS = 12_000;

    private static final int BG_COLOR     = 0xD8101010;
    private static final int BORDER_COLOR = 0xFF333333;

    private static TownPopupData currentData;
    private static int screenX, screenY;
    private static long showUntil;
    private static boolean loading;
    private static int favoriteX1, favoriteY1, favoriteX2, favoriteY2;
    private static int routeX1, routeY1, routeX2, routeY2;
    private static boolean hasButtons;

    private TownInfoOverlay() {}

    public static void showLoading(int sx, int sy) {
        loading     = true;
        currentData = null;
        screenX     = sx;
        screenY     = sy;
        showUntil   = System.currentTimeMillis() + DISPLAY_MS;
    }

    public static void show(TownPopupData data, int sx, int sy) {
        loading     = false;
        currentData = data;
        screenX     = sx;
        screenY     = sy;
        showUntil   = System.currentTimeMillis() + DISPLAY_MS;
    }

    public static void dismiss() {
        currentData = null;
        loading     = false;
    }

    public static TownPopupData currentData() {
        return loading ? null : currentData;
    }

    public static void render(DrawContext ctx, int sw, int sh, boolean favorite,
                              Map<String, EarthMcNationData> nationDetails) {
        if (!loading && currentData == null) return;
        if (System.currentTimeMillis() > showUntil) {
            dismiss();
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;

        List<String> lines = buildLines(nationDetails);
        if (lines.isEmpty()) return;

        // Measure rendered text width and add more breathing room for long names.
        int maxW = 0;
        int longestVisibleChars = 0;
        for (String line : lines) {
            String visible = stripFormatting(line);
            int w = Math.max(tr.getWidth(line), tr.getWidth(visible));
            if (w > maxW) maxW = w;
            if (visible.length() > longestVisibleChars) longestVisibleChars = visible.length();
        }
        int horizontalPadding = PADDING + extraHorizontalPadding(longestVisibleChars);
        int boxW = maxW + horizontalPadding * 2;
        boolean showButtons = !loading && currentData != null && currentData != TownPopupData.WILDERNESS;
        if (showButtons) {
            boxW = Math.max(boxW, PADDING * 2 + 52 * 2 + 6);
        }
        int buttonRowHeight = showButtons ? BUTTON_HEIGHT + 6 : 0;
        int boxH = lines.size() * LINE_HEIGHT + PADDING * 2 - 1 + buttonRowHeight;

        // Position — clamp to screen
        int bx = Math.min(screenX + 6, sw - boxW - 4);
        int by = Math.min(screenY + 6, sh - boxH - 4);
        if (bx < 4) bx = 4;
        if (by < 4) by = 4;

        // Background + border
        ctx.fill(bx - 1, by - 1, bx + boxW + 1, by + boxH + 1, BORDER_COLOR);
        ctx.fill(bx,     by,     bx + boxW,     by + boxH,     BG_COLOR);

        // Text — use the String overload (same path as player name labels)
        int ty = by + PADDING;
        for (String line : lines) {
            ctx.drawText(tr, line, bx + horizontalPadding, ty, 0xFFFFFFFF, true);
            ty += LINE_HEIGHT;
        }

        hasButtons = false;
        if (showButtons) {
            drawButtons(ctx, tr, bx, by + boxH - PADDING - BUTTON_HEIGHT + 2, boxW, favorite);
        }
    }

    public static ActionResult handleClick(double mouseX, double mouseY) {
        if (!hasButtons || currentData == null || currentData == TownPopupData.WILDERNESS) return ActionResult.none();
        String town = currentData.townName();
        if (inside(mouseX, mouseY, favoriteX1, favoriteY1, favoriteX2, favoriteY2)) return ActionResult.favorite(town);
        if (inside(mouseX, mouseY, routeX1, routeY1, routeX2, routeY2)) return ActionResult.route(town);
        return ActionResult.none();
    }

    private static List<String> buildLines(Map<String, EarthMcNationData> nationDetails) {
        List<String> lines = new ArrayList<>();

        if (loading) {
            lines.add("§7§oLooking up town...");
            return lines;
        }

        TownPopupData d = currentData;
        if (d == null) return lines;

        if (d == TownPopupData.WILDERNESS) {
            lines.add("§aWilderness");
            return lines;
        }

        // Title
        String title = d.nationName().isEmpty()
                ? "§f§l" + d.townName()
                : "§f§l" + d.townName() + " §7§l(" + d.nationName() + ")";
        lines.add(title);

        // Board
        if (hasBoard(d.board())) {
            lines.add("§7§o" + d.board());
        }

        // Spacer
        lines.add("");

        lines.add("§7Mayor: §f§l"     + d.mayor());
        int possibleChunks = possibleTownChunks(d, nationDetails);
        boolean overLimit = d.isOverClaimed() || d.numChunks() > possibleChunks;
        String sizeColor = overLimit ? "§c§l" : "§f§l";
        lines.add("§7Size: " + sizeColor + d.numChunks() + " / " + possibleChunks + " chunks");
        if (!d.founded().isEmpty()) {
            lines.add("§7Founded: §f§l" + d.founded());
        }
        lines.add("§7Open: §f§l"      + (d.isOpen()   ? "Yes" : "No"));
        lines.add("§7Public: §f§l"    + (d.isPublic() ? "Yes" : "No"));
        lines.add("§7Residents: §f§l" + d.residentCount());
        lines.add("§7Gold: §f§l"      + formatGold(d.balance()));

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

    private static void drawButtons(DrawContext ctx, TextRenderer tr, int bx, int by, int boxW, boolean favorite) {
        int gap = 6;
        int available = boxW - PADDING * 2;
        int buttonW = Math.max(52, Math.min(82, (available - gap) / 2));
        favoriteX1 = bx + PADDING;
        favoriteY1 = by;
        favoriteX2 = favoriteX1 + buttonW;
        favoriteY2 = by + BUTTON_HEIGHT;
        routeX1 = favoriteX2 + gap;
        routeY1 = by;
        routeX2 = routeX1 + buttonW;
        routeY2 = by + BUTTON_HEIGHT;
        hasButtons = true;

        drawButton(ctx, tr, favoriteX1, favoriteY1, favoriteX2, favoriteY2, favorite ? "Unstar" : "Star");
        drawButton(ctx, tr, routeX1, routeY1, routeX2, routeY2, "Route");
    }

    private static void drawButton(DrawContext ctx, TextRenderer tr, int x1, int y1, int x2, int y2, String label) {
        ButtonWidget button = ButtonWidget.builder(coloredText(label, 0xFFFFFF), ignored -> {})
                .dimensions(x1, y1, x2 - x1, y2 - y1)
                .build();
        button.render(ctx, scaledMouseX(), scaledMouseY(), 0.0F);
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

    public record ActionResult(Action action, String townName) {
        public static ActionResult none() {
            return new ActionResult(Action.NONE, "");
        }
        public static ActionResult favorite(String townName) {
            return new ActionResult(Action.FAVORITE, townName);
        }
        public static ActionResult route(String townName) {
            return new ActionResult(Action.ROUTE, townName);
        }
    }

    public enum Action {
        NONE,
        FAVORITE,
        ROUTE
    }
}
