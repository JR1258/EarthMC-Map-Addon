package net.townymap.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
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
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;
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
    private static final int MINIMAP_CLIP_INSET = 0;
    private static final int MINIMAP_RENDER_CACHE_STEP_CHUNKS = 4;
    /** Chunks of neighbour context kept outside the drawn window, so boundary chunks know their neighbours. */
    private static final int EDGE_MARGIN_CHUNKS = 1;
    private static final int MINIMAP_RENDER_CACHE_PADDING_CHUNKS = 4;
    private static final long MINIMAP_SHAPE_CACHE_MS = 100L;
    private static final double MIN_MINIMAP_CHUNK_GRID_SPACING = 3.5;
    private static final int CHUNK_SIZE = 16;
    private static final int MINIMAP_CHUNK_GRID_COLOR = 0xCC000000;
    private static List<TownData> cachedTownSource = List.of();
    private static int cachedMinChunkX;
    private static int cachedMinChunkZ;
    private static int cachedChunkWidth;
    private static int cachedChunkHeight;
    private static VisibleRenderData cachedRenderData =
            new VisibleRenderData(new TownData[0], List.of(), List.of(), List.of());
    private static int lastSyncedXaeroChunkGrid = Integer.MIN_VALUE;
    private static long lastXaeroChunkGridSyncAttemptMs;
    private static long lastMinimapShapeReadAtMs;
    private static int cachedMinimapShape = 0;
    private static long lastArrowOptionReadAtMs;
    private static double cachedArrowScale = 1.0;
    private static long lastWaypointConfigReadAtMs;
    private static WaypointDrawConfig cachedWaypointDrawConfig =
            new WaypointDrawConfig(true, 100, 1.0F, 0.0, false, false);
    private static final long WAYPOINT_COLLECT_CACHE_MS = 500L;
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

    private TownyMinimapOverlay() {
    }

    public static void invalidateTownCache() {
        cachedTownSource = List.of();
        cachedMinChunkX = 0;
        cachedMinChunkZ = 0;
        cachedChunkWidth = 0;
        cachedChunkHeight = 0;
        cachedRenderData = new VisibleRenderData(new TownData[0], List.of(), List.of(), List.of());
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

    public static void render(GuiGraphicsExtractor ctx, MinimapSession session, ModuleRenderContext rc) {
        if (TownyMapMod.isAccessBlocked()) return;
        render(ctx, session, rc.x, rc.y, Math.min(rc.w, rc.h));
    }

    public static void render(GuiGraphicsExtractor ctx, MinimapSession session, int mapX, int mapY, int mapSize) {
        if (TownyMapMod.isAccessBlocked()) return;
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

        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.level == null) return;

        int size = mapSize;
        if (size <= 12) return;

        if (session.getProcessor().isCaveModeDisplayed()) return;

        // The EarthMC map is overworld-only. Outside the overworld (e.g. the Nether) the raw X/Z
        // would place our overlay at the wrong spot, so apply the configured behaviour. Resolved
        // before the projection below, which needs it for both the coordinates and the block size.
        double dimScale = 1.0;
        if (client.level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
            if (config.netherMode == 2
                    && client.level.dimension() == net.minecraft.world.level.Level.NETHER) {
                dimScale = TownyMapMod.dimensionCoordinateScale();   // Overworld Coords
            } else if (!TownyMapMod.WORLD_OVERWORLD.equals(TownyMapMod.playerWorldResolved())) {
                // A world squaremap maps -- the Moon. Claims there are at real 1:1 coordinates, so draw
                // exactly as on Earth. Deliberately NOT tied to the world the map is showing: the
                // minimap draws where the player stands, whatever the world map is pinned to.
                dimScale = 1.0;
            } else if (TownyMapMod.isOnEarthMcServer()) {
                return;                                              // Hidden
            }
            // Off EarthMC, leave the coords unscaled and draw: the overworld-only restriction
            // protects alignment with EarthMC's own world, which does not apply on another server.
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

        // The minimap draws where the player IS, so it asks for that world's claims by name -- the world
        // map may be pinned to the other one, and both are held now.
        String minimapWorld = TownyMapMod.playerWorldResolved();
        if (api.getTowns(minimapWorld).isEmpty()) {
            lastRenderCanCoverWaypoints = squaremapRendered || config.chunkCounterEnabled;
            if (squaremapRendered) {
                renderMinimapChunkGrid(ctx, session, config, centerX, centerY, playerX, playerZ,
                        visibleBlocks, pixelsPerBlock, angle, clip);
            }
            renderChunkCounterSelection(ctx, client, config, mapX, mapY, size, centerX, centerY,
                    playerX, playerZ, pixelsPerBlock, angle, sin, cos, clip);
            if (squaremapRendered) ctx.extractDeferredElements(0, 0, 0.0F);
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
                return;
            }
        }

        List<TownData> towns = api.getTowns(minimapWorld);
        VisibleRenderData renderData = cachedVisibleRenderData(towns, minChunkX, minChunkZ, chunkWidth, chunkHeight);
        lastRenderCanCoverWaypoints = squaremapRendered || config.chunkCounterEnabled || !renderData.fillSpans().isEmpty();

        ctx.enableScissor(clip.left(), clip.top(), clip.right() + 1, clip.bottom() + 1);
        Matrix3x2fStack matrices = ctx.pose();
        matrices.pushMatrix();
        try {
            matrices.translate((float) centerX, (float) centerY);
            matrices.rotate((float) angle);
            matrices.scale((float) pixelsPerBlock, (float) pixelsPerBlock);
            matrices.translate((float) -playerX, (float) -playerZ);

            if (clip.circular()) {
                fillVisibleTownSpansCircleClipped(ctx, renderData.fillSpans(), config, clip,
                        centerX, centerY, playerX, playerZ, pixelsPerBlock, sin, cos);
            } else {
                fillVisibleTownSpans(ctx, renderData.fillSpans(), config);
            }
        } finally {
            matrices.popMatrix();
            ctx.disableScissor();
        }

        if (squaremapRendered) {
            renderMinimapChunkGrid(ctx, session, config, centerX, centerY, playerX, playerZ,
                    visibleBlocks, pixelsPerBlock, angle, clip);
        }

        ctx.enableScissor(clip.left(), clip.top(), clip.right() + 1, clip.bottom() + 1);
        try {
            if (clip.circular()) {
                drawVisibleTownEdgesCircleClipped(ctx, renderData.edges(), config, clip,
                        centerX, centerY, playerX, playerZ, pixelsPerBlock, sin, cos);
                drawOptimisticClaimChunks(ctx, clip,
                        centerX, centerY, playerX, playerZ, pixelsPerBlock, sin, cos);
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

        if (config.playersEnabled && config.minimapPlayersEnabled
                && !TownyMapMod.isArchiveMode()) {   // archived snapshots have no live players
            renderPlayerDots(ctx, api.getPlayers(minimapWorld), player.getName().getString(),
                    mapX, mapY, size, playerX, playerZ, pixelsPerBlock, sin, cos,
                    clip);
        }

        if (config.minimapTownNamesEnabled && config.minimapTownNameMode != 0) {
            renderTownNames(ctx, client, renderData.labelAnchors(),
                    mapX, mapY, size, playerX, playerZ, pixelsPerBlock, sin, cos, config.minimapTownNameMode,
                    clip);
        }

        if (config.chunkCounterEnabled) {
            ChunkCounterOverlay.renderMinimapLabels(ctx, client, mapX, mapY, size,
                    playerX, playerZ, pixelsPerBlock, sin, cos,
                    clip.left(), clip.top(), clip.right(), clip.bottom());
        }

        ctx.extractDeferredElements(0, 0, 0.0F);
    }

    public static void renderWaypointsOnTop(GuiGraphicsExtractor ctx, MinimapSession session,
                                            int mapX, int mapY, int size) {
        if (TownyMapMod.isAccessBlocked()) return;
        TownyMapConfig config = TownyMapMod.getConfig();
        if (config == null || !config.minimapExtensionsEnabled || size <= 12) return;
        if (session.getProcessor().isCaveModeDisplayed()) return;
        if (session.getProcessor().isEnlargedMap()) return;
        if (!lastRenderCanCoverWaypoints) return;

        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.level == null) return;
        if (!session.getProcessor().getMinimap().getWaypointMapRenderer()
                .shouldRender(MinimapElementRenderLocation.OVER_MINIMAP)) {
            return;
        }
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

        Matrix3x2fStack matrices = ctx.pose();
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

            matrices.pushMatrix();
            try {
                matrices.translate(screenX, screenY);
                if (iconScale != 1.0F) {
                    matrices.scale(iconScale, iconScale);
                }
                session.getProcessor().getMinimap().getWaypointMapRenderer()
                        .drawIconGUI(ctx, waypoint, 0, 0, opacity);
            } finally {
                matrices.popMatrix();
            }
        }
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

    private static void renderChunkCounterSelection(GuiGraphicsExtractor ctx, Minecraft client, TownyMapConfig config,
                                                    int mapX, int mapY, int size,
                                                    double centerX, double centerY,
                                                    double playerX, double playerZ,
                                                    double pixelsPerBlock, double angle,
                                                    double sin, double cos,
                                                    MinimapClip clip) {
        if (!config.chunkCounterEnabled) return;
        ctx.enableScissor(clip.left(), clip.top(), clip.right() + 1, clip.bottom() + 1);
        Matrix3x2fStack matrices = ctx.pose();
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
                clip.left(), clip.top(), clip.right(), clip.bottom());
    }

    private static void renderMinimapChunkGrid(GuiGraphicsExtractor ctx, MinimapSession session, TownyMapConfig config,
                                               double centerX, double centerY,
                                               double playerX, double playerZ,
                                               double visibleBlocks, double pixelsPerBlock, double angle,
                                               MinimapClip clip) {
        if (!shouldRenderMinimapChunkGrid(session, config, pixelsPerBlock)) return;
        if (clip.circular()) return;
        ctx.enableScissor(clip.left(), clip.top(), clip.right() + 1, clip.bottom() + 1);
        Matrix3x2fStack matrices = ctx.pose();
        matrices.pushMatrix();
        try {
            matrices.translate((float) centerX, (float) centerY);
            matrices.rotate((float) angle);
            drawChunkGridScreenSpace(ctx, playerX, playerZ, visibleBlocks, pixelsPerBlock);
        } finally {
            matrices.popMatrix();
            ctx.disableScissor();
        }
    }

    private static boolean shouldRenderMinimapChunkGrid(MinimapSession session, TownyMapConfig config,
                                                        double pixelsPerBlock) {
        if (config.minimapChunkGridMode == 0) return false;
        if (config.minimapChunkGridMode == 2 && !session.getProcessor().isEnlargedMap()) return false;
        return CHUNK_SIZE * pixelsPerBlock >= MIN_MINIMAP_CHUNK_GRID_SPACING;
    }

    private static void drawChunkGridScreenSpace(GuiGraphicsExtractor ctx, double playerX, double playerZ,
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
        return new VisibleRenderData(chunkTowns, List.copyOf(fillSpans), List.copyOf(edges), List.copyOf(labelAnchors));
    }



    /** Minimap town fill alpha. (Boosting it exposed the world-space row banding under rotation, so
     *  we keep the config value here and rely on the outline to delineate towns.) */
    private static int minimapFillAlpha(TownyMapConfig config) {
        return config.fillAlpha;
    }

    private static void fillVisibleTownSpans(GuiGraphicsExtractor ctx, List<ChunkFill> fillSpans, TownyMapConfig config) {
        for (ChunkFill span : fillSpans) {
            TownData town = span.town();
            boolean favorite = TownyMapMod.isFavorite(town.name());
            int fillColor = favorite ? FAVORITE_FILL : town.argbFillColor(minimapFillAlpha(config));
            if ((fillColor >>> 24) == 0) continue;
            ctx.fill(span.blockX(), span.blockZ(),
                    span.blockX() + span.blockWidth(), span.blockZ() + CHUNK_SIZE, fillColor);
        }
    }

    private static void fillVisibleTownSpansCircleClipped(GuiGraphicsExtractor ctx, List<ChunkFill> fillSpans,
                                                          TownyMapConfig config, MinimapClip clip,
                                                          double centerX, double centerY,
                                                          double playerX, double playerZ,
                                                          double pixelsPerBlock, double sin, double cos) {
        TownData cachedTown = null;
        int cachedColor = 0;
        for (ChunkFill span : fillSpans) {
            TownData town = span.town();
            if (town != cachedTown) {
                cachedTown = town;
                cachedColor = TownyMapMod.isFavorite(town.name())
                        ? FAVORITE_FILL : town.argbFillColor(minimapFillAlpha(config));
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
                // emitting a separate translucent quad every 16 blocks -- and rows that straddle the
                // circle's rim are exactly where those quads are, which is where the vertical banding
                // shows. fillRectCircleClipped already trims row by row against the circle and merges
                // rows of equal extent, so giving it the full width yields the same shape with no
                // internal column edges. Row height is unchanged, so the work per row is the same.
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

    private static void drawVisibleTownEdges(GuiGraphicsExtractor ctx, List<ChunkEdge> edges, TownyMapConfig config) {
        for (ChunkEdge edge : edges) {
            boolean favorite = TownyMapMod.isFavorite(edge.town().name());
            int outlineColor = favorite ? FAVORITE_OUTLINE
                    : townEdgeColor(edge.town().argbColor(config.borderAlpha), config.squaremapOnMinimap());
            drawChunkEdge(ctx, edge.x1(), edge.z1(), edge.x2(), edge.z2(), outlineColor);
        }
    }

    private static void drawVisibleTownEdgesCircleClipped(GuiGraphicsExtractor ctx, List<ChunkEdge> edges,
                                                          TownyMapConfig config, MinimapClip clip,
                                                          double centerX, double centerY,
                                                          double playerX, double playerZ,
                                                          double pixelsPerBlock, double sin, double cos) {
        double rw = clip.radius() / pixelsPerBlock;
        double rwSq = rw * rw;
        Matrix3x2fStack matrices = ctx.pose();
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
                drawEdgeClippedToCircle(ctx, edge.x1(), edge.z1(), edge.x2(), edge.z2(),
                        cachedColor, playerX, playerZ, rwSq);
            }
        } finally {
            matrices.popMatrix();
        }
    }

    private static void drawEdgeClippedToCircle(GuiGraphicsExtractor ctx, int x1, int z1, int x2, int z2,
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

    private static void drawOptimisticClaimChunks(GuiGraphicsExtractor ctx) {
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

    private static void drawOptimisticClaimChunks(GuiGraphicsExtractor ctx, MinimapClip clip,
                                                  double centerX, double centerY,
                                                  double playerX, double playerZ,
                                                  double pixelsPerBlock, double sin, double cos) {
        Matrix3x2fStack matrices = ctx.pose();
        for (OptimisticClaimChunk chunk : TownyMapMod.optimisticClaimChunks(TownyMapMod.playerWorldResolved())) {
            int blockX = chunk.blockX();
            int blockZ = chunk.blockZ();
            matrices.pushMatrix();
            try {
                matrices.translate((float) centerX, (float) centerY);
                matrices.rotate((float) Math.atan2(sin, cos));
                matrices.scale((float) pixelsPerBlock, (float) pixelsPerBlock);
                matrices.translate((float) -playerX, (float) -playerZ);
                int cls = clip.worldRectCircleClass(blockX, blockZ, blockX + CHUNK_SIZE, blockZ + CHUNK_SIZE,
                        centerX, centerY, playerX, playerZ, pixelsPerBlock, sin, cos);
                if (cls > 0) {
                    ctx.fill(blockX, blockZ, blockX + CHUNK_SIZE, blockZ + CHUNK_SIZE, chunk.fillColor());
                } else if (cls == 0) {
                    fillRectCircleClipped(ctx, blockX, blockZ, CHUNK_SIZE, CHUNK_SIZE, chunk.fillColor(), clip,
                            centerX, centerY, playerX, playerZ, pixelsPerBlock, sin, cos);
                }
            } finally {
                matrices.popMatrix();
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

    private static void fillRectCircleClipped(GuiGraphicsExtractor ctx, int blockX, int blockZ,
                                              int blockWidth, int blockHeight, int color,
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
        //
        // That matters because these are drawn inside a rotated matrix: every band boundary is a shared
        // edge between two rotated quads, and rasterising those leaves hairline seams that crawl as the
        // map turns. Fewer bands, fewer seams. Overlapping them the way the tile renderer does is not an
        // option here -- town fills are translucent, so an overlap would double the alpha and replace
        // the gaps with dark lines.
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

    private static void drawWorldLineCircleClipped(GuiGraphicsExtractor ctx,
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

    private static void drawScreenLineCircleClipped(GuiGraphicsExtractor ctx,
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
        double ta = Math.max(0.0, Math.min(t1, t2));
        double tb = Math.min(1.0, Math.max(t1, t2));
        if (ta > tb) return;
        if (startInside) ta = 0.0;
        if (endInside) tb = 1.0;
        if (ta <= tb) {
            drawScreenLine(ctx, x1 + dx * ta, y1 + dy * ta,
                    x1 + dx * tb, y1 + dy * tb, color);
        }
    }

    private static void drawScreenLine(GuiGraphicsExtractor ctx, double x1, double y1,
                                       double x2, double y2, int color) {
        int ix1 = (int) Math.round(x1);
        int iy1 = (int) Math.round(y1);
        int ix2 = (int) Math.round(x2);
        int iy2 = (int) Math.round(y2);
        if (Math.abs(ix2 - ix1) >= Math.abs(iy2 - iy1)) {
            if (ix2 < ix1) {
                int tx = ix1; ix1 = ix2; ix2 = tx;
                int ty = iy1; iy1 = iy2; iy2 = ty;
            }
            ctx.fill(ix1, iy1, ix2 + 1, iy1 + 1, color);
        } else {
            if (iy2 < iy1) {
                int tx = ix1; ix1 = ix2; ix2 = tx;
                int ty = iy1; iy1 = iy2; iy2 = ty;
            }
            ctx.fill(ix1, iy1, ix1 + 1, iy2 + 1, color);
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

    private static void drawChunkEdge(GuiGraphicsExtractor ctx, int x1, int z1, int x2, int z2, int color) {
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

    private static void renderTownNames(GuiGraphicsExtractor ctx, Minecraft client,
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
            int textWidth = client.font.width(anchor.name());
            if (textWidth > size * 0.55) continue;
            if (!clip.containsText(x, y, textWidth, client.font.lineHeight)) continue;
            Label label = new Label(anchor.name(), x, y, textWidth);
            if (overlaps(labels, label)) continue;
            labels.add(label);
            if (labels.size() >= MAX_MINIMAP_LABELS) break;
        }

        ctx.enableScissor(clip.left(), clip.top(), clip.right() + 1, clip.bottom() + 1);
        try {
            for (Label label : labels) {
                drawScaledLabelCentered(ctx, client.font, label.text, label.x, label.y,
                        0xFFFFFFFF, 0xAA000000, false);
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
    private static final Identifier XAERO_GUI = Identifier.fromNamespaceAndPath("xaeroworldmap", "gui/gui.png");

    /**
     * Draws Xaero's own arrow sprite at the minimap centre, matching the player's yaw.
     * Uses GuiGraphicsExtractor so it composites on top of the squaremap tile batch at frame-end.
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
    public static void renderPlayerIndicator(GuiGraphicsExtractor ctx, MinimapSession session,
                                             int mapX, int mapY, int size) {
        TownyMapConfig config = TownyMapMod.getConfig();
        boolean cave;
        try { cave = session.getProcessor().isCaveModeDisplayed(); } catch (Throwable ignored) { cave = false; }
        if (config == null || !config.minimapExtensionsEnabled) return;
        if (cave) return;
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

        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        float arrowYaw = isMinimapNorthLocked(session) ? client.player.getYRot() : 180.0F;
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

        Matrix3x2fStack m = ctx.pose();

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

        // Flush our own draw. On this MC version GUI blits go into a deferred batch, and the only
        // extraction happening after this point lived inside renderCompassDirections / the squaremap
        // background — both gated on the EMC layer. With that layer off the arrow was queued and then
        // silently dropped, which is exactly what the logs showed: the draw ran, no exception, nothing
        // on screen. Extracting here makes the indicator independent of whatever else is enabled.
        ctx.extractDeferredElements(0, 0, 0.0F);
    }

    private static void drawXaeroArrowSprite(GuiGraphicsExtractor ctx, int color) {
        ctx.blit(RenderPipelines.GUI_TEXTURED, XAERO_GUI,
                -13, -5,   // centers the 26×28 sprite at the origin
                0f, 0f,    // UV start in the 256×256 sheet (sprite is at top-left)
                26, 28,
                256, 256,
                color);
    }

    public static void renderCompassDirections(GuiGraphicsExtractor ctx, MinimapSession session,
                                               int mapX, int mapY, int size) {
        TownyMapConfig config = TownyMapMod.getConfig();
        if (config == null || !config.minimapExtensionsEnabled || !config.squaremapOnMinimap()) return;
        if (session.getProcessor().isCaveModeDisplayed()) return;
        if (size <= 24) return;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        double angle = minimapAngle(session, client);
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        double centerX = mapX + size / 2.0;
        double centerY = mapY + size / 2.0;
        // Match the user's chosen Xaero minimap frame colour (the ring around the minimap).
        int accent = 0xFF000000 | (TownyMapMod.minimapFrameColor() & 0x00FFFFFF);
        boolean circular = isCircularMinimap(session);

        drawCompassLetterOnBorder(ctx, client, "N", centerX, centerY, sin, -cos, size, accent, circular);
        drawCompassLetterOnBorder(ctx, client, "E", centerX, centerY, cos, sin, size, accent, circular);
        drawCompassLetterOnBorder(ctx, client, "S", centerX, centerY, -sin, cos, size, accent, circular);
        drawCompassLetterOnBorder(ctx, client, "W", centerX, centerY, -cos, -sin, size, accent, circular);
        ctx.extractDeferredElements(0, 0, 0.0F);
    }

    private static void drawCompassLetterOnBorder(GuiGraphicsExtractor ctx, Minecraft client, String letter,
                                                  double centerX, double centerY,
                                                  double dirX, double dirY, int size, int accent,
                                                  boolean circular) {
        double length = Math.hypot(dirX, dirY);
        if (length < 0.0001) return;
        // Inset the letter so the WHOLE glyph sits just inside the minimap edge. Previously the
        // letter centre was at radius size/2 + 1 (just OUTSIDE the edge), so its outer half spilled
        // past the circle/box and off-screen when the minimap was near a screen corner. Pull it in
        // by half the (scaled) glyph height plus a small margin.
        double inset = client.font.lineHeight * TownyMapMod.minimapTextScale() * 0.5 + 3.0;
        double edgeDistance = Math.max(1.0, size / 2.0 - inset);
        double scale = circular ? edgeDistance / length
                : edgeDistance / Math.max(Math.abs(dirX), Math.abs(dirY));
        drawCompassLetter(ctx, client, letter, centerX + dirX * scale, centerY + dirY * scale, accent);
    }

    private static void drawCompassLetter(GuiGraphicsExtractor ctx, Minecraft client, String letter,
                                          double centerX, double centerY, int accent) {
        // Coloured drop-shadow in the chosen frame colour, white letter on top for legibility.
        drawScaledLabelCentered(ctx, client.font, letter, centerX, centerY, 0xFFFFFFFF, accent, false);
    }

    /**
     * Draws a single text label centred at (cx, cy), scaled by {@link TownyMapMod#minimapTextScale()}
     * so our minimap labels match the size of Xaero's own info/coordinate text (Xaero renders that
     * inside its 1/xaeroScale matrix). {@code manualShadow != 0} draws a 1px offset coloured shadow;
     * otherwise {@code builtinShadow} uses the font's vanilla shadow. The pose is baked at draw time,
     * so this composes with the deferred pipeline like our other scaled draws.
     */
    public static void drawScaledLabelCentered(GuiGraphicsExtractor ctx, net.minecraft.client.gui.Font font,
                                        String text, double cx, double cy,
                                        int color, int manualShadow, boolean builtinShadow) {
        float s = TownyMapMod.minimapTextScale();
        int w = font.width(text);
        int h = font.lineHeight;
        Matrix3x2fStack m = ctx.pose();
        m.pushMatrix();
        m.translate((float) cx, (float) cy);
        if (s < 0.999f || s > 1.001f) {
            m.scale(s, s);
        }
        if (manualShadow != 0) {
            ctx.text(font, text, -w / 2 + 1, -h / 2 + 1, manualShadow, false);
        }
        ctx.text(font, text, -w / 2, -h / 2, color, builtinShadow);
        m.popMatrix();
    }

    public static void renderSquaremapBackground(GuiGraphicsExtractor ctx, MinimapSession session,
                                                 int mapX, int mapY, int size) {
        TownyMapConfig config = TownyMapMod.getConfig();
        if (config == null || !config.minimapExtensionsEnabled || !config.squaremapOnMinimap()) return;
        if (session.getProcessor().isCaveModeDisplayed()) return;

        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.level == null || size <= 12) return;

        // This is the fallback path used when the town overlay above didn't run, so it has to repeat
        // that method's dimension handling. It previously drew with raw X/Z, which put the squaremap
        // tiles 8x off from the town borders in the Nether and kept drawing them in "Hidden" mode.
        double dimScale = 1.0;
        if (client.level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
            if (config.netherMode == 2
                    && client.level.dimension() == net.minecraft.world.level.Level.NETHER) {
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

    private static void renderSquaremapBackground(GuiGraphicsExtractor ctx,
                                                  int mapX, int mapY, int size,
                                                  double playerX, double playerZ,
                                                  double pixelsPerBlock, double angle,
                                                  MinimapClip clip) {
        if (clip.left() > clip.right() || clip.top() > clip.bottom()) return;
        ctx.enableScissor(clip.left(), clip.top(), clip.right() + 1, clip.bottom() + 1);
        Matrix3x2fStack matrices = ctx.pose();
        matrices.pushMatrix();
        try {
            float center = size / 2.0F;
            matrices.translate(mapX + center, mapY + center);
            matrices.rotate((float) angle);
            matrices.translate(-center, -center);
            TownyMapMod.renderSquaremapMinimapViewport(ctx, playerX, playerZ, pixelsPerBlock,
                    size, size, clip.circular(), clip.radius());
            ctx.extractDeferredElements(0, 0, 0.0F);
        } finally {
            matrices.popMatrix();
            int darken = TownyMapMod.getConfig() != null ? TownyMapMod.getConfig().squaremapDarken : 0;
            if (darken > 0) fillDark(ctx, clip, (darken * 0x38) << 24);   // "Darken Map" 1-3 → alpha 56/112/168
            ctx.disableScissor();
        }
    }

    /** Fills the minimap squaremap area with a translucent overlay, matching the minimap's shape (a circle
     *  for round minimaps so the square corners aren't darkened). The active scissor clamps it to bounds. */
    private static void fillDark(GuiGraphicsExtractor ctx, MinimapClip clip, int argb) {
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

    private static void renderPlayerDots(GuiGraphicsExtractor ctx, List<PlayerMarker> players, String selfName,
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
                if (!clip.containsPoint(x, y, radius + 1.0)) continue;

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

    private static double minimapAngle(MinimapSession session, Minecraft client) {
        if (isMinimapNorthLocked(session)) {
            return 0.0;
        }
        return Math.toRadians(180.0 - client.gameRenderer.mainCamera().yRot());
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

    private static final RenderPipeline RING_PIPELINE = RenderPipelines.GUI_TEXTURED;
    private static final int RING_PAD = 2;                          // px the shadow extends past the radius
    // Cache for the ring texture (the outline is a static circle that only changes when its size or
    // colour changes, so we rasterise it ONCE into a texture and blit it each frame instead of the
    // ~480 per-row fills the scanline path needs (~0.85ms/frame)).
    private static Identifier ringTextureId;
    private static int ringCacheSize = -1, ringCacheColor, ringCacheShadow, ringCacheThickness;

    public static void renderCircularOutline(GuiGraphicsExtractor ctx, int x, int y, int size,
                                             int color, int shadowColor, int thickness) {
        if (size <= 8 || thickness <= 0) return;
        Identifier tex = ringTexture(size, color, shadowColor, thickness);
        if (tex != null) {
            int dim = size + 2 * RING_PAD;
            ctx.blit(RING_PIPELINE, tex, x - RING_PAD, y - RING_PAD, 0.0F, 0.0F,
                    dim, dim, dim, dim, dim, dim);
            return;
        }
        scanlineRingOutline(ctx, x, y, size, color, shadowColor, thickness);
    }

    // Returns a cached ring texture, (re)generating it only when size/colour/thickness change.
    private static Identifier ringTexture(int size, int color, int shadowColor, int thickness) {
        if (ringTextureId != null && size == ringCacheSize && color == ringCacheColor
                && shadowColor == ringCacheShadow && thickness == ringCacheThickness) {
            return ringTextureId;
        }
        try {
            int dim = size + 2 * RING_PAD;
            double c = RING_PAD + size / 2.0;                        // ring centre in texture space
            double rOuter = Math.max(1.0, size / 2.0);
            double rInner = Math.max(0.0, rOuter - thickness);
            boolean hasShadow = (shadowColor >>> 24) != 0;
            double rShadow = hasShadow ? rOuter + 1.0 : rOuter;
            int mainAbgr = argbToAbgr(color);
            int shadowAbgr = argbToAbgr(shadowColor);

            NativeImage img = new NativeImage(NativeImage.Format.RGBA, dim, dim, false);
            for (int py = 0; py < dim; py++) {
                double dy = (py + 0.5) - c;
                for (int px = 0; px < dim; px++) {
                    double dx = (px + 0.5) - c;
                    double d = Math.sqrt(dx * dx + dy * dy);
                    int abgr;
                    if (d >= rInner && d <= rOuter) {
                        abgr = mainAbgr;                              // coloured ring band
                    } else if (hasShadow && d > rOuter && d <= rShadow) {
                        abgr = shadowAbgr;                           // 1px shadow just outside
                    } else {
                        abgr = 0;                                    // transparent
                    }
                    img.setPixelABGR(px, py, abgr);
                }
            }

            Minecraft client = Minecraft.getInstance();
            Identifier id = Identifier.fromNamespaceAndPath("townymapaddon", "minimap_ring");
            if (ringTextureId != null) {
                client.getTextureManager().release(ringTextureId);
            }
            client.getTextureManager().register(id, new DynamicTexture(() -> "TownyMap minimap ring", img));
            ringTextureId = id;
            ringCacheSize = size;
            ringCacheColor = color;
            ringCacheShadow = shadowColor;
            ringCacheThickness = thickness;
            return id;
        } catch (Exception | LinkageError e) {
            ringTextureId = null;
            ringCacheSize = -1;
            return null;                                             // fall back to scanline
        }
    }

    // NativeImage stores pixels little-endian as ABGR (0xAABBGGRR); convert from ARGB (0xAARRGGBB).
    private static int argbToAbgr(int argb) {
        return (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
    }

    private static void scanlineRingOutline(GuiGraphicsExtractor ctx, int x, int y, int size,
                                            int color, int shadowColor, int thickness) {
        double cx = x + size / 2.0;
        double cy = y + size / 2.0;
        double rOuter = Math.max(1.0, size / 2.0);                 // outer edge of the coloured ring
        double rInner = Math.max(0.0, rOuter - thickness);          // inner edge of the coloured ring
        boolean hasShadow = (shadowColor >>> 24) != 0;
        double rShadow = hasShadow ? rOuter + 1.0 : rOuter;         // 1px shadow just outside

        // Scanline annulus: for each pixel row, fill the horizontal span(s) where the ring band
        // crosses that row. ~4*radius fills total vs the old ~3*(2*pi*r) tiny per-segment fills
        // (each ctx.fill is ~2.5us in the deferred pipeline, so the old path cost ~2.8ms/frame).
        // Result is a solid, gap-free, perfectly round ring.
        double rOuterSq = rOuter * rOuter;
        double rInnerSq = rInner * rInner;
        double rShadowSq = rShadow * rShadow;
        int yStart = (int) Math.floor(cy - rShadow);
        int yEnd = (int) Math.ceil(cy + rShadow);
        for (int py = yStart; py < yEnd; py++) {
            double dy = (py + 0.5) - cy;
            double dySq = dy * dy;
            if (dySq > rShadowSq) continue;
            double xOuter = Math.sqrt(Math.max(0.0, rOuterSq - dySq));
            double xInner = dySq < rInnerSq ? Math.sqrt(rInnerSq - dySq) : 0.0;
            if (hasShadow) {
                double xShadow = Math.sqrt(Math.max(0.0, rShadowSq - dySq));
                fillSpan(ctx, cx + xOuter, cx + xShadow, py, shadowColor);
                fillSpan(ctx, cx - xShadow, cx - xOuter, py, shadowColor);
            }
            if (dySq <= rOuterSq) {
                fillSpan(ctx, cx + xInner, cx + xOuter, py, color);
                fillSpan(ctx, cx - xOuter, cx - xInner, py, color);
            }
        }
    }

    private static void fillSpan(GuiGraphicsExtractor ctx, double xl, double xr, int py, int color) {
        int l = (int) Math.round(xl);
        int r = (int) Math.round(xr);
        if (r > l) ctx.fill(l, py, r, py + 1, color);
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
    private record ChunkEdge(int x1, int z1, int x2, int z2, TownData town) {}
    private record VisibleRenderData(TownData[] chunkTowns, List<ChunkFill> fillSpans,
                                     List<ChunkEdge> edges, List<LabelAnchor> labelAnchors) {}
    private record MinimapClip(int left, int top, int right, int bottom,
                               double centerX, double centerY,
                               double radius, double radiusSq,
                               boolean circular) {
        private static MinimapClip of(int left, int top, int right, int bottom, boolean circular) {
            double centerX = (left + right + 1) / 2.0;
            double centerY = (top + bottom + 1) / 2.0;
            double radius = Math.max(1.0, Math.min(right - left + 1, bottom - top + 1) / 2.0);
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
            double sx1 = screenX(x1, z1, mapCenterX, playerX, playerZ, pixelsPerBlock, sin, cos);
            double sy1 = screenY(x1, z1, mapCenterY, playerX, playerZ, pixelsPerBlock, sin, cos);
            double sx2 = screenX(x2, z2, mapCenterX, playerX, playerZ, pixelsPerBlock, sin, cos);
            double sy2 = screenY(x2, z2, mapCenterY, playerX, playerZ, pixelsPerBlock, sin, cos);
            if (containsPoint(sx1, sy1, 0.0) || containsPoint(sx2, sy2, 0.0)) return true;

            double dx = sx2 - sx1;
            double dy = sy2 - sy1;
            double lenSq = dx * dx + dy * dy;
            if (lenSq < 0.0001) return false;
            double t = ((centerX - sx1) * dx + (centerY - sy1) * dy) / lenSq;
            t = Math.max(0.0, Math.min(1.0, t));
            double px = sx1 + dx * t;
            double py = sy1 + dy * t;
            return containsPoint(px, py, 0.0);
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
