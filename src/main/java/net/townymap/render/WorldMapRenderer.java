package net.townymap.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.townymap.TownyMapConfig;
import net.townymap.TownyMapMod;
import net.townymap.api.SquaremapApiClient;
import net.townymap.model.EarthMcNationData;
import net.townymap.model.EarthMcPlayerData;
import net.townymap.model.OptimisticClaimChunk;
import net.townymap.model.PlayerMarker;
import net.townymap.model.TownData;
import net.townymap.model.TownPopupData;
import java.util.Arrays;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;

/**
 * Draws Towny town borders and online-player dots over Xaero's WorldMap.
 *
 * screenX = sw/2 + (worldX - cameraX) * scale
 * screenY = sh/2 + (worldZ - cameraZ) * scale
 * where scale = GUI pixels per world block.
 */
public class WorldMapRenderer {

    private static final int DOT_HALF = 2;
    private static final double TOWN_FILL_MIN_SCALE = 0.035;
    private static final double MIN_TOWN_SCREEN_PIXELS = 0.0;
    private static final int CHUNK_SIZE = 16;
    private static final int HOVER_CHUNK_FILL = 0x22FFFFFF;
    private static final int HOVER_CHUNK_BORDER = 0xD8FFFFFF;
    private static final double MIN_CHUNK_GRID_SPACING = 4.0;
    private static final int TOWN_INDEX_CELL_SIZE = 2048;
    private static final long STATUS_RGB_CYCLE_MS = 5000L;
    private static final int TINY_TOWN_SCREEN_PIXELS = 2;

    // ── Outline level-of-detail ───────────────────────────────────────────────
    // Each ring stores its outline at several resolutions, built by snapping the
    // (chunk-aligned) vertices to a coarser grid and merging the result.  Coarser
    // grids drop small features → fewer connected line segments when zoomed out.
    //
    // LOD_GRID[k] = snap grid in blocks for level k.  Level 0 is the raw outline
    // (Towny data is already on the 16-block chunk grid, so grid 16 == raw).
    // LOD_MIN_SCALE[k] = use level k while blockScale (px/block) ≥ this value.
    //   Chosen so the snap grid maps to roughly 2–4 px on screen at each level,
    //   i.e. features smaller than a few pixels are removed.
    private static final int[]    LOD_GRID      = { 16,   32,    80,    192   };
    private static final double[] LOD_MIN_SCALE = { 0.125, 0.05, 0.022, 0.0   };

    private final TownyMapConfig     config;
    private final SquaremapApiClient api;
    private final SquaremapTileRenderer squaremapTiles;
    private final BorderOverlayRenderer borderOverlay;
    private final ExecutorService townCacheExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TownyMap-TownRenderCache");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean townCacheBuildRunning = new AtomicBoolean(false);
    private final List<RenderTown> visibleTownScratch = new ArrayList<>(256);
    private final Set<RenderTown> visibleTownSeen = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<RenderTown> detailQuerySeen = Collections.newSetFromMap(new IdentityHashMap<>());
    private volatile TownRenderCache townRenderCache = TownRenderCache.empty();
    private volatile List<TownData> townCacheRequestedSource = List.of();
    private final Set<String> favoriteTownKeys = new HashSet<>();
    private int favoriteTownCount = -1;

    public WorldMapRenderer(TownyMapConfig config, SquaremapApiClient api) {
        this.config = config;
        this.api    = api;
        this.squaremapTiles = new SquaremapTileRenderer(config);
        this.borderOverlay = new BorderOverlayRenderer(config);
    }

    public void invalidateTownCaches() {
        visibleTownScratch.clear();
        visibleTownSeen.clear();
        townRenderCache = TownRenderCache.empty();
        townCacheRequestedSource = List.of();
    }

    public void render(DrawContext ctx,
                       double cameraX, double cameraZ, double blockScale,
                       int sw, int sh,
                       Map<String, TownPopupData> townDetails,
                       Map<String, EarthMcPlayerData> playerDetails,
                       Map<String, EarthMcNationData> nationDetails) {
        if (blockScale <= 0) return;
        double worldLeft   = cameraX - sw / 2.0 / blockScale;
        double worldRight  = cameraX + sw / 2.0 / blockScale;
        double worldTop    = cameraZ - sh / 2.0 / blockScale;
        double worldBottom = cameraZ + sh / 2.0 / blockScale;
        List<RenderTown> visibleTowns = visibleTowns(blockScale, worldLeft, worldRight, worldTop, worldBottom);
        refreshFavoriteTownKeys();

        borderOverlay.render(ctx, cameraX, cameraZ, blockScale, sw, sh,
                worldLeft, worldRight, worldTop, worldBottom);

        renderChunkGrid(ctx, cameraX, cameraZ, blockScale, sw, sh,
                worldLeft, worldRight, worldTop, worldBottom);
        if (config.townsEnabled) {
            // Single pass: fill + outline per town — halves bounding-box computations
            // vs. the old separate fill-pass / outline-pass approach.
            renderTowns(ctx, cameraX, cameraZ, blockScale, sw, sh, visibleTowns, townDetails);
            renderOptimisticClaimChunks(ctx, cameraX, cameraZ, blockScale, sw, sh,
                    worldLeft, worldRight, worldTop, worldBottom);
        }
        renderNationCapitalStars(ctx, cameraX, cameraZ, blockScale, sw, sh,
                worldLeft, worldRight, worldTop, worldBottom, nationDetails);
        if (config.playersEnabled) renderPlayers(ctx, cameraX, cameraZ, blockScale, sw, sh, playerDetails);
    }

    public void renderSquaremapBackground(DrawContext ctx,
                                          double cameraX, double cameraZ, double blockScale,
                                          int sw, int sh, boolean moving) {
        if (!config.squaremapBackgroundEnabled || blockScale <= 0) return;
        ctx.fill(0, 0, sw, sh, 0xFF101418);

        double worldLeft   = cameraX - sw / 2.0 / blockScale;
        double worldRight  = cameraX + sw / 2.0 / blockScale;
        double worldTop    = cameraZ - sh / 2.0 / blockScale;
        double worldBottom = cameraZ + sh / 2.0 / blockScale;

        squaremapTiles.render(ctx, cameraX, cameraZ, blockScale, sw, sh,
                worldLeft, worldRight, worldTop, worldBottom, moving);
        ctx.fill(0, 0, sw, sh, 0x38000000);
    }

    public void renderSquaremapViewport(DrawContext ctx,
                                        double cameraX, double cameraZ, double blockScale,
                                        int sw, int sh, boolean moving) {
        if (!config.squaremapBackgroundEnabled || blockScale <= 0 || sw <= 0 || sh <= 0) return;

        double worldLeft = cameraX - sw / 2.0 / blockScale;
        double worldRight = cameraX + sw / 2.0 / blockScale;
        double worldTop = cameraZ - sh / 2.0 / blockScale;
        double worldBottom = cameraZ + sh / 2.0 / blockScale;

        squaremapTiles.render(ctx, cameraX, cameraZ, blockScale, sw, sh,
                worldLeft, worldRight, worldTop, worldBottom, moving);
    }

    public void renderSquaremapMinimapViewport(DrawContext ctx,
                                               double cameraX, double cameraZ, double blockScale,
                                               int sw, int sh, boolean moving) {
        if (!config.squaremapBackgroundEnabled || blockScale <= 0 || sw <= 0 || sh <= 0) return;

        double worldLeft = cameraX - sw / 2.0 / blockScale;
        double worldRight = cameraX + sw / 2.0 / blockScale;
        double worldTop = cameraZ - sh / 2.0 / blockScale;
        double worldBottom = cameraZ + sh / 2.0 / blockScale;

        squaremapTiles.renderMinimap(ctx, cameraX, cameraZ, blockScale, sw, sh,
                worldLeft, worldRight, worldTop, worldBottom, moving);
    }

    public boolean isSquaremapLoading() {
        return squaremapTiles.isLoading();
    }

    public boolean isBorderLoading() {
        return borderOverlay.isLoading();
    }

    public void renderHoveredChunk(DrawContext ctx,
                                   double cameraX, double cameraZ, double blockScale,
                                   int sw, int sh,
                                   double mouseWorldX, double mouseWorldZ) {
        if (!config.squaremapBackgroundEnabled || blockScale <= 0) return;
        double spacing = CHUNK_SIZE * blockScale;
        if (spacing < 3.0) return;

        int chunkX = floorToChunk(mouseWorldX);
        int chunkZ = floorToChunk(mouseWorldZ);
        int x1 = toScreenX(chunkX * CHUNK_SIZE, cameraX, blockScale, sw);
        int y1 = toScreenY(chunkZ * CHUNK_SIZE, cameraZ, blockScale, sh);
        int x2 = toScreenX((chunkX + 1) * CHUNK_SIZE, cameraX, blockScale, sw);
        int y2 = toScreenY((chunkZ + 1) * CHUNK_SIZE, cameraZ, blockScale, sh);

        int left = Math.min(x1, x2);
        int right = Math.max(x1, x2);
        int top = Math.min(y1, y2);
        int bottom = Math.max(y1, y2);
        if (right < 0 || left > sw || bottom < 0 || top > sh) return;

        left = Math.max(0, left);
        right = Math.min(sw, right);
        top = Math.max(0, top);
        bottom = Math.min(sh, bottom);
        if (right - left < 2 || bottom - top < 2) return;

        ctx.fill(left, top, right, bottom, HOVER_CHUNK_FILL);
        ctx.fill(left, top, right, top + 1, HOVER_CHUNK_BORDER);
        ctx.fill(left, bottom - 1, right, bottom, HOVER_CHUNK_BORDER);
        ctx.fill(left, top, left + 1, bottom, HOVER_CHUNK_BORDER);
        ctx.fill(right - 1, top, right, bottom, HOVER_CHUNK_BORDER);
    }

    // ── Coordinate helpers ────────────────────────────────────────────────────

    private static int toScreenX(double worldX, double camX, double scale, int sw) {
        return sw / 2 + (int) Math.round((worldX - camX) * scale);
    }

    private static int toScreenY(double worldZ, double camZ, double scale, int sh) {
        return sh / 2 + (int) Math.round((worldZ - camZ) * scale);
    }

    // ── Town rendering ───────────────────────────────────────────────────────

    private void renderChunkGrid(DrawContext ctx,
                                 double cameraX, double cameraZ, double blockScale,
                                 int sw, int sh,
                                 double worldLeft, double worldRight,
                                 double worldTop, double worldBottom) {
        if (!config.chunkGridEnabled) return;
        double spacing = CHUNK_SIZE * blockScale;
        if (spacing < MIN_CHUNK_GRID_SPACING) return;

        int minChunkX = floorToChunk(worldLeft) - 1;
        int maxChunkX = floorToChunk(worldRight) + 1;
        int minChunkZ = floorToChunk(worldTop) - 1;
        int maxChunkZ = floorToChunk(worldBottom) + 1;
        int color = 0xCC000000;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            int x = toScreenX(chunkX * CHUNK_SIZE, cameraX, blockScale, sw);
            if (x >= 0 && x < sw) ctx.fill(x, 0, x + 1, sh, color);
        }
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            int y = toScreenY(chunkZ * CHUNK_SIZE, cameraZ, blockScale, sh);
            if (y >= 0 && y < sh) ctx.fill(0, y, sw, y + 1, color);
        }
    }

    private static int floorToChunk(double worldCoord) {
        return (int) Math.floor(worldCoord / CHUNK_SIZE);
    }

    private void renderOptimisticClaimChunks(DrawContext ctx,
                                             double cameraX, double cameraZ, double blockScale,
                                             int sw, int sh,
                                             double worldLeft, double worldRight,
                                             double worldTop, double worldBottom) {
        for (OptimisticClaimChunk chunk : TownyMapMod.optimisticClaimChunks()) {
            int blockX = chunk.blockX();
            int blockZ = chunk.blockZ();
            if (blockX + CHUNK_SIZE < worldLeft || blockX > worldRight
                    || blockZ + CHUNK_SIZE < worldTop || blockZ > worldBottom) continue;

            int x1 = toScreenX(blockX, cameraX, blockScale, sw);
            int y1 = toScreenY(blockZ, cameraZ, blockScale, sh);
            int x2 = toScreenX(blockX + CHUNK_SIZE, cameraX, blockScale, sw);
            int y2 = toScreenY(blockZ + CHUNK_SIZE, cameraZ, blockScale, sh);

            ctx.fill(Math.min(x1, x2), Math.min(y1, y2),
                    Math.max(x1, x2), Math.max(y1, y2), chunk.fillColor());
            int left = Math.min(x1, x2);
            int right = Math.max(x1, x2);
            int top = Math.min(y1, y2);
            int bottom = Math.max(y1, y2);
            ctx.fill(left, top, right, top + 1, chunk.outlineColor());
            ctx.fill(left, bottom - 1, right, bottom, chunk.outlineColor());
            ctx.fill(left, top, left + 1, bottom, chunk.outlineColor());
            ctx.fill(right - 1, top, right, bottom, chunk.outlineColor());
        }
    }

    private void renderTowns(DrawContext ctx,
                             double cameraX, double cameraZ, double blockScale,
                             int sw, int sh,
                             List<RenderTown> visibleTowns,
                             Map<String, TownPopupData> townDetails) {
        int statusRgb = statusHighlightRgb();
        int fillColor0 = blockScale >= TOWN_FILL_MIN_SCALE ? 0xFF : 0;  // fill enabled?
        for (RenderTown town : visibleTowns) {
            int borderColor = town.data().argbColor(config.borderAlpha);
            int fillColor   = fillColor0 != 0 ? town.data().argbColor(config.fillAlpha) : 0;
            boolean favorite = isFavorite(town.name());

            TownPopupData details = townDetails.get(town.key());
            boolean statusHighlighted = false;
            if (details != null && config.townStatusOverlayMode > 0) {
                statusHighlighted = switch (config.townStatusOverlayMode) {
                    case 1 -> details.canOutsidersSpawn();
                    case 2 -> details.isOverClaimed();
                    case 3 -> details.isOpen();
                    case 4 -> details.isForSale();
                    case 5 -> !details.hasNation();
                    default -> false;
                };
            }

            for (RingGeometry ring : town.rings()) {
                // Base fill + outline in one call — bounding box computed once per ring
                renderRing(ctx, ring, borderColor, fillColor, cameraX, cameraZ, blockScale, sw, sh);
                if (statusHighlighted) {
                    renderRing(ctx, ring, 0xFF000000 | statusRgb, 0x44000000 | statusRgb,
                               cameraX, cameraZ, blockScale, sw, sh);
                }
                if (favorite) {
                    renderRing(ctx, ring, 0xFFFFE066, 0x22FFE066,
                               cameraX, cameraZ, blockScale, sw, sh);
                }
            }
        }
    }

    // ── Shared ring renderer ─────────────────────────────────────────────────
    //
    // Iterates the pre-merged H/V segment arrays directly — no allocation, no
    // matrix ops.  Segments whose endpoints map to the same screen pixel are
    // skipped (truly sub-pixel and invisible).
    //
    // Safety net: if every single merged segment was sub-pixel (happens only for
    // very small or highly irregular towns at extreme zoom-out), we fall back to
    // a 4-call bounding-box outline so the town is always visible.  This fallback
    // fires rarely and costs at most 4 draw calls when it does.

    private void renderRing(DrawContext ctx, RingGeometry ring,
                            int borderColor, int fillColor,
                            double cameraX, double cameraZ, double blockScale,
                            int sw, int sh) {
        // Town-level bounding-box cull
        int screenMinX = toScreenX(ring.minX(), cameraX, blockScale, sw);
        int screenMaxX = toScreenX(ring.maxX(), cameraX, blockScale, sw);
        int screenMinY = toScreenY(ring.minZ(), cameraZ, blockScale, sh);
        int screenMaxY = toScreenY(ring.maxZ(), cameraZ, blockScale, sh);
        if (screenMaxX < 0 || screenMinX > sw || screenMaxY < 0 || screenMinY > sh) return;

        int bbW = Math.abs(screenMaxX - screenMinX);
        int bbH = Math.abs(screenMaxY - screenMinY);

        // Tiny town: bounding box ≤ TINY pixels — single dot
        if (bbW <= TINY_TOWN_SCREEN_PIXELS && bbH <= TINY_TOWN_SCREEN_PIXELS) {
            int dotColor = (borderColor >>> 24) > 0 ? borderColor : fillColor;
            renderTinyTown(ctx, screenMinX, screenMinY, screenMaxX, screenMaxY, dotColor, sw, sh);
            return;
        }

        // Fill (pre-computed rects, gated by TOWN_FILL_MIN_SCALE in caller)
        if ((fillColor >>> 24) > 0 && bbW > 2 && bbH > 2) {
            renderCachedFill(ctx, ring, fillColor, cameraX, cameraZ, blockScale, sw, sh);
        }

        if ((borderColor >>> 24) == 0) return;

        // Pick the LOD whose snap grid maps to a few pixels at this zoom.
        // Higher zoom → finer detail; lower zoom → coarser, fewer segments.
        int lod = selectLod(blockScale);
        int[] xs = ring.lodX(lod), zs = ring.lodZ(lod);
        int n = xs.length;
        if (n < 2) {
            renderBoundingBoxBorder(ctx, screenMinX, screenMinY, screenMaxX, screenMaxY,
                                    borderColor, sw, sh);
            return;
        }

        // Draw the outline as ONE connected closed loop.  We walk vertices in
        // order and draw every on-screen edge, so visible corners always join.
        //
        // Per-edge trivial reject: if both endpoints are off the SAME side of the
        // screen the whole edge is invisible, so we skip its draw call but still
        // advance prevX/prevY — connectivity is preserved for everything visible.
        // (Without this, panning near a large town submits draw calls for all of
        // its off-screen edges every frame, which is what caused the pan stutter.)
        int prevX = toScreenX(xs[0], cameraX, blockScale, sw);
        int prevY = toScreenY(zs[0], cameraZ, blockScale, sh);
        for (int i = 1; i <= n; i++) {
            int idx = i == n ? 0 : i;   // close the loop on the final iteration
            int cx = toScreenX(xs[idx], cameraX, blockScale, sw);
            int cy = toScreenY(zs[idx], cameraZ, blockScale, sh);

            boolean offscreen = (prevX < 0 && cx < 0) || (prevX > sw && cx > sw)
                             || (prevY < 0 && cy < 0) || (prevY > sh && cy > sh);
            if (!offscreen) {
                if (prevY == cy) {
                    if (prevX != cx) ctx.drawHorizontalLine(Math.min(prevX, cx), Math.max(prevX, cx), cy, borderColor);
                    else ctx.fill(cx, cy, cx + 1, cy + 1, borderColor);   // 1px corner dot
                } else if (prevX == cx) {
                    ctx.drawVerticalLine(cx, Math.min(prevY, cy), Math.max(prevY, cy), borderColor);
                } else {
                    // Defensive (Towny data is axis-aligned, so this is unreachable):
                    // draw an L-bend to keep the loop connected without a diagonal.
                    ctx.drawHorizontalLine(Math.min(prevX, cx), Math.max(prevX, cx), prevY, borderColor);
                    ctx.drawVerticalLine(cx, Math.min(prevY, cy), Math.max(prevY, cy), borderColor);
                }
            }
            prevX = cx;
            prevY = cy;
        }
    }

    /** Largest LOD level (coarsest) whose min-scale threshold is satisfied. */
    private static int selectLod(double blockScale) {
        for (int k = 0; k < LOD_MIN_SCALE.length; k++) {
            if (blockScale >= LOD_MIN_SCALE[k]) return k;
        }
        return LOD_MIN_SCALE.length - 1;
    }

    /** Draws the four sides of the screen bounding box — always visible, 4 calls. */
    private static void renderBoundingBoxBorder(DrawContext ctx,
                                                int x1, int y1, int x2, int y2,
                                                int color, int sw, int sh) {
        int bx1 = Math.max(-1, Math.min(x1, x2));
        int bx2 = Math.min(sw,  Math.max(x1, x2));
        int by1 = Math.max(-1, Math.min(y1, y2));
        int by2 = Math.min(sh,  Math.max(y1, y2));
        if (bx1 >= bx2 || by1 >= by2) return;
        ctx.drawHorizontalLine(bx1, bx2, by1, color);
        ctx.drawHorizontalLine(bx1, bx2, by2, color);
        ctx.drawVerticalLine(bx1, by1, by2, color);
        ctx.drawVerticalLine(bx2, by1, by2, color);
    }

    private static void renderTinyTown(DrawContext ctx, int x1, int y1, int x2, int y2,
                                       int color, int sw, int sh) {
        if ((color >>> 24) == 0) return;
        int x = Math.max(0, Math.min(sw - 1, (x1 + x2) / 2));
        int y = Math.max(0, Math.min(sh - 1, (y1 + y2) / 2));
        ctx.fill(x, y, x + 1, y + 1, color);
    }

    private static void renderCachedFill(DrawContext ctx, RingGeometry ring, int color,
                                         double cameraX, double cameraZ, double blockScale,
                                         int sw, int sh) {
        // fillData is a flat int[] with 4 values per rect: [minX, minZ, maxX, maxZ, ...]
        int[] fd = ring.fillData();
        for (int i = 0, len = fd.length; i < len; i += 4) {
            int x1 = toScreenX(fd[i],     cameraX, blockScale, sw);
            int y1 = toScreenY(fd[i + 1], cameraZ, blockScale, sh);
            int x2 = toScreenX(fd[i + 2], cameraX, blockScale, sw);
            int y2 = toScreenY(fd[i + 3], cameraZ, blockScale, sh);
            int left   = Math.min(x1, x2), right  = Math.max(x1, x2);
            int top    = Math.min(y1, y2), bottom = Math.max(y1, y2);
            if (right <= 0 || left >= sw || bottom <= 0 || top >= sh) continue;
            if (right <= left || bottom <= top) continue;
            ctx.fill(left, top, right, bottom, color);
        }
    }

    private boolean isFavorite(String townName) {
        return favoriteTownKeys.contains(townName == null ? "" : townName.toLowerCase(Locale.ROOT));
    }

    private int statusHighlightRgb() {
        if (!config.statusHighlightRainbow) return config.statusHighlightColor & 0x00FFFFFF;
        double hue = (System.currentTimeMillis() % STATUS_RGB_CYCLE_MS) / (double) STATUS_RGB_CYCLE_MS;
        return hsvToRgb(hue, 0.78, 1.0);
    }

    private static int hsvToRgb(double hue, double saturation, double value) {
        double h = (hue - Math.floor(hue)) * 6.0;
        int sector = (int) Math.floor(h);
        double fraction = h - sector;
        double p = value * (1.0 - saturation);
        double q = value * (1.0 - fraction * saturation);
        double t = value * (1.0 - (1.0 - fraction) * saturation);
        double r, g, b;
        switch (sector) {
            case 0 -> { r = value; g = t; b = p; }
            case 1 -> { r = q; g = value; b = p; }
            case 2 -> { r = p; g = value; b = t; }
            case 3 -> { r = p; g = q; b = value; }
            case 4 -> { r = t; g = p; b = value; }
            default -> { r = value; g = p; b = q; }
        }
        return ((int) Math.round(r * 255.0) << 16)
                | ((int) Math.round(g * 255.0) << 8)
                | (int) Math.round(b * 255.0);
    }

    private void refreshFavoriteTownKeys() {
        if (favoriteTownCount == config.favoriteTowns.size()) return;
        favoriteTownKeys.clear();
        for (String favorite : config.favoriteTowns) {
            if (favorite != null && !favorite.isBlank()) {
                favoriteTownKeys.add(favorite.toLowerCase(Locale.ROOT));
            }
        }
        favoriteTownCount = config.favoriteTowns.size();
    }

    private List<RenderTown> visibleTowns(double blockScale,
                                          double worldLeft, double worldRight,
                                          double worldTop, double worldBottom) {
        TownRenderCache cache = townRenderCache();
        visibleTownScratch.clear();
        visibleTownSeen.clear();

        int minCellX = floorToIndexCell(worldLeft);
        int maxCellX = floorToIndexCell(worldRight);
        int minCellZ = floorToIndexCell(worldTop);
        int maxCellZ = floorToIndexCell(worldBottom);
        long cellCount = (long) (maxCellX - minCellX + 1) * (long) (maxCellZ - minCellZ + 1);
        if (cellCount > Math.max(1, cache.spatialIndex().size())) {
            for (RenderTown town : cache.allTowns()) {
                addVisibleTown(town, blockScale, worldLeft, worldRight, worldTop, worldBottom);
            }
            return visibleTownScratch;
        }

        for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                List<RenderTown> cellTowns = cache.spatialIndex().get(indexCellKey(cellX, cellZ));
                if (cellTowns == null) continue;
                for (RenderTown town : cellTowns) {
                    addVisibleTown(town, blockScale, worldLeft, worldRight, worldTop, worldBottom);
                }
            }
        }
        return visibleTownScratch;
    }

    private void addVisibleTown(RenderTown town, double blockScale,
                                double worldLeft, double worldRight,
                                double worldTop, double worldBottom) {
        if (!visibleTownSeen.add(town)) return;
        if (!town.intersectsWorld(worldLeft, worldRight, worldTop, worldBottom)) return;
        if (!largeEnoughOnScreen(town, blockScale, MIN_TOWN_SCREEN_PIXELS)) return;
        visibleTownScratch.add(town);
    }

    /**
     * Visits towns whose bounding box intersects the viewport, using the spatial
     * index instead of a full scan.  {@code action} receives each town's display
     * name and its precomputed lowercase key (no per-call string allocation) and
     * returns true if it issued a request; iteration stops after {@code limit}
     * such requests.  Called off the render path (every ~500 ms), on the client
     * thread, using its own dedup scratch so it never disturbs render state.
     */
    public void forEachVisibleTownDetail(double worldLeft, double worldRight,
                                         double worldTop, double worldBottom,
                                         int limit, BiPredicate<String, String> action) {
        TownRenderCache cache = townRenderCache();
        int issued = 0;

        int minCellX = floorToIndexCell(worldLeft);
        int maxCellX = floorToIndexCell(worldRight);
        int minCellZ = floorToIndexCell(worldTop);
        int maxCellZ = floorToIndexCell(worldBottom);
        long cellCount = (long) (maxCellX - minCellX + 1) * (long) (maxCellZ - minCellZ + 1);

        // Same heuristic as visibleTowns(): if the viewport spans more index cells
        // than the index has entries, a flat scan is cheaper than cell lookups.
        if (cellCount > Math.max(1, cache.spatialIndex().size())) {
            for (RenderTown town : cache.allTowns()) {
                if (!town.intersectsWorld(worldLeft, worldRight, worldTop, worldBottom)) continue;
                if (action.test(town.name(), town.key()) && ++issued >= limit) return;
            }
            return;
        }

        detailQuerySeen.clear();
        for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                List<RenderTown> cellTowns = cache.spatialIndex().get(indexCellKey(cellX, cellZ));
                if (cellTowns == null) continue;
                for (RenderTown town : cellTowns) {
                    if (!detailQuerySeen.add(town)) continue;   // town spans several cells
                    if (!town.intersectsWorld(worldLeft, worldRight, worldTop, worldBottom)) continue;
                    if (action.test(town.name(), town.key()) && ++issued >= limit) return;
                }
            }
        }
    }

    private TownRenderCache townRenderCache() {
        List<TownData> towns = api.getTowns();
        TownRenderCache cache = townRenderCache;
        if (cache.matches(towns)) return cache;
        requestTownRenderCacheBuild(towns);
        return cache;
    }

    private void requestTownRenderCacheBuild(List<TownData> towns) {
        if (towns.isEmpty()) return;
        if (towns == townCacheRequestedSource && townCacheBuildRunning.get()) return;
        townCacheRequestedSource = towns;
        if (!townCacheBuildRunning.compareAndSet(false, true)) return;
        townCacheExecutor.execute(() -> {
            List<TownData> source = townCacheRequestedSource;
            try {
                TownRenderCache built = buildTownRenderCache(source, townRenderCache);
                if (api.getTowns() == source) {
                    townRenderCache = built;
                }
            } finally {
                townCacheBuildRunning.set(false);
                if (api.getTowns() != townRenderCache.source()) {
                    requestTownRenderCacheBuild(api.getTowns());
                }
            }
        });
    }

    private static TownRenderCache buildTownRenderCache(List<TownData> towns, TownRenderCache previous) {
        Map<String, RenderTown> byName = new HashMap<>(Math.max(16, towns.size() * 2));
        Map<Long, List<RenderTown>> mutableSpatialIndex = new HashMap<>();
        ArrayList<RenderTown> allTowns = new ArrayList<>(towns.size());
        for (TownData town : towns) {
            RenderTown renderTown = reusableRenderTown(town, previous);
            allTowns.add(renderTown);
            byName.put(renderTown.key(), renderTown);

            int minCellX = floorToIndexCell(town.minX());
            int maxCellX = floorToIndexCell(town.maxX());
            int minCellZ = floorToIndexCell(town.minZ());
            int maxCellZ = floorToIndexCell(town.maxZ());
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                    mutableSpatialIndex.computeIfAbsent(indexCellKey(cellX, cellZ), ignored -> new ArrayList<>())
                            .add(renderTown);
                }
            }
        }
        Map<Long, List<RenderTown>> spatialIndex = new HashMap<>(mutableSpatialIndex.size());
        for (Map.Entry<Long, List<RenderTown>> entry : mutableSpatialIndex.entrySet()) {
            spatialIndex.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return new TownRenderCache(towns, towns.size(), Map.copyOf(byName),
                Map.copyOf(spatialIndex), List.copyOf(allTowns));
    }

    private static RenderTown reusableRenderTown(TownData town, TownRenderCache previous) {
        RenderTown cached = previous.byName().get(town.key());
        if (cached != null && cached.signature() == town.renderSignature()) {
            return cached;
        }
        return RenderTown.from(town);
    }

    private static int floorToIndexCell(double worldCoord) {
        return (int) Math.floor(worldCoord / TOWN_INDEX_CELL_SIZE);
    }

    private static long indexCellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
    }

    private static boolean largeEnoughOnScreen(RenderTown town, double blockScale, double minPixels) {
        double width = (town.maxX() - town.minX()) * blockScale;
        double height = (town.maxZ() - town.minZ()) * blockScale;
        return width >= minPixels || height >= minPixels;
    }

    private record TownRenderCache(List<TownData> source, int sourceSize,
                                   Map<String, RenderTown> byName,
                                   Map<Long, List<RenderTown>> spatialIndex,
                                   List<RenderTown> allTowns) {
        private static TownRenderCache empty() {
            return new TownRenderCache(List.of(), 0, Map.of(), Map.of(), List.of());
        }

        private boolean matches(List<TownData> towns) {
            return towns == source && towns.size() == sourceSize;
        }
    }

    private record RenderTown(TownData data, String name, String key, long signature, List<RingGeometry> rings,
                              int minX, int maxX, int minZ, int maxZ) {
        private static RenderTown from(TownData town) {
            ArrayList<RingGeometry> rings = new ArrayList<>(town.polygonRings().size());
            for (int[][] ring : town.polygonRings()) {
                rings.add(RingGeometry.from(ring));
            }
            return new RenderTown(town, town.name(), town.key(), town.renderSignature(),
                    List.copyOf(rings), town.minX(), town.maxX(), town.minZ(), town.maxZ());
        }

        private boolean intersectsWorld(double left, double right, double top, double bottom) {
            return maxX >= left && minX <= right && maxZ >= top && minZ <= bottom;
        }
    }

    // ── RingGeometry ─────────────────────────────────────────────────────────
    //
    // Stores pre-merged outline segments and pre-computed fill rects.
    // Both are built once on the background cache thread; the render thread
    // only iterates flat int[] arrays — zero allocations on the hot path.
    // ── RingGeometry ─────────────────────────────────────────────────────────
    //
    // Research finding: squaremap polygon data already contains only corner
    // vertices — every polygon edge is already a maximal axis-aligned segment.
    // No collinear-vertex merging is needed or useful.
    //
    // Build strategy:
    //   The raw outline (level 0) is the ordered corner vertices straight from
    //   squaremap.  Coarser levels are produced by snapping every vertex to a
    //   larger grid (LOD_GRID) and then dropping duplicate / collinear vertices.
    //   Because snapping is a pure function of the world coordinate, axis-aligned
    //   edges stay axis-aligned and shared town borders snap identically, so the
    //   loop stays closed and connected at every level.
    //
    // Render strategy:
    //   Walk the chosen level's ordered vertices and draw every edge in sequence
    //   as a connected closed loop — no edge is ever skipped, so there are no
    //   gaps at corners.  Coarser levels simply contain fewer vertices.
    //
    // Fill data: flat int[] [minX, minZ, maxX, maxZ, ...], 4 ints per rect.

    private record RingGeometry(
            int[][] lodX,     // lodX[level] = ordered X coords for that LOD level
            int[][] lodZ,     // lodZ[level] = ordered Z coords for that LOD level
            int[] fillData,
            int minX, int maxX, int minZ, int maxZ) {

        private int[] lodX(int level) { return lodX[level]; }
        private int[] lodZ(int level) { return lodZ[level]; }

        private static RingGeometry from(int[][] ring) {
            int n = ring.length;
            int[] px = new int[n], pz = new int[n];
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (int i = 0; i < n; i++) {
                px[i] = ring[i].length > 0 ? ring[i][0] : 0;
                pz[i] = ring[i].length > 1 ? ring[i][1] : 0;
                if (px[i] < minX) minX = px[i];
                if (px[i] > maxX) maxX = px[i];
                if (pz[i] < minZ) minZ = pz[i];
                if (pz[i] > maxZ) maxZ = pz[i];
            }
            if (n == 0) { minX = maxX = minZ = maxZ = 0; }

            int levels = LOD_GRID.length;
            int[][] lodX = new int[levels][];
            int[][] lodZ = new int[levels][];

            // Level 0: raw outline (clean up any incidental dup/collinear points)
            int[][] base = cleanRectilinear(px, pz, px.length);
            lodX[0] = base[0];
            lodZ[0] = base[1];

            // Coarser levels: snap to grid, then clean.  If a level collapses to a
            // degenerate shape (< 4 pts), reuse the previous (finer) level so the
            // town never disappears.
            for (int k = 1; k < levels; k++) {
                int grid = LOD_GRID[k];
                int[] sx = new int[n], sz = new int[n];
                for (int i = 0; i < n; i++) {
                    sx[i] = snap(px[i], grid);
                    sz[i] = snap(pz[i], grid);
                }
                int[][] simplified = cleanRectilinear(sx, sz, n);
                if (simplified[0].length >= 4 && simplified[0].length < lodX[k - 1].length) {
                    lodX[k] = simplified[0];
                    lodZ[k] = simplified[1];
                } else {
                    // No further reduction (or degenerate) — share the finer level's arrays
                    lodX[k] = lodX[k - 1];
                    lodZ[k] = lodZ[k - 1];
                }
            }

            return new RingGeometry(lodX, lodZ,
                                    buildFillData(px, pz, minX, maxX, minZ, maxZ),
                                    minX, maxX, minZ, maxZ);
        }
    }

    /** Rounds a coordinate to the nearest multiple of {@code grid} (symmetric). */
    private static int snap(int v, int grid) {
        return Math.floorDiv(v + (grid >> 1), grid) * grid;
    }

    /**
     * Cleans an ordered rectilinear closed-loop vertex set:
     *   1. drops consecutive duplicate points (incl. the wrap-around)
     *   2. drops collinear vertices (where prev, cur, next share an X or a Z),
     *      repeating until stable so flattened staircases fully collapse
     * Returns {outX, outZ}.  Axis-alignment and closure are preserved.
     */
    private static int[][] cleanRectilinear(int[] x, int[] z, int n) {
        // Pass 1: remove consecutive duplicates
        int[] ax = new int[n], az = new int[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (m > 0 && ax[m - 1] == x[i] && az[m - 1] == z[i]) continue;
            ax[m] = x[i]; az[m] = z[i]; m++;
        }
        while (m > 1 && ax[m - 1] == ax[0] && az[m - 1] == az[0]) m--;   // wrap dup

        // Pass 2: iteratively drop collinear vertices on the closed loop
        boolean changed = true;
        while (changed && m > 3) {
            changed = false;
            int w = 0;
            int[] bx = new int[m], bz = new int[m];
            for (int i = 0; i < m; i++) {
                int prev = (i - 1 + m) % m, next = (i + 1) % m;
                boolean colX = ax[prev] == ax[i] && ax[i] == ax[next];
                boolean colZ = az[prev] == az[i] && az[i] == az[next];
                if (colX || colZ) { changed = true; continue; }   // drop this vertex
                bx[w] = ax[i]; bz[w] = az[i]; w++;
            }
            if (changed) { ax = bx; az = bz; m = w; }
        }

        return new int[][]{ Arrays.copyOf(ax, m), Arrays.copyOf(az, m) };
    }

    /**
     * Builds fill data as a flat int[] with 4 ints per rect: [minX, minZ, maxX, maxZ, ...].
     * Uses Arrays.sort for O(n log n) band deduplication instead of the previous O(n²) scan.
     */
    private static int[] buildFillData(int[] x, int[] z, int minX, int maxX, int minZ, int maxZ) {
        int n = x.length;
        if (n < 3 || minX == maxX || minZ == maxZ) return new int[0];

        // Deduplicate Z band boundaries: sort a copy, then take unique values — O(n log n)
        int[] sortedZ = Arrays.copyOf(z, n);
        Arrays.sort(sortedZ);
        int bandCount = 0;
        int[] bands = new int[n + 1];
        for (int val : sortedZ) {
            if (bandCount == 0 || bands[bandCount - 1] != val) bands[bandCount++] = val;
        }
        if (bands[bandCount - 1] != maxZ) bands[bandCount++] = maxZ;
        // bands[] already sorted by Arrays.sort — no second pass needed

        int[] intersections = new int[n];
        int[] temp = new int[Math.max(16, n * 2)];  // generous initial cap, no FillRect objects
        int tempLen = 0;

        for (int bi = 1; bi < bandCount; bi++) {
            int zTop    = bands[bi - 1];
            int zBottom = bands[bi];
            if (zTop >= zBottom) continue;

            int xCount = 0;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                int z1 = z[i], z2 = z[j];
                if (z1 == z2) continue;
                int lo = Math.min(z1, z2), hi = Math.max(z1, z2);
                if (zTop >= lo && zTop < hi) intersections[xCount++] = x[i];
            }

            // Sort intersections (insertion sort — xCount is usually very small)
            for (int i = 1; i < xCount; i++) {
                int key = intersections[i], j = i - 1;
                while (j >= 0 && intersections[j] > key) { intersections[j + 1] = intersections[j--]; }
                intersections[j + 1] = key;
            }

            for (int i = 0; i + 1 < xCount; i += 2) {
                int xLeft = intersections[i], xRight = intersections[i + 1];
                if (xLeft == xRight) continue;
                if (tempLen + 4 > temp.length) temp = Arrays.copyOf(temp, temp.length * 2);
                temp[tempLen++] = Math.max(minX, xLeft);
                temp[tempLen++] = zTop;
                temp[tempLen++] = Math.min(maxX, xRight);
                temp[tempLen++] = zBottom;
            }
        }
        if (tempLen == 0) return new int[0];

        // Merge vertically adjacent rects that share the same X extent.
        // Sort by (minX, maxX) so identical-width columns are grouped together;
        // within each group the scanline order is already Z-ascending, so adjacent
        // bands that have the same left/right edge collapse into one tall rect.
        // For a rectangular town this turns N band-rects into 1 draw call.
        int numRects = tempLen / 4;
        if (numRects > 1) {
            // Pack (minX + 50000, maxX + 50000, rectIndex) as a sort key.
            // Coords ≤ 100 k blocks → +50000 fits in 17 bits (2^17 = 131072 > 90000).
            // 17 + 17 + 17 = 51 bits — no overflow.
            long[] rsk = new long[numRects];
            for (int i = 0; i < numRects; i++) {
                rsk[i] = ((long)(temp[i * 4] + 50000) << 34)
                        | ((long)(temp[i * 4 + 2] + 50000) << 17)
                        | i;
            }
            Arrays.sort(rsk, 0, numRects);

            int[] merged = new int[tempLen];
            int mLen = 0;
            int ox1 = Integer.MIN_VALUE, oz1 = 0, ox2 = 0, oz2 = 0;
            for (long rk : rsk) {
                int ri  = (int)(rk & 0x1FFFF) * 4;
                int rx1 = temp[ri], rz1 = temp[ri + 1], rx2 = temp[ri + 2], rz2 = temp[ri + 3];
                if (rx1 == ox1 && rx2 == ox2 && rz1 == oz2) {
                    oz2 = rz2;  // extend current rect downward
                } else {
                    if (ox1 != Integer.MIN_VALUE) {
                        merged[mLen++] = ox1; merged[mLen++] = oz1;
                        merged[mLen++] = ox2; merged[mLen++] = oz2;
                    }
                    ox1 = rx1; oz1 = rz1; ox2 = rx2; oz2 = rz2;
                }
            }
            merged[mLen++] = ox1; merged[mLen++] = oz1;
            merged[mLen++] = ox2; merged[mLen++] = oz2;
            return Arrays.copyOf(merged, mLen);
        }

        return Arrays.copyOf(temp, tempLen);
    }

    // ── Nation capital markers ───────────────────────────────────────────────

    private void renderNationCapitalStars(DrawContext ctx,
                                          double cameraX, double cameraZ, double blockScale,
                                          int sw, int sh,
                                          double worldLeft, double worldRight,
                                          double worldTop, double worldBottom,
                                          Map<String, EarthMcNationData> nationDetails) {
        if (!config.townsEnabled || !config.nationStarsEnabled || nationDetails.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;

        for (EarthMcNationData nation : nationDetails.values()) {
            double markerX, markerZ;

            // Prefer spawn coordinates from the EarthMC API — they are always accurate
            // and don't depend on squaremap polygon names matching the API's capital name.
            if (nation.hasSpawn()) {
                markerX = nation.spawnX();
                markerZ = nation.spawnZ();
            } else if (!nation.capitalName().isBlank()) {
                // Fall back to locating the capital town in the squaremap polygon list.
                TownData capital = townByName(nation.capitalName());
                if (capital == null) continue;
                markerX = capital.centerX();
                markerZ = capital.centerZ();
            } else {
                continue;
            }

            if (markerX < worldLeft || markerX > worldRight
                    || markerZ < worldTop || markerZ > worldBottom) continue;

            int x = toScreenX(markerX, cameraX, blockScale, sw);
            int y = toScreenY(markerZ, cameraZ, blockScale, sh);
            if (x < -10 || x > sw + 10 || y < -10 || y > sh + 10) continue;

            ctx.drawText(client.textRenderer, "★", x - 3, y - 5, 0xFFFFD84D, true);
        }
    }

    private TownData townByName(String name) {
        RenderTown town = townRenderCache().byName().get(name.toLowerCase(Locale.ROOT));
        return town == null ? null : town.data();
    }

    // ── Player rendering ─────────────────────────────────────────────────────

    private void renderPlayers(DrawContext ctx,
                               double cameraX, double cameraZ, double blockScale,
                               int sw, int sh, Map<String, EarthMcPlayerData> playerDetails) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        String selfName = client.getSession().getUsername();
        for (PlayerMarker p : api.getPlayers()) {
            if (p.name().equalsIgnoreCase(selfName)) continue;

            int dotX = toScreenX(p.x(), cameraX, blockScale, sw);
            int dotY = toScreenY(p.z(), cameraZ, blockScale, sh);

            if (dotX < -10 || dotX > sw + 10 || dotY < -10 || dotY > sh + 10) continue;

            int color = TownyMapMod.playerDotColor(p.name(), p.key());
            if ((color >>> 24) == 0) continue;
            ctx.fill(dotX - DOT_HALF, dotY - DOT_HALF,
                     dotX + DOT_HALF, dotY + DOT_HALF,
                     color);

            if (config.showPlayerNames && blockScale >= config.playerNameMinScale) {
                EarthMcPlayerData details = playerDetails.get(p.name().toLowerCase(Locale.ROOT));
                String affiliation = affiliation(details);
                boolean showAffiliation = !affiliation.isBlank() && blockScale >= config.playerAffiliationMinScale;
                int nameY = showAffiliation ? dotY + 7 : dotY - 4;
                if (showAffiliation) {
                    ctx.drawText(
                            client.textRenderer,
                            affiliation,
                            dotX + DOT_HALF + 2,
                            dotY - 5,
                            0xFFB8D7FF,
                            true);
                }
                ctx.drawText(
                        client.textRenderer,
                        p.name(),
                        dotX + DOT_HALF + 2,
                        nameY,
                        config.playerLabelColor,
                        true);
            }
        }
    }

    private static String affiliation(EarthMcPlayerData details) {
        if (details == null) return "";
        if (!details.townName().isBlank() && !details.nationName().isBlank()) {
            return details.townName() + " / " + details.nationName();
        }
        if (!details.townName().isBlank()) return details.townName();
        if (!details.nationName().isBlank()) return details.nationName();
        return "";
    }
}
