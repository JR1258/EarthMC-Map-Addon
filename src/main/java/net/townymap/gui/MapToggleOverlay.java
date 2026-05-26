package net.townymap.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.townymap.TownyMapConfig;

public final class MapToggleOverlay {

    private static final int WIDTH = 92;
    private static final int HEIGHT = 20;
    private static final int GAP = 3;
    private static final int LEFT = 8;
    private static final int RESET_GAP = 3;
    private static final int RESET_WIDTH = 44;
    private static final int GROUP_GAP = 3;
    private static final int GROUP_WIDTH = 18;
    private static final int GROUP_LEFT = 40;
    private static final int GROUP_TOP = 8;
    private static final int ADD_WIDTH = 20;
    private static final int SETTINGS_GAP = 7;   // extra gap above the settings button
    private static final int TOGGLE_ROWS = 5;    // EMC | Borders | Map mode | Chunks | Counter

    private MapToggleOverlay() {}

    public static void render(DrawContext ctx, int sh, TownyMapConfig config,
                              boolean squaremapLoading, boolean bordersLoading) {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int y = togglesTop(sh);

        drawToggle(ctx, tr, 0, y, squaremapLoading ? "EMC..." : "EMC", config.squaremapBackgroundEnabled);
        drawMode(ctx, tr, 1, y, bordersLoading ? "Borders..." : "Borders", borderModeLabel(config.borderOverlayMode),
                config.borderOverlayMode != 0);
        drawMode(ctx, tr, 2, y, "Map", statusModeLabel(config.townStatusOverlayMode),
                config.townStatusOverlayMode != 0);
        drawToggle(ctx, tr, 3, y, "Chunks", config.chunkGridEnabled);
        drawMode(ctx, tr, 4, y, "Counter", ChunkCounterOverlay.toolbarLabel(config), config.chunkCounterEnabled);
        if (config.chunkCounterEnabled) {
            if (ChunkCounterOverlay.isMultiMode(config)) {
                drawCounterGroupButtons(ctx, tr, config);
            }
            drawCounterResetButton(ctx, tr, y);
        }

        drawSettingsButton(ctx, tr, settingsTop(sh));
    }

    /** Returns true if a toggle was clicked (caller should NOT open settings). */
    public static boolean handleClick(double mouseX, double mouseY, int sh, TownyMapConfig config) {
        if (ChunkCounterOverlay.isMultiMode(config)) {
            int group = counterGroupAt(mouseX, mouseY, config);
            if (group >= 0) {
                ChunkCounterOverlay.setActiveGroup(config, group);
                return true;
            }
            if (insideCounterAdd(mouseX, mouseY, config)) {
                ChunkCounterOverlay.addGroup(config);
                return true;
            }
        }
        if (config.chunkCounterEnabled && insideCounterReset(mouseX, mouseY, sh)) {
            ChunkCounterOverlay.clearActive(config);
            return true;
        }
        if (mouseX < LEFT || mouseX > LEFT + WIDTH) return false;

        int row = toggleRowAt(mouseY, sh);
        if (row >= 0) {
            switch (row) {
                case 0 -> config.squaremapBackgroundEnabled = !config.squaremapBackgroundEnabled;
                case 1 -> config.borderOverlayMode = (config.borderOverlayMode + 1) % 3;
                case 2 -> config.townStatusOverlayMode = (config.townStatusOverlayMode + 1) % 6;
                case 3 -> config.chunkGridEnabled = !config.chunkGridEnabled;
                case 4 -> {
                    if (config.chunkCounterEnabled) ChunkCounterOverlay.flushSelection();
                    if (!config.chunkCounterEnabled) {
                        config.chunkCounterEnabled = true;
                        config.chunkCounterMode = 2;
                        ChunkCounterOverlay.prepareMultiMode(config);
                    } else {
                        config.chunkCounterEnabled = false;
                        config.chunkCounterMode = 2;
                    }
                }
                default -> { return false; }
            }
            config.save();
            return true;
        }
        return false;
    }

    /** Returns true if the ⚙ Settings button was clicked. */
    public static boolean handleSettingsClick(double mouseX, double mouseY, int sh) {
        if (mouseX < LEFT || mouseX > LEFT + WIDTH) return false;
        int sy = settingsTop(sh);
        return mouseY >= sy && mouseY <= sy + HEIGHT;
    }

    private static void drawToggle(DrawContext ctx, TextRenderer tr, int row, int baseY,
                                   String name, boolean enabled) {
        int x = LEFT;
        int y = baseY + row * (HEIGHT + GAP);
        int text = enabled ? 0xFFFFFFFF : 0xFFBDBDBD;
        String label = name + ": " + (enabled ? "ON" : "OFF");

        drawTexturedButton(ctx, x, y, WIDTH, HEIGHT, label, true, text);
        ctx.fill(x + 2, y + 3, x + 5, y + HEIGHT - 3, enabled ? 0xFF67D76B : 0xFF606060);
    }

    private static void drawMode(DrawContext ctx, TextRenderer tr, int row, int baseY,
                                 String name, String mode, boolean enabled) {
        int x = LEFT;
        int y = baseY + row * (HEIGHT + GAP);
        int text = enabled ? 0xFFFFFFFF : 0xFFBDBDBD;
        String label = name + ": " + mode;

        drawTexturedButton(ctx, x, y, WIDTH, HEIGHT, label, true, text);
        ctx.fill(x + 2, y + 3, x + 5, y + HEIGHT - 3, enabled ? 0xFF67D76B : 0xFF606060);
    }

    private static int toggleRowAt(double mouseY, int sh) {
        int baseY = togglesTop(sh);
        for (int row = 0; row < TOGGLE_ROWS; row++) {
            int top = baseY + row * (HEIGHT + GAP);
            if (mouseY >= top && mouseY <= top + HEIGHT) return row;
        }
        return -1;
    }

    private static int togglesTop(int sh) {
        int totalHeight = TOGGLE_ROWS * HEIGHT + (TOGGLE_ROWS - 1) * GAP + SETTINGS_GAP + HEIGHT;
        return Math.max(8, (sh - totalHeight) / 2);
    }

    private static int settingsTop(int sh) {
        return togglesTop(sh) + TOGGLE_ROWS * (HEIGHT + GAP) + SETTINGS_GAP;
    }

    private static void drawSettingsButton(DrawContext ctx, TextRenderer tr, int y) {
        String label = "⚙ Settings";
        drawTexturedButton(ctx, LEFT, y, WIDTH, HEIGHT, label, true, 0xFFCCCCCC);
    }

    private static void drawCounterResetButton(DrawContext ctx, TextRenderer tr, int baseY) {
        int x = LEFT + WIDTH + RESET_GAP;
        int y = baseY + 4 * (HEIGHT + GAP);
        drawTexturedButton(ctx, x, y, RESET_WIDTH, HEIGHT, "Reset", true, 0xFFFF5555);
    }

    private static void drawCounterGroupButtons(DrawContext ctx, TextRenderer tr, TownyMapConfig config) {
        int y = GROUP_TOP;
        int x = counterGroupsX();
        int visibleGroups = ChunkCounterOverlay.visibleGroupCount(config);
        for (int i = 0; i < visibleGroups; i++) {
            boolean active = ChunkCounterOverlay.isActiveGroup(config, i);
            int textColor = active ? 0xFFFFFFFF : 0xFFBDBDBD;
            drawTexturedButton(ctx, x, y, GROUP_WIDTH, HEIGHT, ChunkCounterOverlay.groupLabel(i), true, textColor);
            if (active) {
                ctx.fill(x + 2, y + HEIGHT - 4, x + GROUP_WIDTH - 2, y + HEIGHT - 2,
                        0xFF000000 | ChunkCounterOverlay.groupColor(i));
            }
            x += GROUP_WIDTH + GROUP_GAP;
        }
        if (ChunkCounterOverlay.canAddGroup(config)) {
            drawTexturedButton(ctx, x, y, ADD_WIDTH, HEIGHT, "+", true, 0xFFFFFFFF);
        }
    }

    private static boolean insideCounterReset(double mouseX, double mouseY, int sh) {
        int x = LEFT + WIDTH + RESET_GAP;
        int y = togglesTop(sh) + 4 * (HEIGHT + GAP);
        return mouseX >= x && mouseX <= x + RESET_WIDTH
                && mouseY >= y && mouseY <= y + HEIGHT;
    }

    private static int counterGroupAt(double mouseX, double mouseY, TownyMapConfig config) {
        int y = GROUP_TOP;
        if (mouseY < y || mouseY > y + HEIGHT) return -1;
        int x = counterGroupsX();
        int visibleGroups = ChunkCounterOverlay.visibleGroupCount(config);
        for (int i = 0; i < visibleGroups; i++) {
            if (mouseX >= x && mouseX <= x + GROUP_WIDTH) return i;
            x += GROUP_WIDTH + GROUP_GAP;
        }
        return -1;
    }

    private static boolean insideCounterAdd(double mouseX, double mouseY, TownyMapConfig config) {
        if (!ChunkCounterOverlay.canAddGroup(config)) return false;
        int y = GROUP_TOP;
        if (mouseY < y || mouseY > y + HEIGHT) return false;
        int visibleGroups = ChunkCounterOverlay.visibleGroupCount(config);
        int x = counterGroupsX() + visibleGroups * (GROUP_WIDTH + GROUP_GAP);
        return mouseX >= x && mouseX <= x + ADD_WIDTH;
    }

    private static int counterGroupsX() {
        return GROUP_LEFT;
    }

    private static void drawTexturedButton(DrawContext ctx, int x, int y, int w, int h,
                                           String label, boolean active, int textColor) {
        ButtonWidget button = ButtonWidget.builder(coloredText(label, textColor), ignored -> {})
                .dimensions(x, y, w, h)
                .build();
        button.active = active;
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

    private static String borderModeLabel(int mode) {
        return switch (mode) {
            case 1 -> "Countries";
            case 2 -> "States";
            default -> "OFF";
        };
    }

    private static String statusModeLabel(int mode) {
        return switch (mode) {
            case 1 -> "Public";
            case 2 -> "Overclaim";
            case 3 -> "Open";
            case 4 -> "For Sale";
            case 5 -> "No Nation";
            default -> "None";
        };
    }

}
