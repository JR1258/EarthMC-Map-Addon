package net.townymap.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.townymap.TownyMapConfig;
import net.townymap.TownyMapMod;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;

public final class ChunkCounterOverlay {

    private static final int CHUNK_SIZE = 16;
    private static final int MODE_MULTI = 2;
    private static final int MAX_GROUPS = 7;
    private static final int PREVIEW_FILL = 0x26FFFFFF;
    private static final int PREVIEW_BORDER = 0xB8FFFFFF;
    private static final double LOW_ZOOM_CHUNK_PIXELS = 2.0;
    private static final double LABEL_MIN_CHUNK_PIXELS = 8.0;
    private static final long PERSIST_INTERVAL_MS = 750L;
    private static final String[] GROUP_LABELS = {"A", "B", "C", "D", "E", "F", "G"};
    private static final int[] GROUP_RGB = {
            0xA970FF, 0x35F2FF, 0xFFE066, 0x67D76B, 0xFF5ACD, 0xFF9F43, 0xFF5555
    };

    private static final List<SelectionState> GROUPS = new ArrayList<>(MAX_GROUPS);
    private static int activeGroup;
    private static long lastRightDownKey = Long.MIN_VALUE;
    private static long lastPersistMs;
    private static boolean rightDragSelecting = true;
    private static boolean persistDirty;
    private static boolean activeGroupEmptiedByRemoval;

    static {
        ensureGroups();
    }

    private ChunkCounterOverlay() {
    }

    public static int count() {
        TownyMapConfig config = TownyMapMod.getConfig();
        return activeSelection(config).chunks.size();
    }

    public static int totalCount() {
        Set<Long> unique = new HashSet<>();
        for (SelectionState state : GROUPS) unique.addAll(state.chunks);
        return unique.size();
    }

    public static String toolbarLabel(TownyMapConfig config) {
        if (config == null || !config.chunkCounterEnabled) return "OFF";
        return activeGroupLabel(config) + " " + activeSelection(config).chunks.size();
    }

    public static String activeGroupLabel(TownyMapConfig config) {
        int index = normalizedActiveGroup(config);
        return GROUP_LABELS[index];
    }

    public static boolean isMultiMode(TownyMapConfig config) {
        return config != null && config.chunkCounterEnabled;
    }

    public static int groupSlotCount() {
        return MAX_GROUPS;
    }

    public static int visibleGroupCount(TownyMapConfig config) {
        if (config == null) return 1;
        return Math.max(1, Math.min(MAX_GROUPS, config.chunkCounterGroupCount));
    }

    public static String groupLabel(int index) {
        int safeIndex = Math.max(0, Math.min(MAX_GROUPS - 1, index));
        return GROUP_LABELS[safeIndex];
    }

    public static int groupColor(int index) {
        int safeIndex = Math.max(0, Math.min(MAX_GROUPS - 1, index));
        return GROUP_RGB[safeIndex];
    }

    public static boolean isActiveGroup(TownyMapConfig config, int index) {
        return normalizedActiveGroup(config) == index;
    }

    public static boolean canAddGroup(TownyMapConfig config) {
        return visibleGroupCount(config) < MAX_GROUPS;
    }

    public static void setActiveGroup(TownyMapConfig config, int index) {
        if (config == null) return;
        ensureGroups();
        flushSelection();
        activeGroup = Math.max(0, Math.min(visibleGroupCount(config) - 1, index));
        config.activeChunkCounterGroup = activeGroup;
        compactEmptyGroups(config, true);
        config.save();
    }

    public static void addGroup(TownyMapConfig config) {
        if (config == null) return;
        ensureGroups();
        flushSelection();
        compactEmptyGroups(config, false);
        int count = visibleGroupCount(config);
        if (count >= MAX_GROUPS) return;
        GROUPS.get(count).clear(false);
        config.chunkCounterGroupCount = count + 1;
        activeGroup = count;
        config.activeChunkCounterGroup = activeGroup;
        config.save();
    }

    public static void prepareMultiMode(TownyMapConfig config) {
        if (config == null) return;
        ensureGroups();
        config.chunkCounterGroupCount = Math.max(1, Math.min(MAX_GROUPS, config.chunkCounterGroupCount));
        activeGroup = 0;
        config.activeChunkCounterGroup = 0;
        compactEmptyGroups(config, true);
    }

    public static void cycleActiveGroup(TownyMapConfig config) {
        if (config == null) return;
        ensureGroups();
        flushSelection();
        compactEmptyGroups(config, false);
        activeGroup = (normalizedActiveGroup(config) + 1) % visibleGroupCount(config);
        config.activeChunkCounterGroup = activeGroup;
        config.save();
    }

    public static void loadSelection(List<Long> selectedChunks) {
        loadSelection(selectedChunks, List.of(), 0);
    }

    public static void loadSelection(List<Long> selectedChunks, List<List<Long>> selectedGroups, int groupIndex) {
        ensureGroups();
        for (SelectionState group : GROUPS) group.clear(false);
        boolean loadedGroups = false;
        if (selectedGroups != null) {
            for (int i = 0; i < Math.min(MAX_GROUPS, selectedGroups.size()); i++) {
                GROUPS.get(i).set(selectedGroups.get(i));
                if (!GROUPS.get(i).chunks.isEmpty()) loadedGroups = true;
            }
        }
        if (!loadedGroups && selectedChunks != null && !selectedChunks.isEmpty()) {
            GROUPS.get(0).set(selectedChunks);
        }
        activeGroup = Math.max(0, Math.min(MAX_GROUPS - 1, groupIndex));
        lastRightDownKey = Long.MIN_VALUE;
        lastPersistMs = 0;
        rightDragSelecting = true;
        persistDirty = false;
        activeGroupEmptiedByRemoval = false;
    }

    public static void clear() {
        for (SelectionState group : GROUPS) group.clear(false);
        lastRightDownKey = Long.MIN_VALUE;
        rightDragSelecting = true;
        persistDirty = false;
        activeGroupEmptiedByRemoval = false;
        persistSelectionNow();
    }

    public static void clearActive(TownyMapConfig config) {
        activeSelection(config).clear(true);
        lastRightDownKey = Long.MIN_VALUE;
        rightDragSelecting = true;
        persistDirty = true;
        activeGroupEmptiedByRemoval = false;
        compactEmptyGroups(config, false);
        persistSelectionNow();
    }

    public static boolean hasSelection() {
        for (SelectionState group : GROUPS) {
            if (!group.chunks.isEmpty()) return true;
        }
        return false;
    }

    public static boolean handleRightClick(double worldX, double worldZ) {
        TownyMapConfig config = TownyMapMod.getConfig();
        SelectionState selection = activeSelection(config);
        long key = key(floorToChunk(worldX), floorToChunk(worldZ));
        rightDragSelecting = !selection.chunks.contains(key);
        applyDragAction(selection, key);
        lastRightDownKey = key;
        return true;
    }

    public static void tickDrag(double worldX, double worldZ) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getWindow() == null) return;
        long handle = client.getWindow().handle();
        if (GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS) {
            long key = key(floorToChunk(worldX), floorToChunk(worldZ));
            if (key != lastRightDownKey) {
                applyDragPath(activeSelection(TownyMapMod.getConfig()), lastRightDownKey, key);
                lastRightDownKey = key;
            }
        } else {
            lastRightDownKey = Long.MIN_VALUE;
            rightDragSelecting = true;
            compactEmptyGroups(TownyMapMod.getConfig(), !activeGroupEmptiedByRemoval);
            activeGroupEmptiedByRemoval = false;
            flushSelection();
        }
    }

    public static void flushSelection() {
        compactEmptyGroups(TownyMapMod.getConfig(), true);
        if (persistDirty) persistSelectionNow();
    }

    public static void render(GuiGraphicsExtractor ctx, double cameraX, double cameraZ, double blockScale,
                              int sw, int sh, double mouseWorldX, double mouseWorldZ, boolean preview) {
        if (blockScale <= 0) return;
        TownyMapConfig config = TownyMapMod.getConfig();
        int groupCount = visibleGroupCount(config);
        for (int i = 0; i < groupCount; i++) {
            if (i == normalizedActiveGroup(config)) continue;
            drawSelection(ctx, GROUPS.get(i), cameraX, cameraZ, blockScale, sw, sh, false);
        }
        drawSelection(ctx, activeSelection(config), cameraX, cameraZ, blockScale, sw, sh, true);
        drawOverlapBadges(ctx, cameraX, cameraZ, blockScale, sw, sh);

        drawRegionLabels(ctx, cameraX, cameraZ, blockScale, sw, sh, config);
        if (preview) {
            drawChunk(ctx, floorToChunk(mouseWorldX), floorToChunk(mouseWorldZ),
                    cameraX, cameraZ, blockScale, sw, sh, PREVIEW_FILL, PREVIEW_BORDER, true);
        }
    }

    public static void renderWorldSpace(GuiGraphicsExtractor ctx) {
        TownyMapConfig config = TownyMapMod.getConfig();
        if (config == null || !config.chunkCounterEnabled) return;
        int groupCount = visibleGroupCount(config);
        for (int i = 0; i < groupCount; i++) {
            SelectionState state = GROUPS.get(i);
            drawSelectionWorldSpace(ctx, state, i == normalizedActiveGroup(config));
        }
    }

    public static void renderMinimapLabels(GuiGraphicsExtractor ctx, Minecraft client,
                                           int mapX, int mapY, int size,
                                           double playerX, double playerZ,
                                           double pixelsPerBlock, double sin, double cos,
                                           int clipLeft, int clipTop, int clipRight, int clipBottom) {
        TownyMapConfig config = TownyMapMod.getConfig();
        if (config == null || !config.chunkCounterEnabled || client == null) return;
        double centerX = mapX + size / 2.0;
        double centerY = mapY + size / 2.0;

        ctx.enableScissor(clipLeft, clipTop, clipRight + 1, clipBottom + 1);
        try {
            int groupCount = visibleGroupCount(config);
            for (int i = 0; i < groupCount; i++) {
                drawMinimapLabelsForSelection(ctx, client.font, GROUPS.get(i), GROUP_LABELS[i],
                        centerX, centerY, playerX, playerZ, pixelsPerBlock, sin, cos,
                        clipLeft, clipTop, clipRight, clipBottom);
            }
        } finally {
            ctx.disableScissor();
        }
    }

    public static List<Long> selectedChunks() {
        return List.of();
    }

    public static List<List<Long>> selectedGroups() {
        ensureGroups();
        TownyMapConfig config = TownyMapMod.getConfig();
        int count = visibleGroupCount(config);
        ArrayList<List<Long>> groups = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            groups.add(new ArrayList<>(GROUPS.get(i).chunks));
        }
        return groups;
    }

    private static void drawSelection(GuiGraphicsExtractor ctx, SelectionState selection,
                                      double cameraX, double cameraZ, double blockScale,
                                      int sw, int sh, boolean active) {
        selection.ensureBuilt();
        int fill = argb(active ? 0x4A : 0x30, selection.rgb);
        int border = argb(active ? 0xF0 : 0xA8, selection.rgb);
        if (CHUNK_SIZE * blockScale < LOW_ZOOM_CHUNK_PIXELS) {
            drawLowZoomSelection(ctx, selection, cameraX, cameraZ, blockScale, sw, sh, active);
            return;
        }
        for (long key : selection.chunks) {
            drawChunk(ctx, chunkX(key), chunkZ(key), cameraX, cameraZ, blockScale, sw, sh, fill, border, false);
        }
        for (Edge edge : selection.edges) {
            drawEdge(ctx, edge.chunkX, edge.chunkZ, edge.side, cameraX, cameraZ, blockScale, sw, sh, border);
        }
    }

    private static void drawLowZoomSelection(GuiGraphicsExtractor ctx, SelectionState selection,
                                             double cameraX, double cameraZ, double blockScale,
                                             int sw, int sh, boolean active) {
        int fill = argb(active ? 0x58 : 0x38, selection.rgb);
        int border = argb(active ? 0xF8 : 0xB0, selection.rgb);
        for (LowZoomRect rect : selection.lowZoomRects) {
            drawLowZoomRectFill(ctx, rect, cameraX, cameraZ, blockScale, sw, sh, fill);
        }
        for (Edge edge : selection.edges) {
            drawLowZoomEdge(ctx, edge, cameraX, cameraZ, blockScale, sw, sh, border);
        }
    }

    private static void drawLowZoomRectFill(GuiGraphicsExtractor ctx, LowZoomRect rect,
                                            double cameraX, double cameraZ, double blockScale,
                                            int sw, int sh, int color) {
        int left = (int) Math.round(screenX(rect.minChunkX * CHUNK_SIZE, cameraX, blockScale, sw));
        int right = (int) Math.round(screenX((rect.maxChunkX + 1) * CHUNK_SIZE, cameraX, blockScale, sw));
        int top = (int) Math.round(screenY(rect.minChunkZ * CHUNK_SIZE, cameraZ, blockScale, sh));
        int bottom = (int) Math.round(screenY((rect.maxChunkZ + 1) * CHUNK_SIZE, cameraZ, blockScale, sh));

        if (right < left) {
            int tmp = left;
            left = right;
            right = tmp;
        }
        if (bottom < top) {
            int tmp = top;
            top = bottom;
            bottom = tmp;
        }
        if (right <= left) right = left + 1;
        if (bottom <= top) bottom = top + 1;
        if (right < 0 || left > sw || bottom < 0 || top > sh) return;
        ctx.fill(Math.max(0, left), Math.max(0, top), Math.min(sw, right), Math.min(sh, bottom), color);
    }

    private static void drawLowZoomEdge(GuiGraphicsExtractor ctx, Edge edge,
                                        double cameraX, double cameraZ, double blockScale,
                                        int sw, int sh, int color) {
        double blockX = edge.chunkX * CHUNK_SIZE;
        double blockZ = edge.chunkZ * CHUNK_SIZE;
        int x1;
        int x2;
        int y1;
        int y2;
        switch (edge.side) {
            case 0 -> {
                x1 = (int) Math.floor(screenX(blockX, cameraX, blockScale, sw));
                x2 = (int) Math.ceil(screenX(blockX + CHUNK_SIZE, cameraX, blockScale, sw));
                y1 = (int) Math.round(screenY(blockZ, cameraZ, blockScale, sh));
                y2 = y1 + 1;
            }
            case 1 -> {
                x1 = (int) Math.round(screenX(blockX + CHUNK_SIZE, cameraX, blockScale, sw));
                x2 = x1 + 1;
                y1 = (int) Math.floor(screenY(blockZ, cameraZ, blockScale, sh));
                y2 = (int) Math.ceil(screenY(blockZ + CHUNK_SIZE, cameraZ, blockScale, sh));
            }
            case 2 -> {
                x1 = (int) Math.floor(screenX(blockX, cameraX, blockScale, sw));
                x2 = (int) Math.ceil(screenX(blockX + CHUNK_SIZE, cameraX, blockScale, sw));
                y1 = (int) Math.round(screenY(blockZ + CHUNK_SIZE, cameraZ, blockScale, sh));
                y2 = y1 + 1;
            }
            case 3 -> {
                x1 = (int) Math.round(screenX(blockX, cameraX, blockScale, sw));
                x2 = x1 + 1;
                y1 = (int) Math.floor(screenY(blockZ, cameraZ, blockScale, sh));
                y2 = (int) Math.ceil(screenY(blockZ + CHUNK_SIZE, cameraZ, blockScale, sh));
            }
            default -> {
                return;
            }
        }
        int left = Math.min(x1, x2);
        int right = Math.max(x1, x2);
        int top = Math.min(y1, y2);
        int bottom = Math.max(y1, y2);
        if (right == left) right++;
        if (bottom == top) bottom++;
        if (right < 0 || left > sw || bottom < 0 || top > sh) return;
        ctx.fill(Math.max(0, left), Math.max(0, top), Math.min(sw, right), Math.min(sh, bottom), color);
    }

    private static void drawSelectionWorldSpace(GuiGraphicsExtractor ctx, SelectionState selection, boolean active) {
        selection.ensureBuilt();
        int fill = argb(active ? 0x42 : 0x2B, selection.rgb);
        int border = argb(active ? 0xE8 : 0xA0, selection.rgb);
        for (long key : selection.chunks) {
            int blockX = chunkX(key) * CHUNK_SIZE;
            int blockZ = chunkZ(key) * CHUNK_SIZE;
            ctx.fill(blockX, blockZ, blockX + CHUNK_SIZE, blockZ + CHUNK_SIZE, fill);
        }
        for (Edge edge : selection.edges) {
            int blockX = edge.chunkX * CHUNK_SIZE;
            int blockZ = edge.chunkZ * CHUNK_SIZE;
            switch (edge.side) {
                case 0 -> ctx.fill(blockX, blockZ, blockX + CHUNK_SIZE, blockZ + 1, border);
                case 1 -> ctx.fill(blockX + CHUNK_SIZE - 1, blockZ, blockX + CHUNK_SIZE, blockZ + CHUNK_SIZE, border);
                case 2 -> ctx.fill(blockX, blockZ + CHUNK_SIZE - 1, blockX + CHUNK_SIZE, blockZ + CHUNK_SIZE, border);
                case 3 -> ctx.fill(blockX, blockZ, blockX + 1, blockZ + CHUNK_SIZE, border);
                default -> {
                }
            }
        }
    }

    private static void drawRegionLabels(GuiGraphicsExtractor ctx,
                                         double cameraX, double cameraZ, double blockScale,
                                         int sw, int sh, TownyMapConfig config) {
        if (CHUNK_SIZE * blockScale < LABEL_MIN_CHUNK_PIXELS) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.font == null) return;
        int groupCount = visibleGroupCount(config);
        for (int i = 0; i < groupCount; i++) {
            drawLabelsForSelection(ctx, client.font, GROUPS.get(i), GROUP_LABELS[i],
                    cameraX, cameraZ, blockScale, sw, sh);
        }
    }

    private static void drawLabelsForSelection(GuiGraphicsExtractor ctx, Font tr, SelectionState selection,
                                               String prefix, double cameraX, double cameraZ,
                                               double blockScale, int sw, int sh) {
        selection.ensureBuilt();
        for (Component component : selection.components) {
            int x = toScreenX(component.centerX, cameraX, blockScale, sw);
            int y = toScreenY(component.centerZ, cameraZ, blockScale, sh);
            if (x < -40 || x > sw + 40 || y < -20 || y > sh + 20) continue;
            String text = prefix.equals("Chunks") ? "Chunks: " + component.count : prefix + ": " + component.count;
            drawLabel(ctx, tr, text, x, y, selection.rgb);
        }
    }

    private static void drawMinimapLabelsForSelection(GuiGraphicsExtractor ctx, Font tr, SelectionState selection,
                                                      String prefix, double centerX, double centerY,
                                                      double playerX, double playerZ, double pixelsPerBlock,
                                                      double sin, double cos,
                                                      int clipLeft, int clipTop, int clipRight, int clipBottom) {
        selection.ensureBuilt();
        for (Component component : selection.components) {
            double dx = component.centerX - playerX;
            double dz = component.centerZ - playerZ;
            int x = (int) Math.round(centerX + (dx * cos - dz * sin) * pixelsPerBlock);
            int y = (int) Math.round(centerY + (dx * sin + dz * cos) * pixelsPerBlock);
            if (x < clipLeft || x > clipRight || y < clipTop || y > clipBottom) continue;
            String text = prefix.equals("Chunks") ? Integer.toString(component.count) : prefix + ":" + component.count;
            drawLabel(ctx, tr, text, x, y, selection.rgb);
        }
    }

    private static void drawLabel(GuiGraphicsExtractor ctx, Font tr, String text, int centerX, int centerY, int rgb) {
        int width = tr.width(text);
        int x = centerX - width / 2;
        int y = centerY - tr.lineHeight / 2;
        ctx.text(tr, text, x + 1, y + 1, 0xCC000000, false);
        ctx.text(tr, text, x, y, 0xFFFFFFFF, false);
    }

    private static void drawOverlapBadges(GuiGraphicsExtractor ctx, double cameraX, double cameraZ,
                                          double blockScale, int sw, int sh) {
        if (blockScale <= 0.8) return;
        Set<Long> seen = new HashSet<>();
        TownyMapConfig config = TownyMapMod.getConfig();
        int groupCount = visibleGroupCount(config);
        for (int i = 0; i < groupCount; i++) {
            SelectionState group = GROUPS.get(i);
            for (long key : group.chunks) {
                if (!seen.add(key)) continue;
                int count = groupCount(key);
                if (count <= 1) continue;
                int centerX = toScreenX(chunkX(key) * CHUNK_SIZE + CHUNK_SIZE / 2.0, cameraX, blockScale, sw);
                int centerY = toScreenY(chunkZ(key) * CHUNK_SIZE + CHUNK_SIZE / 2.0, cameraZ, blockScale, sh);
                int startX = centerX - (count * 4 - 1) / 2;
                int offset = 0;
                for (int groupIndex = 0; groupIndex < groupCount; groupIndex++) {
                    if (!GROUPS.get(groupIndex).chunks.contains(key)) continue;
                    int x = startX + offset * 4;
                    ctx.fill(x, centerY - 2, x + 3, centerY + 2, argb(0xF0, GROUP_RGB[groupIndex]));
                    offset++;
                }
            }
        }
    }

    private static int groupCount(long key) {
        int count = 0;
        TownyMapConfig config = TownyMapMod.getConfig();
        int groupCount = visibleGroupCount(config);
        for (int i = 0; i < groupCount; i++) {
            if (GROUPS.get(i).chunks.contains(key)) count++;
        }
        return count;
    }

    private static void applyDragAction(SelectionState selection, long key) {
        boolean changed = rightDragSelecting ? selection.chunks.add(key) : selection.chunks.remove(key);
        if (changed) {
            if (selection.chunks.isEmpty() && !rightDragSelecting && selection.hadChunks) {
                activeGroupEmptiedByRemoval = true;
            } else if (!selection.chunks.isEmpty()) {
                selection.hadChunks = true;
            }
            selection.dirty = true;
            persistDirty = true;
            maybePersistSelection();
        }
    }

    private static void applyDragPath(SelectionState selection, long fromKey, long toKey) {
        if (fromKey == Long.MIN_VALUE) {
            applyDragAction(selection, toKey);
            return;
        }

        int x0 = chunkX(fromKey);
        int z0 = chunkZ(fromKey);
        int x1 = chunkX(toKey);
        int z1 = chunkZ(toKey);
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int stepX = x0 < x1 ? 1 : -1;
        int stepZ = z0 < z1 ? 1 : -1;
        int error = dx - dz;

        while (true) {
            applyDragAction(selection, key(x0, z0));
            if (x0 == x1 && z0 == z1) return;

            int doubledError = error * 2;
            if (doubledError > -dz) {
                error -= dz;
                x0 += stepX;
            }
            if (doubledError < dx) {
                error += dx;
                z0 += stepZ;
            }
        }
    }

    private static void maybePersistSelection() {
        long now = System.currentTimeMillis();
        if (now - lastPersistMs < PERSIST_INTERVAL_MS) return;
        persistSelectionNow();
    }

    private static void persistSelectionNow() {
        lastPersistMs = System.currentTimeMillis();
        persistDirty = false;
        TownyMapMod.saveChunkCounterState(selectedChunks(), selectedGroups(), activeGroup);
    }

    private static SelectionState activeSelection(TownyMapConfig config) {
        ensureGroups();
        if (config != null) {
            config.chunkCounterMode = MODE_MULTI;
            activeGroup = normalizedActiveGroup(config);
            return GROUPS.get(activeGroup);
        }
        return GROUPS.get(Math.max(0, Math.min(MAX_GROUPS - 1, activeGroup)));
    }

    private static int normalizedActiveGroup(TownyMapConfig config) {
        if (config == null) return activeGroup;
        int group = Math.max(0, Math.min(visibleGroupCount(config) - 1, config.activeChunkCounterGroup));
        activeGroup = group;
        return group;
    }

    private static void ensureGroups() {
        while (GROUPS.size() < MAX_GROUPS) {
            GROUPS.add(new SelectionState(GROUP_RGB[GROUPS.size()]));
        }
    }

    private static void compactEmptyGroups(TownyMapConfig config, boolean keepActiveEmpty) {
        if (config == null) return;
        config.chunkCounterMode = MODE_MULTI;
        ensureGroups();

        int oldCount = visibleGroupCount(config);
        int oldActive = Math.max(0, Math.min(oldCount - 1, config.activeChunkCounterGroup));
        ArrayList<List<Long>> kept = new ArrayList<>(oldCount);
        int newActive = -1;
        int nonEmptyBeforeOrAtActive = 0;

        for (int i = 0; i < oldCount; i++) {
            SelectionState state = GROUPS.get(i);
            boolean keep = !state.chunks.isEmpty() || (keepActiveEmpty && i == oldActive);
            if (!state.chunks.isEmpty() && i <= oldActive) {
                nonEmptyBeforeOrAtActive++;
            }
            if (!keep) continue;
            if (i == oldActive) newActive = kept.size();
            kept.add(new ArrayList<>(state.chunks));
        }

        if (kept.isEmpty()) {
            kept.add(new ArrayList<>());
            newActive = 0;
        } else if (newActive < 0) {
            newActive = Math.max(0, Math.min(kept.size() - 1, nonEmptyBeforeOrAtActive));
        }

        boolean changed = kept.size() != oldCount || newActive != oldActive;
        for (int i = 0; i < kept.size(); i++) {
            SelectionState state = GROUPS.get(i);
            if (!state.chunks.equals(new LinkedHashSet<>(kept.get(i)))) {
                changed = true;
            }
            state.set(kept.get(i));
        }
        for (int i = kept.size(); i < oldCount; i++) {
            if (!GROUPS.get(i).chunks.isEmpty()) changed = true;
            GROUPS.get(i).clear(false);
        }

        config.chunkCounterGroupCount = Math.max(1, Math.min(MAX_GROUPS, kept.size()));
        activeGroup = Math.max(0, Math.min(config.chunkCounterGroupCount - 1, newActive));
        config.activeChunkCounterGroup = activeGroup;
        if (changed) persistDirty = true;
    }

    private static void drawChunk(GuiGraphicsExtractor ctx, int chunkX, int chunkZ,
                                  double cameraX, double cameraZ, double blockScale,
                                  int sw, int sh, int fill, int border, boolean outline) {
        int x1 = toScreenX(chunkX * CHUNK_SIZE, cameraX, blockScale, sw);
        int y1 = toScreenY(chunkZ * CHUNK_SIZE, cameraZ, blockScale, sh);
        int x2 = toScreenX((chunkX + 1) * CHUNK_SIZE, cameraX, blockScale, sw);
        int y2 = toScreenY((chunkZ + 1) * CHUNK_SIZE, cameraZ, blockScale, sh);

        int left = Math.min(x1, x2);
        int right = Math.max(x1, x2);
        int top = Math.min(y1, y2);
        int bottom = Math.max(y1, y2);
        if (right < 0 || left > sw || bottom < 0 || top > sh) return;
        if (right - left < 2 || bottom - top < 2) return;

        ctx.fill(left, top, right, bottom, fill);
        if (!outline) return;
        ctx.fill(left, top, right, top + 1, border);
        ctx.fill(left, bottom - 1, right, bottom, border);
        ctx.fill(left, top, left + 1, bottom, border);
        ctx.fill(right - 1, top, right, bottom, border);
    }

    private static void drawEdge(GuiGraphicsExtractor ctx, int chunkX, int chunkZ, int side,
                                 double cameraX, double cameraZ, double blockScale,
                                 int sw, int sh, int color) {
        int left = toScreenX(chunkX * CHUNK_SIZE, cameraX, blockScale, sw);
        int top = toScreenY(chunkZ * CHUNK_SIZE, cameraZ, blockScale, sh);
        int right = toScreenX((chunkX + 1) * CHUNK_SIZE, cameraX, blockScale, sw);
        int bottom = toScreenY((chunkZ + 1) * CHUNK_SIZE, cameraZ, blockScale, sh);
        int x1 = Math.min(left, right);
        int x2 = Math.max(left, right);
        int y1 = Math.min(top, bottom);
        int y2 = Math.max(top, bottom);
        if (x2 < 0 || x1 > sw || y2 < 0 || y1 > sh) return;
        if (x2 - x1 < 2 || y2 - y1 < 2) return;
        x1 = Math.max(0, x1);
        x2 = Math.min(sw, x2);
        y1 = Math.max(0, y1);
        y2 = Math.min(sh, y2);
        switch (side) {
            case 0 -> ctx.fill(x1, y1, x2, Math.min(y2, y1 + 1), color);
            case 1 -> ctx.fill(Math.max(x1, x2 - 1), y1, x2, y2, color);
            case 2 -> ctx.fill(x1, Math.max(y1, y2 - 1), x2, y2, color);
            case 3 -> ctx.fill(x1, y1, Math.min(x2, x1 + 1), y2, color);
            default -> {
            }
        }
    }

    private static int toScreenX(double worldX, double cameraX, double scale, int sw) {
        return sw / 2 + (int) Math.round((worldX - cameraX) * scale);
    }

    private static int toScreenY(double worldZ, double cameraZ, double scale, int sh) {
        return sh / 2 + (int) Math.round((worldZ - cameraZ) * scale);
    }

    private static double screenX(double worldX, double cameraX, double scale, int sw) {
        return sw / 2.0 + (worldX - cameraX) * scale;
    }

    private static double screenY(double worldZ, double cameraZ, double scale, int sh) {
        return sh / 2.0 + (worldZ - cameraZ) * scale;
    }

    private static int floorToChunk(double blockCoord) {
        return Math.floorDiv((int) Math.floor(blockCoord), CHUNK_SIZE);
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private static int chunkX(long key) {
        return (int) (key >> 32);
    }

    private static int chunkZ(long key) {
        return (int) key;
    }

    private static int argb(int alpha, int rgb) {
        return ((alpha & 0xFF) << 24) | (rgb & 0x00FFFFFF);
    }

    private static final class SelectionState {
        private final int rgb;
        private final Set<Long> chunks = new LinkedHashSet<>();
        private List<Edge> edges = List.of();
        private List<Component> components = List.of();
        private List<LowZoomRect> lowZoomRects = List.of();
        private boolean dirty = true;
        private boolean hadChunks;

        private SelectionState(int rgb) {
            this.rgb = rgb;
        }

        private void set(List<Long> keys) {
            chunks.clear();
            if (keys != null) {
                for (Long key : keys) {
                    if (key != null) chunks.add(key);
                }
            }
            hadChunks = !chunks.isEmpty();
            dirty = true;
        }

        private void clear(boolean markDirty) {
            chunks.clear();
            edges = List.of();
            components = List.of();
            lowZoomRects = List.of();
            hadChunks = false;
            dirty = markDirty;
        }

        private void ensureBuilt() {
            if (!dirty) return;
            rebuildEdgesAndComponents(this);
            dirty = false;
        }
    }

    private static void rebuildEdgesAndComponents(SelectionState selection) {
        ArrayList<Edge> edges = new ArrayList<>(selection.chunks.size() * 2);
        for (long key : selection.chunks) {
            int chunkX = chunkX(key);
            int chunkZ = chunkZ(key);
            if (!selection.chunks.contains(key(chunkX, chunkZ - 1))) edges.add(new Edge(chunkX, chunkZ, 0));
            if (!selection.chunks.contains(key(chunkX + 1, chunkZ))) edges.add(new Edge(chunkX, chunkZ, 1));
            if (!selection.chunks.contains(key(chunkX, chunkZ + 1))) edges.add(new Edge(chunkX, chunkZ, 2));
            if (!selection.chunks.contains(key(chunkX - 1, chunkZ))) edges.add(new Edge(chunkX, chunkZ, 3));
        }
        selection.edges = List.copyOf(edges);
        selection.components = buildComponents(selection.chunks);
        selection.lowZoomRects = buildLowZoomRects(selection.chunks);
    }

    private static List<LowZoomRect> buildLowZoomRects(Set<Long> chunks) {
        if (chunks.isEmpty()) return List.of();
        TreeMap<Integer, List<Integer>> byZ = new TreeMap<>();
        for (long key : chunks) {
            byZ.computeIfAbsent(chunkZ(key), ignored -> new ArrayList<>()).add(chunkX(key));
        }

        ArrayList<LowZoomRect> rects = new ArrayList<>();
        Map<Long, MutableRect> active = new HashMap<>();
        Integer previousZ = null;
        for (Map.Entry<Integer, List<Integer>> entry : byZ.entrySet()) {
            int z = entry.getKey();
            if (previousZ == null || z != previousZ + 1) {
                flushRects(rects, active);
            }

            Collections.sort(entry.getValue());
            Map<Long, MutableRect> next = new HashMap<>();
            int runStart = Integer.MIN_VALUE;
            int previousX = Integer.MIN_VALUE;
            for (int x : entry.getValue()) {
                if (runStart != Integer.MIN_VALUE && x == previousX) continue;
                if (runStart == Integer.MIN_VALUE) {
                    runStart = x;
                    previousX = x;
                } else if (x == previousX + 1) {
                    previousX = x;
                } else {
                    continueLowZoomRun(active, next, runStart, previousX, z);
                    runStart = x;
                    previousX = x;
                }
            }
            if (runStart != Integer.MIN_VALUE) {
                continueLowZoomRun(active, next, runStart, previousX, z);
            }

            flushRects(rects, active);
            active = next;
            previousZ = z;
        }
        flushRects(rects, active);
        return List.copyOf(rects);
    }

    private static void continueLowZoomRun(Map<Long, MutableRect> active, Map<Long, MutableRect> next,
                                           int startX, int endX, int z) {
        long key = runKey(startX, endX);
        MutableRect rect = active.remove(key);
        if (rect == null) {
            rect = new MutableRect(startX, z, endX, z);
        } else {
            rect.maxChunkZ = z;
        }
        next.put(key, rect);
    }

    private static void flushRects(List<LowZoomRect> rects, Map<Long, MutableRect> active) {
        if (active.isEmpty()) return;
        for (MutableRect rect : active.values()) {
            rects.add(new LowZoomRect(rect.minChunkX, rect.minChunkZ, rect.maxChunkX, rect.maxChunkZ));
        }
        active.clear();
    }

    private static long runKey(int startX, int endX) {
        return ((long) startX << 32) ^ (endX & 0xFFFFFFFFL);
    }

    private static List<Component> buildComponents(Set<Long> chunks) {
        if (chunks.isEmpty()) return List.of();
        ArrayList<Component> components = new ArrayList<>();
        HashSet<Long> remaining = new HashSet<>(chunks);
        Queue<Long> queue = new ArrayDeque<>();
        while (!remaining.isEmpty()) {
            long first = remaining.iterator().next();
            remaining.remove(first);
            queue.add(first);
            int count = 0;
            double sumX = 0.0;
            double sumZ = 0.0;
            int minChunkX = Integer.MAX_VALUE;
            int minChunkZ = Integer.MAX_VALUE;
            int maxChunkX = Integer.MIN_VALUE;
            int maxChunkZ = Integer.MIN_VALUE;

            while (!queue.isEmpty()) {
                long key = queue.remove();
                int cx = chunkX(key);
                int cz = chunkZ(key);
                count++;
                sumX += cx * CHUNK_SIZE + CHUNK_SIZE / 2.0;
                sumZ += cz * CHUNK_SIZE + CHUNK_SIZE / 2.0;
                minChunkX = Math.min(minChunkX, cx);
                minChunkZ = Math.min(minChunkZ, cz);
                maxChunkX = Math.max(maxChunkX, cx);
                maxChunkZ = Math.max(maxChunkZ, cz);
                enqueueIfPresent(remaining, queue, cx, cz - 1);
                enqueueIfPresent(remaining, queue, cx + 1, cz);
                enqueueIfPresent(remaining, queue, cx, cz + 1);
                enqueueIfPresent(remaining, queue, cx - 1, cz);
            }

            components.add(new Component(sumX / count, sumZ / count, count,
                    minChunkX, minChunkZ, maxChunkX, maxChunkZ));
        }
        return List.copyOf(components);
    }

    private static void enqueueIfPresent(Set<Long> remaining, Queue<Long> queue, int chunkX, int chunkZ) {
        long key = key(chunkX, chunkZ);
        if (remaining.remove(key)) queue.add(key);
    }

    private record Edge(int chunkX, int chunkZ, int side) {}

    private record LowZoomRect(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {}

    private record Component(double centerX, double centerZ, int count,
                             int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {}

    private static final class MutableRect {
        private final int minChunkX;
        private final int minChunkZ;
        private final int maxChunkX;
        private int maxChunkZ;

        private MutableRect(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
            this.minChunkX = minChunkX;
            this.minChunkZ = minChunkZ;
            this.maxChunkX = maxChunkX;
            this.maxChunkZ = maxChunkZ;
        }
    }
}
