package net.townymap.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.townymap.TownyMapConfig;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("TownyMapAddon");
    private static final int DOT_HALF = 2;
    // Hard ceiling on heads drawn in one frame; past it the rest are dots. Heads are 2 unbatchable textured
    // draws each, so this caps the worst case — a very dense town at high zoom with hundreds on screen. The
    // Head Range setting (config.playerHeadMinScale) is the main gate: below that zoom, everyone is a dot.
    private static final int MAX_PLAYER_HEADS = 90;
    private static final double TOWN_FILL_MIN_SCALE = 0.035;
    private static final double MIN_TOWN_SCREEN_PIXELS = 0.0;
    private static final int CHUNK_SIZE = 16;
    private static final int HOVER_CHUNK_FILL = 0x22FFFFFF;
    private static final int HOVER_CHUNK_BORDER = 0xD8FFFFFF;
    private static final double MIN_CHUNK_GRID_SPACING = 4.0;
    private static final int TOWN_INDEX_CELL_SIZE = 2048;
    private static final long STATUS_RGB_CYCLE_MS = 5000L;
    private static final int TINY_TOWN_SCREEN_PIXELS = 2;
    // Interior fills (renderCachedFill draws EVERY merged chunk-rect of EVERY town) are by far the most
    // expensive part — at the zoom where fills switch on but many towns are visible it's ~150ms/frame.
    // So only fill when few enough towns are on screen that it's cheap, i.e. when actually zoomed in.
    private static final int FILL_MAX_TOWNS = 120;

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
    // 384 is the coarsest level — the old 768 step was dropped because at full zoom-out it read worse
    // (coarse snap + heaviest tile downscale) than the level before it. Full zoom-out now caps at 384.
    private static final int[]    LOD_GRID      = { 16,   32,    80,    192,   384  };
    private static final double[] LOD_MIN_SCALE = { 0.125, 0.05, 0.022, 0.012, 0.0  };

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
    private int favoritesVersion = 0;   // bumped when the favourite set changes (tiles bake favourites in)

    // ── Cached town-outline tiles ─────────────────────────────────────────────
    // Outlines rasterise once (off-thread) into world-anchored tile textures, then just blit — the same
    // technique BorderOverlayRenderer uses.
    private static final int OUTLINE_TILE_PIXELS = 256;
    // The DP+AA tile path owns the outlines whenever one BLOCK is at most this many PHYSICAL pixels on
    // screen (a chunk ≤ 32 phys px) — i.e. all of mid-zoom out to the full overview, with NO town-count
    // gate. That matches Leaflet exactly: the website simplify+AA-renders every polygon at every zoom,
    // and its shapes only look rectilinear close-in because the chunk steps exceed the simplify tolerance
    // there. Zoomed in past this, the crisp 1:1 direct renderer takes over (the website is fully
    // rectilinear at those zooms as well).
    private static final double OUTLINE_TILE_MAX_PPB = 2.0;
    private static final int MAX_OUTLINE_TILES = 320;
    private static final int MAX_OUTLINE_TILE_LOADS = 48;              // bake more in parallel → sharp fast
    private static final int MAX_OUTLINE_TILE_UPLOADS_PER_FRAME = 24;  // and upload them fast on settle
    // The browser renders its canvas at devicePixelRatio (PHYSICAL pixels), so we rasterise at up to this
    // multiple of the GUI scale (the window scale factor, capped — 2× already matches the site's sharpness
    // at 4× the tile count; uncapped retina scales would explode tile counts for invisible gains).
    private static final double OUTLINE_TILE_MAX_DENSITY = 2.0;
    // Settle time before a new zoom bucket rasterises (Leaflet redraws its canvas on zoom END, scaling
    // the old picture during the gesture).
    private static final long OUTLINE_ZOOM_SETTLE_MS = 55;   // bake the new bucket almost immediately once
                                                             // the zoom stops → sharp tiles appear near-instantly
    // Whole-map overview level: coarse enough that a couple dozen tiles cover ALL of EarthMC (at this
    // ppb one tile spans ~12.8k blocks), baked once and never evicted. It is drawn ONLY as a last-resort
    // floor — when neither this zoom nor the previous one has a single cached tile for the view — so the
    // outlines can never fully disappear mid-zoom, yet it never double-draws under the finer levels
    // (which would thicken/halo the lines you just approved).
    private static final double OUTLINE_OVERVIEW_PPB = 0.02;
    // Leaflet's smoothFactor (1.0), in GUI px — scaled by density into tile px at use. Verified against
    // Leaflet 1.9.4 source: simplify = radial vertex reduction THEN Douglas-Peucker, both with sqTolerance.
    // The tolerance is the same world-space distance for every tile of a zoom level, so neighbouring tiles
    // — and adjacent towns sharing a border edge — simplify identically and stay flush.
    private static final double OUTLINE_SIMPLIFY_TOLERANCE_PX = 1.0;
    // The site's exact town-polygon paint (read from map.earthmc.net markers.json — every one of its
    // ~5100 polygons ships weight:2, opacity:0.3, and Leaflet's default fillOpacity 0.2): a WIDE
    // translucent round-joined stroke over a soft fill. That style — not the geometry — is most of the
    // "smooth, flowing, never-intersecting" look: the fat 30%-alpha stroke visually rounds the chunk
    // staircase and adjacent towns' strokes overlap into one soft shared edge.
    private static final double SITE_STROKE_WEIGHT_GUI = 2.0;   // CSS px on the site == GUI px here
    private static final int SITE_STROKE_ALPHA = 165;          // ~0.65: crisper/more defined than the
                                                               // site's faint 0.3, which read fuzzy in-game
    private static final int SITE_FILL_ALPHA = 64;              // ~0.25 — a touch over the site's 0.2 so
                                                                // the nation fill colour actually reads
    // Overview tiles are few but expensive (thousands of towns each), so parallelise their rasterisation
    // to shorten the load when zooming into a new coarse bucket — capped so it never starves the render
    // thread / game tick.
    private static final int OUTLINE_TILE_THREADS =
            Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
    private final ExecutorService outlineTileExecutor = Executors.newFixedThreadPool(OUTLINE_TILE_THREADS, r -> {
        Thread t = new Thread(r, "TownyMap-OutlineTiles");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);   // yield to the render thread
        return t;
    });
    /**
     * One bakeable outline layer. There are two, sharing all of the tiling machinery:
     *  - main   — every town EXCEPT the map-mode ones, in its own colours.
     *  - status — ONLY the map-mode towns, rasterised in WHITE and blitted with the animated highlight
     *             colour as a tint. Baking white and tinting at blit time is what lets the highlight
     *             animate every frame while still being a baked tile, so its line is pixel-identical to
     *             the towns around it. Re-baking per frame to animate would be the churn we removed.
     */
    private static final class TileLayer {
        final String name;              // namespaces the texture id; both layers share the key space
        final boolean statusLayer;
        final LinkedHashMap<Long, Identifier> tiles = new LinkedHashMap<>(64, 0.75f, true);
        final Set<Long> loading = ConcurrentHashMap.newKeySet();
        final Set<Long> empty = ConcurrentHashMap.newKeySet();
        final Queue<LoadedOutlineTile> completed = new ConcurrentLinkedQueue<>();
        int lastReadyZoom = Integer.MIN_VALUE;
        int pendingZoom = Integer.MIN_VALUE;
        long pendingSinceMs;
        int tint = 0xFFFFFFFF;          // set per frame; 0xFFFFFFFF leaves the baked colours untouched

        TileLayer(String name, boolean statusLayer) { this.name = name; this.statusLayer = statusLayer; }
    }

    private final TileLayer mainTiles = new TileLayer("main", false);
    private final TileLayer statusTiles = new TileLayer("status", true);
    private TownRenderCache outlineTilesSnapshot;   // which snapshot the current tiles were built for
    private boolean outlineTilesSmooth = true;     // outline STYLE baked into the current tiles
    private int outlineTilesFavVersion = -1;        // favourites baked into the current tiles
    private int outlineTilesStatusVersion = -1;     // status-highlight exclusion baked into the current tiles

    // Towns the active status mode highlights. They are EXCLUDED from the tile bake and drawn live in the
    // animated status colour instead, so the highlight is a single flush line (no baked line under it).
    // statusVersion bumps when this set changes → tiles rebuild, exactly like favouritesVersion.
    private Set<String> statusHighlightKeys = Set.of();
    private int statusVersion = 0;
    private int lastStatusMode = -1;
    private int lastStatusDetailsSize = -1;
    private int lastStatusFavVersion = -1;

    // Render source for the frame. In the Meganations/Alliances modes this is a recoloured copy of the town
    // list (each town tinted by its alliance); otherwise it's api.getTowns() untouched. Published to a
    // volatile so the off-thread cache builder reads one consistent instance. Memo fields make the recolour
    // rebuild only when the base list, the mode, or the alliance data actually changes.
    private volatile List<TownData> renderSource = null;
    private List<TownData> recolored;
    private List<TownData> recoloredBase;
    private int recoloredMode = -1;
    private int recoloredVersion = -1;
    // Same memo pattern for the search-filter dim, keyed on the filter's own version.
    private List<TownData> filtered;
    private List<TownData> filteredBase;
    private int filteredVersion = -1;


    public WorldMapRenderer(TownyMapConfig config, SquaremapApiClient api) {
        this.config = config;
        this.api    = api;
        this.squaremapTiles = new SquaremapTileRenderer(config);
        this.borderOverlay = new BorderOverlayRenderer(config);
    }

    /** HTTP status of a recent squaremap tile refusal, or 0 when the imagery is loading normally. */
    public int tileRefusalStatus() {
        return squaremapTiles.recentRefusalStatus();
    }

    /** Drops every cached squaremap tile — used when the map switches world. */
    public void clearSquaremapTiles() {
        squaremapTiles.clearAll();
    }

    public void invalidateTownCaches() {
        visibleTownScratch.clear();
        visibleTownSeen.clear();
        townRenderCache = TownRenderCache.empty();
        townCacheRequestedSource = List.of();
        // Force the baked outline tiles to rebuild on the next frame, so a data swap (entering/leaving
        // archive mode) replaces the borders instead of the old ones lingering. Nulling the snapshot makes
        // the render path's own mismatch check clear the tiles — on the render thread, where the texture
        // manager is safe to touch — rather than clearing textures here, which may run off-thread.
        outlineTilesSnapshot = null;
    }

    public void render(GuiGraphicsExtractor ctx,
                       double cameraX, double cameraZ, double blockScale,
                       int sw, int sh,
                       Map<String, TownPopupData> townDetails,
                       Map<String, EarthMcPlayerData> playerDetails,
                       Map<String, EarthMcNationData> nationDetails) {
        if (blockScale <= 0) return;
        renderSource = effectiveSource();   // publish this frame's source before the cache reads it
        double worldLeft   = cameraX - sw / 2.0 / blockScale;
        double worldRight  = cameraX + sw / 2.0 / blockScale;
        double worldTop    = cameraZ - sh / 2.0 / blockScale;
        double worldBottom = cameraZ + sh / 2.0 / blockScale;
        List<RenderTown> visibleTowns = visibleTowns(blockScale, worldLeft, worldRight, worldTop, worldBottom);
        refreshFavoriteTownKeys();

        // Real-world country and state borders, so they mean nothing anywhere but Terra Nostra -- on
        // the Moon they drew Earth's coastlines across lunar terrain.
        if (TownyMapMod.viewingEarth()) {
            borderOverlay.render(ctx, cameraX, cameraZ, blockScale, sw, sh,
                    worldLeft, worldRight, worldTop, worldBottom);
        }

        renderChunkGrid(ctx, cameraX, cameraZ, blockScale, sw, sh,
                worldLeft, worldRight, worldTop, worldBottom);
        if (config.townsEnabled) {
            renderTowns(ctx, cameraX, cameraZ, blockScale, sw, sh, visibleTowns, townDetails);
        }
        // Optimistic claim chunks are NOT drawn here - see renderPlayersLayer. They are ctx.fill quads,
        // which the textured outline tiles paint over inside one batch, so drawing them in this pass made
        // freshly claimed chunks invisible behind the tiles.
        renderNationCapitalStars(ctx, cameraX, cameraZ, blockScale, sw, sh,
                worldLeft, worldRight, worldTop, worldBottom, nationDetails);
        // Player dots are NOT drawn here: the town outline tiles are textured quads and, within a single
        // GuiGraphicsExtractor batch, textured draws paint over the colored ctx.fill dots — so tiles would hide (and,
        // as they async-rebuild, "blink") the dots at zoom-out. They're drawn in renderPreDropdown instead
        // (see MixinGuiMap#renderWorldMapPlayers), after the tile batch is flushed, so they always sit on top.
    }

    /** Player dots, drawn in the renderPreDropdown pass (after the tile batch is flushed) so they render
     *  above the textured town-outline tiles instead of being covered by them. */
    public void renderPlayersLayer(GuiGraphicsExtractor ctx, double cameraX, double cameraZ, double blockScale,
                                   int sw, int sh, Map<String, EarthMcPlayerData> playerDetails) {
        if (config == null || blockScale <= 0) return;

        // Freshly claimed chunks are drawn in THIS pass, not in render(), for the same reason as the
        // player dots: they are ctx.fill quads and the town outline tiles are textured draws, which paint
        // over ctx.fill within a single GuiGraphicsExtractor batch. Drawn in render() they ended up hidden behind
        // the tiles. By here the tile batch has been flushed, so they sit on top as they used to.
        if (config.townsEnabled) {
            renderOptimisticClaimChunks(ctx, cameraX, cameraZ, blockScale, sw, sh,
                    cameraX - sw / 2.0 / blockScale, cameraX + sw / 2.0 / blockScale,
                    cameraZ - sh / 2.0 / blockScale, cameraZ + sh / 2.0 / blockScale);
        }

        if (!config.playersEnabled) return;
        renderPlayers(ctx, cameraX, cameraZ, blockScale, sw, sh, playerDetails);
    }

    public void renderSquaremapBackground(GuiGraphicsExtractor ctx,
                                          double cameraX, double cameraZ, double blockScale,
                                          int sw, int sh, boolean moving) {
        if (!config.squaremapOnWorldMap() || blockScale <= 0) return;
        ctx.fill(0, 0, sw, sh, 0xFF101418);

        double worldLeft   = cameraX - sw / 2.0 / blockScale;
        double worldRight  = cameraX + sw / 2.0 / blockScale;
        double worldTop    = cameraZ - sh / 2.0 / blockScale;
        double worldBottom = cameraZ + sh / 2.0 / blockScale;

        squaremapTiles.render(ctx, cameraX, cameraZ, blockScale, sw, sh,
                worldLeft, worldRight, worldTop, worldBottom, moving);
        // Baseline dim (0x38) plus the optional user "Darken Map" level (0-3), so the squaremap can be
        // pushed darker to make the town overlay stand out.
        int darkAlpha = Math.min(0xFF, 0x38 + config.squaremapDarken * 0x2A);
        ctx.fill(0, 0, sw, sh, darkAlpha << 24);
    }

    public void renderSquaremapViewport(GuiGraphicsExtractor ctx,
                                        double cameraX, double cameraZ, double blockScale,
                                        int sw, int sh, boolean moving) {
        if (!config.squaremapOnWorldMap() || blockScale <= 0 || sw <= 0 || sh <= 0) return;

        double worldLeft = cameraX - sw / 2.0 / blockScale;
        double worldRight = cameraX + sw / 2.0 / blockScale;
        double worldTop = cameraZ - sh / 2.0 / blockScale;
        double worldBottom = cameraZ + sh / 2.0 / blockScale;

        squaremapTiles.render(ctx, cameraX, cameraZ, blockScale, sw, sh,
                worldLeft, worldRight, worldTop, worldBottom, moving);
    }

    public void renderSquaremapMinimapViewport(GuiGraphicsExtractor ctx,
                                               double cameraX, double cameraZ, double blockScale,
                                               int sw, int sh, boolean moving) {
        renderSquaremapMinimapViewport(ctx, cameraX, cameraZ, blockScale, sw, sh, moving, 0.0);
    }

    public void renderSquaremapMinimapViewport(GuiGraphicsExtractor ctx,
                                               double cameraX, double cameraZ, double blockScale,
                                               int sw, int sh, boolean moving, double circularClipRadius) {
        // Minimap path (squaremapTiles.renderMinimap), so it keys off the minimap half of the setting.
        // It lives in WorldMapRenderer only because the tile cache does; gating it on the world map
        // meant "Minimap" mode drew our outlines over Xaero's terrain with no squaremap tiles at all.
        if (!config.squaremapOnMinimap() || blockScale <= 0 || sw <= 0 || sh <= 0) return;

        double worldLeft = cameraX - sw / 2.0 / blockScale;
        double worldRight = cameraX + sw / 2.0 / blockScale;
        double worldTop = cameraZ - sh / 2.0 / blockScale;
        double worldBottom = cameraZ + sh / 2.0 / blockScale;

        squaremapTiles.renderMinimap(ctx, cameraX, cameraZ, blockScale, sw, sh,
                worldLeft, worldRight, worldTop, worldBottom, moving, circularClipRadius);
    }

    public boolean isSquaremapLoading() {
        return squaremapTiles.isLoading();
    }

    public boolean isBorderLoading() {
        return borderOverlay.isLoading();
    }

    public void renderHoveredChunk(GuiGraphicsExtractor ctx,
                                   double cameraX, double cameraZ, double blockScale,
                                   int sw, int sh,
                                   double mouseWorldX, double mouseWorldZ) {
        if (!config.squaremapOnWorldMap() || blockScale <= 0) return;
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

    private void renderChunkGrid(GuiGraphicsExtractor ctx,
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

    private void renderOptimisticClaimChunks(GuiGraphicsExtractor ctx,
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

    private void renderTowns(GuiGraphicsExtractor ctx,
                             double cameraX, double cameraZ, double blockScale,
                             int sw, int sh,
                             List<RenderTown> visibleTowns,
                             Map<String, TownPopupData> townDetails) {
        double worldLeft   = cameraX - sw / 2.0 / blockScale;
        double worldRight  = cameraX + sw / 2.0 / blockScale;
        double worldTop    = cameraZ - sh / 2.0 / blockScale;
        double worldBottom = cameraZ + sh / 2.0 / blockScale;

        refreshStatusHighlightKeys(townDetails);

        // ── Outline strategy: exactly squaremap's ────────────────────────────────
        // The smooth (DP + anti-aliased) tile path owns the outlines at every zoom where a chunk is small
        // on screen — gated by ZOOM, not town count, because Leaflet canvas-renders every polygon at every
        // zoom. The tiles bake every town's own border colour EXCEPT the status-highlighted subset, which
        // is excluded from the bake and drawn live in the animated status colour (it IS the border, with
        // no baked line under it to drift against). renderTownOutlineTiles returns false until every
        // visible tile is ready, in which case we fall through to direct rendering so the map is never
        // left blank.
        double density = tileRenderDensity();
        // "Smooth Town Outlines" off routes everything through the direct path below, which draws the
        // raw chunk-aligned rings - the original blocky border style, kept for people who prefer it.
        // BOTH styles go through the tiles: measured on live EarthMC data the direct path peaks at
        // ~13.9k draw calls per frame around blockScale 0.05, while a tiled screen is ~20 blits whatever
        // the zoom. The tile bake rasterises rectilinear rings instead of simplified ones when the blocky
        // style is selected, so the look is preserved and the per-frame cost is not.
        boolean useTiles = blockScale * density <= OUTLINE_TILE_MAX_PPB;
        boolean fillsEligible = visibleTowns.size() <= FILL_MAX_TOWNS && blockScale >= TOWN_FILL_MIN_SCALE;

        // Tiles bake fill AND stroke on the same simplified path (Leaflet's _fillStroke), so nothing
        // else needs to draw when they're ready.
        if (useTiles && renderTownOutlineTiles(ctx, mainTiles, 0xFFFFFFFF, cameraX, cameraZ, blockScale,
                                               sw, sh, worldLeft, worldRight, worldTop, worldBottom)) {
            // Highlight layer on top, tinted with the live (optionally animating) colour. Blitted after the
            // main layer so it sits above the outlines it is highlighting.
            if (isHighlightMode()) {
                renderTownOutlineTiles(ctx, statusTiles, 0xFF000000 | statusHighlightRgb(),
                                       cameraX, cameraZ, blockScale, sw, sh,
                                       worldLeft, worldRight, worldTop, worldBottom);
            }
            // Map-mode towns are excluded from the bake (their colour animates) and must be drawn live,
            // but NOT here: these tiles are textured quads and would paint straight over a ctx.fill in the
            // same batch, which is why the highlight never appeared. Deferred to the late pass instead,
            // exactly like the player dots and the claim chunks.
            return;
        }

        int statusRgb = statusHighlightRgb();
        int fillColor0 = fillsEligible ? 0xFF : 0;
        boolean overlayOn = isHighlightMode();
        boolean haveFavorites = !favoriteTownKeys.isEmpty();

        // Opacity depends on which outline style is active, because the two modes have different
        // constraints:
        //  - Smooth: this path draws only at close zoom and the baked tiles take over further out, so it
        //    must use the tiles' own alphas or towns visibly change brightness across that boundary.
        //  - Blocky: there are no tiles at all, so nothing to match. Use the user's Border/Fill Opacity
        //    settings, which is what 1.3.1 did - forcing the tile alphas here (stroke 220 -> 165,
        //    fill 35 -> 64) is what made "blocky" look washed out rather than like the old style.
        int strokeAlpha = config.smoothTownOutlines ? SITE_STROKE_ALPHA : (config.borderAlpha & 0xFF);
        int interiorAlpha = config.smoothTownOutlines ? SITE_FILL_ALPHA : (config.fillAlpha & 0xFF);
        for (RenderTown town : visibleTowns) {
            // town.key() is already lower-cased, so no per-town String allocation here.
            boolean favorite = haveFavorites && favoriteTownKeys.contains(town.key());
            boolean statusHighlighted = overlayOn && isStatusHighlighted(town, townDetails);
            // One colour encodes everything → one outline pass (favourite > status > the town's own colour).
            // Use the SAME opacities as the baked tiles (SITE_*), not borderAlpha/fillAlpha. They used to
            // differ — stroke 220 vs 165, fill 35 vs 64 — so crossing the tile/direct zoom boundary made
            // towns visibly change brightness, most obvious on the bright gold favourites.
            // Favourites differ from other towns only in COLOUR, never in opacity.
            int outlineColor = favorite ? ((strokeAlpha << 24) | 0xFFE066)
                    : statusHighlighted ? (0xFF000000 | statusRgb)
                    : ((strokeAlpha << 24) | (town.data().rgbColor() & 0xFFFFFF));
            // Interior uses the town's FILL colour (nation fill), not the outline colour — except
            // favourites, which stay gold inside as well as out.
            int fillColor = fillColor0 == 0 ? 0
                    : favorite ? ((interiorAlpha << 24) | 0xFFE066)
                    : ((interiorAlpha << 24) | (town.data().fillRgbColor() & 0xFFFFFF));

            List<RingGeometry> townRings = town.rings();
            for (int ri = 0; ri < townRings.size(); ri++) {
                renderRing(ctx, townRings.get(ri), 0, outlineColor, fillColor,
                        ri == 0 ? town.fillData() : null, cameraX, cameraZ, blockScale, sw, sh);
            }
        }
    }

    private boolean isStatusHighlighted(RenderTown town, Map<String, TownPopupData> townDetails) {
        TownPopupData details = townDetails.get(town.key());
        if (details == null) return false;
        return switch (config.townStatusOverlayMode) {
            case 1 -> details.canOutsidersSpawn();
            case 2 -> details.isOverClaimed();
            case 3 -> details.isOpen();
            // 4 (Meganations) and 5 (Alliances) are not single-colour highlights — they recolour the source.
            default -> false;
        };
    }

    /** The single-colour highlight modes (Public/Overclaim/Open). Modes 4/5 recolour the source instead. */
    private boolean isHighlightMode() {
        int m = config.townStatusOverlayMode;
        return m >= 1 && m <= 3;
    }

    // Recomputes the set of towns the status mode highlights, which the tile bake EXCLUDES (they're drawn
    // live instead). Cheap: only re-scans when the mode, the loaded-detail count, or the favourite set
    // actually changes, and only bumps statusVersion (forcing a tile rebuild) when the set really differs.
    private void refreshStatusHighlightKeys(Map<String, TownPopupData> townDetails) {
        int mode = config.townStatusOverlayMode;
        int detailsSize = townDetails.size();
        if (mode == lastStatusMode && detailsSize == lastStatusDetailsSize
                && favoritesVersion == lastStatusFavVersion) {
            return;
        }
        lastStatusMode = mode;
        lastStatusDetailsSize = detailsSize;
        lastStatusFavVersion = favoritesVersion;

        Set<String> next;
        if (!isHighlightMode()) {
            next = Set.of();   // None, or an alliance mode (which recolours the source instead)
        } else {
            next = new HashSet<>();
            for (RenderTown town : townRenderCache.allTowns()) {
                if (favoriteTownKeys.contains(town.key())) continue;   // favourites stay baked as gold
                if (isStatusHighlighted(town, townDetails)) next.add(town.key());
            }
        }
        if (!next.equals(statusHighlightKeys)) {
            statusHighlightKeys = next.isEmpty() ? Set.of() : Set.copyOf(next);
            statusVersion++;
        }
    }

    // The status-highlighted subset is excluded from the tile bake, so draw it here live in the animated
    // status colour — one outline, flush on the border, with no baked line beneath it to drift against.
    /**
     * Draws a 1px line at an arbitrary angle by rotating the matrix around its start point — GuiGraphicsExtractor
     * can only fill axis-aligned rects, so a diagonal has to be a rotated quad. Axis-aligned cases take
     * the plain fill path (no rotation, so they stay pixel-crisp).
     */
    public static void drawThinSegment(GuiGraphicsExtractor ctx, double x1, double y1, double x2, double y2,
                                       int argb, int sw, int sh) {
        if ((x1 < 0 && x2 < 0) || (x1 > sw && x2 > sw) || (y1 < 0 && y2 < 0) || (y1 > sh && y2 > sh)) return;
        int ix1 = (int) Math.round(x1), iy1 = (int) Math.round(y1);
        int ix2 = (int) Math.round(x2), iy2 = (int) Math.round(y2);
        if (iy1 == iy2) {
            if (ix1 != ix2) ctx.fill(Math.min(ix1, ix2), iy1, Math.max(ix1, ix2), iy1 + 1, argb);
            return;
        }
        if (ix1 == ix2) {
            ctx.fill(ix1, Math.min(iy1, iy2), ix1 + 1, Math.max(iy1, iy2), argb);
            return;
        }
        double dx = x2 - x1, dy = y2 - y1;
        int len = (int) Math.ceil(Math.hypot(dx, dy));
        org.joml.Matrix3x2fStack m = ctx.pose();
        m.pushMatrix();
        try {
            m.translate((float) x1, (float) y1);
            m.rotate((float) Math.atan2(dy, dx));
            ctx.fill(0, 0, len, 1, argb);
        } finally {
            m.popMatrix();
        }
    }

    // ── Cached town-outline tiles ─────────────────────────────────────────────

    private boolean renderTownOutlineTiles(GuiGraphicsExtractor ctx, TileLayer layer, int tint, double cameraX, double cameraZ, double blockScale,
                                           int sw, int sh, double worldLeft, double worldRight,
                                           double worldTop, double worldBottom) {
        layer.tint = tint;
        TownRenderCache snapshot = townRenderCache;
        if (snapshot != outlineTilesSnapshot || favoritesVersion != outlineTilesFavVersion
                || statusVersion != outlineTilesStatusVersion
                || config.smoothTownOutlines != outlineTilesSmooth) {
            // Style is part of what a tile contains, and the cache key has no spare bits (16 zoom + 24 tx
            // + 24 ty fills the long), so toggling smooth/blocky must drop the cache or the old style
            // would keep being blitted. Toggling is a settings action, so the re-bake cost is irrelevant.
            // Both layers are invalidated together: which towns are highlighted decides how the two are
            // partitioned, so a stale half would double-draw or drop towns. The highlight COLOUR is not in
            // here on purpose — it is applied as a blit tint, so changing it costs no re-bake at all.
            clearOutlineTiles(mainTiles);
            clearOutlineTiles(statusTiles);
            outlineTilesSmooth = config.smoothTownOutlines;
            outlineTilesSnapshot = snapshot;
            outlineTilesFavVersion = favoritesVersion;
            outlineTilesStatusVersion = statusVersion;
        }
        if (snapshot.allTowns().isEmpty()) return true;   // nothing to draw, but still "handled" (no direct)
        processOutlineTileUploads(layer);
        Set<String> favSnapshot = favoriteTownKeys.isEmpty() ? Set.of() : Set.copyOf(favoriteTownKeys);
        Set<String> statusSnapshot = statusHighlightKeys;   // already immutable (Set.of() / Set.copyOf)

        // Rasterise at PHYSICAL pixel density (like the browser's devicePixelRatio canvas): the zoom
        // bucket is chosen from blockScale × GUI scale, so ppb below is physical px per block and the
        // blit lands 1:1 with the framebuffer instead of being magnified (the old fuzz).
        double density = tileRenderDensity();
        int zoom = chooseOutlineTileZoom(blockScale * density);
        double ppb = outlineTilePixelsPerBlock(zoom);
        double tileWorldSize = OUTLINE_TILE_PIXELS / ppb;

        int minTx = (int) Math.floor(worldLeft / tileWorldSize);
        int maxTx = (int) Math.floor(worldRight / tileWorldSize);
        int minTy = (int) Math.floor(worldTop / tileWorldSize);
        int maxTy = (int) Math.floor(worldBottom / tileWorldSize);

        // Leaflet redraws its canvas on zoom END, scaling the old picture during the gesture. While the
        // zoom bucket is still settling, show a coarser cached level and DON'T bake the new bucket yet
        // (avoids the rebuild flood).
        if (zoom != layer.lastReadyZoom) {
            long now = System.currentTimeMillis();
            if (zoom != layer.pendingZoom) {
                layer.pendingZoom = zoom;
                layer.pendingSinceMs = now;
            }
            if (now - layer.pendingSinceMs < OUTLINE_ZOOM_SETTLE_MS) {
                drawCoarserFallback(ctx, layer, zoom, cameraX, cameraZ, blockScale, sw, sh,
                                    worldLeft, worldRight, worldTop, worldBottom,
                                    density, snapshot, favSnapshot, statusSnapshot);
                return true;
            }
        }

        // Pass 1 — take stock of THIS zoom without drawing yet, and request whatever's missing. Deciding
        // first is what removes the flicker: a coarser fallback must never be painted *underneath* this
        // zoom's tiles, or every line that exists in both layers double-draws (thicker + darker) and then
        // visibly thins the moment the fallback stops.
        boolean allReady = true;
        for (int ty = minTy; ty <= maxTy; ty++) {
            for (int tx = minTx; tx <= maxTx; tx++) {
                long key = outlineTileKey(zoom, tx, ty);
                if (!layer.tiles.containsKey(key) && !layer.empty.contains(key)) {
                    requestOutlineTile(layer, key, zoom, tx, ty, tileWorldSize, ppb, density,
                                       snapshot, favSnapshot, statusSnapshot);
                    allReady = false;
                }
            }
        }

        // Pass 2 — draw exactly ONE layer. Either this zoom's tiles (never mixed with a coarser level), or,
        // if this zoom isn't fully baked yet, the best coarser level so the outlines never vanish.
        //
        // Switching the moment *any* tile of this zoom was ready is what made claims flash on zoom: one
        // baked tile dropped the coarser fallback, leaving every other tile blank until it caught up, so
        // most of the map lost its claims mid-zoom and they popped back in a few at a time. Hold the
        // coarser level until this zoom covers the whole view, then swap in one go. Once this zoom has
        // been established, keep drawing it even if a tile goes missing later (cache eviction, or panning
        // into new world at the same zoom) rather than dropping back to a coarser level and oscillating.
        if (allReady || layer.lastReadyZoom == zoom) {
            for (int ty = minTy; ty <= maxTy; ty++) {
                for (int tx = minTx; tx <= maxTx; tx++) {
                    Identifier tex = layer.tiles.get(outlineTileKey(zoom, tx, ty));
                    if (tex != null) {
                        blitOutlineTile(ctx, layer, tex, tx, ty, tileWorldSize, cameraX, cameraZ, blockScale, sw, sh);
                    }
                }
            }
        } else {
            drawCoarserFallback(ctx, layer, zoom, cameraX, cameraZ, blockScale, sw, sh,
                                worldLeft, worldRight, worldTop, worldBottom,
                                density, snapshot, favSnapshot, statusSnapshot);
        }
        if (allReady) layer.lastReadyZoom = zoom;

        // ALWAYS handled in the tile range — the caller must never fall to the old rectilinear renderer.
        return true;
    }

    /** Single coarser layer for when this zoom has nothing cached: the last fully-ready level if it has
     *  anything, else the pinned whole-map overview floor. Only ever drawn INSTEAD of the current zoom's
     *  tiles, never under them. */
    private void drawCoarserFallback(GuiGraphicsExtractor ctx, TileLayer layer, int zoom, double cameraX, double cameraZ,
                                     double blockScale, int sw, int sh, double worldLeft, double worldRight,
                                     double worldTop, double worldBottom, double density,
                                     TownRenderCache snapshot, Set<String> favSnapshot,
                                     Set<String> statusSnapshot) {
        // Stand in with the last fully-ready level. (Picking the "nearest cached" level instead was tried
        // and reverted: which level is nearest changes as tiles bake and evict, so the stand-in hopped
        // between levels frame to frame and made every town flicker.)
        if (layer.lastReadyZoom != Integer.MIN_VALUE && layer.lastReadyZoom != zoom
                && blitOutlineTilesAtZoom(ctx, layer, layer.lastReadyZoom, cameraX, cameraZ, blockScale,
                                          sw, sh, worldLeft, worldRight, worldTop, worldBottom)) {
            return;
        }
        drewOverviewFloor(ctx, layer, cameraX, cameraZ, blockScale, sw, sh,
                          worldLeft, worldRight, worldTop, worldBottom,
                          density, snapshot, favSnapshot, statusSnapshot);
    }

    /** Blits whatever is cached at {@code zoom}, scaled to the current view. Partial coverage is drawn
     *  rather than skipped: zooming out exposes world the finer level never covered, so requiring full
     *  coverage here meant the stand-in never drew at all. Returns false only if nothing was drawn, so
     *  the caller can fall back to the overview floor. */
    private boolean blitOutlineTilesAtZoom(GuiGraphicsExtractor ctx, TileLayer layer, int zoom, double cameraX, double cameraZ,
                                           double blockScale, int sw, int sh, double worldLeft,
                                           double worldRight, double worldTop, double worldBottom) {
        double ppb = outlineTilePixelsPerBlock(zoom);
        double tileWorldSize = OUTLINE_TILE_PIXELS / ppb;
        int minTx = (int) Math.floor(worldLeft / tileWorldSize);
        int maxTx = (int) Math.floor(worldRight / tileWorldSize);
        int minTy = (int) Math.floor(worldTop / tileWorldSize);
        int maxTy = (int) Math.floor(worldBottom / tileWorldSize);
        // Draw whatever of this zoom IS cached — partial coverage beats nothing. (It used to bail unless
        // every visible tile was present, which zooming OUT can never satisfy: the new view exposes world
        // the old, more-zoomed-in level never covered — so the outlines vanished mid-zoom.)
        boolean drewAny = false;
        for (int ty = minTy; ty <= maxTy; ty++) {
            for (int tx = minTx; tx <= maxTx; tx++) {
                Identifier tex = layer.tiles.get(outlineTileKey(zoom, tx, ty));
                if (tex != null) {
                    blitOutlineTile(ctx, layer, tex, tx, ty, tileWorldSize, cameraX, cameraZ, blockScale, sw, sh);
                    drewAny = true;
                }
            }
        }
        return drewAny;
    }

    /** Zoom bucket of the pinned whole-map overview level. */
    private static int overviewOutlineZoom() {
        return (int) Math.round(Math.log(OUTLINE_OVERVIEW_PPB) / Math.log(OUTLINE_ZOOM_STEP));
    }

    /** Requests (once) and blits the pinned overview level for the visible area. Used only as the floor
     *  when no finer level had anything cached, so it never stacks under the fine tiles. */
    private void drewOverviewFloor(GuiGraphicsExtractor ctx, TileLayer layer, double cameraX, double cameraZ, double blockScale,
                                   int sw, int sh, double worldLeft, double worldRight,
                                   double worldTop, double worldBottom, double density,
                                   TownRenderCache snapshot, Set<String> favSnapshot,
                                   Set<String> statusSnapshot) {
        int ovZoom = overviewOutlineZoom();
        double ovPpb = outlineTilePixelsPerBlock(ovZoom);
        double ovTileWorld = OUTLINE_TILE_PIXELS / ovPpb;
        int minTx = (int) Math.floor(worldLeft / ovTileWorld);
        int maxTx = (int) Math.floor(worldRight / ovTileWorld);
        int minTy = (int) Math.floor(worldTop / ovTileWorld);
        int maxTy = (int) Math.floor(worldBottom / ovTileWorld);
        for (int ty = minTy; ty <= maxTy; ty++) {
            for (int tx = minTx; tx <= maxTx; tx++) {
                long key = outlineTileKey(ovZoom, tx, ty);
                Identifier tex = layer.tiles.get(key);
                if (tex != null) {
                    blitOutlineTile(ctx, layer, tex, tx, ty, ovTileWorld, cameraX, cameraZ, blockScale, sw, sh);
                } else if (!layer.empty.contains(key)) {
                    requestOutlineTile(layer, key, ovZoom, tx, ty, ovTileWorld, ovPpb, density,
                                       snapshot, favSnapshot, statusSnapshot);
                }
            }
        }
    }

    /** Physical-px-per-GUI-px multiple the tiles rasterise at (capped window scale factor). */
    private static double tileRenderDensity() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getWindow() == null) return 1.0;
        return Math.min(OUTLINE_TILE_MAX_DENSITY, Math.max(1.0, client.getWindow().getGuiScale()));
    }

    private void requestOutlineTile(TileLayer layer, long key, int zoom, int tx, int ty, double tileWorldSize, double ppb,
                                    double density, TownRenderCache snapshot, Set<String> favSnapshot,
                                    Set<String> statusSnapshot) {
        if (layer.loading.size() >= MAX_OUTLINE_TILE_LOADS) return;
        if (!layer.loading.add(key)) return;
        // Stamp the versions this tile is being baked with, so a tile that finishes after the favourite or
        // status-exclusion set changes is rejected on upload instead of showing a stale exclusion.
        int favVer = outlineTilesFavVersion;
        int statVer = outlineTilesStatusVersion;
        outlineTileExecutor.execute(() -> {
            try {
                NativeImage img = rasterizeOutlineTile(layer, zoom, tx, ty, tileWorldSize, ppb, density, snapshot, favSnapshot, statusSnapshot);
                if (img == null) layer.empty.add(key);
                else layer.completed.add(new LoadedOutlineTile(key, snapshot, img, favVer, statVer));
            } catch (Exception e) {
                LOGGER.warn("[TownyMap] Failed to rasterise town outline tile: {}", e.getMessage());
            } finally {
                layer.loading.remove(key);
            }
        });
    }

    private NativeImage rasterizeOutlineTile(TileLayer layer, int zoom, int tx, int ty, double tileWorldSize, double ppb,
                                             double density, TownRenderCache snapshot, Set<String> favSnapshot,
                                             Set<String> statusSnapshot) {
        double tileWorldX = tx * tileWorldSize;
        double tileWorldZ = ty * tileWorldSize;
        double pad = tileWorldSize * 0.02;
        double left = tileWorldX - pad, right = tileWorldX + tileWorldSize + pad;
        double top = tileWorldZ - pad, bottom = tileWorldZ + tileWorldSize + pad;

        HashSet<RenderTown> towns = new HashSet<>();
        int minCellX = floorToIndexCell(left), maxCellX = floorToIndexCell(right);
        int minCellZ = floorToIndexCell(top), maxCellZ = floorToIndexCell(bottom);
        Map<Long, List<RenderTown>> index = snapshot.spatialIndex();
        for (int cz = minCellZ; cz <= maxCellZ; cz++) {
            for (int cx = minCellX; cx <= maxCellX; cx++) {
                List<RenderTown> cell = index.get(indexCellKey(cx, cz));
                if (cell != null) towns.addAll(cell);
            }
        }
        if (towns.isEmpty()) return null;

        // Input geometry: ALWAYS the raw rings (lod 0), simplified in pixel space — Leaflet's way, at
        // every zoom out to the full overview. Pre-snapped LOD grids change the character of the
        // simplified output at each LOD band (and can collapse mid-size towns into degenerate rings that
        // vanish), which is exactly the "looks different past this zoom" the raw path avoids. The
        // settle-then-redraw debounce makes raw affordable: one off-thread bake per settled zoom, and the
        // radial reduction stage eats most of the raw points in a single O(n) pass anyway.
        double simplifyTol = OUTLINE_SIMPLIFY_TOLERANCE_PX * density;
        // Style-dependent bake. Blocky reproduces the direct renderer exactly: the LOD tier that zoom
        // would have picked, no pixel-space simplification, no anti-aliasing, and the user's own
        // Border/Fill Opacity - so a baked tile is what the direct path would have drawn, minus the
        // thousands of draw calls.
        boolean smooth = config.smoothTownOutlines;
        double guiScale = ppb / density;                       // GUI-space blockScale this tile represents
        int lod = smooth ? 0 : Math.min(LOD_GRID.length - 1, selectLod(guiScale));
        int strokeAlphaT = smooth ? SITE_STROKE_ALPHA : (config.borderAlpha & 0xFF);
        int fillAlphaT   = smooth ? SITE_FILL_ALPHA   : (config.fillAlpha & 0xFF);
        // The direct path also suppresses fills past FILL_MAX_TOWNS, but that is a per-frame cost guard
        // which a baked tile does not need; the scale gate is the part that is actually about looks.
        boolean bakeFill = smooth || guiScale >= TOWN_FILL_MIN_SCALE;
        // Anti-aliased strokes with round joins over DP-simplified rings — the squaremap/Leaflet look.
        // The fine 1.1× zoom buckets keep the blit within ~5% of 1:1, and the LINEAR sampler smooths the
        // residual resample uniformly (sub-half-pixel), the same way the browser composites its canvas.
        BufferedImage img = new BufferedImage(OUTLINE_TILE_PIXELS, OUTLINE_TILE_PIXELS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        boolean drew = false;
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    smooth ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            // Zoom-adaptive on-screen stroke width: fine when zoomed in (a chunk spans many tile px),
            // a touch bolder when zoomed out where the diagonal simplification reads best. Fixes the
            // "too fat when zoomed in close" of the old constant weight. 16*ppb = a chunk's width in tile
            // px; guiW is the resulting on-screen width in GUI px (tiles blit at ~1/density, so ×density
            // to convert to the tile-space stroke).
            // Upper clamp only bites when zoomed OUT. Keeping it under a pixel there is what stops the
            // far-zoom mush: a ~2px stroke on a town that's only a few px wide swallows the shape.
            if (smooth) {
                double guiW = Math.max(0.65, Math.min(0.85, 20.0 / (16.0 * ppb)));
                g.setStroke(new BasicStroke((float) (guiW * density),
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            } else {
                // Exactly one GUI pixel, square ends and mitred corners: the direct path's
                // drawHorizontalLine/drawVerticalLine produce hard 1px lines with square joins.
                g.setStroke(new BasicStroke((float) density, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            }
            for (RenderTown town : towns) {
                if (!town.intersectsWorld(left, right, top, bottom)) continue;
                // Each layer bakes only its own towns, through the SAME rasteriser — so a highlighted
                // town's line is pixel-identical to its neighbours', which stroking it live could never
                // achieve (a ctx.fill line cannot be an anti-aliased sub-pixel stroke over a soft fill).
                boolean statusHit = statusSnapshot.contains(town.key());
                if (layer.statusLayer != statusHit) continue;
                // Favourites are baked exactly like every other town — identical thin stroke, identical
                // translucent fill — only the COLOUR differs (gold). Previously they also got a fully
                // opaque line, which made them shout next to the ~65%-alpha neighbours.
                boolean favorite = favSnapshot.contains(town.key());
                // Outline uses squaremap's stroke colour, interior uses its fillColor — on EarthMC those
                // are the nation's two colours, so towns read as their nation instead of one flat tint.
                // White on the status layer: the blit tint multiplies, so white x tint == the tint's
                // colour exactly, while the baked alphas (stroke 165 / fill 64) survive untouched.
                int rgb = layer.statusLayer ? 0xFFFFFF
                        : favorite ? 0xFFE066 : (town.data().rgbColor() & 0xFFFFFF);
                int fillRgb = layer.statusLayer ? 0xFFFFFF
                        : favorite ? 0xFFE066 : (town.data().fillRgbColor() & 0xFFFFFF);

                // With "Far Zoom Town Dots" ON, a town only a few px across collapses to a crisp dot
                // (an anti-aliased outline that small can read as a blob). With it OFF we keep drawing
                // the real outline right down to 1px — below that there is literally no shape left to
                // resolve, so the dot is the only thing that can be drawn at all.
                double tw = (town.maxX() - town.minX()) * ppb;
                double th = (town.maxZ() - town.minZ()) * ppb;
                double dotBelow = config.farZoomTownDots ? 2.5 * density : 1.0;
                if (Math.max(tw, th) <= dotBelow) {
                    int cx = (int) Math.round(((town.minX() + town.maxX()) / 2.0 - tileWorldX) * ppb);
                    int cy = (int) Math.round(((town.minZ() + town.maxZ()) / 2.0 - tileWorldZ) * ppb);
                    g.setColor(awtColor(0xC8000000 | rgb));   // dot needs more alpha than a stroke to read
                    g.fillRect(cx, cy, 1, 1);
                    drew = true;
                    continue;
                }
                // All of a town's rings go into ONE even-odd path, then fill once. A town's rings are an
                // outer boundary plus any unclaimed pockets inside it; filling them one at a time painted
                // those pockets solid instead of punching them out, so a hollow town read as fully claimed
                // while its outline correctly traced the hole. Even-odd makes an enclosed ring subtract.
                Path2D.Double path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
                boolean anyRing = false;
                for (RingGeometry ring : town.rings()) {
                    int[] xs = ring.lodX(lod), zs = ring.lodZ(lod);
                    if (xs.length < 2) continue;
                    double[] px = new double[xs.length];
                    double[] pz = new double[xs.length];
                    for (int i = 0; i < xs.length; i++) {
                        px[i] = (xs[i] - tileWorldX) * ppb;
                        pz[i] = (zs[i] - tileWorldZ) * ppb;
                    }
                    int n = smooth ? simplifyRing(px, pz, simplifyTol) : xs.length;
                    path.moveTo(px[0], pz[0]);
                    for (int i = 1; i < n; i++) {
                        path.lineTo(px[i], pz[i]);
                    }
                    path.closePath();
                    anyRing = true;
                }
                if (anyRing) {
                    // Leaflet's _fillStroke on the SAME simplified path: soft fill first, wide translucent
                    // stroke over it. Fill and stroke can never disagree about the shape — the reason the
                    // site's borders always sit flush on their fills. Stroking the combined path also
                    // outlines the holes, which is what you want: a pocket gets its own border.
                    if (bakeFill) {
                        g.setColor(awtColor((fillAlphaT << 24) | fillRgb));
                        g.fill(path);
                    }
                    g.setColor(awtColor((strokeAlphaT << 24) | rgb));
                    g.draw(path);
                    drew = true;
                }
            }
        } finally {
            g.dispose();
        }
        if (!drew) return null;

        NativeImage ni = new NativeImage(OUTLINE_TILE_PIXELS, OUTLINE_TILE_PIXELS, false);
        for (int y = 0; y < OUTLINE_TILE_PIXELS; y++) {
            for (int x = 0; x < OUTLINE_TILE_PIXELS; x++) {
                ni.setPixel(x, y, img.getRGB(x, y));   // BufferedImage.getRGB is ARGB; NativeImage.setPixel takes ARGB
            }
        }
        return ni;
    }

    /**
     * Leaflet 1.9.4's LineUtil.simplify, in-place: STAGE 1 radial vertex reduction (drop every point
     * within {@code tolerance} of the previously kept point — the aggressive pass that collapses chunk
     * staircases the moment their steps shrink under the tolerance), then STAGE 2 Douglas-Peucker.
     * Compacts survivors to the front of the arrays and returns their count. Endpoints always kept (the
     * ring closes back to index 0 via closePath). Iterative DP so a huge ring can't overflow the stack.
     */
    public static int simplifyRing(double[] px, double[] pz, double tolerance) {
        int n = px.length;
        if (n < 8) return n;                       // tiny ring: nothing worth removing
        double sqTol = tolerance * tolerance;

        // Stage 1 — Leaflet _reducePoints: radial distance to the last KEPT point.
        int m = 1;                                 // px[0]/pz[0] kept in place
        int prev = 0;
        for (int i = 1; i < n; i++) {
            double dx = px[i] - px[prev], dz = pz[i] - pz[prev];
            if (dx * dx + dz * dz > sqTol) {
                px[m] = px[i];
                pz[m] = pz[i];
                prev = i;
                m++;
            }
        }
        if (prev < n - 1) {
            px[m] = px[n - 1];
            pz[m] = pz[n - 1];
            m++;
        }
        n = m;
        if (n < 3) return n;

        // Stage 2 — Douglas-Peucker over the reduced points.
        boolean[] keep = new boolean[n];
        keep[0] = keep[n - 1] = true;
        double tolSq = tolerance * tolerance;
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        stack.push(new int[]{0, n - 1});
        while (!stack.isEmpty()) {
            int[] seg = stack.pop();
            int a = seg[0], b = seg[1];
            if (b - a < 2) continue;
            double ax = px[a], az = pz[a];
            double dx = px[b] - ax, dz = pz[b] - az;
            double lenSq = dx * dx + dz * dz;
            double maxDistSq = -1.0;
            int maxIdx = -1;
            for (int i = a + 1; i < b; i++) {
                double distSq;
                if (lenSq <= 1e-9) {               // degenerate segment → plain point distance
                    double ex = px[i] - ax, ez = pz[i] - az;
                    distSq = ex * ex + ez * ez;
                } else {
                    double t = ((px[i] - ax) * dx + (pz[i] - az) * dz) / lenSq;
                    t = Math.max(0.0, Math.min(1.0, t));
                    double ex = px[i] - (ax + t * dx), ez = pz[i] - (az + t * dz);
                    distSq = ex * ex + ez * ez;
                }
                if (distSq > maxDistSq) {
                    maxDistSq = distSq;
                    maxIdx = i;
                }
            }
            if (maxDistSq > tolSq) {
                keep[maxIdx] = true;
                stack.push(new int[]{a, maxIdx});
                stack.push(new int[]{maxIdx, b});
            }
        }
        int kept = 0;
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                px[kept] = px[i];
                pz[kept] = pz[i];
                kept++;
            }
        }
        return kept;
    }

    private void processOutlineTileUploads(TileLayer layer) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        for (int i = 0; i < MAX_OUTLINE_TILE_UPLOADS_PER_FRAME; i++) {
            LoadedOutlineTile loaded = layer.completed.poll();
            if (loaded == null) return;
            if (loaded.snapshot() != outlineTilesSnapshot
                    || loaded.favVersion() != outlineTilesFavVersion
                    || loaded.statusVersion() != outlineTilesStatusVersion
                    || layer.tiles.containsKey(loaded.key())) {
                loaded.image().close();   // stale (data/favourites/status changed) or duplicate
                continue;
            }
            try {
                Identifier id = Identifier.fromNamespaceAndPath("townymapaddon",
                        "town_outline_tile/" + layer.name + "/" + loaded.key());
                OutlineTileTexture tex = new OutlineTileTexture(
                        () -> "TownyMap " + layer.name + " outline tile " + loaded.key(), loaded.image());
                client.getTextureManager().register(id, tex);
                layer.tiles.put(loaded.key(), id);
                evictOldOutlineTiles(layer, client);
            } catch (Exception e) {
                loaded.image().close();
                LOGGER.warn("[TownyMap] Failed to upload town outline tile: {}", e.getMessage());
            }
        }
    }

    private void blitOutlineTile(GuiGraphicsExtractor ctx, TileLayer layer, Identifier texture, int tx, int ty, double tileWorldSize,
                                 double cameraX, double cameraZ, double blockScale, int sw, int sh) {
        double tileWorldX = tx * tileWorldSize;
        double tileWorldZ = ty * tileWorldSize;
        int x1 = toScreenX(tileWorldX, cameraX, blockScale, sw);
        int y1 = toScreenY(tileWorldZ, cameraZ, blockScale, sh);
        int x2 = toScreenX(tileWorldX + tileWorldSize, cameraX, blockScale, sw);
        int y2 = toScreenY(tileWorldZ + tileWorldSize, cameraZ, blockScale, sh);
        if (x2 <= 0 || x1 >= sw || y2 <= 0 || y1 >= sh) return;
        int drawW = Math.max(1, x2 - x1);
        int drawH = Math.max(1, y2 - y1);
        // Exact-rect blit, no bleed: the old ±1px bleed stretched every tile by +2px, so it was never 1:1
        // and NEAREST duplicated random rows of the anti-aliased lines (visible as smudge). Adjacent tiles
        // share their rounded screen edge by construction (x2 of one IS x1 of the next), so there are no
        // gaps to hide, and overlapping translucent AA strokes would double-blend into dark seams anyway.
        ctx.blit(RenderPipelines.GUI_TEXTURED, texture, x1, y1, 0.0F, 0.0F,
                drawW, drawH, OUTLINE_TILE_PIXELS, OUTLINE_TILE_PIXELS, OUTLINE_TILE_PIXELS, OUTLINE_TILE_PIXELS,
                layer.tint);
    }

    private void clearOutlineTiles(TileLayer layer) {
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            for (Identifier id : layer.tiles.values()) client.getTextureManager().release(id);
        }
        layer.tiles.clear();
        layer.empty.clear();
        layer.loading.clear();
        layer.lastReadyZoom = Integer.MIN_VALUE;   // cached tiles are gone → no stale zoom to fall back to
        LoadedOutlineTile loaded;
        while ((loaded = layer.completed.poll()) != null) loaded.image().close();
    }

    private void evictOldOutlineTiles(TileLayer layer, Minecraft client) {
        // The pinned overview level is never evicted — it's the floor that keeps outlines on screen, and
        // it's only a couple dozen tiles.
        int pinnedZoom = overviewOutlineZoom();
        while (layer.tiles.size() > MAX_OUTLINE_TILES) {
            Long victim = null;
            for (Long k : layer.tiles.keySet()) {
                if (outlineTileKeyZoom(k) != pinnedZoom) { victim = k; break; }
            }
            if (victim == null) break;   // nothing left but pinned overview tiles
            client.getTextureManager().release(layer.tiles.get(victim));
            layer.tiles.remove(victim);
        }
    }

    // Zoom needs 16 bits, not 8: with the fine 1.03 step the bucket index spans roughly -141..+115, so an
    // 8-bit field aliased the whole-map overview (-141) onto a very-close zoom (+115). A collision handed
    // back a massively-coarse tile for a fine slot — it rendered hugely magnified (fat, blocky) until the
    // correct tile baked and overwrote the key. 16+24+24 fills the long exactly.
    private static long outlineTileKey(int zoom, int tx, int ty) {
        return ((long) (zoom & 0xFFFF) << 48) | ((long) (tx & 0xFFFFFF) << 24) | (long) (ty & 0xFFFFFF);
    }

    /** Zoom bucket packed into a tile key (sign-extended back from its 16-bit field). */
    private static int outlineTileKeyZoom(long key) {
        return (short) ((key >>> 48) & 0xFFFF);
    }

    // Fine zoom buckets (10% steps) instead of power-of-2. With power-of-2, a tile was drawn anywhere
    // from 1:1 down to 0.5× within a bucket — the GPU then filters that scaling, which is the blur AND
    // the "sharp→soft→sharp as you zoom" oscillation. With 10% buckets the tile is always blitted within
    // ~5% of 1:1, so there's essentially nothing to filter and the lines stay crisp at every zoom.
    // Fine 3% zoom buckets: the tile then always blits within ~1.5% of exactly 1:1 with the framebuffer,
    // so NEAREST sampling passes the baked pixels through pixel-perfect. The old 10% buckets left up to a
    // 5% scale mismatch → resample blur (the "fuzzy lines"). Panning holds one bucket (reuse); only a
    // settled zoom change rebakes.
    private static final double OUTLINE_ZOOM_STEP = 1.03;
    // Coarse power-of-2-ish snap fallback for scales below anything actually reachable. This USED to be
    // 0.008 — but the full overview floor is 0.0625/8 ≈ 0.0078, so the LAST zoom-out notch fell into the
    // coarse snap and blitted a tile from a bucket up to ~2× away: blocky right when the map should look
    // its smoothest. DP-simplified tiles are cheap to re-rasterise, so fine 1.1× buckets now cover the
    // entire reachable range; the coarse path remains only as a safety net for absurd scales.
    private static final double OUTLINE_FINE_MIN_SCALE = 0.004;
    private static final int OUTLINE_COARSE_SNAP = 23;  // ~one power-of-2 step (log2/log1.03 ≈ 23.4)

    private static int chooseOutlineTileZoom(double blockScale) {
        int z = (int) Math.round(Math.log(blockScale) / Math.log(OUTLINE_ZOOM_STEP));
        if (blockScale < OUTLINE_FINE_MIN_SCALE) {
            z = Math.round((float) z / OUTLINE_COARSE_SNAP) * OUTLINE_COARSE_SNAP;
        }
        return z;
    }

    private static double outlineTilePixelsPerBlock(int zoom) {
        return Math.pow(OUTLINE_ZOOM_STEP, zoom);
    }

    private static Color awtColor(int argb) {
        return new Color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
    }

    private record LoadedOutlineTile(long key, TownRenderCache snapshot, NativeImage image,
                                     int favVersion, int statusVersion) {}

    private static final class OutlineTileTexture extends DynamicTexture {
        private OutlineTileTexture(java.util.function.Supplier<String> name, NativeImage image) {
            super(name, image);
            // NEAREST, not LINEAR: the tile is drawn at a non-1:1 scale every frame, and linear filtering
            // re-blurs it on the GPU no matter how crisp we rasterised it. Nearest keeps the edges hard.
            // (The 2× supersample above gives the tile enough line width to survive the nearest downscale.)
            this.sampler = RenderSystem.getSamplerCache().getSampler(
                    AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST, FilterMode.NEAREST, false);
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

    private void renderRing(GuiGraphicsExtractor ctx, RingGeometry ring, int lodBoost,
                            int borderColor, int fillColor, int[] townFill,
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
        // townFill is non-null only for the town's FIRST ring: the rects already cover the whole
        // town with its pockets removed, so drawing them once per town is both correct and cheaper.
        if (townFill != null && (fillColor >>> 24) > 0 && bbW > 2 && bbH > 2) {
            renderCachedFill(ctx, townFill, fillColor, cameraX, cameraZ, blockScale, sw, sh);
        }

        if ((borderColor >>> 24) == 0) return;

        // Pick the LOD whose snap grid maps to a few pixels at this zoom (higher zoom → finer detail).
        // lodBoost coarsens it further when the map is busy — still the real shape, just fewer segments.
        // BUT when the interior is filled, trace the outline at the finest LOD (level 0 = the raw chunk
        // outline) so it actually bounds the EXACT fill rects — a coarser snap lets the fill spill outside
        // the border. Fills are gated to ≤120 towns at zoomed-in scale, so the finer outline is cheap here.
        int lod = (fillColor >>> 24) > 0 ? 0
                : Math.min(LOD_GRID.length - 1, selectLod(blockScale) + lodBoost);
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
                    if (prevX != cx) ctx.horizontalLine(Math.min(prevX, cx), Math.max(prevX, cx), cy, borderColor);
                    else ctx.fill(cx, cy, cx + 1, cy + 1, borderColor);   // 1px corner dot
                } else if (prevX == cx) {
                    ctx.verticalLine(cx, Math.min(prevY, cy), Math.max(prevY, cy), borderColor);
                } else {
                    // Defensive (Towny data is axis-aligned, so this is unreachable):
                    // draw an L-bend to keep the loop connected without a diagonal.
                    ctx.horizontalLine(Math.min(prevX, cx), Math.max(prevX, cx), prevY, borderColor);
                    ctx.verticalLine(cx, Math.min(prevY, cy), Math.max(prevY, cy), borderColor);
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
    private static void renderBoundingBoxBorder(GuiGraphicsExtractor ctx,
                                                int x1, int y1, int x2, int y2,
                                                int color, int sw, int sh) {
        int bx1 = Math.max(-1, Math.min(x1, x2));
        int bx2 = Math.min(sw,  Math.max(x1, x2));
        int by1 = Math.max(-1, Math.min(y1, y2));
        int by2 = Math.min(sh,  Math.max(y1, y2));
        if (bx1 >= bx2 || by1 >= by2) return;
        ctx.horizontalLine(bx1, bx2, by1, color);
        ctx.horizontalLine(bx1, bx2, by2, color);
        ctx.verticalLine(bx1, by1, by2, color);
        ctx.verticalLine(bx2, by1, by2, color);
    }

    private static void renderTinyTown(GuiGraphicsExtractor ctx, int x1, int y1, int x2, int y2,
                                       int color, int sw, int sh) {
        if ((color >>> 24) == 0) return;
        int x = Math.max(0, Math.min(sw - 1, (x1 + x2) / 2));
        int y = Math.max(0, Math.min(sh - 1, (y1 + y2) / 2));
        ctx.fill(x, y, x + 1, y + 1, color);
    }

    private static void renderCachedFill(GuiGraphicsExtractor ctx, int[] fd, int color,
                                         double cameraX, double cameraZ, double blockScale,
                                         int sw, int sh) {
        // fd is a flat int[] with 4 values per rect: [minX, minZ, maxX, maxZ, ...]
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
        favoritesVersion++;   // tiles bake favourites in → rebuild them when the set changes
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
        List<TownData> towns = currentRenderSource();
        TownRenderCache cache = townRenderCache;
        if (cache.matches(towns)) return cache;
        requestTownRenderCacheBuild(towns);
        return cache;
    }

    /** The town list to render this frame: alliance-recoloured in modes 4/5, otherwise api.getTowns(). */
    private List<TownData> currentRenderSource() {
        List<TownData> s = renderSource;
        return s != null ? s : api.getTowns();
    }

    private List<TownData> effectiveSource() {
        List<TownData> base = api.getTowns();
        // Archive snapshots keep their own historical colours: the alliance recolour resolves nations from the
        // LIVE feed, which doesn't know the archived towns, so applying it here would black the whole snapshot
        // out (and make leaving archive look like nothing changed). Always show the snapshot as-is.
        if (TownyMapMod.isArchiveMode()) return base;

        // A property filter in the search bar dims the map the same way an alliance layer does: matches keep
        // their colour, everything else goes black, so the result set reads at a glance instead of being a
        // list you have to cross-reference. Takes precedence — you filtered, that's what you want to see.
        if (net.townymap.gui.TownSearchOverlay.isFilterActive()) {
            java.util.Set<String> keep = net.townymap.gui.TownSearchOverlay.filterMatches();
            int fVersion = net.townymap.gui.TownSearchOverlay.filterVersion();
            boolean dropDimmed = TownyMapMod.composingScreenshot() && TownyMapMod.screenshotHidesDimmedTowns();
            if (!dropDimmed && base == filteredBase && fVersion == filteredVersion && filtered != null) {
                return filtered;
            }
            ArrayList<TownData> out = new ArrayList<>(base.size());
            for (TownData t : base) {
                if (keep.contains(t.key())) out.add(t);
                else if (!dropDimmed) out.add(t.withColors(0x000000, 0x000000));
            }
            if (dropDimmed) return List.copyOf(out);   // one-off for the capture; don't poison the cache
            filteredBase = base;
            filteredVersion = fVersion;
            filtered = List.copyOf(out);
            return filtered;
        }

        int mode = config == null ? 0 : config.townStatusOverlayMode;
        if (mode != 4 && mode != 5) return base;   // not an alliance layer → identity preserved (no rebuild)
        int version = TownyMapMod.allianceDataVersion();
        if (version == 0) return base;             // alliance data not loaded yet → keep normal colours
        boolean dropDimmedAlliance =
                TownyMapMod.composingScreenshot() && TownyMapMod.screenshotHidesDimmedTowns();
        if (!dropDimmedAlliance && base == recoloredBase && mode == recoloredMode
                && version == recoloredVersion && recolored != null) {
            return recolored;
        }
        boolean mega = (mode == 4);
        ArrayList<TownData> out = new ArrayList<>(base.size());
        for (TownData t : base) {
            int[] c = TownyMapMod.allianceColorsForNation(TownyMapMod.bareTownNation(t.name()), mega);
            // In an alliance layer, towns that belong to one show its colour; every other town (nationless,
            // or in a nation that isn't in this layer) is blacked out so the alliances stand alone.
            if (c != null) out.add(t.withColors(c[0], c[1]));
            else if (!dropDimmedAlliance) out.add(t.withColors(0x000000, 0x000000));
        }
        if (dropDimmedAlliance) return List.copyOf(out);   // one-off for the capture
        recoloredBase = base;
        recoloredMode = mode;
        recoloredVersion = version;
        recolored = List.copyOf(out);
        return recolored;
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
                if (currentRenderSource() == source) {
                    townRenderCache = built;
                }
            } finally {
                townCacheBuildRunning.set(false);
                if (currentRenderSource() != townRenderCache.source()) {
                    requestTownRenderCacheBuild(currentRenderSource());
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
                              int[] fillData,
                              int minX, int maxX, int minZ, int maxZ) {
        private static RenderTown from(TownData town) {
            ArrayList<RingGeometry> rings = new ArrayList<>(town.polygonRings().size());
            for (int[][] ring : town.polygonRings()) {
                rings.add(RingGeometry.from(ring));
            }
            // The fill belongs to the TOWN, not to each ring — see buildTownFillData.
            return new RenderTown(town, town.name(), town.key(), town.renderSignature(),
                    List.copyOf(rings),
                    buildTownFillData(rings, town.minX(), town.maxX(), town.minZ(), town.maxZ()),
                    town.minX(), town.maxX(), town.minZ(), town.maxZ());
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

            return new RingGeometry(lodX, lodZ, minX, maxX, minZ, maxZ);
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
     * Scanline-fills a town from ALL of its rings at once, at LOD 0 (the exact chunk outline).
     * Crossings are consumed in even-odd pairs, so a ring enclosed by another one — an unclaimed
     * pocket inside a town — toggles back to "outside" and is left empty. Filling each ring
     * separately, which is what this used to do, painted those pockets solid: the outline correctly
     * traced the hole while the fill covered it, showing chunks as claimed that never were.
     */
    private static int[] buildTownFillData(List<RingGeometry> rings, int minX, int maxX, int minZ, int maxZ) {
        int total = 0;
        for (RingGeometry r : rings) total += r.lodX(0).length;
        if (total < 3) return new int[0];

        int[] x = new int[total], z = new int[total];
        int[] ringEnd = new int[rings.size()];   // exclusive end index of each ring in x/z
        int at = 0, k = 0;
        for (RingGeometry r : rings) {
            int[] rx = r.lodX(0), rz = r.lodZ(0);
            System.arraycopy(rx, 0, x, at, rx.length);
            System.arraycopy(rz, 0, z, at, rz.length);
            at += rx.length;
            ringEnd[k++] = at;
        }
        return buildFillData(x, z, ringEnd, minX, maxX, minZ, maxZ);
    }

    /**
     * Builds fill data as a flat int[] with 4 ints per rect: [minX, minZ, maxX, maxZ, ...].
     * Uses Arrays.sort for O(n log n) band deduplication instead of the previous O(n²) scan.
     * {@code ringEnd} holds the exclusive end index of each ring, so edges wrap within their own
     * ring instead of stitching the last vertex of one ring to the first vertex of the next.
     */
    private static int[] buildFillData(int[] x, int[] z, int[] ringEnd,
                                       int minX, int maxX, int minZ, int maxZ) {
        int n = x.length;
        if (n < 3 || minX == maxX || minZ == maxZ) return new int[0];

        // Per-vertex successor, respecting ring boundaries.
        int[] next = new int[n];
        int start = 0;
        for (int end : ringEnd) {
            for (int i = start; i < end; i++) next[i] = (i + 1 < end) ? i + 1 : start;
            start = end;
        }

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
                int j = next[i];
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

    private void renderNationCapitalStars(GuiGraphicsExtractor ctx,
                                          double cameraX, double cameraZ, double blockScale,
                                          int sw, int sh,
                                          double worldLeft, double worldRight,
                                          double worldTop, double worldBottom,
                                          Map<String, EarthMcNationData> nationDetails) {
        if (TownyMapMod.composingScreenshot() && !TownyMapMod.screenshotWantsNationStars()) {
            nationStarHits = List.of();
            return;
        }
        if (!config.townsEnabled || !config.nationStarsEnabled || nationDetails.isEmpty()) {
            nationStarHits = List.of();
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;

        ArrayList<StarHit> hits = new ArrayList<>();
        for (EarthMcNationData nation : nationDetails.values()) {
            double markerX, markerZ;

            // The API's nation spawn is an EARTH coordinate. On another world it would plant the star at
            // a place that has nothing to do with the nation's presence there -- a moon outpost sits
            // somewhere else entirely, and the two worlds' coordinates overlap numerically, so the star
            // would look plausible while being wrong. Off Earth, use only the capital's polygon in the
            // world actually being shown, and drop nations with no claim there at all.
            if (TownyMapMod.viewingEarth() && nation.hasSpawn()) {
                markerX = nation.spawnX();
                markerZ = nation.spawnZ();
            } else if (!nation.capitalName().isBlank()) {
                // The town list is the active world's, so this resolves to the capital's outpost there.
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

            ctx.text(client.font, "★", x - 3, y - 5, 0xFFFFD84D, true);
            hits.add(new StarHit(x, y, nation.name()));   // record where it landed for click hit-testing
        }
        nationStarHits = hits.isEmpty() ? List.of() : List.copyOf(hits);
    }

    private record StarHit(int x, int y, String nation) {}
    private volatile List<StarHit> nationStarHits = List.of();
    private static final double STAR_CLICK_RADIUS = 7.0;

    /** The nation whose capital star is under the given screen point (last drawn frame), or null. */
    public String nationStarAt(double screenX, double screenY) {
        String best = null;
        double bestDist = STAR_CLICK_RADIUS + 1;
        for (StarHit h : nationStarHits) {
            double d = Math.hypot(screenX - h.x(), screenY - h.y());
            if (d < bestDist) { bestDist = d; best = h.nation(); }
        }
        return bestDist <= STAR_CLICK_RADIUS ? best : null;
    }

    private TownData townByName(String name) {
        RenderTown town = townRenderCache().byName().get(name.toLowerCase(Locale.ROOT));
        return town == null ? null : town.data();
    }

    // ── Nation join-range overlay ────────────────────────────────────────────
    // EarthMC lets a town join a nation within 5k of the capital, and every existing town extends the
    // reachable frontier by 1.5k around itself; the union of those circles is the joinable area. Every
    // centre comes from already-cached data (the nation's own spawn + the town geometry we render anyway
    // + the tooltip-parsed town→nation map), so this costs no extra requests — unlike the old version.
    private static final int NATION_JOIN_RANGE = 5000;
    private static final int TOWN_JOIN_RANGE   = 1500;
    private static final long RANGE_REBUILD_MS = 500;   // cap the full-town-list scan to twice a second

    // Memoised join-range circles in WORLD space for the selected nation, so the ~40k-town scan runs at most
    // twice a second (on nation change / data refresh) instead of every frame — only the cheap projection is.
    private String rangeCacheNation = null;
    private long rangeCacheBuiltAt = 0;
    private int rangeCachePlanVersion = -1;
    private boolean rangeCacheArchive = false;
    private int rangeCacheRgb = 0x3BAAFF;
    private List<int[]> rangeCacheCircles = List.of();   // {worldX, worldZ, worldRadius}
    // Per circle, the angular spans of its rim that no OTHER circle covers — i.e. the outline of the union.
    // Overlap geometry is scale-independent, so this is computed with the circle set and reused every frame;
    // only the tessellation density changes with zoom, which is what keeps the edge smooth instead of blocky.
    private List<double[]> rangeCacheArcs = List.of();   // {circleIndex, startAngle, endAngle}

    /** Draws the joinable-area union for one nation on the world map. {@code nd} may be null (details not yet
     *  loaded) — then the capital's 5k circle is skipped and every town gets a 1.5k circle until it arrives. */
    public void renderNationJoinRange(GuiGraphicsExtractor ctx, String nationName, EarthMcNationData nd,
                                      double cameraX, double cameraZ, double blockScale, int sw, int sh) {
        if (nationName == null || nationName.isBlank() || blockScale <= 0 || api == null) return;
        drawJoinZone(ctx, nationName, nd, cameraX, cameraZ, blockScale, sw, sh);
        // Markers draw even when the zone itself is empty or off-screen, so a planned town is never invisible.
        renderPlannedMarkers(ctx, nationName, cameraX, cameraZ, blockScale, sw, sh);
    }

    private void drawJoinZone(GuiGraphicsExtractor ctx, String nationName, EarthMcNationData nd,
                              double cameraX, double cameraZ, double blockScale, int sw, int sh) {
        long now = System.currentTimeMillis();
        int planVersion = net.townymap.gui.PlanningOverlay.version();
        boolean archive = TownyMapMod.isArchiveMode();
        // Archive state is part of the key: entering or leaving swaps the whole town set, and without
        // this the zone kept its old shape until the refresh timer happened to come round.
        if (!nationName.equalsIgnoreCase(rangeCacheNation) || planVersion != rangeCachePlanVersion
                || archive != rangeCacheArchive
                || now - rangeCacheBuiltAt > RANGE_REBUILD_MS) {
            rebuildNationRange(nationName, nd);
            rangeCacheNation = nationName;
            rangeCachePlanVersion = planVersion;
            rangeCacheArchive = archive;
            rangeCacheBuiltAt = now;
        }
        if (rangeCacheCircles.isEmpty()) return;

        // Project the cached world circles to screen each frame (cheap). Keep full-precision centres so the
        // outline doesn't wobble between frames, and keep off-screen circles' geometry for the fill pass.
        int n = rangeCacheCircles.size();
        double[] scx = new double[n], scy = new double[n], sr = new double[n];
        List<int[]> onScreen = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int[] c = rangeCacheCircles.get(i);
            scx[i] = (c[0] - cameraX) * blockScale + sw / 2.0;
            scy[i] = (c[1] - cameraZ) * blockScale + sh / 2.0;
            sr[i]  = c[2] * blockScale;
            if (sr[i] > 0 && scx[i] + sr[i] >= 0 && scx[i] - sr[i] <= sw
                    && scy[i] + sr[i] >= 0 && scy[i] - sr[i] <= sh) {
                onScreen.add(new int[]{(int) Math.round(scx[i]), (int) Math.round(scy[i]),
                                       (int) Math.round(sr[i])});
            }
        }
        if (onScreen.isEmpty()) return;

        // Interior shading first, inset by a pixel so its stair-stepped edge stays behind the smooth outline.
        List<int[]> inset = new java.util.ArrayList<>(onScreen.size());
        for (int[] c : onScreen) if (c[2] > 1) inset.add(new int[]{c[0], c[1], c[2] - 1});
        if (!inset.isEmpty()) fillCircleUnion(ctx, inset, sw, sh, (0x38 << 24) | rangeCacheRgb);

        // Smooth union outline: draw only the arcs no other circle covers, as sub-pixel-positioned line
        // segments (rotated-quad, like the minimap ring) so the boundary reads as a true curve at any zoom.
        int edge = (0xF0 << 24) | rangeCacheRgb;
        for (double[] arc : rangeCacheArcs) {
            int i = (int) arc[0];
            double r = sr[i];
            if (r <= 0.5) continue;
            double a0 = arc[1], a1 = arc[2];
            double span = a1 - a0;
            // ~2px per segment along the arc, clamped, so smoothness tracks the on-screen size.
            int steps = Math.max(2, Math.min(512, (int) Math.ceil(span * r / 2.0)));
            double prevX = scx[i] + Math.cos(a0) * r, prevY = scy[i] + Math.sin(a0) * r;
            for (int s = 1; s <= steps; s++) {
                double ang = a0 + span * s / steps;
                double x = scx[i] + Math.cos(ang) * r, y = scy[i] + Math.sin(ang) * r;
                // Cull segments fully outside the viewport; the rest are cheap.
                if (!((prevX < 0 && x < 0) || (prevX > sw && x > sw)
                        || (prevY < 0 && y < 0) || (prevY > sh && y > sh))) {
                    drawSmoothLine(ctx, prevX, prevY, x, y, edge);
                }
                prevX = x; prevY = y;
            }
        }
    }

    /**
     * Labelled pins for planned towns (T1, T2 …). Red means the spot is outside the nation's reachable area
     * and has to be moved; hovering its chip in the counter makes the matching pin stand out.
     */
    private void renderPlannedMarkers(GuiGraphicsExtractor ctx, String nationName,
                                      double cameraX, double cameraZ, double blockScale, int sw, int sh) {
        if (!net.townymap.gui.PlanningOverlay.isActive()
                || !nationName.equalsIgnoreCase(net.townymap.gui.PlanningOverlay.nation())) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        List<int[]> pts = net.townymap.gui.PlanningOverlay.plannedPoints();
        int hoverIdx = net.townymap.gui.PlanningOverlay.hoveredIndex();

        for (int i = 0; i < pts.size(); i++) {
            int[] p = pts.get(i);
            int x = toScreenX(p[0], cameraX, blockScale, sw);
            int y = toScreenY(p[1], cameraZ, blockScale, sh);
            if (x < -40 || x > sw + 40 || y < -40 || y > sh + 40) continue;

            boolean ok = net.townymap.gui.PlanningOverlay.isValid(i);
            boolean hot = i == hoverIdx;
            int accent = ok ? 0xFF6FD3A0 : 0xFFE2564E;
            String label = "T" + (i + 1);
            int lw = client.font.width(label);

            // A hovered pin gets a halo ring so it's obvious which chip points where.
            if (hot) {
                drawSmoothRing(ctx, x, y, 9.0, accent);
                drawSmoothRing(ctx, x, y, 10.0, accent);
            }
            // Cross-hair marker at the exact spot.
            ctx.fill(x - 4, y - 1, x + 4, y + 1, accent);
            ctx.fill(x - 1, y - 4, x + 1, y + 4, accent);
            ctx.fill(x - 2, y - 2, x + 2, y + 2, 0xFF101114);

            // Label sits just above, on its own plate so it stays readable over any terrain.
            int lx = x - lw / 2, ly = y - 16;
            ctx.fill(lx - 3, ly - 2, lx + lw + 3, ly + 10, hot ? 0xF0101114 : 0xC0101114);
            ctx.fill(lx - 3, ly - 2, lx + lw + 3, ly - 1, accent);
            ctx.text(client.font, label, lx, ly, ok ? 0xFFFFFFFF : accent, false);
        }
    }

    /** A smooth 1px ring built from the same rotated-quad segments as the range outline. */
    private static void drawSmoothRing(GuiGraphicsExtractor ctx, double cx, double cy, double r, int color) {
        int segments = Math.max(20, Math.min(96, (int) Math.round(r * 6)));
        double px = cx + r, py = cy;
        for (int i = 1; i <= segments; i++) {
            double a = Math.PI * 2 * i / segments;
            double nx = cx + Math.cos(a) * r, ny = cy + Math.sin(a) * r;
            drawSmoothLine(ctx, px, py, nx, ny, color);
            px = nx; py = ny;
        }
    }

    /** A 1px line at any angle, drawn as a rotated quad so diagonals don't stair-step (same trick the
     *  minimap's circular frame uses). */
    private static void drawSmoothLine(GuiGraphicsExtractor ctx, double x1, double y1, double x2, double y2, int color) {
        double dx = x2 - x1, dy = y2 - y1;
        double length = Math.hypot(dx, dy);
        if (length < 0.01) return;
        org.joml.Matrix3x2fStack m = ctx.pose();
        m.pushMatrix();
        try {
            m.translate((float) x1, (float) y1);
            m.rotate((float) Math.atan2(dy, dx));
            m.scale((float) length, 1f);
            ctx.fill(0, 0, 1, 1, color);   // unit quad stretched to the exact sub-pixel length
        } finally {
            m.popMatrix();
        }
    }

    /** Rebuilds the world-space circle set: capital 5k + each town 1.5k, all from cached data. */
    private void rebuildNationRange(String nationName, EarthMcNationData nd) {
        String capitalName = nd != null ? nd.capitalName() : null;
        List<int[]> circles = new java.util.ArrayList<>();
        // Off Earth the API spawn is the wrong world's coordinate, and the per-town circles below already
        // come from the active world -- mixing the two would put the capital's ring nowhere near its
        // outpost.
        if (nd != null && nd.hasSpawn() && TownyMapMod.viewingEarth()) {
            circles.add(new int[]{nd.spawnX(), nd.spawnZ(), NATION_JOIN_RANGE});
        } else if (capitalName != null) {
            TownData cap = townByName(capitalName);
            if (cap != null) circles.add(new int[]{cap.centerX(), cap.centerZ(), NATION_JOIN_RANGE});
        }
        for (TownData t : api.getTowns()) {
            String tn = TownyMapMod.townNationAt(t.key());
            if (tn == null || !tn.equalsIgnoreCase(nationName)) continue;
            if (capitalName != null && t.name().equalsIgnoreCase(capitalName)) continue;   // capital is the 5k circle
            circles.add(new int[]{t.centerX(), t.centerZ(), TOWN_JOIN_RANGE});
        }
        // Planning mode: hypothetical towns extend the frontier exactly like real ones do — but only the ones
        // that are themselves reachable. resolve() works that out and flags the rest for the red markers.
        if (net.townymap.gui.PlanningOverlay.isActive()
                && nationName.equalsIgnoreCase(net.townymap.gui.PlanningOverlay.nation())) {
            circles.addAll(net.townymap.gui.PlanningOverlay.resolve(circles));
        }
        rangeCacheCircles = circles;
        rangeCacheArcs = computeUnionArcs(circles);

        // Tint the zone with the nation's own colour (its capital town's outline colour) so it reads as theirs.
        rangeCacheRgb = 0x3BAAFF;
        TownData capTown = capitalName != null ? townByName(capitalName) : null;
        if (capTown != null) rangeCacheRgb = capTown.rgbColor() & 0xFFFFFF;
    }

    /**
     * For each circle, the angular spans of its rim that lie outside every other circle — the outline of the
     * union. Done in world space once per rebuild: a circle j hides the arc of circle i centred on the
     * direction i→j with half-width acos((d² + ri² − rj²) / (2·d·ri)).
     */
    private static List<double[]> computeUnionArcs(List<int[]> circles) {
        List<double[]> arcs = new java.util.ArrayList<>();
        int n = circles.size();
        for (int i = 0; i < n; i++) {
            double xi = circles.get(i)[0], yi = circles.get(i)[1], ri = circles.get(i)[2];
            if (ri <= 0) continue;
            List<double[]> covered = new java.util.ArrayList<>();
            boolean swallowed = false;
            for (int j = 0; j < n && !swallowed; j++) {
                if (j == i) continue;
                double xj = circles.get(j)[0], yj = circles.get(j)[1], rj = circles.get(j)[2];
                double d = Math.hypot(xj - xi, yj - yi);
                if (d >= ri + rj) continue;              // disjoint: hides nothing
                if (d + ri <= rj) { swallowed = true; break; }   // i lies wholly inside j: no visible rim
                if (d + rj <= ri) continue;              // j inside i: doesn't touch i's rim
                double cos = (d * d + ri * ri - rj * rj) / (2.0 * d * ri);
                if (cos > 1.0 || cos < -1.0) continue;
                double mid = Math.atan2(yj - yi, xj - xi);
                double half = Math.acos(cos);
                covered.add(new double[]{mid - half, mid + half});
            }
            if (swallowed) continue;
            for (double[] visible : invertSpans(normaliseSpans(covered))) {
                arcs.add(new double[]{i, visible[0], visible[1]});
            }
        }
        return arcs;
    }

    /** Wraps spans into [0, 2π), splitting any that cross the seam, then merges overlaps. */
    private static List<double[]> normaliseSpans(List<double[]> spans) {
        List<double[]> out = new java.util.ArrayList<>();
        for (double[] s : spans) {
            double a = s[0], b = s[1];
            if (b - a >= Math.PI * 2) { out.clear(); out.add(new double[]{0, Math.PI * 2}); return out; }
            a = ((a % (Math.PI * 2)) + Math.PI * 2) % (Math.PI * 2);
            b = a + (s[1] - s[0]);
            if (b > Math.PI * 2) { out.add(new double[]{a, Math.PI * 2}); out.add(new double[]{0, b - Math.PI * 2}); }
            else out.add(new double[]{a, b});
        }
        out.sort((p, q) -> Double.compare(p[0], q[0]));
        List<double[]> merged = new java.util.ArrayList<>();
        for (double[] s : out) {
            if (!merged.isEmpty() && s[0] <= merged.get(merged.size() - 1)[1] + 1e-9) {
                double[] last = merged.get(merged.size() - 1);
                if (s[1] > last[1]) last[1] = s[1];
            } else merged.add(new double[]{s[0], s[1]});
        }
        return merged;
    }

    /** The complement of merged covered spans within [0, 2π) — the arcs that are actually on the outline. */
    private static List<double[]> invertSpans(List<double[]> covered) {
        List<double[]> out = new java.util.ArrayList<>();
        double cursor = 0;
        for (double[] s : covered) {
            if (s[0] > cursor + 1e-6) out.add(new double[]{cursor, s[0]});
            cursor = Math.max(cursor, s[1]);
        }
        if (cursor < Math.PI * 2 - 1e-6) out.add(new double[]{cursor, Math.PI * 2});
        return out;
    }

    /** Fills the union of the circles with a single flat alpha (no overlap compounding) by scanning rows and
     *  merging each row's x-intervals. The visible boundary is drawn separately as smooth arcs. */
    private static void fillCircleUnion(GuiGraphicsExtractor ctx, List<int[]> circles, int sw, int sh, int fillArgb) {
        int top = sh, bot = 0;
        for (int[] c : circles) { top = Math.min(top, c[1] - c[2]); bot = Math.max(bot, c[1] + c[2]); }
        top = Math.max(0, top); bot = Math.min(sh, bot);
        int n = circles.size();
        int[] lo = new int[n], hi = new int[n];
        for (int y = top; y < bot; y++) {
            int m = 0;
            for (int[] c : circles) {
                int dy = y - c[1], rr = c[2];
                if (dy < -rr || dy > rr) continue;
                int dx = (int) Math.sqrt((double) rr * rr - (double) dy * dy);
                lo[m] = c[0] - dx; hi[m] = c[0] + dx; m++;
            }
            if (m == 0) continue;
            for (int i = 1; i < m; i++) {   // insertion sort by interval start
                int a = lo[i], b = hi[i], j = i - 1;
                while (j >= 0 && lo[j] > a) { lo[j + 1] = lo[j]; hi[j + 1] = hi[j]; j--; }
                lo[j + 1] = a; hi[j + 1] = b;
            }
            int curL = lo[0], curR = hi[0];
            for (int i = 1; i < m; i++) {
                if (lo[i] <= curR) { if (hi[i] > curR) curR = hi[i]; }
                else { emitUnionRow(ctx, curL, curR, y, sw, fillArgb); curL = lo[i]; curR = hi[i]; }
            }
            emitUnionRow(ctx, curL, curR, y, sw, fillArgb);
        }
    }

    private static void emitUnionRow(GuiGraphicsExtractor ctx, int x1, int x2, int y, int sw, int fillArgb) {
        int a = Math.max(0, x1), b = Math.min(sw, x2);
        if (b > a) ctx.fill(a, y, b, y + 1, fillArgb);
    }

    // ── Player rendering ─────────────────────────────────────────────────────

    private void renderPlayers(GuiGraphicsExtractor ctx,
                               double cameraX, double cameraZ, double blockScale,
                               int sw, int sh, Map<String, EarthMcPlayerData> playerDetails) {
        // Browsing a world you are not standing in: player positions belong to the other world,
        // so drawing them here would put people in places they are not.
        if (TownyMapMod.isArchiveMode()) return;   // archived snapshots have no live players
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        String selfName = client.getUser().getName();
        // bit 0 = world map, and only when zoomed in past the Head Range threshold.
        boolean heads = (config.playerHeadMode & 1) != 0 && blockScale >= config.playerHeadMinScale;
        boolean names = config.showPlayerNames && blockScale >= config.playerNameMinScale;
        boolean affil = blockScale >= config.playerAffiliationMinScale;
        int headsDrawn = 0;

        // Last-seen ghosts first, so live players draw on top of them.
        if (config.playerLastSeen) {
            for (TownyMapMod.GhostMarker g : TownyMapMod.lastSeenGhosts()) {
                if (g.name().equalsIgnoreCase(selfName)) continue;
                int gx = toScreenX(g.x(), cameraX, blockScale, sw);
                int gy = toScreenY(g.z(), cameraZ, blockScale, sh);
                if (gx < -10 || gx > sw + 10 || gy < -10 || gy > sh + 10) continue;
                int red = (g.alpha() << 24) | 0xE23B3B;
                if (heads) {
                    PlayerHeadRenderer.draw(ctx, g.uuid(), g.name(), gx, gy, 8, red);
                } else {
                    ctx.fill(gx - DOT_HALF, gy - DOT_HALF, gx + DOT_HALF, gy + DOT_HALF, red);
                }
                // A ghost had no name/town/nation before, so you couldn't tell who it was. Draw them now,
                // in the same red (faded with the dot) so it still reads as a last-seen marker.
                if (names) {
                    EarthMcPlayerData d = playerDetails.get(g.name().toLowerCase(Locale.ROOT));
                    // Ghosts never go through playerDotColor (fixed red), so nothing else fetches their
                    // town/nation — ask for it here, or the affiliation line stays permanently blank.
                    if (affil && d == null) TownyMapMod.requestPlayerLabelDetails(g.name());
                    String aff = affil ? affiliation(d, g.name()) : "";
                    drawPlayerLabel(ctx, client, g.name(), aff, gx, gy, heads,
                            (g.alpha() << 24) | 0xFFB0B0, (g.alpha() << 24) | 0xE7B0B0);
                }
            }
        }

        for (PlayerMarker p : api.getPlayers()) {
            if (p.name().equalsIgnoreCase(selfName)) continue;

            int dotX = toScreenX(p.x(), cameraX, blockScale, sw);
            int dotY = toScreenY(p.z(), cameraZ, blockScale, sh);

            if (dotX < -10 || dotX > sw + 10 || dotY < -10 || dotY > sh + 10) continue;

            int color = TownyMapMod.playerDotColor(p.name(), p.key());
            if ((color >>> 24) == 0) continue;
            boolean drewHead = heads && headsDrawn < MAX_PLAYER_HEADS;
            if (drewHead) {
                headsDrawn++;
                PlayerHeadRenderer.draw(ctx, p.uuid(), p.name(), dotX, dotY, 8, color);
            } else {
                ctx.fill(dotX - DOT_HALF, dotY - DOT_HALF,
                         dotX + DOT_HALF, dotY + DOT_HALF,
                         color);
            }

            if (names) {
                EarthMcPlayerData details = playerDetails.get(p.name().toLowerCase(Locale.ROOT));
                // playerDotColor above already requests this, but ask again if it is still missing so the
                // label populates even when the colour path deferred (e.g. self details not loaded yet).
                if (affil && details == null) TownyMapMod.requestPlayerLabelDetails(p.name());
                String affiliation = affil ? affiliation(details, p.name()) : "";
                drawPlayerLabel(ctx, client, p.name(), affiliation, dotX, dotY, drewHead,
                        config.playerLabelColor, 0xFFB8D7FF);
            }
        }
    }

    /**
     * Player name plus optional town/nation.
     *
     * <p>With a head, both are stacked ABOVE the marker (town/nation on top, name below it) and centred,
     * so neither overlaps the head. With a plain dot, they sit beside it as before. The affiliation is
     * always the line above the name.
     */
    private void drawPlayerLabel(GuiGraphicsExtractor ctx, Minecraft client, String name, String affiliation,
                                 int cx, int cy, boolean head, int nameColor, int affColor) {
        var tr = client.font;
        boolean showAff = affiliation != null && !affiliation.isBlank();
        if (head) {
            int headHalf = 6;                    // matches PlayerHeadRenderer's dot half for size 8
            int nameY = cy - headHalf - 11;      // name just above the head
            ctx.text(tr, name, cx - tr.width(name) / 2, nameY, nameColor, true);
            if (showAff) {
                ctx.text(tr, affiliation, cx - tr.width(affiliation) / 2, nameY - 9, affColor, true);
            }
        } else {
            int textX = cx + DOT_HALF + 2;
            int nameY = showAff ? cy + 7 : cy - 4;
            if (showAff) ctx.text(tr, affiliation, textX, cy - 5, affColor, true);
            ctx.text(tr, name, textX, nameY, nameColor, true);
        }
    }

    private static String affiliation(EarthMcPlayerData details, String playerName) {
        if (details == null) {
            // Opted out of the API: their town's public roster still lists them, so label them from that
            // rather than leaving a bare name floating over the map.
            net.townymap.api.SquaremapApiClient api = TownyMapMod.getApiClient();
            String town = api == null ? null : api.townOfResident(playerName);
            if (town == null) return "";
            String nation = api.getTownNation(town.toLowerCase(Locale.ROOT));
            return nation == null || nation.isBlank() ? town : town + " / " + nation;
        }
        if (!details.townName().isBlank() && !details.nationName().isBlank()) {
            return details.townName() + " / " + details.nationName();
        }
        if (!details.townName().isBlank()) return details.townName();
        if (!details.nationName().isBlank()) return details.nationName();
        return "";
    }
}
