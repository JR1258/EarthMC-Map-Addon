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
    private static final double MIN_TOWN_SCREEN_PIXELS = 2.0;
    private static final int CHUNK_SIZE = 16;
    private static final int HOVER_CHUNK_FILL = 0x22FFFFFF;
    private static final int HOVER_CHUNK_BORDER = 0xD8FFFFFF;
    private static final double MIN_CHUNK_GRID_SPACING = 4.0;
    private static final int TOWN_INDEX_CELL_SIZE = 2048;
    private static final long STATUS_RGB_CYCLE_MS = 5000L;

    // ── Render-thread scratch buffers (never allocate in the hot path) ────────
    // All polygon rendering happens on the MC main/render thread, so static
    // scratch arrays are safe and eliminate GC pressure that caused map-pan stutter.
    private static int[] scratchSX = new int[512];
    private static int[] scratchSY = new int[512];
    private static int[] scratchBandY = new int[512];
    private static int[] scratchBandX = new int[256];

    private static int[] grow(int[] arr, int needed) {
        if (arr.length >= needed) return arr;
        return new int[Math.max(needed, arr.length * 2)];
    }

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

        if (config.townsEnabled) {
            renderTownFills(ctx, cameraX, cameraZ, blockScale, sw, sh, visibleTowns);
        }
        renderChunkGrid(ctx, cameraX, cameraZ, blockScale, sw, sh,
                worldLeft, worldRight, worldTop, worldBottom);
        if (config.townsEnabled) {
            renderTownOutlines(ctx, cameraX, cameraZ, blockScale, sw, sh,
                    visibleTowns, townDetails);
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

    private void renderTownFills(DrawContext ctx,
                                 double cameraX, double cameraZ, double blockScale,
                                 int sw, int sh,
                                 List<RenderTown> visibleTowns) {
        for (RenderTown town : visibleTowns) {
            int fillColor   = blockScale >= TOWN_FILL_MIN_SCALE ? town.data().argbColor(config.fillAlpha) : 0;
            boolean favorite = isFavorite(town.name());

            for (RingGeometry ring : town.rings()) {
                renderRing(ctx, ring, 0, fillColor,
                        cameraX, cameraZ, blockScale, sw, sh);
                if (favorite) {
                    renderRing(ctx, ring, 0, 0x22FFE066,
                            cameraX, cameraZ, blockScale, sw, sh);
                }
            }
        }
    }

    private void renderTownOutlines(DrawContext ctx,
                                    double cameraX, double cameraZ, double blockScale,
                                    int sw, int sh,
                                    List<RenderTown> visibleTowns,
                                    Map<String, TownPopupData> townDetails) {
        int statusRgb = statusHighlightRgb();
        for (RenderTown town : visibleTowns) {
            int borderColor = town.data().argbColor(config.borderAlpha);
            boolean favorite = isFavorite(town.name());
            TownPopupData details = townDetails.get(town.key());
            boolean publicSpawn = config.townStatusOverlayMode == 1
                    && details != null
                    && details.canOutsidersSpawn();
            boolean overclaimed = config.townStatusOverlayMode == 2
                    && details != null
                    && details.isOverClaimed();
            boolean open = config.townStatusOverlayMode == 3
                    && details != null
                    && details.isOpen();
            boolean forSale = config.townStatusOverlayMode == 4
                    && details != null
                    && details.isForSale();
            boolean noNation = config.townStatusOverlayMode == 5
                    && details != null
                    && !details.hasNation();
            boolean statusHighlighted = publicSpawn || overclaimed || open || forSale || noNation;
            for (RingGeometry ring : town.rings()) {
                renderRing(ctx, ring, borderColor, 0,
                           cameraX, cameraZ, blockScale, sw, sh);
                if (statusHighlighted) {
                    renderRing(ctx, ring, 0xFF000000 | statusRgb, 0x44000000 | statusRgb,
                            cameraX, cameraZ, blockScale, sw, sh);
                }
                if (favorite) {
                    renderRing(ctx, ring, 0xFFFFE066, 0,
                            cameraX, cameraZ, blockScale, sw, sh);
                }
            }
        }
    }

    // ── Shared ring renderer ─────────────────────────────────────────────────

    private void renderRing(DrawContext ctx, RingGeometry ring,
                            int borderColor, int fillColor,
                            double cameraX, double cameraZ, double blockScale,
                            int sw, int sh) {
        int n = ring.length();
        if (n < 2) return;

        int screenMinX = toScreenX(ring.minX(), cameraX, blockScale, sw);
        int screenMaxX = toScreenX(ring.maxX(), cameraX, blockScale, sw);
        int screenMinY = toScreenY(ring.minZ(), cameraZ, blockScale, sh);
        int screenMaxY = toScreenY(ring.maxZ(), cameraZ, blockScale, sh);
        if (screenMaxX < 0 || screenMinX > sw || screenMaxY < 0 || screenMinY > sh) return;

        // Use scratch buffers — no heap allocation on the hot path.
        scratchSX = grow(scratchSX, n);
        scratchSY = grow(scratchSY, n);
        int[] sx = scratchSX;
        int[] sy = scratchSY;
        int[] worldX = ring.x();
        int[] worldZ = ring.z();

        for (int i = 0; i < n; i++) {
            sx[i] = toScreenX(worldX[i], cameraX, blockScale, sw);
            sy[i] = toScreenY(worldZ[i], cameraZ, blockScale, sh);
        }

        int minX = sx[0], maxX = sx[0], minY = sy[0], maxY = sy[0];
        for (int i = 1; i < n; i++) {
            if (sx[i] < minX) minX = sx[i];
            if (sx[i] > maxX) maxX = sx[i];
            if (sy[i] < minY) minY = sy[i];
            if (sy[i] > maxY) maxY = sy[i];
        }
        if (maxX < 0 || minX > sw || maxY < 0 || minY > sh) return;

        // Skip fill for polygons whose screen bounding box is too small to see.
        if ((fillColor >>> 24) > 0 && maxX - minX > 2 && maxY - minY > 2) {
            scanlineFill(ctx, sx, sy, n, minY, maxY, fillColor, sw);
        }

        if ((borderColor >>> 24) > 0) {
            for (int i = 0; i < n; i++) {
                int j  = (i + 1) % n;
                int x1 = sx[i], y1 = sy[i];
                int x2 = sx[j], y2 = sy[j];
                if (y1 == y2) {
                    ctx.drawHorizontalLine(Math.min(x1, x2), Math.max(x1, x2), y1, borderColor);
                } else if (x1 == x2) {
                    ctx.drawVerticalLine(x1, Math.min(y1, y2), Math.max(y1, y2), borderColor);
                } else {
                    ctx.fill(Math.min(x1, x2), Math.min(y1, y2),
                             Math.max(x1, x2) + 1, Math.max(y1, y2) + 1, borderColor);
                }
            }
        }
    }

    /**
     * Scanline fill for rectilinear polygons.
     *
     * Uses static scratch arrays so zero heap objects are allocated per call —
     * this was the primary cause of GC-induced stutter during map panning.
     */
    private static void scanlineFill(DrawContext ctx, int[] sx, int[] sy, int n,
                                     int minY, int maxY, int color, int sw) {
        // Collect unique Y breakpoints into scratchBandY (no TreeSet allocation).
        scratchBandY = grow(scratchBandY, n + 1);
        int yCount = 0;
        outer:
        for (int i = 0; i < n; i++) {
            int y = sy[i];
            if (y < minY || y > maxY) continue;
            for (int j = 0; j < yCount; j++) {
                if (scratchBandY[j] == y) continue outer;
            }
            scratchBandY[yCount++] = y;
        }
        scratchBandY[yCount++] = maxY;

        // Insertion sort — tiny array, always faster than Arrays.sort here.
        for (int i = 1; i < yCount; i++) {
            int key = scratchBandY[i], j = i - 1;
            while (j >= 0 && scratchBandY[j] > key) { scratchBandY[j + 1] = scratchBandY[j--]; }
            scratchBandY[j + 1] = key;
        }

        // Fill each horizontal band.
        scratchBandX = grow(scratchBandX, n);
        for (int bi = 1; bi < yCount; bi++) {
            int yTop    = scratchBandY[bi - 1];
            int yBottom = scratchBandY[bi];
            if (yTop >= yBottom) continue;

            // Collect X intersections for this band (no ArrayList allocation).
            int xCount = 0;
            int ySample = yTop;
            for (int i = 0; i < n; i++) {
                int j  = (i + 1) % n;
                int y1 = sy[i], y2 = sy[j];
                if (y1 == y2) continue;
                int yMin = Math.min(y1, y2), yMax = Math.max(y1, y2);
                if (ySample >= yMin && ySample < yMax) scratchBandX[xCount++] = sx[i];
            }

            // Insertion sort the X values.
            for (int i = 1; i < xCount; i++) {
                int key = scratchBandX[i], j = i - 1;
                while (j >= 0 && scratchBandX[j] > key) { scratchBandX[j + 1] = scratchBandX[j--]; }
                scratchBandX[j + 1] = key;
            }

            for (int k = 0; k + 1 < xCount; k += 2) {
                int xLeft = scratchBandX[k], xRight = scratchBandX[k + 1];
                if (xRight > 0 && xLeft < sw) {
                    ctx.fill(xLeft, yTop, xRight, yBottom, color);
                }
            }
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
                TownRenderCache built = buildTownRenderCache(source);
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

    private static TownRenderCache buildTownRenderCache(List<TownData> towns) {
        Map<String, RenderTown> byName = new HashMap<>(Math.max(16, towns.size() * 2));
        Map<Long, List<RenderTown>> mutableSpatialIndex = new HashMap<>();
        ArrayList<RenderTown> allTowns = new ArrayList<>(towns.size());
        for (TownData town : towns) {
            RenderTown renderTown = RenderTown.from(town);
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

    private record RenderTown(TownData data, String name, String key, List<RingGeometry> rings,
                              int minX, int maxX, int minZ, int maxZ) {
        private static RenderTown from(TownData town) {
            ArrayList<RingGeometry> rings = new ArrayList<>(town.polygonRings().size());
            for (int[][] ring : town.polygonRings()) {
                rings.add(RingGeometry.from(ring));
            }
            return new RenderTown(town, town.name(), town.name().toLowerCase(Locale.ROOT),
                    List.copyOf(rings), town.minX(), town.maxX(), town.minZ(), town.maxZ());
        }

        private boolean intersectsWorld(double left, double right, double top, double bottom) {
            return maxX >= left && minX <= right && maxZ >= top && minZ <= bottom;
        }
    }

    private record RingGeometry(int[] x, int[] z, int minX, int maxX, int minZ, int maxZ) {
        private static RingGeometry from(int[][] ring) {
            int[] x = new int[ring.length];
            int[] z = new int[ring.length];
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (int i = 0; i < ring.length; i++) {
                int worldX = ring[i].length > 0 ? ring[i][0] : 0;
                int worldZ = ring[i].length > 1 ? ring[i][1] : 0;
                x[i] = worldX;
                z[i] = worldZ;
                if (worldX < minX) minX = worldX;
                if (worldX > maxX) maxX = worldX;
                if (worldZ < minZ) minZ = worldZ;
                if (worldZ > maxZ) maxZ = worldZ;
            }
            if (ring.length == 0) {
                minX = maxX = minZ = maxZ = 0;
            }
            return new RingGeometry(x, z, minX, maxX, minZ, maxZ);
        }

        private int length() {
            return x.length;
        }
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

            int color = TownyMapMod.playerDotColor(p.name());
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
