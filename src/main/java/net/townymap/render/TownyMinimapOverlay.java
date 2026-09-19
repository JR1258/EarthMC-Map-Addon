package net.townymap.render;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.world.World;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Identifier;
import net.townymap.TownyMapConfig;
import net.townymap.TownyMapMod;
import net.townymap.api.SquaremapApiClient;
import net.townymap.gui.ChunkCounterOverlay;
import net.townymap.model.OptimisticClaimChunk;
import net.townymap.model.PlayerMarker;
import net.townymap.model.TownData;
import org.joml.Matrix3x2fStack;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.config.util.MinimapConfigClientUtils;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.render.module.ModuleRenderContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TownyMinimapOverlay {

    private static final int FAVORITE_OUTLINE = 0xFFFFE066;
    private static final int FAVORITE_FILL = 0x44FFE066;
    private static final double EXTRA_BLOCK_PADDING = 96.0;
    // Edge budget for one cached build (not per frame — buildRenderData only runs when the window or the
    // town data changes). At 1200 a busy view ran out mid-scan, so towns later in the scan lost their
    // outline entirely and the one at the cutoff got a half-drawn box; which towns lost out shifted as the
    // window snapped while walking, which is what made the borders look glitchy.
    private static final int MAX_CHUNK_EDGES_PER_FRAME = 24_000;
    // Cells in the cached chunk mask. Only rebuilt when the window or the town data changes, so this can be
    // generous; at 7000 (~83x83 chunks) a zoomed-out enlarged minimap exceeded it and the overlay bailed out
    // entirely, which looked like the town borders randomly vanishing.
    private static final int MAX_CHUNK_CELLS_PER_FRAME = 40_000;
    private static final int MAX_MINIMAP_CHUNK_GRID_LINES = 260;
    private static final int MAX_MINIMAP_LABELS = 24;
    private static final int MAX_MINIMAP_WAYPOINTS_ON_TOP = 96;
    private static final int MINIMAP_CLIP_INSET = 2;
    private static final int MINIMAP_RENDER_CACHE_STEP_CHUNKS = 4;
    /** Chunks of neighbour context kept outside the drawn window, so boundary chunks know their neighbours. */
    private static final int EDGE_MARGIN_CHUNKS = 1;
    private static final int MINIMAP_RENDER_CACHE_PADDING_CHUNKS = 4;
    private static final long MINIMAP_SHAPE_CACHE_MS = 100L;
    private static final long MINIMAP_FRAME_BUDGET_NS = 12_000_000L;
    private static final long MINIMAP_PERFORMANCE_SHED_MS = 2_000L;
    private static final double MIN_MINIMAP_CHUNK_GRID_SPACING = 3.5;
    private static final int CHUNK_SIZE = 16;
    private static final int MINIMAP_CHUNK_GRID_COLOR = 0xCC000000;
    private static List<TownData> cachedTownSource = List.of();
    private static int cachedMinChunkX;
    private static int cachedMinChunkZ;
    private static int cachedChunkWidth;
    private static int cachedChunkHeight;
    private static VisibleRenderData cachedRenderData =
            new VisibleRenderData(new TownData[0], List.of(), List.of(), List.of(), List.of());
    private static int lastSyncedXaeroChunkGrid = Integer.MIN_VALUE;
    private static long lastXaeroChunkGridSyncAttemptMs;
    private static long lastWaypointConfigReadAtMs;
    private static long lastMinimapShapeReadAtMs;
    private static int cachedMinimapShape = 0;
    private static long lastArrowOptionReadAtMs;
    private static double cachedArrowScale = 1.0;
    private static WaypointDrawConfig cachedWaypointDrawConfig =
            new WaypointDrawConfig(true, 100, 1.0F, 0.0, false, false);
    private static final long WAYPOINT_COLLECT_CACHE_MS = 2_000L;
    private static MinimapSession cachedWaypointSession;
    private static long cachedWaypointSetChangedAt;
    private static long cachedWaypointCollectAtMs;
    private static List<Waypoint> cachedMinimapWaypoints = List.of();
    private static boolean lastRenderCanCoverWaypoints;
    /**
     * Blocks-per-block factor the minimap was actually drawn at this frame, for the waypoint layer.
     * 1.0 normally. In the Nether with "Overworld Coords" the town overlay and the squaremap tiles are
     * drawn in overworld space, so a waypoint delta measured in Nether blocks has to be multiplied by
     * this to land in the right place. Only set when the squaremap layer is what's actually on screen:
     * over Xaero's own terrain the waypoints belong to the Nether and must not be scaled.
     */
    private static double lastRenderWaypointScale = 1.0;
    private static long minimapPerformanceShedUntilMs;

    private TownyMinimapOverlay() {
    }

    public static void invalidateTownCache() {
        cachedTownSource = List.of();
        cachedMinChunkX = 0;
        cachedMinChunkZ = 0;
        cachedChunkWidth = 0;
        cachedChunkHeight = 0;
        cachedRenderData = new VisibleRenderData(new TownData[0], List.of(), List.of(), List.of(), List.of());
    }

    /**
     * How many blocks span the width of the minimap.
     * <p>
     * Xaero applies its zoom as a <em>magnification</em> of the map quad, so a higher zoom
     * makes each block bigger and shrinks the visible span — the span is the map size
     * divided by the zoom, not multiplied by it. Both agree at zoom 1 (the default), which
     * is why getting this backwards only misaligned the overlay for players who had zoomed in.
     */
    private static double minimapBlocksAcross(MinimapSession session) {
        double zoom = Math.max(0.25, session.getProcessor().getMinimapZoom());
        return Math.max(8.0, session.getProcessor().getMinimapSize() / zoom);
    }

    public static void render(DrawContext ctx, MinimapSession session, ModuleRenderContext rc) {
        if (TownyMapMod.isAccessBlocked()) return;
        render(ctx, session, rc.x, rc.y, Math.min(rc.w, rc.h));
    }

    public static void render(DrawContext ctx, MinimapSession session, int mapX, int mapY, int mapSize) {
        if (TownyMapMod.isAccessBlocked()) return;
        long renderStartNs = System.nanoTime();   // 1.21.11-only frame-cost tracking; 26.2 has no equivalent
        TownyMapConfig config = TownyMapMod.getConfig();
        SquaremapApiClient api = TownyMapMod.getApiClient();
        lastRenderCanCoverWaypoints = false;
        lastRenderWaypointScale = 1.0;
        if (config == null || api == null || !config.minimapExtensionsEnabled) return;
        // The same gate the world map has always used. Without it the minimap ignored "EarthMC Only"
        // entirely: on any other server it kept drawing EarthMC claims over unrelated terrain while the
        // world map correctly went quiet, which reads as the mod being stuck half-on.
        if (!TownyMapMod.isActiveOnCurrentServer()) return;
        syncXaeroChunkGrid(session, config);

        api.tickMinimapTownMarkers();
        api.tickPlayers();   // keep player dots + the under-minimap list live even when the full map is closed

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;

        int size = mapSize;
        if (size <= 12) return;

        if (session.getProcessor().isCaveModeDisplayed()) return;

        // The EarthMC map is overworld-only. Outside the overworld (e.g. the Nether) the raw X/Z
        // would place our overlay at the wrong spot, so apply the configured behaviour. Resolved
        // before the projection below, which needs it for both the coordinates and the block size.
        double dimScale = 1.0;
        if (client.world.getRegistryKey() != World.OVERWORLD) {
            if (config.netherMode == 2 && client.world.getRegistryKey() == World.NETHER) {
                dimScale = TownyMapMod.dimensionCoordinateScale();   // Overworld Coords (Nether x8)
            } else if (!TownyMapMod.WORLD_OVERWORLD.equals(TownyMapMod.playerWorldResolved())) {
                // A world squaremap maps -- the Moon. Claims there are at real 1:1 coordinates, so draw
                // exactly as on Earth. Deliberately NOT tied to the world the map is showing: the
                // minimap draws where the player stands, whatever the world map is pinned to.
                dimScale = 1.0;
            } else if (TownyMapMod.isOnEarthMcServer()) {
                return;                                              // Hidden
            }
            // Off EarthMC, keep dimScale 1.0 and draw: the overworld-only restriction protects the
            // overlay's alignment with EarthMC's own world, which does not apply on another server.
        }

        double blocksAcross = minimapBlocksAcross(session);
        // Pixels per *overworld* block, and deliberately NOT divided by dimScale. The minimap shows the
        // overworld map at its normal scale centred on where you'd be in the overworld, rather than
        // shrinking it to line up with the Nether terrain underneath (which is what the world map does).
        // Everything downstream depends on that reading: visibleBlocks below derives the chunk scan
        // window from blocksAcross in these same units, so dividing here — as 1.3.8 briefly did —
        // shrinks the scan window 8x and most borders never get drawn.
        double pixelsPerBlock = size / blocksAcross;
        if (pixelsPerBlock <= 0) return;

        double centerX = mapX + size / 2.0;
        double centerY = mapY + size / 2.0;
        double playerX = player.getX() * dimScale;
        double playerZ = player.getZ() * dimScale;
        double angle = minimapAngle(session, client);
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        boolean circular = isCircularMinimap(session);
        boolean enlarged = session.getProcessor().isEnlargedMap();
        // Frame-budget shedding used to hide names / rim fills / rim outlines for ~2s whenever
        // a one-off frame (cache rebuild, tile upload) blew the budget — which read on screen as
        // the overlay flickering away every few seconds. With the world-space + merged-span
        // rendering and the hard per-frame caps, that safety net is no longer needed and did more
        // harm than good, so the overlay now renders fully every frame.
        boolean performanceShed = false;

        int left = mapX;
        int top = mapY;
        int right = mapX + size - 1;
        int bottom = mapY + size - 1;
        int clipLeft = left + MINIMAP_CLIP_INSET;
        int clipTop = top + MINIMAP_CLIP_INSET;
        int clipRight = right - MINIMAP_CLIP_INSET;
        int clipBottom = bottom - MINIMAP_CLIP_INSET;
        MinimapClip clip = MinimapClip.of(clipLeft, clipTop, clipRight, clipBottom, circular);
        double visibleBlocks = circular
                ? clip.radius() / pixelsPerBlock + CHUNK_SIZE * 1.5
                : blocksAcross / 2.0 + EXTRA_BLOCK_PADDING;
        TownyMapMod.updateMinimapNationAlert(playerX, playerZ, visibleBlocks);

        boolean squaremapRendered = config.squaremapOnMinimap();
        lastRenderWaypointScale = squaremapRendered ? dimScale : 1.0;
        if (squaremapRendered) {
            renderSquaremapBackground(ctx, mapX, mapY, size, playerX, playerZ,
                    pixelsPerBlock, angle, clip);
        }
        // The squaremap covers Xaero's in-pip waypoints, so the on-top redraw must run whenever
        // it (or the fills/grid) drew — set this now so any early-return below can't skip it.
        lastRenderCanCoverWaypoints = squaremapRendered || config.chunkCounterEnabled;

        // The minimap draws where the player IS, so it asks for that world's claims by name -- the world
        // map may be pinned to the other one, and both are held now.
        String minimapWorld = TownyMapMod.playerWorldResolved();
        if (api.getTowns(minimapWorld).isEmpty()) {
            lastRenderCanCoverWaypoints = !performanceShed && (squaremapRendered || config.chunkCounterEnabled);
            if (squaremapRendered) {
                renderMinimapChunkGrid(ctx, session, config, centerX, centerY, playerX, playerZ,
                        visibleBlocks, pixelsPerBlock, angle, sin, cos, clip);
            }
            if (!performanceShed) {
                renderChunkCounterSelection(ctx, client, config, mapX, mapY, size, centerX, centerY,
                        playerX, playerZ, pixelsPerBlock, angle, sin, cos, clip);
            }
            if (squaremapRendered) ctx.drawDeferredElements();
            recordMinimapFrameCost(renderStartNs);
            return;
        }

        int rawMinChunkX = floorToChunk(playerX - visibleBlocks);
        int rawMaxChunkX = floorToChunk(playerX + visibleBlocks);
        int rawMinChunkZ = floorToChunk(playerZ - visibleBlocks);
        int rawMaxChunkZ = floorToChunk(playerZ + visibleBlocks);
        int minChunkX = alignDown(rawMinChunkX - MINIMAP_RENDER_CACHE_PADDING_CHUNKS,
                MINIMAP_RENDER_CACHE_STEP_CHUNKS);
        int maxChunkX = alignUp(rawMaxChunkX + MINIMAP_RENDER_CACHE_PADDING_CHUNKS + 1,
                MINIMAP_RENDER_CACHE_STEP_CHUNKS) - 1;
        int minChunkZ = alignDown(rawMinChunkZ - MINIMAP_RENDER_CACHE_PADDING_CHUNKS,
                MINIMAP_RENDER_CACHE_STEP_CHUNKS);
        int maxChunkZ = alignUp(rawMaxChunkZ + MINIMAP_RENDER_CACHE_PADDING_CHUNKS + 1,
                MINIMAP_RENDER_CACHE_STEP_CHUNKS) - 1;
        int chunkWidth = maxChunkX - minChunkX + 1;
        int chunkHeight = maxChunkZ - minChunkZ + 1;
        if (chunkWidth <= 0 || chunkHeight <= 0 || chunkWidth * chunkHeight > MAX_CHUNK_CELLS_PER_FRAME) {
            // Drop the cache padding first — it only exists to make the window change less often.
            minChunkX = rawMinChunkX;
            maxChunkX = rawMaxChunkX;
            minChunkZ = rawMinChunkZ;
            maxChunkZ = rawMaxChunkZ;
            chunkWidth = maxChunkX - minChunkX + 1;
            chunkHeight = maxChunkZ - minChunkZ + 1;
        }
        if (chunkWidth <= 0 || chunkHeight <= 0) {
            recordMinimapFrameCost(renderStartNs);
            return;
        }
        if (chunkWidth * chunkHeight > MAX_CHUNK_CELLS_PER_FRAME) {
            // Still too wide: draw the middle rather than nothing. Bailing here meant the whole overlay —
            // borders, fills and names — disappeared at wide zoom on a large minimap.
            int halfSpan = (int) ((Math.sqrt(MAX_CHUNK_CELLS_PER_FRAME) - 1) / 2);
            int playerChunkX = floorToChunk(playerX);
            int playerChunkZ = floorToChunk(playerZ);
            minChunkX = Math.max(minChunkX, playerChunkX - halfSpan);
            maxChunkX = Math.min(maxChunkX, playerChunkX + halfSpan);
            minChunkZ = Math.max(minChunkZ, playerChunkZ - halfSpan);
            maxChunkZ = Math.min(maxChunkZ, playerChunkZ + halfSpan);
            chunkWidth = maxChunkX - minChunkX + 1;
            chunkHeight = maxChunkZ - minChunkZ + 1;
            if (chunkWidth <= 0 || chunkHeight <= 0) {
                recordMinimapFrameCost(renderStartNs);
                return;
            }
        }

        List<TownData> towns = api.getTowns(minimapWorld);
        VisibleRenderData renderData = cachedVisibleRenderData(towns, minChunkX, minChunkZ, chunkWidth, chunkHeight);
        lastRenderCanCoverWaypoints = !performanceShed
                && (squaremapRendered || config.chunkCounterEnabled || !renderData.fillSpans().isEmpty());

        ctx.enableScissor(clip.left(), clip.top(), clip.right() + 1, clip.bottom() + 1);
        Matrix3x2fStack matrices = ctx.getMatrices();
        matrices.pushMatrix();
        try {
            matrices.translate((float) centerX, (float) centerY);
            matrices.rotate((float) angle);
            matrices.scale((float) pixelsPerBlock, (float) pixelsPerBlock);
            matrices.translate((float) -playerX, (float) -playerZ);

            if (clip.circular()) {
                fillVisibleTownSpansCircleClipped(ctx, renderData.fillSpans(), config, clip,
                        centerX, centerY, playerX, playerZ, pixelsPerBlock, sin, cos, performanceShed);
            } else {
                fillVisibleTownSpans(ctx, renderData.fillSpans(), config);
            }
        } finally {
            matrices.popMatrix();
            ctx.disableScissor();
        }

        if (squaremapRendered) {
            renderMinimapChunkGrid(ctx, session, config, centerX, centerY, playerX, playerZ,
                    visibleBlocks, pixelsPerBlock, angle, sin, cos, clip);
        }

        ctx.enableScissor(clip.left(), clip.top(), clip.right() + 1, clip.bottom() + 1);
        try {
            if (clip.circular()) {
                drawVisibleTownEdgesCircleClipped(ctx, renderData.edges(), config, clip,
                        centerX, centerY, playerX, playerZ, pixelsPerBlock, sin, cos,
                        !performanceShed && renderData.edges().size() <= MAX_CHUNK_EDGES_PER_FRAME);
                drawOptimisticClaimChunks(ctx, clip,
                        centerX, centerY, playerX, playerZ, pixelsPerBlock, sin, cos);
                if (!performanceShed && config.chunkCounterEnabled) {
                    matrices.pushMatrix();
                    try {
                        matrices.translate((float) centerX, (float) centerY);
                        matrices.rotate((float) angle);
                        matrices.scale((float) pixelsPerBlock, (float) pixelsPerBlock);
                        matrices.translate((float) -playerX, (float) -playerZ);
                        // Clip the counter fills/edges to the circle (like the town fills) so they
                        // don't spill into the rectangular corners outside the ring.
                        ChunkCounterOverlay.renderMinimapFillsClipped((bx, bz, bw, bh, c) ->
                                fillRectCircleClipped(ctx, bx, bz, bw, bh, c, clip,
                                        centerX, centerY, playerX, playerZ, pixelsPerBlock, sin, cos));
                    } finally {
                        matrices.popMatrix();
                    }
                }
            } else {
                matrices.pushMatrix();
                try {
                    matrices.translate((float) centerX, (float) centerY);
                    matrices.rotate((float) angle);
                    matrices.scale((float) pixelsPerBlock, (float) pixelsPerBlock);
                    matrices.translate((float) -playerX, (float) -playerZ);

                    drawVisibleTownEdges(ctx, renderData.edges(), config);
                    drawOptimisticClaimChunks(ctx);
                    if (config.chunkCounterEnabled) {
                        // Pass the visible block rect so a large selection only draws what is on screen.
                        // Rotation means the diagonal is the worst case, hence the 1.5x margin.
                        double half = (size / 2.0) / Math.max(0.0001, pixelsPerBlock) * 1.5;
                        ChunkCounterOverlay.renderWorldSpace(ctx,
                                (int) (playerX - half), (int) (playerZ - half),
                                (int) (playerX + half), (int) (playerZ + half));
                    }
                } finally {
                    matrices.popMatrix();
                }
            }
        } finally {
            ctx.disableScissor();
        }

        if (!performanceShed && config.playersEnabled && config.minimapPlayersEnabled
                && !TownyMapMod.isArchiveMode()) {   // archived snapshots have no live players
            renderPlayerDots(ctx, api.getPlayers(minimapWorld), player.getName().getString(),
                    mapX, mapY, size, playerX, playerZ, pixelsPerBlock, sin, cos,
                    clip);
        }

        if (!performanceShed && config.minimapTownNamesEnabled && config.minimapTownNameMode != 0) {
            renderTownNames(ctx, client, renderData.labelAnchors(),
                    mapX, mapY, size, playerX, playerZ, pixelsPerBlock, sin, cos, config.minimapTownNameMode,
                    clip);
        }

        if (!performanceShed && config.chunkCounterEnabled) {
            ChunkCounterOverlay.renderMinimapLabels(ctx, client, mapX, mapY, size,
                    playerX, playerZ, pixelsPerBlock, sin, cos,
                    clip.left(), clip.top(), clip.right(), clip.bottom(), clip.circular(), clip.radius());
        }

        ctx.drawDeferredElements();
        recordMinimapFrameCost(renderStartNs);
    }

    public static void renderWaypointsOnTop(DrawContext ctx, MinimapSession session,
                                            int mapX, int mapY, int size) {
        if (TownyMapMod.isAccessBlocked()) return;
        TownyMapConfig config = TownyMapMod.getConfig();
        if (config == null || !config.minimapExtensionsEnabled || size <= 12) return;
        if (session.getProcessor().isCaveModeDisplayed()) return;
        if (!lastRenderCanCoverWaypoints) return;

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;
        WaypointDrawConfig waypointConfig = waypointDrawConfig();
        if (!waypointConfig.waypointsOnMinimap()) return;

        List<Waypoint> waypoints = cachedWaypoints(session);
        if (waypoints.isEmpty()) return;

        int opacity = waypointConfig.opacity();
        float iconScale = waypointConfig.iconScale();
        boolean temporaryWaypointsGlobal = waypointConfig.temporaryWaypointsGlobal();
        boolean dimensionScaleDistance = waypointConfig.dimensionScaleDistance();
        double maxDistance = waypointConfig.maxDistance();

        double blocksAcross = minimapBlocksAcross(session);
        // Match whatever projection the map was drawn at this frame. Waypoints are stored in the
        // dimension the player is in, so over the overworld-coords Nether view their deltas have to be
        // scaled up the same way the town overlay's were, or they cluster 8x too close to the player.
        double pixelsPerBlock = size / blocksAcross * lastRenderWaypointScale;
        if (pixelsPerBlock <= 0) return;

        double backgroundScale = Math.max(0.0001, session.getProcessor().getLastMapDimensionScale());
        double dimCoordinateScale = 1.0;
        try {
            var world = session.getWorldManager().getCurrentWorld();
            if (world != null) {
                dimCoordinateScale = Math.max(0.0001,
                        session.getDimensionHelper().getDimCoordinateScale(world));
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
        double waypointCoordinateScale = backgroundScale / dimCoordinateScale;
        double distanceScale = dimensionScaleDistance ? backgroundScale : 1.0;

        double centerX = mapX + size / 2.0;
        double centerY = mapY + size / 2.0;
        double playerX = player.getX();
        double playerZ = player.getZ();
        double angle = minimapAngle(session, client);
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        int iconPadding = Math.max(10, (int) Math.ceil(12.0 * iconScale));
        int clipLeft = mapX + MINIMAP_CLIP_INSET;
        int clipTop = mapY + MINIMAP_CLIP_INSET;
        int clipRight = mapX + size - 1 - MINIMAP_CLIP_INSET;
        int clipBottom = mapY + size - 1 - MINIMAP_CLIP_INSET;
        MinimapClip clip = MinimapClip.of(clipLeft, clipTop, clipRight, clipBottom,
                isCircularMinimap(session));

        Matrix3x2fStack matrices = ctx.getMatrices();
        int drawn = 0;
        for (Waypoint waypoint : waypoints) {
            if (waypoint == null || waypoint.isDisabled()) continue;

            double waypointX = waypoint.getX(waypointCoordinateScale) + 0.5;
            double waypointZ = waypoint.getZ(waypointCoordinateScale) + 0.5;
            double dx = waypointX - playerX;
            double dz = waypointZ - playerZ;
            if (!shouldDrawWaypointAtDistance(waypoint, dx, dz, maxDistance, distanceScale,
                    temporaryWaypointsGlobal)) {
                continue;
            }

            int screenX = (int) Math.round(centerX + (dx * cos - dz * sin) * pixelsPerBlock);
            int screenY = (int) Math.round(centerY + (dx * sin + dz * cos) * pixelsPerBlock);
            if (screenX < mapX - iconPadding || screenX > mapX + size + iconPadding
                    || screenY < mapY - iconPadding || screenY > mapY + size + iconPadding) {
                continue;
            }
            if (!clip.containsPoint(screenX, screenY, iconPadding * 0.35)) continue;

            matrices.pushMatrix();
            try {
                matrices.translate(screenX, screenY);
                if (iconScale != 1.0F) {
                    matrices.scale(iconScale, iconScale);
                }
                // Draw via DrawContext (deferred) so it composites on top of the squaremap,
                // instead of Xaero's immediate drawIconGUI which renders underneath it.
                drawWaypointBadge(ctx, waypoint, opacity);
            } finally {
                matrices.popMatrix();
            }
            if (++drawn >= MAX_MINIMAP_WAYPOINTS_ON_TOP) break;
        }
    }

    /** A small coloured badge with the waypoint's symbol, drawn at the already-translated origin. */
    private static void drawWaypointBadge(DrawContext ctx, Waypoint waypoint, int opacity) {
        var tr = MinecraftClient.getInstance().textRenderer;
        String label = waypoint.getSymbolSafe("");
        if (label.isBlank()) label = waypoint.getInitialsSafe("");
        if (label.isBlank()) label = "?";
        int alpha = Math.max(60, Math.min(255, (int) Math.round(opacity * 2.55)));
        int badge = (alpha << 24) | (waypointColorRgb(waypoint) & 0x00FFFFFF);
        int outline = alpha << 24;
        int w = tr.getWidth(label);
        int halfW = w / 2 + 2;
        int halfH = tr.fontHeight / 2 + 1;
        ctx.fill(-halfW - 1, -halfH - 1, halfW + 1, halfH + 1, outline); // dark outline
        ctx.fill(-halfW, -halfH, halfW, halfH, badge);                   // coloured badge
        ctx.drawText(tr, label, -w / 2, -tr.fontHeight / 2 + 1, 0xFFFFFFFF, true);
    }

    private static int waypointColorRgb(Waypoint waypoint) {
        try {
            net.minecraft.util.Formatting fmt = net.minecraft.util.Formatting.byColorIndex(waypoint.getColor());
            if (fmt != null && fmt.getColorValue() != null) return fmt.getColorValue();
        } catch (RuntimeException ignored) {
        }
        return 0xFFFFFF;
    }

    private static List<Waypoint> cachedWaypoints(MinimapSession session) {
        long now = System.currentTimeMillis();
        long setChangedAt = session.getWaypointSession().getSetChangedTime();
        if (session == cachedWaypointSession
                && setChangedAt == cachedWaypointSetChangedAt
                && now - cachedWaypointCollectAtMs < WAYPOINT_COLLECT_CACHE_MS) {
            return cachedMinimapWaypoints;
        }

        ArrayList<Waypoint> waypoints = new ArrayList<>();
        session.getWaypointSession().getCollector().collect(waypoints);
        cachedWaypointSession = session;
        cachedWaypointSetChangedAt = setChangedAt;
        cachedWaypointCollectAtMs = now;
        cachedMinimapWaypoints = List.copyOf(waypoints);
        return cachedMinimapWaypoints;
    }

    private static boolean shouldDrawWaypointAtDistance(Waypoint waypoint,
                                                        double dx, double dz,
                                                        double maxDistance,
                                                        double distanceScale,
                                                        boolean temporaryWaypointsGlobal) {
        if (waypoint.isDestination()
                || waypoint.getPurpose() == xaero.hud.minimap.waypoint.WaypointPurpose.DEATH
                || waypoint.isGlobal()
                || (waypoint.isTemporary() && temporaryWaypointsGlobal)
                || maxDistance == 0.0) {
            return true;
        }
        double distance = Math.sqrt(dx * dx + dz * dz) * distanceScale;
        return distance <= maxDistance;
    }

    private static WaypointDrawConfig waypointDrawConfig() {
        long now = System.currentTimeMillis();
        if (now - lastWaypointConfigReadAtMs < 1_000L) return cachedWaypointDrawConfig;
        lastWaypointConfigReadAtMs = now;

        WaypointDrawConfig result = cachedWaypointDrawConfig;
        Path path = FabricLoader.getInstance().getConfigDir()
                .resolve("xaero/minimap/profiles/default.cfg");
        if (!Files.exists(path)) return result;

        boolean waypointsOnMinimap = result.waypointsOnMinimap();
        int opacity = result.opacity();
        float iconScale = result.iconScale();
        double maxDistance = result.maxDistance();
        boolean dimensionScaleDistance = result.dimensionScaleDistance();
        boolean temporaryWaypointsGlobal = result.temporaryWaypointsGlobal();

        try {
            for (String line : Files.readAllLines(path)) {
                int equals = line.indexOf('=');
                if (equals < 0) continue;
                String key = line.substring(0, equals).trim();
                String value = line.substring(equals + 1).trim();
                switch (key) {
                    case "waypoints_on_minimap" -> waypointsOnMinimap = Boolean.parseBoolean(value);
                    case "waypoint_opacity_on_minimap" -> opacity = clamp(parseInt(value, opacity), 0, 100);
                    case "waypoint_icon_scale_on_minimap" -> iconScale = waypointIconScaleFromConfig(value);
                    case "waypoint_max_distance" -> maxDistance = Math.max(0, parseInt(value, (int) maxDistance));
                    case "waypoint_max_distance_dimension_scale" ->
                            dimensionScaleDistance = Boolean.parseBoolean(value);
                    case "temporary_waypoints_global" -> temporaryWaypointsGlobal = Boolean.parseBoolean(value);
                    default -> {
                    }
                }
            }
            cachedWaypointDrawConfig = new WaypointDrawConfig(waypointsOnMinimap, opacity, iconScale,
                    maxDistance, dimensionScaleDistance, temporaryWaypointsGlobal);
        } catch (Exception ignored) {
        }
        return cachedWaypointDrawConfig;
    }

    private static float waypointIconScaleFromConfig(String value) {
        int configured = parseInt(value, 0);
        if (configured <= 0) return 1.0F;
        return Math.max(1.0F, Math.min(8.0F, configured));
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void renderChunkCounterSelection(DrawContext ctx, MinecraftClient client, TownyMapConfig config,
                                                    int mapX, int mapY, int size,
                                                    double centerX, double centerY,
                                                    double playerX, double playerZ,
                                                    double pixelsPerBlock, double angle,
                                                    double sin, double cos,
                                                    MinimapClip clip) {
        if (!config.chunkCounterEnabled) return;
        ctx.enableScissor(clip.left(), clip.top(), clip.right() + 1, clip.bottom() + 1);
        Matrix3x2fStack matrices = ctx.getMatrices();
        matrices.pushMatrix();
        try {
            matrices.translate((float) centerX, (float) centerY);
            matrices.rotate((float) angle);
            matrices.scale((float) pixelsPerBlock, (float) pixelsPerBlock);
            matrices.translate((float) -playerX, (float) -playerZ);
            double halfSel = (size / 2.0) / Math.max(0.0001, pixelsPerBlock) * 1.5;
            ChunkCounterOverlay.renderWorldSpace(ctx,
                    (int) (playerX - halfSel), (int) (playerZ - halfSel),
                    (int) (playerX + halfSel), (int) (playerZ + halfSel));
        } finally {
            matrices.popMatrix();
            ctx.disableScissor();
        }
        ChunkCounterOverlay.renderMinimapLabels(ctx, client, mapX, mapY, size,
                playerX, playerZ, pixelsPerBlock, sin, cos,
                clip.left(), clip.top(), clip.right(), clip.bottom(), clip.circular(), clip.radius());
    }

    private static void renderMinimapChunkGrid(DrawContext ctx, MinimapSession session, TownyMapConfig config,
                                               double centerX, double centerY,
                                               double playerX, double playerZ,
                                               double visibleBlocks, double pixelsPerBlock, double angle,
                                               double sin, double cos,
                                               MinimapClip clip) {
        if (!shouldRenderMinimapChunkGrid(session, config, pixelsPerBlock)) return;
        if (clip.circular()) return;
        ctx.enableScissor(clip.left(), clip.top(), clip.right() + 1, clip.bottom() + 1);
        try {
            Matrix3x2fStack matrices = ctx.getMatrices();
            matrices.pushMatrix();
            try {
                matrices.translate((float) centerX, (float) centerY);
                matrices.rotate((float) angle);
                drawChunkGridScreenSpace(ctx, playerX, playerZ, visibleBlocks, pixelsPerBlock);
            } finally {
                matrices.popMatrix();
            }
        } finally {
            ctx.disableScissor();
        }
    }

    private static boolean shouldRenderMinimapChunkGrid(MinimapSession session, TownyMapConfig config,
                                                        double pixelsPerBlock) {
        if (config.minimapChunkGridMode == 0) return false;
        if (config.minimapChunkGridMode == 2 && !session.getProcessor().isEnlargedMap()) return false;
        return CHUNK_SIZE * pixelsPerBlock >= MIN_MINIMAP_CHUNK_GRID_SPACING;
    }

    private static void drawChunkGridScreenSpace(DrawContext ctx, double playerX, double playerZ,
                                                 double visibleBlocks, double pixelsPerBlock) {
        if (CHUNK_SIZE * pixelsPerBlock < MIN_MINIMAP_CHUNK_GRID_SPACING) return;
        int minChunkX = floorToChunk(playerX - visibleBlocks);
        int maxChunkX = floorToChunk(playerX + visibleBlocks) + 1;
        int minChunkZ = floorToChunk(playerZ - visibleBlocks);
        int maxChunkZ = floorToChunk(playerZ + visibleBlocks) + 1;
        int verticalLines = maxChunkX - minChunkX + 1;
        int horizontalLines = maxChunkZ - minChunkZ + 1;
        if (verticalLines + horizontalLines > MAX_MINIMAP_CHUNK_GRID_LINES) return;

        int minBlockZ = minChunkZ * CHUNK_SIZE;
        int maxBlockZ = maxChunkZ * CHUNK_SIZE;
        int localTop = (int) Math.floor((minBlockZ - playerZ) * pixelsPerBlock);
        int localBottom = (int) Math.ceil((maxBlockZ - playerZ) * pixelsPerBlock);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            int blockX = chunkX * CHUNK_SIZE;
            int localX = (int) Math.round((blockX - playerX) * pixelsPerBlock);
            ctx.fill(localX, localTop, localX + 1, localBottom, MINIMAP_CHUNK_GRID_COLOR);
        }

        int minBlockX = minChunkX * CHUNK_SIZE;
        int maxBlockX = maxChunkX * CHUNK_SIZE;
        int localLeft = (int) Math.floor((minBlockX - playerX) * pixelsPerBlock);
        int localRight = (int) Math.ceil((maxBlockX - playerX) * pixelsPerBlock);
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            int blockZ = chunkZ * CHUNK_SIZE;
            int localY = (int) Math.round((blockZ - playerZ) * pixelsPerBlock);
            ctx.fill(localLeft, localY, localRight, localY + 1, MINIMAP_CHUNK_GRID_COLOR);
        }
    }

    private static void drawChunkGridCircleClipped(DrawContext ctx,
                                                   double centerX, double centerY,
                                                   double playerX, double playerZ,
                                                   double visibleBlocks, double pixelsPerBlock,
                                                   double sin, double cos,
                                                   MinimapClip clip,
                                                   int maxLines) {
        if (CHUNK_SIZE * pixelsPerBlock < MIN_MINIMAP_CHUNK_GRID_SPACING) return;
        int minChunkX = floorToChunk(playerX - visibleBlocks);
        int maxChunkX = floorToChunk(playerX + visibleBlocks) + 1;
        int minChunkZ = floorToChunk(playerZ - visibleBlocks);
        int maxChunkZ = floorToChunk(playerZ + visibleBlocks) + 1;
        int verticalLines = maxChunkX - minChunkX + 1;
        int horizontalLines = maxChunkZ - minChunkZ + 1;
        if (verticalLines + horizontalLines > maxLines) return;

        int minBlockZ = minChunkZ * CHUNK_SIZE;
        int maxBlockZ = maxChunkZ * CHUNK_SIZE;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            int blockX = chunkX * CHUNK_SIZE;
            drawWorldLineCircleClipped(ctx, blockX, minBlockZ, blockX, maxBlockZ,
                    MINIMAP_CHUNK_GRID_COLOR, clip, centerX, centerY, playerX, playerZ,
                    pixelsPerBlock, sin, cos);
        }

        int minBlockX = minChunkX * CHUNK_SIZE;
        int maxBlockX = maxChunkX * CHUNK_SIZE;
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            int blockZ = chunkZ * CHUNK_SIZE;
            drawWorldLineCircleClipped(ctx, minBlockX, blockZ, maxBlockX, blockZ,
                    MINIMAP_CHUNK_GRID_COLOR, clip, centerX, centerY, playerX, playerZ,
                    pixelsPerBlock, sin, cos);
        }
    }

    private static TownData[] buildVisibleChunkMask(List<TownData> towns, int minChunkX, int minChunkZ,
                                                    int chunkWidth, int chunkHeight) {
        TownData[] chunkTowns = new TownData[chunkWidth * chunkHeight];
        int maxChunkX = minChunkX + chunkWidth - 1;
        int maxChunkZ = minChunkZ + chunkHeight - 1;

        for (TownData town : towns) {
            int townMinChunkX = Math.max(minChunkX, floorToChunk(town.minX()));
            int townMaxChunkX = Math.min(maxChunkX, floorToChunk(town.maxX()));
            int townMinChunkZ = Math.max(minChunkZ, floorToChunk(town.minZ()));
            int townMaxChunkZ = Math.min(maxChunkZ, floorToChunk(town.maxZ()));
            if (townMinChunkX > townMaxChunkX || townMinChunkZ > townMaxChunkZ) continue;

            for (int chunkZ = townMinChunkZ; chunkZ <= townMaxChunkZ; chunkZ++) {
                double blockZ = chunkZ * CHUNK_SIZE + CHUNK_SIZE / 2.0;
                for (int chunkX = townMinChunkX; chunkX <= townMaxChunkX; chunkX++) {
                    int index = index(chunkX - minChunkX, chunkZ - minChunkZ, chunkWidth);
                    if (chunkTowns[index] != null) continue;
                    double blockX = chunkX * CHUNK_SIZE + CHUNK_SIZE / 2.0;
                    if (containsTown(town, blockX, blockZ)) {
                        chunkTowns[index] = town;
                    }
                }
            }
        }

        return chunkTowns;
    }

    private static VisibleRenderData cachedVisibleRenderData(List<TownData> towns, int minChunkX, int minChunkZ,
                                                             int chunkWidth, int chunkHeight) {
        if (towns == cachedTownSource
                && minChunkX == cachedMinChunkX
                && minChunkZ == cachedMinChunkZ
                && chunkWidth == cachedChunkWidth
                && chunkHeight == cachedChunkHeight) {
            return cachedRenderData;
        }

        // One chunk of margin around the window: without it a town that continues past the edge looks like
        // it ends there, and a border was drawn along the window boundary — a line that moved with the
        // window as you walked rather than sitting on the real claim edge.
        int padMinChunkX = minChunkX - EDGE_MARGIN_CHUNKS;
        int padMinChunkZ = minChunkZ - EDGE_MARGIN_CHUNKS;
        int padWidth = chunkWidth + 2 * EDGE_MARGIN_CHUNKS;
        int padHeight = chunkHeight + 2 * EDGE_MARGIN_CHUNKS;
        TownData[] chunkTowns = buildVisibleChunkMask(towns, padMinChunkX, padMinChunkZ, padWidth, padHeight);
        // No hole-closing pass. It used to fill unclaimed chunks by looking at their neighbours, which
        // meant the minimap showed claims that don't exist -- corridors, notches, and any gap a town
        // happened to surround. The world map never did this, which is why the two disagreed. The chunk
        // mask now reflects the claim data exactly: if a chunk isn't claimed, it isn't drawn as claimed.
        VisibleRenderData renderData = buildRenderData(chunkTowns, padMinChunkX, padMinChunkZ,
                padWidth, padHeight, EDGE_MARGIN_CHUNKS);
        cachedTownSource = towns;
        cachedMinChunkX = minChunkX;
        cachedMinChunkZ = minChunkZ;
        cachedChunkWidth = chunkWidth;
        cachedChunkHeight = chunkHeight;
        cachedRenderData = renderData;
        return cachedRenderData;
    }

    private static VisibleRenderData buildRenderData(TownData[] chunkTowns, int minChunkX, int minChunkZ,
                                                     int chunkWidth, int chunkHeight, int margin) {
        ArrayList<ChunkFill> fillSpans = new ArrayList<>();
        ArrayList<ChunkEdge> edges = new ArrayList<>();
        Map<String, LabelAnchor> anchors = new LinkedHashMap<>();

        for (int z = margin; z < chunkHeight - margin; z++) {
            int blockZ = (minChunkZ + z) * CHUNK_SIZE;
            double labelZ = blockZ + CHUNK_SIZE / 2.0;
            TownData runTown = null;
            int runStartX = margin;
            for (int x = margin; x < chunkWidth - margin; x++) {
                TownData town = chunkTowns[index(x, z, chunkWidth)];
                int blockX = (minChunkX + x) * CHUNK_SIZE;

                if (!sameTownNullable(runTown, town)) {
                    if (runTown != null) {
                        fillSpans.add(new ChunkFill((minChunkX + runStartX) * CHUNK_SIZE, blockZ,
                                (x - runStartX) * CHUNK_SIZE, runTown));
                    }
                    runTown = town;
                    runStartX = x;
                }

                if (town == null) continue;
                anchors.computeIfAbsent(town.name(), LabelAnchor::new)
                        .add(blockX + CHUNK_SIZE / 2.0, labelZ);

                // All four sides or none: a partial set draws a box with a missing wall.
                if (edges.size() + 4 <= MAX_CHUNK_EDGES_PER_FRAME) {
                    if (!sameTown(town, getTown(chunkTowns, x, z - 1, chunkWidth, chunkHeight))) {
                        edges.add(new ChunkEdge(blockX, blockZ, blockX + CHUNK_SIZE, blockZ, town));
                    }
                    if (!sameTown(town, getTown(chunkTowns, x + 1, z, chunkWidth, chunkHeight))) {
                        edges.add(new ChunkEdge(blockX + CHUNK_SIZE, blockZ, blockX + CHUNK_SIZE, blockZ + CHUNK_SIZE, town));
                    }
                    if (!sameTown(town, getTown(chunkTowns, x, z + 1, chunkWidth, chunkHeight))) {
                        edges.add(new ChunkEdge(blockX, blockZ + CHUNK_SIZE, blockX + CHUNK_SIZE, blockZ + CHUNK_SIZE, town));
                    }
                    if (!sameTown(town, getTown(chunkTowns, x - 1, z, chunkWidth, chunkHeight))) {
                        edges.add(new ChunkEdge(blockX, blockZ, blockX, blockZ + CHUNK_SIZE, town));
                    }
                }
            }
            if (runTown != null) {
                fillSpans.add(new ChunkFill((minChunkX + runStartX) * CHUNK_SIZE, blockZ,
                        ((chunkWidth - margin) - runStartX) * CHUNK_SIZE, runTown));
            }
        }

        ArrayList<LabelAnchor> labelAnchors = new ArrayList<>(anchors.values());
        labelAnchors.sort(Comparator.comparingInt(LabelAnchor::count).reversed()
                .thenComparing(LabelAnchor::name, String.CASE_INSENSITIVE_ORDER));
        return new VisibleRenderData(chunkTowns, List.copyOf(fillSpans), List.of(),
                List.copyOf(edges), List.copyOf(labelAnchors));
    }

    private static void fillVisibleTownSpans(DrawContext ctx, List<ChunkFill> fillSpans, TownyMapConfig config) {
        for (ChunkFill cell : fillSpans) {
            TownData town = cell.town();
            boolean favorite = TownyMapMod.isFavorite(town.name());
            int fillColor = favorite ? FAVORITE_FILL : town.argbFillColor(config.fillAlpha);
            if ((fillColor >>> 24) == 0) continue;
            ctx.fill(cell.blockX(), cell.blockZ(),
                    cell.blockX() + cell.blockWidth(), cell.blockZ() + CHUNK_SIZE, fillColor);
        }
    }

    /**
     * Circular town fill, reusing the merged horizontal spans (one fill per run) the square
     * path uses. A span fully inside the world circle is one fill; a fully-outside span is
     * skipped; only a span straddling the rim is split into chunks and pixel-clipped. When the
     * frame budget is blown ({@code skipRim}), straddling chunks are dropped instead of clipped.
     */
    private static void fillVisibleTownSpansCircleClipped(DrawContext ctx, List<ChunkFill> fillSpans,
                                                          TownyMapConfig config, MinimapClip clip,
                                                          double centerX, double centerY,
                                                          double playerX, double playerZ,
                                                          double pixelsPerBlock, double sin, double cos,
                                                          boolean skipRim) {
        TownData cachedTown = null;
        int cachedColor = 0;
        for (ChunkFill span : fillSpans) {
            TownData town = span.town();
            if (town != cachedTown) {
                cachedTown = town;
                cachedColor = TownyMapMod.isFavorite(town.name())
                        ? FAVORITE_FILL : town.argbFillColor(config.fillAlpha);
            }
            int fillColor = cachedColor;
            if ((fillColor >>> 24) == 0) continue;
            int x1 = span.blockX();
            int z1 = span.blockZ();
            int x2 = x1 + span.blockWidth();
            int z2 = z1 + CHUNK_SIZE;
            if (clip.worldRectFullyInside(x1, z1, x2, z2, centerX, centerY, playerX, playerZ,
                    pixelsPerBlock, sin, cos)) {
                ctx.fill(x1, z1, x2, z2, fillColor);
            } else if (clip.worldRectIntersects(x1, z1, x2, z2, centerX, centerY, playerX, playerZ,
                    pixelsPerBlock, sin, cos)) {
                // Clip the whole row in one pass. This used to walk it chunk column by chunk column,
                // emitting a separate translucent quad every 16 blocks. fillRectCircleClipped already
                // trims row by row against the circle and merges rows of equal extent, so giving it the
                // full width yields the same shape with no internal column edges.
                fillRectCircleClipped(ctx, x1, z1, x2 - x1, z2 - z1, fillColor, clip,
                        centerX, centerY, playerX, playerZ, pixelsPerBlock, sin, cos);
            }
        }
    }

    /**
     * Outline colour for a town edge. Over the squaremap layer the town's own colour is used as-is:
     * those tiles are flat and usually darkened, so it reads fine. Over Xaero's terrain it competes
     * with block colours, so the colour is pushed to full opacity and full brightness.
     *
     * <p>Brightness is raised by scaling all three channels by the same factor until the strongest one
     * hits 255. That keeps the hue and the relative channel mix exactly as the nation picked them -- it
     * only stops a mid-tone colour sinking into the terrain. Clamping channels individually, or mixing
     * toward white, would both shift the hue instead.
     */
    private static int townEdgeColor(int argb, boolean overSquaremap) {
        if (overSquaremap) return argb;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int peak = Math.max(r, Math.max(g, b));
        if (peak > 0) {
            r = r * 255 / peak;
            g = g * 255 / peak;
            b = b * 255 / peak;
        }
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static void drawVisibleTownEdges(DrawContext ctx, List<ChunkEdge> edges, TownyMapConfig config) {
        for (ChunkEdge edge : edges) {
            boolean favorite = TownyMapMod.isFavorite(edge.town().name());
            int outlineColor = favorite ? FAVORITE_OUTLINE
                    : townEdgeColor(edge.town().argbColor(config.borderAlpha), config.squaremapOnMinimap());
            drawChunkEdge(ctx, edge.x1(), edge.z1(), edge.x2(), edge.z2(), outlineColor);
        }
    }

    private static void drawVisibleTownEdgesCircleClipped(DrawContext ctx, List<ChunkEdge> edges,
                                                          TownyMapConfig config, MinimapClip clip,
                                                          double centerX, double centerY,
                                                          double playerX, double playerZ,
                                                          double pixelsPerBlock, double sin, double cos,
                                                          boolean drawRimSegments) {
        // Conformal transform => the screen circle is a world circle around the player.
        double rw = clip.radius() / pixelsPerBlock;
        double rwSq = rw * rw;
        Matrix3x2fStack matrices = ctx.getMatrices();
        matrices.pushMatrix();
        try {
            matrices.translate((float) centerX, (float) centerY);
            matrices.rotate((float) Math.atan2(sin, cos));
            matrices.scale((float) pixelsPerBlock, (float) pixelsPerBlock);
            matrices.translate((float) -playerX, (float) -playerZ);
            TownData cachedTown = null;
            int cachedColor = 0;
            for (ChunkEdge edge : edges) {
                TownData town = edge.town();
                if (town != cachedTown) {
                    cachedTown = town;
                    cachedColor = TownyMapMod.isFavorite(town.name())
                            ? FAVORITE_OUTLINE : townEdgeColor(town.argbColor(config.borderAlpha),
                                    config.squaremapOnMinimap());
                }
                // Trim the axis-aligned edge to the world circle and draw it with the SAME
                // matrix-space primitive as the interior, so the rim looks identical (no
                // screen-space 1px lines, no per-segment matrix push/pop).
                drawEdgeClippedToCircle(ctx, edge.x1(), edge.z1(), edge.x2(), edge.z2(),
                        cachedColor, playerX, playerZ, rwSq);
            }
        } finally {
            matrices.popMatrix();
        }
    }

    /** Draws an axis-aligned chunk edge trimmed to the world circle (centre = player, r² = rwSq). */
    private static void drawEdgeClippedToCircle(DrawContext ctx, int x1, int z1, int x2, int z2,
                                                int color, double playerX, double playerZ, double rwSq) {
        if (z1 == z2) {
            double dz = z1 - playerZ;
            double chordSq = rwSq - dz * dz;
            if (chordSq <= 0.0) return;
            double chord = Math.sqrt(chordSq);
            double xa = Math.max(Math.min(x1, x2), playerX - chord);
            double xb = Math.min(Math.max(x1, x2), playerX + chord);
            if (xa < xb) drawChunkEdge(ctx, (int) Math.round(xa), z1, (int) Math.round(xb), z1, color);
        } else {
            double dx = x1 - playerX;
            double chordSq = rwSq - dx * dx;
            if (chordSq <= 0.0) return;
            double chord = Math.sqrt(chordSq);
            double za = Math.max(Math.min(z1, z2), playerZ - chord);
            double zb = Math.min(Math.max(z1, z2), playerZ + chord);
            if (za < zb) drawChunkEdge(ctx, x1, (int) Math.round(za), x1, (int) Math.round(zb), color);
        }
    }

    private static void drawOptimisticClaimChunks(DrawContext ctx) {
        for (OptimisticClaimChunk chunk : TownyMapMod.optimisticClaimChunks(TownyMapMod.playerWorldResolved())) {
            int blockX = chunk.blockX();
            int blockZ = chunk.blockZ();
            ctx.fill(blockX, blockZ, blockX + CHUNK_SIZE, blockZ + CHUNK_SIZE, chunk.fillColor());
            drawChunkEdge(ctx, blockX, blockZ, blockX + CHUNK_SIZE, blockZ, chunk.outlineColor());
            drawChunkEdge(ctx, blockX + CHUNK_SIZE, blockZ, blockX + CHUNK_SIZE, blockZ + CHUNK_SIZE, chunk.outlineColor());
            drawChunkEdge(ctx, blockX, blockZ + CHUNK_SIZE, blockX + CHUNK_SIZE, blockZ + CHUNK_SIZE, chunk.outlineColor());
            drawChunkEdge(ctx, blockX, blockZ, blockX, blockZ + CHUNK_SIZE, chunk.outlineColor());
        }
    }

    private static void drawOptimisticClaimChunks(DrawContext ctx, MinimapClip clip,
                                                  double centerX, double centerY,
                                                  double playerX, double playerZ,
                                                  double pixelsPerBlock, double sin, double cos) {
        for (OptimisticClaimChunk chunk : TownyMapMod.optimisticClaimChunks(TownyMapMod.playerWorldResolved())) {
            int blockX = chunk.blockX();
            int blockZ = chunk.blockZ();
            if (clip.worldRectFullyInside(blockX, blockZ, blockX + CHUNK_SIZE, blockZ + CHUNK_SIZE,
                    centerX, centerY, playerX, playerZ, pixelsPerBlock, sin, cos)) {
                ctx.getMatrices().pushMatrix();
                try {
                    ctx.getMatrices().translate((float) centerX, (float) centerY);
                    ctx.getMatrices().rotate((float) Math.atan2(sin, cos));
                    ctx.getMatrices().scale((float) pixelsPerBlock, (float) pixelsPerBlock);
                    ctx.getMatrices().translate((float) -playerX, (float) -playerZ);
                    ctx.fill(blockX, blockZ, blockX + CHUNK_SIZE, blockZ + CHUNK_SIZE, chunk.fillColor());
                } finally {
                    ctx.getMatrices().popMatrix();
                }
            } else if (clip.worldRectIntersects(blockX, blockZ, blockX + CHUNK_SIZE, blockZ + CHUNK_SIZE,
                    centerX, centerY, playerX, playerZ, pixelsPerBlock, sin, cos)) {
                ctx.getMatrices().pushMatrix();
                try {
                    ctx.getMatrices().translate((float) centerX, (float) centerY);
                    ctx.getMatrices().rotate((float) Math.atan2(sin, cos));
                    ctx.getMatrices().scale((float) pixelsPerBlock, (float) pixelsPerBlock);
                    ctx.getMatrices().translate((float) -playerX, (float) -playerZ);
                    fillRectCircleClipped(ctx, blockX, blockZ, CHUNK_SIZE, CHUNK_SIZE, chunk.fillColor(), clip,
                            centerX, centerY, playerX, playerZ, pixelsPerBlock, sin, cos);
                } finally {
                    ctx.getMatrices().popMatrix();
                }
            }
            drawWorldLineCircleClipped(ctx, blockX, blockZ, blockX + CHUNK_SIZE, blockZ, chunk.outlineColor(), clip,
                    centerX, centerY, playerX, playerZ, pixelsPerBlock, sin, cos);
            drawWorldLineCircleClipped(ctx, blockX + CHUNK_SIZE, blockZ,
                    blockX + CHUNK_SIZE, blockZ + CHUNK_SIZE, chunk.outlineColor(), clip,
                    centerX, centerY, playerX, playerZ, pixelsPerBlock, sin, cos);
            drawWorldLineCircleClipped(ctx, blockX, blockZ + CHUNK_SIZE,
                    blockX + CHUNK_SIZE, blockZ + CHUNK_SIZE, chunk.outlineColor(), clip,
                    centerX, centerY, playerX, playerZ, pixelsPerBlock, sin, cos);
            drawWorldLineCircleClipped(ctx, blockX, blockZ, blockX, blockZ + CHUNK_SIZE, chunk.outlineColor(), clip,
                    centerX, centerY, playerX, playerZ, pixelsPerBlock, sin, cos);
        }
    }

    private static void fillRectCircleClipped(DrawContext ctx, int blockX, int blockZ, int blockWidth, int blockHeight,
                                              int color,
                                              MinimapClip clip,
                                              double centerX, double centerY,
                                              double playerX, double playerZ,
                                              double pixelsPerBlock, double sin, double cos) {
        double rwSq = clip.radius() * clip.radius() / (pixelsPerBlock * pixelsPerBlock);
        int maxX = blockX + blockWidth;
        int maxZ = blockZ + blockHeight;
        int step = Math.max(1, (int) Math.floor(1.0 / Math.max(0.0001, pixelsPerBlock)));
        // Merge vertically adjacent bands that span the same columns into one quad. The banding only
        // exists to trim the fill to the circle, and away from the rim consecutive rows have identical
        // extents -- so a town's interior collapses to a handful of rects instead of one per row.
        // These fills are translucent, so they cannot be overlapped the way the opaque tiles are; fewer
        // bands is the only way to remove the shared edges.
        int runZ = 0, runZ2 = 0, runXa = 0, runXb = 0;
        boolean run = false;
        for (int z = blockZ; z < maxZ; z += step) {
            int z2 = Math.min(maxZ, z + step);
            double dz = Math.max(Math.abs(z - playerZ), Math.abs(z2 - playerZ));
            double chordSq = rwSq - dz * dz;
            double xa = 0, xb = 0;
            boolean visible = false;
            if (chordSq > 0.0) {
                double chord = Math.sqrt(chordSq);
                xa = Math.max(blockX, playerX - chord);
                xb = Math.min(maxX, playerX + chord);
                visible = xa < xb;
            }
            if (visible) {
                int ia = (int) Math.round(xa);
                int ib = (int) Math.round(xb);
                if (run && ia == runXa && ib == runXb && z == runZ2) {
                    runZ2 = z2;                       // same columns and touching: extend the run
                    continue;
                }
                if (run) ctx.fill(runXa, runZ, runXb, runZ2, color);
                runXa = ia;
                runXb = ib;
                runZ = z;
                runZ2 = z2;
                run = true;
            } else if (run) {
                ctx.fill(runXa, runZ, runXb, runZ2, color);
                run = false;
            }
        }
        if (run) ctx.fill(runXa, runZ, runXb, runZ2, color);
    }

    private static int clippedFillStep(double pixelsPerBlock, MinimapClip clip) {
        if (clip.radius() >= 220.0) return 8;
        if (clip.radius() >= 128.0) return 4;
        if (clip.radius() >= 72.0) return 2;
        if (pixelsPerBlock >= 0.18) return 1;
        if (pixelsPerBlock >= 0.08) return 2;
        return 4;
    }

    private static void drawWorldLineCircleClipped(DrawContext ctx,
                                                   double worldX1, double worldZ1,
                                                   double worldX2, double worldZ2,
                                                   int color,
                                                   MinimapClip clip,
                                                   double centerX, double centerY,
                                                   double playerX, double playerZ,
                                                   double pixelsPerBlock, double sin, double cos) {
        double x1 = screenX(worldX1, worldZ1, centerX, playerX, playerZ, pixelsPerBlock, sin, cos);
        double y1 = screenY(worldX1, worldZ1, centerY, playerX, playerZ, pixelsPerBlock, sin, cos);
        double x2 = screenX(worldX2, worldZ2, centerX, playerX, playerZ, pixelsPerBlock, sin, cos);
        double y2 = screenY(worldX2, worldZ2, centerY, playerX, playerZ, pixelsPerBlock, sin, cos);
        drawScreenLineCircleClipped(ctx, x1, y1, x2, y2, color, clip);
    }

    private static void drawScreenLineCircleClipped(DrawContext ctx,
                                                    double x1, double y1,
                                                    double x2, double y2,
                                                    int color,
                                                    MinimapClip clip) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double lenSq = dx * dx + dy * dy;
        if (lenSq < 0.0001) {
            if (clip.containsPoint(x1, y1, 0.0)) {
                int x = (int) Math.round(x1);
                int y = (int) Math.round(y1);
                ctx.fill(x, y, x + 1, y + 1, color);
            }
            return;
        }

        double fx = x1 - clip.centerX();
        double fy = y1 - clip.centerY();
        double a = lenSq;
        double b = 2.0 * (fx * dx + fy * dy);
        double c = fx * fx + fy * fy - clip.radiusSq();
        double discriminant = b * b - 4.0 * a * c;
        boolean startInside = clip.containsPoint(x1, y1, 0.5);
        boolean endInside = clip.containsPoint(x2, y2, 0.5);
        if (startInside && endInside) {
            drawScreenLine(ctx, x1, y1, x2, y2, color);
            return;
        }
        if (discriminant < 0.0) return;

        double sqrt = Math.sqrt(discriminant);
        double t1 = (-b - sqrt) / (2.0 * a);
        double t2 = (-b + sqrt) / (2.0 * a);
        if (t1 > t2) {
            double tmp = t1;
            t1 = t2;
            t2 = tmp;
        }

        double start = startInside ? 0.0 : t1;
        double end = endInside ? 1.0 : t2;
        start = Math.max(0.0, start);
        end = Math.min(1.0, end);
        if (end - start < 0.0001) return;

        double mid = (start + end) * 0.5;
        double midX = x1 + dx * mid;
        double midY = y1 + dy * mid;
        if (!clip.containsPoint(midX, midY, 0.5)) return;
        drawScreenLine(ctx, x1 + dx * start, y1 + dy * start,
                x1 + dx * end, y1 + dy * end, color);
    }

    private static void drawScreenLine(DrawContext ctx,
                                       double x1, double y1,
                                       double x2, double y2,
                                       int color) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double length = Math.hypot(dx, dy);
        if (length < 0.5) {
            int x = (int) Math.round(x1);
            int y = (int) Math.round(y1);
            ctx.fill(x, y, x + 1, y + 1, color);
            return;
        }
        Matrix3x2fStack matrices = ctx.getMatrices();
        matrices.pushMatrix();
        try {
            matrices.translate((float) x1, (float) y1);
            matrices.rotate((float) Math.atan2(dy, dx));
            ctx.fill(0, 0, Math.max(1, (int) Math.ceil(length)), 1, color);
        } finally {
            matrices.popMatrix();
        }
    }

    private static double screenX(double worldX, double worldZ,
                                  double centerX,
                                  double playerX, double playerZ,
                                  double pixelsPerBlock, double sin, double cos) {
        double dx = worldX - playerX;
        double dz = worldZ - playerZ;
        return centerX + (dx * cos - dz * sin) * pixelsPerBlock;
    }

    private static double screenY(double worldX, double worldZ,
                                  double centerY,
                                  double playerX, double playerZ,
                                  double pixelsPerBlock, double sin, double cos) {
        double dx = worldX - playerX;
        double dz = worldZ - playerZ;
        return centerY + (dx * sin + dz * cos) * pixelsPerBlock;
    }

    private static void drawChunkEdge(DrawContext ctx, int x1, int z1, int x2, int z2, int color) {
        int thickness = 1;
        if (z1 == z2) {
            ctx.fill(Math.min(x1, x2), z1, Math.max(x1, x2), z1 + thickness, color);
        } else {
            ctx.fill(x1, Math.min(z1, z2), x1 + thickness, Math.max(z1, z2), color);
        }
    }

    private static TownData getTown(TownData[] chunkTowns, int x, int z, int width, int height) {
        if (x < 0 || z < 0 || x >= width || z >= height) return null;
        return chunkTowns[index(x, z, width)];
    }

    private static int index(int x, int z, int width) {
        return z * width + x;
    }

    private static int floorToChunk(double blockCoord) {
        return Math.floorDiv((int) Math.floor(blockCoord), CHUNK_SIZE);
    }

    private static int alignDown(int value, int step) {
        return Math.floorDiv(value, step) * step;
    }

    private static int alignUp(int value, int step) {
        return Math.floorDiv(value + step - 1, step) * step;
    }

    private static void recordMinimapFrameCost(long renderStartNs) {
        long elapsedNs = System.nanoTime() - renderStartNs;
        if (elapsedNs <= MINIMAP_FRAME_BUDGET_NS) return;
        minimapPerformanceShedUntilMs = System.currentTimeMillis() + MINIMAP_PERFORMANCE_SHED_MS;
    }

    private static boolean sameTown(TownData a, TownData b) {
        if (a == null || b == null) return false;
        return a == b || a.name().equals(b.name());
    }

    private static boolean sameTownNullable(TownData a, TownData b) {
        if (a == null || b == null) return a == b;
        return a == b || a.name().equals(b.name());
    }

    private static boolean containsTown(TownData town, double x, double z) {
        boolean inside = false;
        for (int[][] ring : town.polygonRings()) {
            if (containsRing(ring, x, z)) inside = !inside;
        }
        return inside;
    }

    private static boolean containsRing(int[][] ring, double x, double z) {
        boolean inside = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            int[] pi = ring[i];
            int[] pj = ring[j];
            if (pi.length < 2 || pj.length < 2) continue;
            double zi = pi[1];
            double zj = pj[1];
            if ((zi > z) == (zj > z)) continue;
            double xi = pi[0];
            double xj = pj[0];
            if (x < (xj - xi) * (z - zi) / (zj - zi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static void renderTownNames(DrawContext ctx, MinecraftClient client,
                                        List<LabelAnchor> anchors,
                                        int mapX, int mapY, int size,
                                        double playerX, double playerZ, double pixelsPerBlock,
                                        double sin, double cos, int mode,
                                        MinimapClip clip) {
        double centerX = mapX + size / 2.0;
        double centerY = mapY + size / 2.0;

        List<Label> labels = new ArrayList<>();
        for (LabelAnchor anchor : anchors) {
            if (!shouldShowTownName(anchor, mode, playerX, playerZ)) continue;
            double dx = anchor.centerX() - playerX;
            double dz = anchor.centerZ() - playerZ;
            int x = (int) Math.round(centerX + (dx * cos - dz * sin) * pixelsPerBlock);
            int y = (int) Math.round(centerY + (dx * sin + dz * cos) * pixelsPerBlock);
            if (x < clip.left() || x > clip.right() || y < clip.top() || y > clip.bottom()) continue;
            int textWidth = client.textRenderer.getWidth(anchor.name());
            if (textWidth > size * 0.55) continue;
            if (!clip.containsText(x, y, textWidth, client.textRenderer.fontHeight)) continue;
            Label label = new Label(anchor.name(), x, y, textWidth);
            if (overlaps(labels, label)) continue;
            labels.add(label);
            if (labels.size() >= MAX_MINIMAP_LABELS) break;
        }

        ctx.enableScissor(clip.left(), clip.top(), clip.right() + 1, clip.bottom() + 1);
        try {
            for (Label label : labels) {
                int x = label.x - label.width / 2;
                int y = label.y - 4;
                ctx.drawText(client.textRenderer, label.text, x + 1, y + 1, 0xAA000000, false);
                ctx.drawText(client.textRenderer, label.text, x, y, 0xFFFFFFFF, false);
            }
        } finally {
            ctx.disableScissor();
        }
    }

    private static boolean shouldShowTownName(LabelAnchor anchor, int mode, double playerX, double playerZ) {
        if (mode == 3) return anchor.count() >= 1;
        if (mode == 2) return anchor.count() >= 8;
        if (mode == 1) {
            double dx = anchor.centerX() - playerX;
            double dz = anchor.centerZ() - playerZ;
            return anchor.count() >= 2 && dx * dx + dz * dz <= 192.0 * 192.0;
        }
        return false;
    }

    private static boolean overlaps(List<Label> labels, Label candidate) {
        int candidateLeft = candidate.x - candidate.width / 2 - 3;
        int candidateRight = candidate.x + candidate.width / 2 + 3;
        int candidateTop = candidate.y - 7;
        int candidateBottom = candidate.y + 7;
        for (Label label : labels) {
            int left = label.x - label.width / 2 - 3;
            int right = label.x + label.width / 2 + 3;
            int top = label.y - 7;
            int bottom = label.y + 7;
            if (candidateLeft <= right && candidateRight >= left
                    && candidateTop <= bottom && candidateBottom >= top) {
                return true;
            }
        }
        return false;
    }

    /**
     * Draws a high-contrast "you are here" indicator at the minimap centre.
     * Called from the mixin redirect AFTER {@code renderOutsidePip} so it is
     * guaranteed to be drawn on top of both Xaero's tiles and our squaremap tiles.
     * Only shown when the squaremap background is active.
     */
    // Xaero's worldmap arrow sprite — same texture, same sprite, now rendered on the minimap.
    private static final Identifier XAERO_GUI = Identifier.of("xaeroworldmap", "gui/gui.png");

    /**
     * Draws Xaero's own arrow sprite at the minimap centre, matching the player's yaw.
     * Uses DrawContext so it composites on top of the squaremap tile batch at frame-end.
     */
    /**
     * Redraws Xaero's player arrow on top of our overlay.
     * <p>
     * Xaero calls drawArrow inside renderMinimap, which runs <em>before</em> the renderOutsidePip call
     * our overlay redirects — so anything we paint lands over its arrow. This used to be gated on the
     * squaremap background as well, on the assumption that only that opaque layer could hide it; with
     * the background off the arrow disappeared instead, so the overlay covers it either way.
     * <p>
     * Drawn whenever our overlay is active, and only when Xaero is set to draw an arrow at all, so a
     * player who turned it off doesn't get one back. Because this is a faithful copy of Xaero's own
     * sprite, colour and scale, landing on top of a visible arrow is indistinguishable from just the
     * one arrow.
     */
    public static void renderPlayerIndicator(DrawContext ctx, MinimapSession session,
                                             int mapX, int mapY, int size) {
        TownyMapConfig config = TownyMapMod.getConfig();
        if (config == null || !config.minimapExtensionsEnabled) return;
        if (session.getProcessor().isCaveModeDisplayed()) return;
        // Which background the user wants the indicator over. Xaero draws its own arrow before our
        // overlay, so with the EMC layer on it's hidden and ours is the only one; with the layer off
        // ours lands on top of Xaero's, matching its sprite and scale, so they read as one arrow.
        boolean emcLayer = config.squaremapOnMinimap();
        switch (config.minimapIndicatorMode) {
            case 0 -> { return; }
            case 1 -> { if (!emcLayer) return; }
            case 2 -> { if (emcLayer) return; }
            default -> { }
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        float arrowYaw = isMinimapNorthLocked(session) ? client.player.getYaw() : 180.0F;
        float yawRad = (float) Math.toRadians(arrowYaw);

        float cx = mapX + size / 2.0f;
        float cy = mapY + size / 2.0f;

        // Replicate Xaero's minimap arrow sizing.
        // Xaero: outer push applies 0.5f scale; drawArrow applies 0.5 × ARROW_SCALE
        // inside that, giving 0.25 × ARROW_SCALE effective in their coord space.
        // Their coord space spans minimapSize/2 units per radius (the outer 0.5 maps
        // 2× internal units → actual pixels), so:
        //   arrowScale = 0.5 × ARROW_SCALE_config × (size_px / minimapSize)
        // With the default config value of 1.0 this simplifies to 0.5 × size/minimapSize.
        int minimapSize = size * 2;  // safe fallback
        try { minimapSize = Math.max(1, session.getProcessor().getMinimapSize()); }
        catch (Exception ignored) {}
        // Match Xaero's own arrow: it scales by ARROW_SCALE * 0.5 inside a coordinate space that maps
        // minimapSize units onto size pixels. Leaving the configured scale out (it used to be hardcoded
        // to the 1.0 case) drew our replica smaller than Xaero's by exactly that factor -- at the default
        // minimap size that's a ~7px arrow, easy to miss entirely against terrain.
        float arrowScale = Math.max(0.2f,
                Math.min(3.0f, (float) (0.5 * xaeroArrowScale()) * size / minimapSize));
        float shadowOffset = 2f * arrowScale;

        Matrix3x2fStack m = ctx.getMatrices();

        // Shadow
        m.pushMatrix();
        m.translate(cx, cy + shadowOffset);
        m.rotate(yawRad);
        m.scale(arrowScale, arrowScale);
        drawXaeroArrowSprite(ctx, 0xE5000000);
        m.popMatrix();

        // Red arrow — same colour Xaero uses (r=1, g=0.08, b=0.08, a=1)
        m.pushMatrix();
        m.translate(cx, cy);
        m.rotate(yawRad);
        m.scale(arrowScale, arrowScale);
        drawXaeroArrowSprite(ctx, 0xFFFF1414);
        m.popMatrix();

        // Flush our own draw. GUI blits go into a deferred batch, and the only flush happening after
        // this point lived inside renderCompassDirections / the squaremap background - both gated on
        // the EMC layer. With that layer off the arrow was queued and then silently dropped: the draw
        // ran, no exception, nothing on screen. Flushing here makes the indicator independent of
        // whatever else is enabled.
        ctx.drawDeferredElements();
    }

    private static void drawXaeroArrowSprite(DrawContext ctx, int color) {
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, XAERO_GUI,
                -13, -5,   // centers the 26×28 sprite at the origin
                0f, 0f,    // UV start in the 256×256 sheet (sprite is at top-left)
                26, 28,
                256, 256,
                color);
    }

    public static void renderCompassDirections(DrawContext ctx, MinimapSession session,
                                               int mapX, int mapY, int size) {
        TownyMapConfig config = TownyMapMod.getConfig();
        if (config == null || !config.minimapExtensionsEnabled || !config.squaremapOnMinimap()) return;
        if (session.getProcessor().isCaveModeDisplayed()) return;
        if (size <= 24) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        double angle = minimapAngle(session, client);
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        double centerX = mapX + size / 2.0;
        double centerY = mapY + size / 2.0;
        boolean circular = isCircularMinimap(session);
        // Match the user's chosen Xaero minimap frame colour (the ring around the minimap).
        int accent = 0xFF000000 | (TownyMapMod.minimapFrameColor() & 0x00FFFFFF);

        drawCompassLetterOnBorder(ctx, client, "N", centerX, centerY, sin, -cos, size, circular, accent);
        drawCompassLetterOnBorder(ctx, client, "E", centerX, centerY, cos, sin, size, circular, accent);
        drawCompassLetterOnBorder(ctx, client, "S", centerX, centerY, -sin, cos, size, circular, accent);
        drawCompassLetterOnBorder(ctx, client, "W", centerX, centerY, -cos, -sin, size, circular, accent);
        ctx.drawDeferredElements();
    }

    public static void renderCircularOutline(DrawContext ctx, int x, int y, int size,
                                             int color, int shadowColor, int thickness) {
        if (size <= 8 || thickness <= 0) return;
        double centerX = x + size / 2.0;
        double centerY = y + size / 2.0;
        double radius = Math.max(1.0, size / 2.0 - 0.75);
        // ~1 segment per 3px of circumference so the ring reads as a true circle, not a polygon.
        // Drawn once per frame, so a high count is cheap. (Old cap of 56 made the facets visible.)
        int segments = Math.max(64, Math.min(360, (int) Math.round(radius * 2.0)));
        if ((shadowColor >>> 24) != 0) {
            drawCircleOutline(ctx, centerX, centerY, radius + 1.0, segments, shadowColor);
        }
        for (int i = 0; i < thickness; i++) {
            drawCircleOutline(ctx, centerX, centerY, Math.max(1.0, radius - i), segments, color);
        }
    }

    private static void drawCircleOutline(DrawContext ctx, double centerX, double centerY,
                                          double radius, int segments, int color) {
        double previousX = centerX + radius;
        double previousY = centerY;
        for (int i = 1; i <= segments; i++) {
            double angle = Math.PI * 2.0 * i / segments;
            double x = centerX + Math.cos(angle) * radius;
            double y = centerY + Math.sin(angle) * radius;
            drawScreenLine(ctx, previousX, previousY, x, y, color);
            previousX = x;
            previousY = y;
        }
    }

    private static void drawCompassLetterOnBorder(DrawContext ctx, MinecraftClient client, String letter,
                                                  double centerX, double centerY,
                                                  double dirX, double dirY, int size,
                                                  boolean circular, int accent) {
        double length = Math.hypot(dirX, dirY);
        if (length < 0.0001) return;
        double edgeDistance = size / 2.0 + 1.0;
        double scale = circular ? edgeDistance / length
                : edgeDistance / Math.max(Math.abs(dirX), Math.abs(dirY));
        drawCompassLetter(ctx, client, letter, centerX + dirX * scale, centerY + dirY * scale, accent);
    }

    private static void drawCompassLetter(DrawContext ctx, MinecraftClient client, String letter,
                                          double centerX, double centerY, int accent) {
        int width = client.textRenderer.getWidth(letter);
        int height = client.textRenderer.fontHeight;
        int x = (int) Math.round(centerX - width / 2.0);
        int y = (int) Math.round(centerY - height / 2.0);
        // Coloured drop-shadow in the chosen frame colour, white letter on top for legibility.
        ctx.drawText(client.textRenderer, letter, x + 1, y + 1, accent, false);
        ctx.drawText(client.textRenderer, letter, x, y, 0xFFFFFFFF, false);
    }

    public static void renderSquaremapBackground(DrawContext ctx, MinimapSession session,
                                                 int mapX, int mapY, int size) {
        TownyMapConfig config = TownyMapMod.getConfig();
        if (config == null || !config.minimapExtensionsEnabled || !config.squaremapOnMinimap()) return;
        if (session.getProcessor().isCaveModeDisplayed()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || size <= 12) return;

        // This is the fallback path used when the town overlay above didn't run, so it has to repeat
        // that method's dimension handling. It previously drew with raw X/Z, which put the squaremap
        // tiles 8x off from the town borders in the Nether and kept drawing them in "Hidden" mode.
        double dimScale = 1.0;
        if (client.world.getRegistryKey() != World.OVERWORLD) {
            if (config.netherMode == 2 && client.world.getRegistryKey() == World.NETHER) {
                dimScale = TownyMapMod.dimensionCoordinateScale();
            } else if (!TownyMapMod.WORLD_OVERWORLD.equals(TownyMapMod.playerWorldResolved())) {
                // A world squaremap maps -- the Moon. Claims there are at real 1:1 coordinates, so draw
                // exactly as on Earth. Deliberately NOT tied to the world the map is showing: the
                // minimap draws where the player stands, whatever the world map is pinned to.
                dimScale = 1.0;
            } else if (TownyMapMod.isOnEarthMcServer()) {
                return;
            }
        }

        // Same units as the town overlay above: pixels per overworld block, not divided by dimScale,
        // so the tiles sit at the same scale as the borders drawn over them.
        double blocksAcross = minimapBlocksAcross(session);
        double pixelsPerBlock = size / blocksAcross;
        if (pixelsPerBlock <= 0) return;

        int left = mapX;
        int top = mapY;
        int right = mapX + size - 1;
        int bottom = mapY + size - 1;
        MinimapClip clip = MinimapClip.of(left + MINIMAP_CLIP_INSET,
                top + MINIMAP_CLIP_INSET,
                right - MINIMAP_CLIP_INSET,
                bottom - MINIMAP_CLIP_INSET,
                isCircularMinimap(session));
        renderSquaremapBackground(ctx, mapX, mapY, size,
                player.getX() * dimScale, player.getZ() * dimScale,
                pixelsPerBlock, minimapAngle(session, client), clip);
    }

    private static void renderSquaremapBackground(DrawContext ctx,
                                                  int mapX, int mapY, int size,
                                                  double playerX, double playerZ,
                                                  double pixelsPerBlock, double angle,
                                                  MinimapClip clip) {
        if (clip.left() > clip.right() || clip.top() > clip.bottom()) return;
        renderSquaremapBackgroundRect(ctx, mapX, mapY, size, playerX, playerZ,
                pixelsPerBlock, angle, clip);
    }

    private static void renderSquaremapBackgroundRect(DrawContext ctx,
                                                      int mapX, int mapY, int size,
                                                      double playerX, double playerZ,
                                                      double pixelsPerBlock, double angle,
                                                      MinimapClip clip) {
        if (clip.left() > clip.right() || clip.top() > clip.bottom()) return;
        ctx.enableScissor(clip.left(), clip.top(), clip.right() + 1, clip.bottom() + 1);
        Matrix3x2fStack matrices = ctx.getMatrices();
        matrices.pushMatrix();
        try {
            float center = size / 2.0F;
            matrices.translate(mapX + center, mapY + center);
            matrices.rotate((float) angle);
            matrices.translate(-center, -center);
            TownyMapMod.renderSquaremapMinimapViewport(ctx, playerX, playerZ, pixelsPerBlock,
                    size, size, clip.circular(), clip.radius());
        } finally {
            matrices.popMatrix();
            int darken = TownyMapMod.getConfig() != null ? TownyMapMod.getConfig().squaremapDarken : 0;
            if (darken > 0) fillDark(ctx, clip, (darken * 0x38) << 24);   // "Darken Map" 1-3 → alpha 56/112/168
            ctx.disableScissor();
        }
    }

    /** Fills the minimap squaremap area with a translucent overlay, matching the minimap's shape (a circle
     *  for round minimaps so the square corners aren't darkened). The active scissor clamps it to bounds. */
    private static void fillDark(DrawContext ctx, MinimapClip clip, int argb) {
        if (clip.circular()) {
            double cx = (clip.left() + clip.right() + 1) / 2.0;
            double cy = (clip.top() + clip.bottom() + 1) / 2.0;
            double r = clip.radius();
            int top = (int) Math.floor(cy - r), bot = (int) Math.ceil(cy + r);
            for (int y = top; y < bot; y++) {
                double dy = y + 0.5 - cy;
                double h2 = r * r - dy * dy;
                if (h2 <= 0) continue;
                double hw = Math.sqrt(h2);
                ctx.fill((int) Math.round(cx - hw), y, (int) Math.round(cx + hw), y + 1, argb);
            }
        } else {
            ctx.fill(clip.left(), clip.top(), clip.right() + 1, clip.bottom() + 1, argb);
        }
    }

    private static void renderPlayerDots(DrawContext ctx, List<PlayerMarker> players, String selfName,
                                         int mapX, int mapY, int size,
                                         double playerX, double playerZ, double pixelsPerBlock,
                                         double sin, double cos,
                                         MinimapClip clip) {
        double centerX = mapX + size / 2.0;
        double centerY = mapY + size / 2.0;
        int radius = 1;
        TownyMapConfig cfg = TownyMapMod.getConfig();
        boolean heads = cfg != null && (cfg.playerHeadMode & 2) != 0;   // bit 1 = minimap
        List<TownyMapMod.GhostMarker> ghosts =
                (cfg != null && cfg.playerLastSeen)
                        ? TownyMapMod.lastSeenGhosts(TownyMapMod.playerWorldResolved()) : java.util.List.of();
        if (players.isEmpty() && ghosts.isEmpty()) return;

        ctx.enableScissor(clip.left(), clip.top(), clip.right() + 1, clip.bottom() + 1);
        try {
            // Last-seen ghosts first, so live players draw over them. Same last-seen positions as the world
            // map: a player off the map feed but still online (Nether/hidden) shows red at where they were.
            for (TownyMapMod.GhostMarker g : ghosts) {
                if (g.name().equalsIgnoreCase(selfName)) continue;
                double dx = g.x() - playerX, dz = g.z() - playerZ;
                int gx = (int) Math.round(centerX + (dx * cos - dz * sin) * pixelsPerBlock);
                int gy = (int) Math.round(centerY + (dx * sin + dz * cos) * pixelsPerBlock);
                if (gx < clip.left() + radius || gx > clip.right() - radius
                        || gy < clip.top() + radius || gy > clip.bottom() - radius) continue;
                if (!clip.containsPoint(gx, gy, radius + 0.5)) continue;
                int red = (g.alpha() << 24) | 0xE23B3B;
                if (heads) {
                    net.townymap.render.PlayerHeadRenderer.draw(ctx, g.uuid(), g.name(), gx, gy, 8, red);
                } else {
                    ctx.fill(gx - radius, gy - radius, gx + radius + 1, gy + radius + 1, red);
                }
            }

            for (PlayerMarker marker : players) {
                if (marker.name() == null || marker.name().equalsIgnoreCase(selfName)) continue;
                double dx = marker.x() - playerX;
                double dz = marker.z() - playerZ;
                int x = (int) Math.round(centerX + (dx * cos - dz * sin) * pixelsPerBlock);
                int y = (int) Math.round(centerY + (dx * sin + dz * cos) * pixelsPerBlock);
                if (x < clip.left() + radius || x > clip.right() - radius
                        || y < clip.top() + radius || y > clip.bottom() - radius) continue;
                if (!clip.containsPoint(x, y, radius + 0.5)) continue;

                int color = TownyMapMod.minimapPlayerDotColor(marker.name());
                if ((color >>> 24) == 0) continue;
                if (heads) {
                    net.townymap.render.PlayerHeadRenderer.draw(ctx, marker.uuid(), marker.name(),
                            x, y, 8, color);
                } else {
                    ctx.fill(x - radius, y - radius, x + radius + 1, y + radius + 1, color);
                }
            }
        } finally {
            ctx.disableScissor();
        }
    }

    private static double minimapAngle(MinimapSession session, MinecraftClient client) {
        if (isMinimapNorthLocked(session)) {
            return 0.0;
        }
        return Math.toRadians(180.0 - client.gameRenderer.getCamera().getYaw());
    }

    /**
     * Mirrors Xaero's own call, which is {@code getEffectiveNorthLocked(minimapSize / 2, SHAPE)}.
     * The shape matters: Xaero force-locks north on a <em>square</em> minimap larger than 180,
     * but leaves a circular one free to rotate. Passing a hard-coded 0 (square) here made us
     * treat a large circular minimap as north-locked, so the overlay stayed north-up while
     * Xaero's terrain rotated under it.
     */
    private static boolean isMinimapNorthLocked(MinimapSession session) {
        try {
            return MinimapConfigClientUtils.getEffectiveNorthLocked(
                    session.getProcessor().getMinimapSize() / 2, xaeroMinimapShape(session));
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    public static boolean isCircularMinimap(MinimapSession session) {
        return xaeroMinimapShape(session) == 1;
    }

    private static int xaeroMinimapShape(MinimapSession session) {
        long now = System.currentTimeMillis();
        if (now - lastMinimapShapeReadAtMs < MINIMAP_SHAPE_CACHE_MS) return cachedMinimapShape;
        lastMinimapShapeReadAtMs = now;

        try {
            Class<?> hudModClass = Class.forName("xaero.common.HudMod");
            Object hudMod = hudModClass.getField("INSTANCE").get(null);
            Object hudConfigs = hudModClass.getMethod("getHudConfigs").invoke(hudMod);
            Object manager = hudConfigs.getClass().getMethod("getClientConfigManager").invoke(hudConfigs);
            Class<?> optionsClass = Class.forName("xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions");
            Object shapeOption = optionsClass.getField("SHAPE").get(null);
            Object value = getXaeroProfileOption(manager, shapeOption);
            if (value instanceof Number number) {
                cachedMinimapShape = number.intValue();
                return cachedMinimapShape;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }

        cachedMinimapShape = readMinimapShapeFromConfig(cachedMinimapShape);
        return cachedMinimapShape;
    }

    /**
     * Xaero's configured arrow scale (its "Arrow Scale" setting), cached like the shape read since it
     * runs per frame. Defaults to 1.0 if it can't be read.
     *
     * <p>This replaced an earlier check for a plain {@code ARROW} on/off option, which does not exist:
     * the minimap options class only has ARROW_SCALE / ARROW_COLOR / ARROW_OPACITY, so that lookup threw
     * every frame and always fell through to its default. The scale is the value that actually matters —
     * ignoring it drew our replica smaller than Xaero's own arrow by exactly that factor.
     */
    private static double xaeroArrowScale() {
        long now = System.currentTimeMillis();
        if (now - lastArrowOptionReadAtMs < MINIMAP_SHAPE_CACHE_MS) return cachedArrowScale;
        lastArrowOptionReadAtMs = now;
        try {
            Class<?> hudModClass = Class.forName("xaero.common.HudMod");
            Object hudMod = hudModClass.getField("INSTANCE").get(null);
            Object hudConfigs = hudModClass.getMethod("getHudConfigs").invoke(hudMod);
            Object manager = hudConfigs.getClass().getMethod("getClientConfigManager").invoke(hudConfigs);
            Class<?> optionsClass = Class.forName(
                    "xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions");
            Object scaleOption = optionsClass.getField("ARROW_SCALE").get(null);
            Object value = getXaeroProfileOption(manager, scaleOption);
            if (value instanceof Number n && n.doubleValue() > 0) {
                cachedArrowScale = n.doubleValue();
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Leave the cached value alone; it starts at 1.0.
        }
        return cachedArrowScale;
    }

    private static Object getXaeroProfileOption(Object manager, Object option)
            throws ReflectiveOperationException {
        for (var method : manager.getClass().getMethods()) {
            if (!"getEffective".equals(method.getName()) || method.getParameterCount() != 1) continue;
            return method.invoke(manager, option);
        }
        return null;
    }

    private static int readMinimapShapeFromConfig(int fallback) {
        Path path = FabricLoader.getInstance().getConfigDir()
                .resolve("xaero/minimap/profiles/default.cfg");
        if (!Files.exists(path)) return fallback;
        try {
            for (String line : Files.readAllLines(path)) {
                int equals = line.indexOf('=');
                if (equals < 0) continue;
                String key = line.substring(0, equals).trim();
                if (!"minimap_shape".equals(key)) continue;
                return parseInt(line.substring(equals + 1), fallback);
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static void syncXaeroChunkGrid(MinimapSession session, TownyMapConfig config) {
        int desired = switch (config.minimapChunkGridMode) {
            case 1 -> 0;
            case 2 -> session.getProcessor().isEnlargedMap() ? 0 : -1;
            default -> -1;
        };
        if (desired == lastSyncedXaeroChunkGrid) return;
        long now = System.currentTimeMillis();
        if (lastSyncedXaeroChunkGrid == Integer.MIN_VALUE
                && now - lastXaeroChunkGridSyncAttemptMs < 5_000L) {
            return;
        }
        lastXaeroChunkGridSyncAttemptMs = now;
        try {
            Class<?> hudModClass = Class.forName("xaero.common.HudMod");
            Object hudMod = hudModClass.getField("INSTANCE").get(null);
            Object hudConfigs = hudModClass.getMethod("getHudConfigs").invoke(hudMod);
            Object manager = hudConfigs.getClass().getMethod("getClientConfigManager").invoke(hudConfigs);
            Object profile = manager.getClass().getMethod("getCurrentProfile").invoke(manager);
            Class<?> optionsClass = Class.forName("xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions");
            Object chunkGridOption = optionsClass.getField("CHUNK_GRID").get(null);
            Object lineWidthOption = optionsClass.getField("CHUNK_GRID_LINE_WIDTH").get(null);
            setXaeroProfileOption(profile, chunkGridOption, Integer.valueOf(desired));
            setXaeroProfileOption(profile, lineWidthOption, Integer.valueOf(1));
            lastSyncedXaeroChunkGrid = desired;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            lastSyncedXaeroChunkGrid = Integer.MIN_VALUE;
        }
    }

    private static void setXaeroProfileOption(Object profile, Object option, Object value)
            throws ReflectiveOperationException {
        for (var method : profile.getClass().getMethods()) {
            if (!"set".equals(method.getName()) || method.getParameterCount() != 2) continue;
            method.invoke(profile, option, value);
            return;
        }
    }

    private record Label(String text, int x, int y, int width) {}
    private record ChunkFill(int blockX, int blockZ, int blockWidth, TownData town) {}
    private record ChunkCell(int blockX, int blockZ, TownData town) {}
    private record ChunkEdge(int x1, int z1, int x2, int z2, TownData town) {}
    private record VisibleRenderData(TownData[] chunkTowns, List<ChunkFill> fillSpans, List<ChunkCell> fillCells,
                                     List<ChunkEdge> edges, List<LabelAnchor> labelAnchors) {}
    private record MinimapClip(int left, int top, int right, int bottom,
                               double centerX, double centerY,
                               double radius, double radiusSq,
                               boolean circular) {
        private static MinimapClip of(int left, int top, int right, int bottom, boolean circular) {
            double centerX = (left + right + 1) / 2.0;
            double centerY = (top + bottom + 1) / 2.0;
            double radius = Math.max(1.0, Math.min(right - left + 1, bottom - top + 1) / 2.0 - 0.75);
            return new MinimapClip(left, top, right, bottom, centerX, centerY,
                    radius, radius * radius, circular);
        }

        private boolean containsPoint(double x, double y, double inset) {
            if (!circular) return true;
            double effectiveRadius = Math.max(0.0, radius - Math.max(0.0, inset));
            double dx = x - centerX;
            double dy = y - centerY;
            return dx * dx + dy * dy <= effectiveRadius * effectiveRadius;
        }

        private boolean containsText(int centerX, int centerY, int width, int height) {
            if (!circular) return true;
            int halfWidth = width / 2;
            int top = centerY - 4;
            int bottom = top + height;
            return containsPoint(centerX - halfWidth, top, 0.5)
                    && containsPoint(centerX + halfWidth, top, 0.5)
                    && containsPoint(centerX - halfWidth, bottom, 0.5)
                    && containsPoint(centerX + halfWidth, bottom, 0.5);
        }

        /** Squared distance from a world point to the player (== minimap centre under the transform). */
        private static double distSqToPlayer(double x, double z, double playerX, double playerZ) {
            double dx = x - playerX;
            double dz = z - playerZ;
            return dx * dx + dz * dz;
        }

        private int worldRectCircleClass(double x1, double z1, double x2, double z2,
                                         double mapCenterX, double mapCenterY,
                                         double playerX, double playerZ,
                                         double pixelsPerBlock, double sin, double cos) {
            if (!circular) return 1;
            // The world->screen transform is rotation + uniform scale + translate (conformal),
            // so the screen circle is exactly a world circle of this radius around the player.
            // Classifying in world space avoids a per-chunk trig transform every frame.
            double rw = radius / pixelsPerBlock;
            double cx = (x1 + x2) * 0.5 - playerX;
            double cz = (z1 + z2) * 0.5 - playerZ;
            double distanceSq = cx * cx + cz * cz;
            double halfDiagonal = Math.hypot(x2 - x1, z2 - z1) * 0.5;
            double insideLimit = rw - 0.5 / pixelsPerBlock - halfDiagonal;
            if (insideLimit >= 0.0 && distanceSq <= insideLimit * insideLimit) return 1;
            double outsideLimit = rw + 1.0 / pixelsPerBlock + halfDiagonal;
            if (distanceSq > outsideLimit * outsideLimit) return -1;
            return 0;
        }

        private boolean worldRectFullyInside(double x1, double z1, double x2, double z2,
                                             double mapCenterX, double mapCenterY,
                                             double playerX, double playerZ,
                                             double pixelsPerBlock, double sin, double cos) {
            if (!circular) return true;
            double rw = (radius - 0.35) / pixelsPerBlock;
            if (rw <= 0.0) return false;
            double rwSq = rw * rw;
            return distSqToPlayer(x1, z1, playerX, playerZ) <= rwSq
                    && distSqToPlayer(x2, z1, playerX, playerZ) <= rwSq
                    && distSqToPlayer(x2, z2, playerX, playerZ) <= rwSq
                    && distSqToPlayer(x1, z2, playerX, playerZ) <= rwSq;
        }

        private boolean worldRectIntersects(double x1, double z1, double x2, double z2,
                                            double mapCenterX, double mapCenterY,
                                            double playerX, double playerZ,
                                            double pixelsPerBlock, double sin, double cos) {
            if (!circular) return true;
            double rw = (radius + 1.0) / pixelsPerBlock;
            double minX = Math.min(x1, x2);
            double maxX = Math.max(x1, x2);
            double minZ = Math.min(z1, z2);
            double maxZ = Math.max(z1, z2);
            double closestX = Math.max(minX, Math.min(playerX, maxX));
            double closestZ = Math.max(minZ, Math.min(playerZ, maxZ));
            double dx = closestX - playerX;
            double dz = closestZ - playerZ;
            return dx * dx + dz * dz <= rw * rw;
        }

        private boolean worldLineFullyInside(double x1, double z1, double x2, double z2,
                                             double mapCenterX, double mapCenterY,
                                             double playerX, double playerZ,
                                             double pixelsPerBlock, double sin, double cos) {
            if (!circular) return true;
            double rw = (radius - 0.75) / pixelsPerBlock;
            if (rw <= 0.0) return false;
            double rwSq = rw * rw;
            return distSqToPlayer(x1, z1, playerX, playerZ) <= rwSq
                    && distSqToPlayer(x2, z2, playerX, playerZ) <= rwSq;
        }

        private boolean worldLineIntersects(double x1, double z1, double x2, double z2,
                                            double mapCenterX, double mapCenterY,
                                            double playerX, double playerZ,
                                            double pixelsPerBlock, double sin, double cos) {
            if (!circular) return true;
            double rw = (radius + 1.0) / pixelsPerBlock;
            double dx = x2 - x1;
            double dz = z2 - z1;
            double lenSq = dx * dx + dz * dz;
            double t = lenSq < 1e-9 ? 0.0 : ((playerX - x1) * dx + (playerZ - z1) * dz) / lenSq;
            t = Math.max(0.0, Math.min(1.0, t));
            double closestX = x1 + dx * t - playerX;
            double closestZ = z1 + dz * t - playerZ;
            return closestX * closestX + closestZ * closestZ <= rw * rw;
        }

    }
    private record WaypointDrawConfig(boolean waypointsOnMinimap,
                                      int opacity,
                                      float iconScale,
                                      double maxDistance,
                                      boolean dimensionScaleDistance,
                                      boolean temporaryWaypointsGlobal) {}

    private static final class LabelAnchor {
        private final String name;
        private double sumX;
        private double sumZ;
        private int count;

        private LabelAnchor(String name) {
            this.name = name;
        }

        private void add(double x, double z) {
            sumX += x;
            sumZ += z;
            count++;
        }

        private String name() {
            return name;
        }

        private double centerX() {
            return sumX / count;
        }

        private double centerZ() {
            return sumZ / count;
        }

        private int count() {
            return count;
        }
    }
}
