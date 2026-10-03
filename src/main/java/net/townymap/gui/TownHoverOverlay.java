package net.townymap.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.townymap.TownyMapMod;
import net.townymap.model.TownData;
import net.townymap.model.TownPopupData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TownHoverOverlay {

    private static final int PADDING = 8;
    private static final int LINE_HEIGHT = 12;
    private static final int BG = 0xD8101010;
    private static final int BORDER = 0xFF333333;
    private static final int INDEX_CELL_SIZE = 2048;

    // Cache: skip the full polygon scan when the world block under the cursor hasn't changed.
    private static int   lastWX   = Integer.MIN_VALUE;
    private static int   lastWZ   = Integer.MIN_VALUE;
    private static TownData cachedHit = null;
    private static List<TownData> indexedTownSource = List.of();
    private static Map<Long, List<TownData>> spatialIndex = Map.of();

    private TownHoverOverlay() {}

    public static void render(GuiGraphicsExtractor ctx, int mouseX, int mouseY, int sw, int sh,
                              TownData town, TownPopupData details, String mapMayor, String mapNation) {
        Minecraft mc = Minecraft.getInstance();
        Font tr = mc.font;

        List<String> lines = new ArrayList<>(4);
        // Title mirrors the right-click popup: "Town (Nation)" / "Town (Capital of Nation)"; bare town if none.
        String nation = nationLabel(details, mapNation);
        String title = "§f§l" + town.name();
        if (!nation.isBlank()) title += " §7§l(" + nation + ")";
        lines.add(title);
        lines.add("§7Mayor: §f" + mayor(details, mapMayor));
        lines.add("§7Chunks: §f" + chunks(town, details));
        lines.add("§8Right-click for more info");

        int maxW = 0;
        for (String line : lines) maxW = Math.max(maxW, tr.width(line));
        int boxW = maxW + PADDING * 2;
        int boxH = LINE_HEIGHT * lines.size() + PADDING * 2;
        int x = Math.min(mouseX + 12, sw - boxW - 4);
        int y = Math.min(mouseY + 12, sh - boxH - 4);
        if (x < 4) x = 4;
        if (y < 4) y = 4;

        boolean scaled = UiScale.active();
        if (scaled) UiScale.push(ctx, x, y);   // non-interactive tooltip: just shrink it around its corner
        ctx.fill(x - 1, y - 1, x + boxW + 1, y + boxH + 1, BORDER);
        ctx.fill(x, y, x + boxW, y + boxH, BG);
        int textY = y + PADDING;
        for (int i = 0; i < lines.size(); i++) {
            ctx.text(tr, lines.get(i), x + PADDING, textY + LINE_HEIGHT * i, 0xFFFFFFFF, true);
        }
        if (scaled) UiScale.pop(ctx);
    }

    public static TownData townAt(double worldX, double worldZ, List<TownData> towns) {
        if (towns != indexedTownSource) {
            rebuildSpatialIndex(towns);
        }

        // Block-resolution cache: world coordinates only need re-testing when the
        // cursor moves to a different block. This avoids O(n_towns) polygon scans
        // every frame while the mouse is stationary (including during map panning).
        int wx = (int) Math.floor(worldX);
        int wz = (int) Math.floor(worldZ);
        if (wx == lastWX && wz == lastWZ) return cachedHit;
        lastWX = wx;
        lastWZ = wz;

        TownData hit = null;
        List<TownData> candidates = spatialIndex.get(indexCellKey(floorToIndexCell(worldX), floorToIndexCell(worldZ)));
        if (candidates == null || candidates.isEmpty()) {
            cachedHit = null;
            return null;
        }
        for (TownData town : candidates) {
            if (!town.intersectsWorld(worldX, worldX, worldZ, worldZ)) continue;
            if (contains(town, worldX, worldZ)) { hit = town; break; }
        }
        cachedHit = hit;
        return hit;
    }

    private static void rebuildSpatialIndex(List<TownData> towns) {
        Map<Long, List<TownData>> built = new HashMap<>();
        Map<Long, ArrayList<TownData>> mutable = new HashMap<>();
        for (TownData town : towns) {
            int minCellX = floorToIndexCell(town.minX());
            int maxCellX = floorToIndexCell(town.maxX());
            int minCellZ = floorToIndexCell(town.minZ());
            int maxCellZ = floorToIndexCell(town.maxZ());
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                    mutable.computeIfAbsent(indexCellKey(cellX, cellZ), ignored -> new ArrayList<>())
                            .add(town);
                }
            }
        }
        for (Map.Entry<Long, ArrayList<TownData>> entry : mutable.entrySet()) {
            built.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        spatialIndex = Map.copyOf(built);
        indexedTownSource = towns;
        lastWX = Integer.MIN_VALUE;
        lastWZ = Integer.MIN_VALUE;
        cachedHit = null;
    }

    private static int floorToIndexCell(double worldCoord) {
        return (int) Math.floor(worldCoord / INDEX_CELL_SIZE);
    }

    private static long indexCellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
    }

    private static boolean contains(TownData town, double worldX, double worldZ) {
        boolean inside = false;
        for (int[][] ring : town.polygonRings()) {
            if (ring.length < 3) continue;
            if (containsRing(ring, worldX, worldZ)) inside = !inside;
        }
        return inside;
    }

    private static boolean containsRing(int[][] ring, double worldX, double worldZ) {
        boolean inside = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            int[] a = ring[i];
            int[] b = ring[j];
            if (a.length < 2 || b.length < 2) continue;
            boolean crosses = (a[1] > worldZ) != (b[1] > worldZ);
            if (crosses) {
                double x = (double) (b[0] - a[0]) * (worldZ - a[1]) / (b[1] - a[1]) + a[0];
                if (worldX < x) inside = !inside;
            }
        }
        return inside;
    }

    private static String mayor(TownPopupData details, String mapMayor) {
        String apiMayor = (details == null || details == TownPopupData.WILDERNESS) ? null : details.mayor();
        if (apiMayor != null && !apiMayor.isBlank()) return apiMayor;
        // The squaremap markers already carry the mayor, so show it instantly while the API detail loads.
        if (mapMayor != null && !mapMayor.isBlank()) return mapMayor;
        return "...";
    }

    private static String nationLabel(TownPopupData details, String mapNation) {
        // squaremap tooltip carries the nation (with the "Capital of" prefix) directly, so it's instant and
        // capital-aware — prefer it, then fall back to the API detail's plain nation name while that loads.
        if (mapNation != null && !mapNation.isBlank()) return mapNation;
        if (details != null && details != TownPopupData.WILDERNESS && !details.nationName().isBlank()) {
            return details.nationName();
        }
        return "";   // nationless town → caller omits the parenthetical
    }

    private static int chunks(TownData town, TownPopupData details) {
        // Off Earth the API's numChunks is the town's total across all worlds, so it would report the
        // whole Earth town for a hovered outpost. The polygon under the cursor is the honest answer.
        if (!TownyMapMod.viewingEarth()) return town.approximateChunks();
        if (details != null && details != TownPopupData.WILDERNESS && details.numChunks() > 0) {
            return details.numChunks();
        }
        return town.approximateChunks();
    }
}
