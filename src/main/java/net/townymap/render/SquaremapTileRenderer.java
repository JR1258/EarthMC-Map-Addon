package net.townymap.render;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class SquaremapTileRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("TownyMapAddon");
    private static final int TILE_PIXELS = 512;
    private static final int MAX_NEW_REQUESTS_PER_FRAME = 28;
    private static final int MAX_CONCURRENT_LOADS = 40;
    private static final int MAX_TEXTURE_UPLOADS_PER_FRAME = 5;
    private static final int MAX_MOVING_REQUESTS_PER_FRAME = 2;
    private static final int MAX_MOVING_TEXTURE_UPLOADS_PER_FRAME = 1;
    private static final long TEXTURE_UPLOAD_BUDGET_NS = 1_500_000L;
    private static final long MOVING_TEXTURE_UPLOAD_BUDGET_NS = 750_000L;
    private static final int MAX_TEXTURES = 1024;
    // The few low-zoom tiles that make up the far zoomed-out overview are pinned (never LRU-evicted), so
    // once they load they stay cached and the overview never has to re-fetch them. They're tiny in number
    // (the whole map is ~1-21 tiles at zoom 0-2), so this costs almost nothing.
    private static final int OVERVIEW_PIN_ZOOM = 2;
    /**
     * Textures kept for the world the minimap is drawing before the cache may start evicting them.
     *
     * <p>With the map pinned to one world and the player standing in another, both worlds share this
     * cache -- and the world map floods it while it is open, so on closing it the minimap found its own
     * imagery gone and had to fetch the lot again. Its working set is a few dozen tiles, so holding this
     * many back costs little of the 1024.
     */
    private static final int MINIMAP_WORLD_RESERVE = 128;
    private static final long FAILED_RETRY_MS = 60_000;
    private static final long TILE_REFRESH_MS = 20 * 60_000L;
    private static final int QUALITY_ZOOM_BIAS = 2;
    private static final double CIRCLE_EDGE_STEP_PX = 1.0;
    private static final int PREFETCH_TILE_MARGIN = 1;
    private static final int MOVING_CURRENT_ZOOM_PREFETCH_REQUESTS = 8;
    private static final int MOVING_ADJACENT_ZOOM_PREFETCH_REQUESTS = 3;
    private static final double PREFETCH_LEAD_VIEWPORTS = 0.75;
    private static final RenderPipeline PIPELINE = RenderPipelines.GUI_TEXTURED;

    private final TownyMapConfig config;
    private final HttpClient http;
    private final ExecutorService executor;
    private final Set<TileKey> loading = ConcurrentHashMap.newKeySet();
    private final Map<TileKey, Long> failedAt = new ConcurrentHashMap<>();
    /** Last ETag per tile, so a stale-check can ask the server to skip sending unchanged imagery. */
    private final Map<TileKey, String> tileEtags = new ConcurrentHashMap<>();
    /** HTTP status codes already reported, so a bulk refusal logs one line and not one per tile. */
    private final Set<Integer> loggedTileStatuses = ConcurrentHashMap.newKeySet();
    /** Newest refusal status and when it happened, so the map can say why the imagery is blank. */
    private volatile int lastRefusedStatus = 0;
    private volatile long lastRefusedMs = 0;
    private final Map<TileKey, LoadedTile> completedTiles = new ConcurrentHashMap<>();
    /** Bumped on every clearAll() so responses for the previous world can be told apart and dropped. */
    private volatile int worldGeneration = 0;
    /**
     * World the current render pass is drawing. Set on the client thread at the top of each entry point;
     * the async fetch never reads it, taking its world from the TileKey instead.
     */
    private String passWorld = net.townymap.TownyMapMod.WORLD_OVERWORLD;
    private final LinkedHashMap<TileKey, Identifier> textures =
            new LinkedHashMap<>(64, 0.75f, true);
    private final Map<TileKey, Long> textureLoadedAt = new ConcurrentHashMap<>();
    private double lastCameraX = Double.NaN;
    private double lastCameraZ = Double.NaN;
    private double panDirectionX = 0.0;
    private double panDirectionZ = 0.0;

    SquaremapTileRenderer(TownyMapConfig config) {
        this.config = config;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(executor)
                .build();
    }

    void render(GuiGraphicsExtractor ctx, double cameraX, double cameraZ, double blockScale, int sw, int sh,
                double worldLeft, double worldRight, double worldTop, double worldBottom,
                boolean moving) {
        this.passWorld = net.townymap.TownyMapMod.activeWorldKey();
        render(ctx, cameraX, cameraZ, blockScale, sw, sh, worldLeft, worldRight, worldTop, worldBottom,
                moving, NetworkPolicy.WORLD_MAP);
    }

    void renderMinimap(GuiGraphicsExtractor ctx, double cameraX, double cameraZ, double blockScale, int sw, int sh,
                       double worldLeft, double worldRight, double worldTop, double worldBottom,
                       boolean moving) {
        renderMinimap(ctx, cameraX, cameraZ, blockScale, sw, sh, worldLeft, worldRight, worldTop, worldBottom,
                moving, 0.0);
    }

    void renderMinimap(GuiGraphicsExtractor ctx, double cameraX, double cameraZ, double blockScale, int sw, int sh,
                       double worldLeft, double worldRight, double worldTop, double worldBottom,
                       boolean moving, double circularClipRadius) {
        // The minimap shows where the player actually is, so its imagery is the player's world even when
        // the map is pinned to the other one.
        this.passWorld = net.townymap.TownyMapMod.playerWorldResolved();
        CircleClip circleClip = circularClipRadius > 0.0
                ? new CircleClip(sw / 2.0, sh / 2.0, circularClipRadius,
                circularClipRadius * circularClipRadius, circleClipStripHeight(circularClipRadius))
                : null;
        render(ctx, cameraX, cameraZ, blockScale, sw, sh, worldLeft, worldRight, worldTop, worldBottom,
                moving, NetworkPolicy.MINIMAP, circleClip);
    }

    private void render(GuiGraphicsExtractor ctx, double cameraX, double cameraZ, double blockScale, int sw, int sh,
                        double worldLeft, double worldRight, double worldTop, double worldBottom,
                        boolean moving, NetworkPolicy policy) {
        render(ctx, cameraX, cameraZ, blockScale, sw, sh, worldLeft, worldRight, worldTop, worldBottom,
                moving, policy, null);
    }

    private void render(GuiGraphicsExtractor ctx, double cameraX, double cameraZ, double blockScale, int sw, int sh,
                        double worldLeft, double worldRight, double worldTop, double worldBottom,
                        boolean moving, NetworkPolicy policy, CircleClip circleClip) {
        int zoom = chooseTileZoom(blockScale);
        PanDirection panDirection = updatePanDirection(cameraX, cameraZ);
        processCompletedTiles(moving, zoom, worldLeft, worldRight, worldTop, worldBottom);
        if (zoom > 0 && circleClip == null) {
            renderLayer(ctx, cameraX, cameraZ, blockScale, sw, sh,
                    worldLeft, worldRight, worldTop, worldBottom, 0, moving, true, policy, circleClip);
        }
        renderLayer(ctx, cameraX, cameraZ, blockScale, sw, sh,
                worldLeft, worldRight, worldTop, worldBottom, zoom, moving, false, policy, circleClip);
        if (!policy.allowPrefetch()) {
            return;
        }
        if (moving) {
            prefetchInPanDirection(zoom, worldLeft, worldRight, worldTop, worldBottom, panDirection);
        } else {
            prefetchAdjacentZooms(zoom, worldLeft, worldRight, worldTop, worldBottom);
        }
    }

    private PanDirection updatePanDirection(double cameraX, double cameraZ) {
        if (Double.isNaN(lastCameraX) || Double.isNaN(lastCameraZ)) {
            lastCameraX = cameraX;
            lastCameraZ = cameraZ;
            return new PanDirection(0.0, 0.0);
        }

        double dx = cameraX - lastCameraX;
        double dz = cameraZ - lastCameraZ;
        lastCameraX = cameraX;
        lastCameraZ = cameraZ;

        double distance = Math.hypot(dx, dz);
        if (distance > 0.25) {
            double normalizedX = dx / distance;
            double normalizedZ = dz / distance;
            panDirectionX = panDirectionX * 0.65 + normalizedX * 0.35;
            panDirectionZ = panDirectionZ * 0.65 + normalizedZ * 0.35;
            double smoothedLength = Math.hypot(panDirectionX, panDirectionZ);
            if (smoothedLength > 0.0001) {
                panDirectionX /= smoothedLength;
                panDirectionZ /= smoothedLength;
            }
        } else {
            panDirectionX *= 0.92;
            panDirectionZ *= 0.92;
        }
        return new PanDirection(panDirectionX, panDirectionZ);
    }

    private void renderLayer(GuiGraphicsExtractor ctx, double cameraX, double cameraZ, double blockScale, int sw, int sh,
                             double worldLeft, double worldRight, double worldTop, double worldBottom,
                             int zoom, boolean moving, boolean fallbackLayer, NetworkPolicy policy,
                             CircleClip circleClip) {
        double pixelsPerBlock = pixelsPerBlock(zoom);
        double tileWorldSize = TILE_PIXELS / pixelsPerBlock;

        int minTileX = floorToTile(worldLeft, tileWorldSize);
        int maxTileX = floorToTile(worldRight, tileWorldSize);
        int minTileY = floorToTile(worldTop, tileWorldSize);
        int maxTileY = floorToTile(worldBottom, tileWorldSize);

        int requested = 0;
        int requestBudget = requestBudget(policy, moving, fallbackLayer);
        int centerTileX = floorToTile((worldLeft + worldRight) * 0.5, tileWorldSize);
        int centerTileY = floorToTile((worldTop + worldBottom) * 0.5, tileWorldSize);
        int maxRadius = Math.max(
                Math.max(Math.abs(minTileX - centerTileX), Math.abs(maxTileX - centerTileX)),
                Math.max(Math.abs(minTileY - centerTileY), Math.abs(maxTileY - centerTileY)));

        for (int radius = 0; radius <= maxRadius; radius++) {
            for (int tileY = centerTileY - radius; tileY <= centerTileY + radius; tileY++) {
                for (int tileX = centerTileX - radius; tileX <= centerTileX + radius; tileX++) {
                    if (tileX < minTileX || tileX > maxTileX || tileY < minTileY || tileY > maxTileY) continue;
                    if (Math.max(Math.abs(tileX - centerTileX), Math.abs(tileY - centerTileY)) != radius) continue;

                    TileKey key = new TileKey(passWorld, zoom, tileX, tileY);
                    Identifier texture = textures.get(key);
                    if (texture == null) {
                        if (requested++ < requestBudget) requestTile(key, false, policy.maxConcurrentLoads());
                        renderParentFallback(ctx, key, tileWorldSize, cameraX, cameraZ, blockScale, sw, sh,
                                circleClip);
                        continue;
                    }
                    if (policy.allowRefresh()) {
                        refreshTileIfStale(key, policy.maxConcurrentLoads());
                    }

                    renderTile(ctx, texture, tileX, tileY, tileWorldSize, cameraX, cameraZ,
                            blockScale, sw, sh, circleClip);
                }
            }
        }
    }

    private static int requestBudget(NetworkPolicy policy, boolean moving, boolean fallbackLayer) {
        int base = moving ? MAX_MOVING_REQUESTS_PER_FRAME : MAX_NEW_REQUESTS_PER_FRAME;
        return fallbackLayer ? Math.max(1, base / 4) : base;
    }

    private void prefetchAdjacentZooms(int zoom, double worldLeft, double worldRight,
                                       double worldTop, double worldBottom) {
        if (zoom > 0) {
            prefetchLayer(zoom - 1, worldLeft, worldRight, worldTop, worldBottom,
                    MAX_NEW_REQUESTS_PER_FRAME / 2);
        }
        if (zoom < config.squaremapMaxZoom) {
            prefetchLayer(zoom + 1, worldLeft, worldRight, worldTop, worldBottom,
                    MAX_NEW_REQUESTS_PER_FRAME / 3);
        }
    }

    private void prefetchInPanDirection(int zoom, double worldLeft, double worldRight,
                                        double worldTop, double worldBottom,
                                        PanDirection panDirection) {
        if (Math.hypot(panDirection.x(), panDirection.z()) < 0.2) return;

        double width = worldRight - worldLeft;
        double height = worldBottom - worldTop;
        double leadX = panDirection.x() * width * PREFETCH_LEAD_VIEWPORTS;
        double leadZ = panDirection.z() * height * PREFETCH_LEAD_VIEWPORTS;

        double aheadLeft = worldLeft + leadX;
        double aheadRight = worldRight + leadX;
        double aheadTop = worldTop + leadZ;
        double aheadBottom = worldBottom + leadZ;

        prefetchLayer(zoom, aheadLeft, aheadRight, aheadTop, aheadBottom,
                MOVING_CURRENT_ZOOM_PREFETCH_REQUESTS);
        if (zoom > 0) {
            prefetchLayer(zoom - 1, aheadLeft, aheadRight, aheadTop, aheadBottom,
                    MOVING_ADJACENT_ZOOM_PREFETCH_REQUESTS);
        }
        if (zoom < config.squaremapMaxZoom) {
            prefetchLayer(zoom + 1, aheadLeft, aheadRight, aheadTop, aheadBottom,
                    MOVING_ADJACENT_ZOOM_PREFETCH_REQUESTS);
        }
    }

    private void prefetchLayer(int zoom, double worldLeft, double worldRight,
                               double worldTop, double worldBottom, int maxRequests) {
        double pixelsPerBlock = pixelsPerBlock(zoom);
        double tileWorldSize = TILE_PIXELS / pixelsPerBlock;
        int minTileX = floorToTile(worldLeft, tileWorldSize) - PREFETCH_TILE_MARGIN;
        int maxTileX = floorToTile(worldRight, tileWorldSize) + PREFETCH_TILE_MARGIN;
        int minTileY = floorToTile(worldTop, tileWorldSize) - PREFETCH_TILE_MARGIN;
        int maxTileY = floorToTile(worldBottom, tileWorldSize) + PREFETCH_TILE_MARGIN;
        int centerTileX = floorToTile((worldLeft + worldRight) * 0.5, tileWorldSize);
        int centerTileY = floorToTile((worldTop + worldBottom) * 0.5, tileWorldSize);
        int maxRadius = Math.max(
                Math.max(Math.abs(minTileX - centerTileX), Math.abs(maxTileX - centerTileX)),
                Math.max(Math.abs(minTileY - centerTileY), Math.abs(maxTileY - centerTileY)));

        int requested = 0;
        for (int radius = 0; radius <= maxRadius && requested < maxRequests; radius++) {
            for (int tileY = centerTileY - radius; tileY <= centerTileY + radius && requested < maxRequests; tileY++) {
                for (int tileX = centerTileX - radius; tileX <= centerTileX + radius && requested < maxRequests; tileX++) {
                    if (tileX < minTileX || tileX > maxTileX || tileY < minTileY || tileY > maxTileY) continue;
                    if (Math.max(Math.abs(tileX - centerTileX), Math.abs(tileY - centerTileY)) != radius) continue;
                    TileKey key = new TileKey(passWorld, zoom, tileX, tileY);
                    if (!textures.containsKey(key)) {
                        requestTile(key);
                        requested++;
                    }
                }
            }
        }
    }

    private boolean renderParentFallback(GuiGraphicsExtractor ctx, TileKey childKey, double childTileWorldSize,
                                         double cameraX, double cameraZ, double blockScale,
                                         int sw, int sh, CircleClip circleClip) {
        for (int parentZoom = childKey.zoom() - 1; parentZoom >= 0; parentZoom--) {
            int factor = 1 << (childKey.zoom() - parentZoom);
            TileKey parentKey = new TileKey(passWorld, parentZoom,
                    Math.floorDiv(childKey.x(), factor),
                    Math.floorDiv(childKey.y(), factor));
            Identifier parentTexture = textures.get(parentKey);
            if (parentTexture == null) continue;

            int localX = Math.floorMod(childKey.x(), factor);
            int localY = Math.floorMod(childKey.y(), factor);
            int srcSize = Math.max(1, TILE_PIXELS / factor);
            int u = localX * srcSize;
            int v = localY * srcSize;
            renderTileRegion(ctx, parentTexture, childKey.x(), childKey.y(), childTileWorldSize,
                    cameraX, cameraZ, blockScale, sw, sh, u, v, srcSize, srcSize, circleClip);
            return true;
        }
        return false;
    }

    private void renderTile(GuiGraphicsExtractor ctx, Identifier texture, int tileX, int tileY,
                            double tileWorldSize, double cameraX, double cameraZ,
                            double blockScale, int sw, int sh, CircleClip circleClip) {
        double tileWorldX = tileX * tileWorldSize;
        double tileWorldZ = tileY * tileWorldSize;
        int x1 = toScreenX(tileWorldX, cameraX, blockScale, sw);
        int y1 = toScreenY(tileWorldZ, cameraZ, blockScale, sh);
        int x2 = toScreenX(tileWorldX + tileWorldSize, cameraX, blockScale, sw);
        int y2 = toScreenY(tileWorldZ + tileWorldSize, cameraZ, blockScale, sh);

        if (x2 <= 0 || x1 >= sw || y2 <= 0 || y1 >= sh) return;
        // Draw each tile at EXACTLY its true size. This used to add a pixel to each dimension to
        // "overlap" the neighbour, but regionW/regionH stayed at the full 256, so the extra pixel did
        // not duplicate an edge -- it stretched the tile's 256 texels over 257 pixels. Every tile then
        // sat up to a pixel off its true position, neighbours disagreed along the shared edge, and the
        // LINEAR sampler turned that disagreement into a bright line: invisible unrotated, because
        // everything lands on integer pixels, but obvious the moment the minimap turns.
        //
        // There was no gap to close in the first place: two quads that share exact vertices rasterise
        // watertight, and the imagery is opaque.
        int drawW = Math.max(1, x2 - x1);
        int drawH = Math.max(1, y2 - y1);
        if (circleClip != null) {
            renderTileRegionCircleClipped(ctx, texture, x1, y1, drawW, drawH,
                    0, 0, TILE_PIXELS, TILE_PIXELS, circleClip);
            return;
        }
        ctx.blit(PIPELINE, texture, x1, y1, 0.0F, 0.0F,
                drawW, drawH,
                TILE_PIXELS, TILE_PIXELS, TILE_PIXELS, TILE_PIXELS);
    }

    private void renderTileRegion(GuiGraphicsExtractor ctx, Identifier texture, int tileX, int tileY,
                                  double tileWorldSize, double cameraX, double cameraZ,
                                  double blockScale, int sw, int sh,
                                  int u, int v, int regionW, int regionH, CircleClip circleClip) {
        double tileWorldX = tileX * tileWorldSize;
        double tileWorldZ = tileY * tileWorldSize;
        int x1 = toScreenX(tileWorldX, cameraX, blockScale, sw);
        int y1 = toScreenY(tileWorldZ, cameraZ, blockScale, sh);
        int x2 = toScreenX(tileWorldX + tileWorldSize, cameraX, blockScale, sw);
        int y2 = toScreenY(tileWorldZ + tileWorldSize, cameraZ, blockScale, sh);

        if (x2 <= 0 || x1 >= sw || y2 <= 0 || y1 >= sh) return;
        // Draw each tile at EXACTLY its true size. This used to add a pixel to each dimension to
        // "overlap" the neighbour, but regionW/regionH stayed at the full 256, so the extra pixel did
        // not duplicate an edge -- it stretched the tile's 256 texels over 257 pixels. Every tile then
        // sat up to a pixel off its true position, neighbours disagreed along the shared edge, and the
        // LINEAR sampler turned that disagreement into a bright line: invisible unrotated, because
        // everything lands on integer pixels, but obvious the moment the minimap turns.
        //
        // There was no gap to close in the first place: two quads that share exact vertices rasterise
        // watertight, and the imagery is opaque.
        int drawW = Math.max(1, x2 - x1);
        int drawH = Math.max(1, y2 - y1);
        if (circleClip != null) {
            renderTileRegionCircleClipped(ctx, texture, x1, y1, drawW, drawH,
                    u, v, regionW, regionH, circleClip);
            return;
        }
        ctx.blit(PIPELINE, texture, x1, y1, (float) u, (float) v,
                drawW, drawH,
                regionW, regionH, TILE_PIXELS, TILE_PIXELS);
    }

    private void renderTileRegionCircleClipped(GuiGraphicsExtractor ctx, Identifier texture,
                                               int x, int y, int drawW, int drawH,
                                               int u, int v, int regionW, int regionH,
                                               CircleClip circleClip) {
        int x2 = x + drawW;
        int y2 = y + drawH;
        if (!circleClip.intersectsRect(x, y, x2, y2)) return;
        if (circleClip.containsRect(x, y, x2, y2)) {
            ctx.blit(PIPELINE, texture, x, y, (float) u, (float) v,
                    drawW, drawH, regionW, regionH, TILE_PIXELS, TILE_PIXELS);
            return;
        }

        int top = Math.max(y, (int) Math.floor(circleClip.centerY() - circleClip.radius()));
        int bottom = Math.min(y2, (int) Math.ceil(circleClip.centerY() + circleClip.radius()));
        // Exactly 1.21.11: variable-height strips clipped to the circle in this (pre-rotation) local
        // space, each drawn as a float-UV textured quad. The outer matrix rotates the strips with the
        // minimap; clipping in local space means the result stays a true circle (no scissor to warp).
        int baseStrip = circleClip.stripHeight();
        double cy = circleClip.centerY();
        double radius = circleClip.radius();

        // Cover each band with ONE core quad, then use the fine strips only for the margins.
        //
        // The strips exist to shape the curved edge, but they were applied across each tile's full width
        // -- and a squaremap tile is far wider than the minimap circle, so virtually no tile takes the
        // containsRect fast path. The whole map was therefore drawn as strips, and the height formula
        // makes those 1-3px tall over most of the circle: hundreds of abutting quads whose shared edges
        // become visible lines once rotation moves them off integer pixels.
        //
        // A band's core is the chord at its FAR edge -- the narrowest the circle gets anywhere in that
        // band, so the rectangle is guaranteed to lie inside it. One quad then covers the bulk of the
        // band and the fine strips only fill the thin crescents where the circle bulges past it. Same
        // shape, same 1px edge steps, but ~90% less internal edge for a line to show along.
        double cxC = circleClip.centerX();
        int bandTop = top;
        while (bandTop < bottom) {
            int bandBottom = Math.min(bottom, bandTop + baseStrip);
            double dyBand = Math.max(Math.abs(bandTop - cy), Math.abs(bandBottom - cy));
            double coreSq = circleClip.radiusSq() - dyBand * dyBand;
            int coreL = 0, coreR = 0;
            if (coreSq > 0.0) {
                double coreHalf = Math.sqrt(coreSq);
                coreL = Math.max(x,  (int) Math.ceil(cxC - coreHalf));
                coreR = Math.min(x2, (int) Math.floor(cxC + coreHalf));
                if (coreL < coreR) {
                    blitRegion(ctx, texture, coreL, bandTop, coreR, bandBottom,
                            x, y, drawW, drawH, u, v, regionW, regionH);
                }
            }

            int stripY = bandTop;
            while (stripY < bandBottom) {
                double dyHere = Math.abs((stripY + 0.5) - cy);
                double chordHere = dyHere < radius ? Math.sqrt(radius * radius - dyHere * dyHere) : 0.0;
                int stripHeight = dyHere < 1.0
                        ? baseStrip
                        : (int) Math.max(1, Math.min(baseStrip,
                        Math.round(CIRCLE_EDGE_STEP_PX * chordHere / dyHere)));
                int stripBottom = Math.min(bandBottom, stripY + stripHeight);
                double dy = Math.max(Math.abs(stripY - cy), Math.abs(stripBottom - cy));
                double chordSq = circleClip.radiusSq() - dy * dy;
                if (chordSq > 0.0) {
                    double halfChord = Math.sqrt(chordSq);
                    int stripLeft = Math.max(x, (int) Math.floor(cxC - halfChord));
                    int stripRight = Math.min(x2, (int) Math.ceil(cxC + halfChord));
                    if (stripLeft < stripRight) {
                        // Overlap a row into the next strip. The imagery is opaque and both rows sample
                        // the same texels, so the duplicate cannot show. (This existed before, but sat on
                        // the stretched drawW/drawH that distorted the very mapping it depends on.)
                        int drawBottom = Math.min(bottom, stripBottom + 1);
                        if (coreL < coreR) {
                            // Bulk is already down; fill only the crescents, lapping 1px into the core.
                            blitRegion(ctx, texture, stripLeft, stripY, Math.min(stripRight, coreL + 1),
                                    drawBottom, x, y, drawW, drawH, u, v, regionW, regionH);
                            blitRegion(ctx, texture, Math.max(stripLeft, coreR - 1), stripY, stripRight,
                                    drawBottom, x, y, drawW, drawH, u, v, regionW, regionH);
                        } else {
                            blitRegion(ctx, texture, stripLeft, stripY, stripRight, drawBottom,
                                    x, y, drawW, drawH, u, v, regionW, regionH);
                        }
                    }
                }
                stripY = stripBottom;
            }
            bandTop = bandBottom;
        }
    }

    private void processCompletedTiles(boolean moving, int currentZoom,
                                       double worldLeft, double worldRight,
                                       double worldTop, double worldBottom) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;

        int uploadLimit = moving ? MAX_MOVING_TEXTURE_UPLOADS_PER_FRAME : MAX_TEXTURE_UPLOADS_PER_FRAME;
        long budgetNs = moving ? MOVING_TEXTURE_UPLOAD_BUDGET_NS : TEXTURE_UPLOAD_BUDGET_NS;
        long startNs = System.nanoTime();
        int uploaded = 0;
        while (uploaded < uploadLimit && System.nanoTime() - startNs < budgetNs) {
            LoadedTile loaded = bestPendingUpload(currentZoom, worldLeft, worldRight, worldTop, worldBottom);
            if (loaded == null) return;
            if (uploaded >= uploadLimit || System.nanoTime() - startNs >= budgetNs) return;
            if (!completedTiles.remove(loaded.key(), loaded)) continue;
            try {
                TileKey key = loaded.key();
                // The world belongs in the texture name too, not just in TileKey. Without it Terra
                // Nostra and the Moon share one Identifier per tile coordinate: registering one
                // overwrote the other's imagery, and evicting either released the id the other was
                // still pointing at -- which draws as the missing-texture checkerboard.
                Identifier id = Identifier.fromNamespaceAndPath("townymapaddon",
                        "squaremap/" + sanitiseWorld(key.world()) + "/" + key.zoom
                                + "/" + key.x + "_" + key.y);
                Identifier old = textures.remove(key);
                if (old != null) {
                    client.getTextureManager().release(old);
                }
                DynamicTexture texture =
                        new SmoothTileTexture(() -> "TownyMap squaremap tile " + key, loaded.image());
                client.getTextureManager().register(id, texture);
                textures.put(key, id);
                textureLoadedAt.put(key, System.currentTimeMillis());
                failedAt.remove(key);
                evictOldTextures(client);
                uploaded++;
            } catch (Exception e) {
                failedAt.put(loaded.key(), System.currentTimeMillis());
                loaded.image().close();
                LOGGER.warn("[TownyMap] Failed to upload squaremap tile {}: {}", loaded.key(), e.getMessage());
            }
        }
    }

    private LoadedTile bestPendingUpload(int currentZoom, double worldLeft, double worldRight,
                                         double worldTop, double worldBottom) {
        LoadedTile best = null;
        double bestPriority = Double.MAX_VALUE;
        for (LoadedTile tile : completedTiles.values()) {
            double priority = uploadPriority(tile.key(), currentZoom, worldLeft, worldRight, worldTop, worldBottom);
            if (priority < bestPriority) {
                best = tile;
                bestPriority = priority;
            }
        }
        return best;
    }

    private double uploadPriority(TileKey key, int currentZoom,
                                  double worldLeft, double worldRight,
                                  double worldTop, double worldBottom) {
        double tileWorldSize = TILE_PIXELS / pixelsPerBlock(key.zoom());
        double centerTileX = Math.floor(((worldLeft + worldRight) * 0.5) / tileWorldSize);
        double centerTileY = Math.floor(((worldTop + worldBottom) * 0.5) / tileWorldSize);
        double distance = Math.max(Math.abs(key.x() - centerTileX), Math.abs(key.y() - centerTileY));
        int zoomPenalty = Math.abs(key.zoom() - currentZoom);
        return zoomPenalty * 10_000.0 + distance;
    }

    private void requestTile(TileKey key) {
        requestTile(key, false, MAX_CONCURRENT_LOADS);
    }

    private void requestTile(TileKey key, boolean refreshExisting) {
        requestTile(key, refreshExisting, MAX_CONCURRENT_LOADS);
    }

    private void requestTile(TileKey key, boolean refreshExisting, int maxConcurrentLoads) {
        if ((!refreshExisting && textures.containsKey(key)) || !loading.add(key)) return;
        // Counted per world, not across the whole set. The minimap is allowed 8 concurrent loads while
        // the world map takes 40; measured against the shared total, the minimap could not start a
        // single fetch until the world map's requests drained, so closing a Moon map left the Earth
        // minimap stalled behind 40 lunar tiles.
        if (loadsForWorld(key.world()) > maxConcurrentLoads) {
            loading.remove(key);
            return;
        }

        Long failed = failedAt.get(key);
        long now = System.currentTimeMillis();
        if (failed != null && now - failed < FAILED_RETRY_MS) {
            loading.remove(key);
            return;
        }

        executor.execute(() -> {
            try {
                fetchTile(key);
            } finally {
                loading.remove(key);
            }
        });
    }

    private void fetchTile(TileKey key) {
        // TileKey is (zoom, x, y) with no world in it, and a switch clears the caches immediately while
        // requests already in flight keep running. Without this stamp a Terra Nostra tile that landed
        // after a switch to the Moon was filed under a key the Moon view then drew: Earth terrain on
        // the Moon, until something happened to evict it.
        final int generation = worldGeneration;
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(tileUrl(key)))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "TownyMapAddon/1.0 (Fabric Mod)")
                .GET();
        // Stale-checks re-downloaded ~180 KB per tile even when the imagery had not changed. With the
        // previous ETag attached the server answers 304 and sends nothing; a screen full of tiles that
        // are merely old now costs a few hundred bytes instead of several megabytes.
        String priorTag = tileEtags.get(key);
        if (priorTag != null) builder.header("If-None-Match", priorTag);
        HttpRequest request = builder.build();

        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 304) {
                // Unchanged: keep the texture we already uploaded and reset its age so the next
                // stale-check waits a full interval instead of asking again immediately.
                if (generation != worldGeneration) return;
                textureLoadedAt.put(key, System.currentTimeMillis());
                return;
            }
            if (response.statusCode() != 200) {
                failedAt.put(key, System.currentTimeMillis());
                // This used to return in silence, which made a server-side refusal (rate limit, 403,
                // Cloudflare, 5xx) indistinguishable from "the map has no tiles here": the squaremap
                // layer just went black with nothing in the log to explain it. Timeouts and decode
                // errors below were always reported; only the refusals were invisible.
                // 404 is NORMAL here: squaremap simply has no tile for ungenerated or empty regions, and
                // every map edge produces them. Only a real refusal (rate limit, auth, server error) means
                // the imagery is being withheld, so only those are worth surfacing or logging.
                boolean refused = response.statusCode() != 404;
                if (refused) {
                    lastRefusedStatus = response.statusCode();
                    lastRefusedMs = System.currentTimeMillis();
                }
                if (refused && loggedTileStatuses.add(response.statusCode())) {
                    LOGGER.warn("[TownyMap] squaremap tile request refused: HTTP {} from {} "
                            + "-- the squaremap layer stays blank until this clears",
                            response.statusCode(), tileUrl(key));
                }
                return;
            }
            byte[] bytes = response.body();
            if (generation != worldGeneration) return;   // belongs to the world we just left
            response.headers().firstValue("ETag").ifPresent(tag -> tileEtags.put(key, tag));
            LoadedTile previous = completedTiles.put(key, new LoadedTile(key, NativeImage.read(bytes)));
            if (previous != null) previous.image().close();
        } catch (Exception e) {
            failedAt.put(key, System.currentTimeMillis());
            LOGGER.warn("[TownyMap] Failed to load squaremap tile {}: {}", key, e.getMessage());
        }
    }

    private void refreshTileIfStale(TileKey key, int maxConcurrentLoads) {
        Long loadedAt = textureLoadedAt.get(key);
        if (loadedAt == null || System.currentTimeMillis() - loadedAt < TILE_REFRESH_MS) return;
        requestTile(key, true, maxConcurrentLoads);
    }

    /** World key reduced to characters an Identifier path allows. */
    private static String sanitiseWorld(String world) {
        if (world == null || world.isEmpty()) return "unknown";
        return world.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    /** How many fetches are in flight for one world. Bounded by the concurrency caps, so a scan is fine. */
    private int loadsForWorld(String world) {
        int n = 0;
        for (TileKey k : loading) if (world.equals(k.world())) n++;
        return n;
    }

    private void evictOldTextures(Minecraft client) {
        if (textures.size() <= MAX_TEXTURES) return;
        // Spare the minimap's world until it holds a working set of its own; the world map otherwise
        // evicts imagery that is on screen right now in favour of tiles for a world the player is only
        // looking at.
        String spared = net.townymap.TownyMapMod.playerWorldResolved();
        int sparedCount = 0;
        for (TileKey k : textures.keySet()) if (spared.equals(k.world())) sparedCount++;

        while (textures.size() > MAX_TEXTURES) {
            // Evict the least-recently-used tile, but SKIP the pinned low-zoom overview tiles so zooming
            // in (which floods the cache with high-zoom tiles) can't drop them and force a reload on the
            // next zoom-out. entrySet() is access-order (eldest first); iterating doesn't re-order it.
            boolean sparing = sparedCount <= MINIMAP_WORLD_RESERVE;
            Map.Entry<TileKey, Identifier> victim = null;
            for (Map.Entry<TileKey, Identifier> e : textures.entrySet()) {
                if (e.getKey().zoom() <= OVERVIEW_PIN_ZOOM) continue;
                if (sparing && spared.equals(e.getKey().world())) continue;
                victim = e; break;
            }
            if (victim == null && sparing) {
                // Nothing outside the reserve left to drop -- fall back to plain LRU rather than spin.
                for (Map.Entry<TileKey, Identifier> e : textures.entrySet()) {
                    if (e.getKey().zoom() > OVERVIEW_PIN_ZOOM) { victim = e; break; }
                }
            }
            if (victim == null) break;   // only pinned overview tiles remain — keep them all
            if (spared.equals(victim.getKey().world())) sparedCount--;
            client.getTextureManager().release(victim.getValue());
            textures.remove(victim.getKey());
            textureLoadedAt.remove(victim.getKey());
            tileEtags.remove(victim.getKey());   // evicted texture: its ETag would validate nothing
        }
    }

    boolean isLoading() {
        return !loading.isEmpty() || !completedTiles.isEmpty();
    }

    private String tileUrl(TileKey key) {
        // From the key, not the active world: a request outlives the switch that started it.
        return config.squaremapBaseUrl + "/tiles/" + key.world() + "/"
                + key.zoom + "/" + key.x + "_" + key.y + ".png";
    }

    private int chooseTileZoom(double blockScale) {
        int zoom = config.squaremapMaxZoom + (int) Math.ceil(log2(blockScale)) + QUALITY_ZOOM_BIAS;
        if (zoom < 0) return 0;
        return Math.min(config.squaremapMaxZoom, zoom);
    }

    private double pixelsPerBlock(int zoom) {
        return Math.pow(2.0, zoom - config.squaremapMaxZoom);
    }

    /**
     * Blits sub-rectangle [l,t)-(r,b) of a tile whose full quad is (tileX,tileY) sized drawW x drawH and
     * whose source region starts at (u,v) sized regionW x regionH. Every caller derives its UVs from the
     * SAME tile-wide mapping, so pieces drawn separately line up exactly with one another.
     */
    private static void blitRegion(GuiGraphicsExtractor ctx, Identifier texture,
                                   int l, int t, int r, int b,
                                   int tileX, int tileY, int drawW, int drawH,
                                   int u, int v, int regionW, int regionH) {
        if (l >= r || t >= b) return;
        float u1 = (float) ((u + (l - tileX) * (double) regionW / drawW) / TILE_PIXELS);
        float u2 = (float) ((u + (r - tileX) * (double) regionW / drawW) / TILE_PIXELS);
        float v1 = (float) ((v + (t - tileY) * (double) regionH / drawH) / TILE_PIXELS);
        float v2 = (float) ((v + (b - tileY) * (double) regionH / drawH) / TILE_PIXELS);
        ctx.blit(texture, l, t, r, b, u1, u2, v1, v2);
    }

    /**
     * Releases every cached tile and its bookkeeping. Called when the map changes world: Moon and Terra
     * Nostra use the same tile coordinates, so a kept texture would show the wrong world's ground.
     */
    void clearAll() {
        worldGeneration++;   // anything already in flight now belongs to the world we are leaving
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            for (Identifier id : textures.values()) client.getTextureManager().release(id);
        }
        textures.clear();
        textureLoadedAt.clear();
        tileEtags.clear();
        failedAt.clear();
        // Take each tile OUT of the map before closing its image. processCompletedTiles claims one with
        // remove(key, value) and uploads only if it wins that race; closing while the entry was still
        // reachable let it win and then upload freed memory, which uploads as a black or noise-filled
        // tile that stays in the cache. Removing first means the render thread either gets a live image
        // or never sees the entry.
        for (TileKey key : java.util.List.copyOf(completedTiles.keySet())) {
            LoadedTile t = completedTiles.remove(key);
            if (t != null) t.image().close();
        }
        completedTiles.clear();
    }

    /** Status code of a tile refusal in the last 30s, or 0 if the imagery is loading normally. */
    int recentRefusalStatus() {
        return System.currentTimeMillis() - lastRefusedMs < 30_000L ? lastRefusedStatus : 0;
    }

    private static int circleClipStripHeight(double radius) {
        if (radius < 72.0) return 6;
        if (radius < 128.0) return 8;
        if (radius < 220.0) return 12;
        if (radius < 360.0) return 18;
        return 24;
    }

    private static int floorToTile(double worldCoord, double tileWorldSize) {
        return (int) Math.floor(worldCoord / tileWorldSize);
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }

    private static int toScreenX(double worldX, double camX, double scale, int sw) {
        return sw / 2 + (int) Math.round((worldX - camX) * scale);
    }

    private static int toScreenY(double worldZ, double camZ, double scale, int sh) {
        return sh / 2 + (int) Math.round((worldZ - camZ) * scale);
    }

    /**
     * A tile identity. The world is part of it: Terra Nostra and the Moon use the same tile coordinates,
     * so without it one world's imagery was served for the other, and the two could not be cached at
     * once -- which the minimap now needs, since it draws the player's world while the map draws another.
     */
    private record TileKey(String world, int zoom, int x, int y) {}
    private record LoadedTile(TileKey key, NativeImage image) {}
    private record PanDirection(double x, double z) {}
    private record CircleClip(double centerX, double centerY, double radius, double radiusSq,
                              int stripHeight) {
        private boolean containsRect(double left, double top, double right, double bottom) {
            return containsPoint(left, top)
                    && containsPoint(right, top)
                    && containsPoint(right, bottom)
                    && containsPoint(left, bottom);
        }

        private boolean intersectsRect(double left, double top, double right, double bottom) {
            double closestX = Math.max(left, Math.min(centerX, right));
            double closestY = Math.max(top, Math.min(centerY, bottom));
            double dx = closestX - centerX;
            double dy = closestY - centerY;
            return dx * dx + dy * dy <= radiusSq;
        }

        private boolean containsPoint(double x, double y) {
            double dx = x - centerX;
            double dy = y - centerY;
            return dx * dx + dy * dy <= radiusSq;
        }
    }

    private enum NetworkPolicy {
        WORLD_MAP(true, true, MAX_CONCURRENT_LOADS),
        // Refresh allowed, prefetch still not. Prefetch is the expensive half -- whole rings of
        // neighbouring tiles and adjacent zooms -- while a refresh only re-pulls a tile already on
        // screen that is over TILE_REFRESH_MS old, at 8 at a time and usually answered with a 304.
        //
        // It matters now that two worlds are held: the world map only ever refreshes the world it is
        // showing, so with the map pinned to one world the OTHER one -- the one the minimap draws,
        // where the player actually is -- had no path to updated imagery at all. Its tiles stayed as
        // first fetched for the whole session.
        MINIMAP(false, true, 8);

        private final boolean allowPrefetch;
        private final boolean allowRefresh;
        private final int maxConcurrentLoads;

        NetworkPolicy(boolean allowPrefetch, boolean allowRefresh, int maxConcurrentLoads) {
            this.allowPrefetch = allowPrefetch;
            this.allowRefresh = allowRefresh;
            this.maxConcurrentLoads = maxConcurrentLoads;
        }

        private boolean allowPrefetch() {
            return allowPrefetch;
        }

        private boolean allowRefresh() {
            return allowRefresh;
        }

        private int maxConcurrentLoads() {
            return maxConcurrentLoads;
        }
    }

    private static final class SmoothTileTexture extends DynamicTexture {
        private SmoothTileTexture(java.util.function.Supplier<String> name, NativeImage image) {
            super(name, image);
            this.sampler = RenderSystem.getSamplerCache().getSampler(
                    AddressMode.CLAMP_TO_EDGE,
                    AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR,
                    FilterMode.LINEAR,
                    false);
        }
    }
}
