package net.townymap.render;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
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
    private static final int MAX_CHUNK_EDGES_PER_FRAME = 1200;
    private static final int MAX_CHUNK_CELLS_PER_FRAME = 7000;
    private static final int MAX_MINIMAP_CHUNK_GRID_LINES = 260;
    private static final int MAX_MINIMAP_LABELS = 24;
    private static final int MINIMAP_CLIP_INSET = 2;
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
    private static long lastWaypointConfigReadAtMs;
    private static WaypointDrawConfig cachedWaypointDrawConfig =
            new WaypointDrawConfig(true, 100, 1.0F, 0.0, false, false);
    private static final long WAYPOINT_COLLECT_CACHE_MS = 500L;
    private static MinimapSession cachedWaypointSession;
    private static long cachedWaypointSetChangedAt;
    private static long cachedWaypointCollectAtMs;
    private static List<Waypoint> cachedMinimapWaypoints = List.of();
    private static boolean lastRenderCanCoverWaypoints;

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

    public static void render(DrawContext ctx, MinimapSession session, ModuleRenderContext rc) {
        render(ctx, session, rc.x, rc.y, Math.min(rc.w, rc.h));
    }

    public static void render(DrawContext ctx, MinimapSession session, int mapX, int mapY, int mapSize) {
        TownyMapConfig config = TownyMapMod.getConfig();
        SquaremapApiClient api = TownyMapMod.getApiClient();
        lastRenderCanCoverWaypoints = false;
        if (config == null || api == null || !config.minimapExtensionsEnabled) return;
        syncXaeroChunkGrid(session, config);

        api.tickMinimapTownMarkers();

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;

        int size = mapSize;
        if (size <= 12) return;

        if (session.getProcessor().isCaveModeDisplayed()) return;
        double zoom = Math.max(0.25, session.getProcessor().getMinimapZoom());
        double blocksAcross = Math.max(64.0, session.getProcessor().getMinimapSize() * zoom);
        double pixelsPerBlock = size / blocksAcross;
        if (pixelsPerBlock <= 0) return;

        double centerX = mapX + size / 2.0;
        double centerY = mapY + size / 2.0;
        double playerX = player.getX();
        double playerZ = player.getZ();
        double visibleBlocks = blocksAcross / 2.0 + EXTRA_BLOCK_PADDING;
        TownyMapMod.updateMinimapNationAlert(playerX, playerZ, visibleBlocks);
        double angle = minimapAngle(session, client);
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);

        int left = mapX;
        int top = mapY;
        int right = mapX + size - 1;
        int bottom = mapY + size - 1;
        int clipLeft = left + MINIMAP_CLIP_INSET;
        int clipTop = top + MINIMAP_CLIP_INSET;
        int clipRight = right - MINIMAP_CLIP_INSET;
        int clipBottom = bottom - MINIMAP_CLIP_INSET;

        boolean squaremapRendered = config.squaremapBackgroundEnabled;
        if (squaremapRendered) {
            renderSquaremapBackground(ctx, mapX, mapY, size, playerX, playerZ,
                    pixelsPerBlock, angle, clipLeft, clipTop, clipRight, clipBottom);
        }

        if (api.getTowns().isEmpty()) {
            lastRenderCanCoverWaypoints = squaremapRendered || config.chunkCounterEnabled;
            if (squaremapRendered) {
                renderMinimapChunkGrid(ctx, session, config, centerX, centerY, playerX, playerZ,
                        visibleBlocks, pixelsPerBlock, angle, clipLeft, clipTop, clipRight, clipBottom);
            }
            renderChunkCounterSelection(ctx, client, config, mapX, mapY, size, centerX, centerY,
                    playerX, playerZ, pixelsPerBlock, angle, sin, cos, clipLeft, clipTop, clipRight, clipBottom);
            if (squaremapRendered) ctx.drawDeferredElements();
            return;
        }

        int minChunkX = floorToChunk(playerX - visibleBlocks);
        int maxChunkX = floorToChunk(playerX + visibleBlocks);
        int minChunkZ = floorToChunk(playerZ - visibleBlocks);
        int maxChunkZ = floorToChunk(playerZ + visibleBlocks);
        int chunkWidth = maxChunkX - minChunkX + 1;
        int chunkHeight = maxChunkZ - minChunkZ + 1;
        if (chunkWidth <= 0 || chunkHeight <= 0 || chunkWidth * chunkHeight > MAX_CHUNK_CELLS_PER_FRAME) {
            return;
        }

        List<TownData> towns = api.getTowns();
        VisibleRenderData renderData = cachedVisibleRenderData(towns, minChunkX, minChunkZ, chunkWidth, chunkHeight);
        lastRenderCanCoverWaypoints = squaremapRendered || config.chunkCounterEnabled || !renderData.fillCells().isEmpty();

        ctx.enableScissor(clipLeft, clipTop, clipRight + 1, clipBottom + 1);
        Matrix3x2fStack matrices = ctx.getMatrices();
        matrices.pushMatrix();
        try {
            matrices.translate((float) centerX, (float) centerY);
            matrices.rotate((float) angle);
            matrices.scale((float) pixelsPerBlock, (float) pixelsPerBlock);
            matrices.translate((float) -playerX, (float) -playerZ);

            fillVisibleTownChunks(ctx, renderData.fillCells(), config);
        } finally {
            matrices.popMatrix();
            ctx.disableScissor();
        }

        if (squaremapRendered) {
            renderMinimapChunkGrid(ctx, session, config, centerX, centerY, playerX, playerZ,
                    visibleBlocks, pixelsPerBlock, angle, clipLeft, clipTop, clipRight, clipBottom);
        }

        ctx.enableScissor(clipLeft, clipTop, clipRight + 1, clipBottom + 1);
        matrices.pushMatrix();
        try {
            matrices.translate((float) centerX, (float) centerY);
            matrices.rotate((float) angle);
            matrices.scale((float) pixelsPerBlock, (float) pixelsPerBlock);
            matrices.translate((float) -playerX, (float) -playerZ);

            drawVisibleTownEdges(ctx, renderData.edges(), config);
            drawOptimisticClaimChunks(ctx);
            if (config.chunkCounterEnabled) {
                ChunkCounterOverlay.renderWorldSpace(ctx);
            }
        } finally {
            matrices.popMatrix();
            ctx.disableScissor();
        }

        if (config.playersEnabled && config.minimapPlayersEnabled) {
            renderPlayerDots(ctx, api.getPlayers(), player.getName().getString(),
                    mapX, mapY, size, playerX, playerZ, pixelsPerBlock, sin, cos,
                    clipLeft, clipTop, clipRight, clipBottom);
        }

        if (config.minimapTownNamesEnabled && config.minimapTownNameMode != 0) {
            renderTownNames(ctx, client, renderData.labelAnchors(),
                    mapX, mapY, size, playerX, playerZ, pixelsPerBlock, sin, cos, config.minimapTownNameMode,
                    clipLeft, clipTop, clipRight, clipBottom);
        }

        if (config.chunkCounterEnabled) {
            ChunkCounterOverlay.renderMinimapLabels(ctx, client, mapX, mapY, size,
                    playerX, playerZ, pixelsPerBlock, sin, cos, clipLeft, clipTop, clipRight, clipBottom);
        }

        ctx.drawDeferredElements();
    }

    public static void renderWaypointsOnTop(DrawContext ctx, MinimapSession session,
                                            int mapX, int mapY, int size) {
        TownyMapConfig config = TownyMapMod.getConfig();
        if (config == null || !config.minimapExtensionsEnabled || size <= 12) return;
        if (session.getProcessor().isCaveModeDisplayed()) return;
        if (session.getProcessor().isEnlargedMap()) return;
        if (!lastRenderCanCoverWaypoints) return;

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;
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

        double zoom = Math.max(0.25, session.getProcessor().getMinimapZoom());
        double blocksAcross = Math.max(64.0, session.getProcessor().getMinimapSize() * zoom);
        double pixelsPerBlock = size / blocksAcross;
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

        Matrix3x2fStack matrices = ctx.getMatrices();
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

    private static void renderChunkCounterSelection(DrawContext ctx, MinecraftClient client, TownyMapConfig config,
                                                    int mapX, int mapY, int size,
                                                    double centerX, double centerY,
                                                    double playerX, double playerZ,
                                                    double pixelsPerBlock, double angle,
                                                    double sin, double cos,
                                                    int clipLeft, int clipTop, int clipRight, int clipBottom) {
        if (!config.chunkCounterEnabled) return;
        ctx.enableScissor(clipLeft, clipTop, clipRight + 1, clipBottom + 1);
        Matrix3x2fStack matrices = ctx.getMatrices();
        matrices.pushMatrix();
        try {
            matrices.translate((float) centerX, (float) centerY);
            matrices.rotate((float) angle);
            matrices.scale((float) pixelsPerBlock, (float) pixelsPerBlock);
            matrices.translate((float) -playerX, (float) -playerZ);
            ChunkCounterOverlay.renderWorldSpace(ctx);
        } finally {
            matrices.popMatrix();
            ctx.disableScissor();
        }
        ChunkCounterOverlay.renderMinimapLabels(ctx, client, mapX, mapY, size,
                playerX, playerZ, pixelsPerBlock, sin, cos, clipLeft, clipTop, clipRight, clipBottom);
    }

    private static void renderMinimapChunkGrid(DrawContext ctx, MinimapSession session, TownyMapConfig config,
                                               double centerX, double centerY,
                                               double playerX, double playerZ,
                                               double visibleBlocks, double pixelsPerBlock, double angle,
                                               int clipLeft, int clipTop, int clipRight, int clipBottom) {
        if (!shouldRenderMinimapChunkGrid(session, config, pixelsPerBlock)) return;
        ctx.enableScissor(clipLeft, clipTop, clipRight + 1, clipBottom + 1);
        Matrix3x2fStack matrices = ctx.getMatrices();
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

        TownData[] chunkTowns = buildVisibleChunkMask(towns, minChunkX, minChunkZ, chunkWidth, chunkHeight);
        closeOneChunkHoles(chunkTowns, chunkWidth, chunkHeight);
        VisibleRenderData renderData = buildRenderData(chunkTowns, minChunkX, minChunkZ, chunkWidth, chunkHeight);
        cachedTownSource = towns;
        cachedMinChunkX = minChunkX;
        cachedMinChunkZ = minChunkZ;
        cachedChunkWidth = chunkWidth;
        cachedChunkHeight = chunkHeight;
        cachedRenderData = renderData;
        return cachedRenderData;
    }

    private static VisibleRenderData buildRenderData(TownData[] chunkTowns, int minChunkX, int minChunkZ,
                                                     int chunkWidth, int chunkHeight) {
        ArrayList<ChunkCell> fillCells = new ArrayList<>();
        ArrayList<ChunkEdge> edges = new ArrayList<>();
        Map<String, LabelAnchor> anchors = new LinkedHashMap<>();

        for (int z = 0; z < chunkHeight; z++) {
            int blockZ = (minChunkZ + z) * CHUNK_SIZE;
            double labelZ = blockZ + CHUNK_SIZE / 2.0;
            for (int x = 0; x < chunkWidth; x++) {
                TownData town = chunkTowns[index(x, z, chunkWidth)];
                if (town == null) continue;
                int blockX = (minChunkX + x) * CHUNK_SIZE;
                fillCells.add(new ChunkCell(blockX, blockZ, town));
                anchors.computeIfAbsent(town.name(), LabelAnchor::new)
                        .add(blockX + CHUNK_SIZE / 2.0, labelZ);

                if (edges.size() >= MAX_CHUNK_EDGES_PER_FRAME) continue;
                if (!sameTown(town, getTown(chunkTowns, x, z - 1, chunkWidth, chunkHeight))) {
                    edges.add(new ChunkEdge(blockX, blockZ, blockX + CHUNK_SIZE, blockZ, town));
                }
                if (edges.size() >= MAX_CHUNK_EDGES_PER_FRAME) continue;
                if (!sameTown(town, getTown(chunkTowns, x + 1, z, chunkWidth, chunkHeight))) {
                    edges.add(new ChunkEdge(blockX + CHUNK_SIZE, blockZ, blockX + CHUNK_SIZE, blockZ + CHUNK_SIZE, town));
                }
                if (edges.size() >= MAX_CHUNK_EDGES_PER_FRAME) continue;
                if (!sameTown(town, getTown(chunkTowns, x, z + 1, chunkWidth, chunkHeight))) {
                    edges.add(new ChunkEdge(blockX, blockZ + CHUNK_SIZE, blockX + CHUNK_SIZE, blockZ + CHUNK_SIZE, town));
                }
                if (edges.size() >= MAX_CHUNK_EDGES_PER_FRAME) continue;
                if (!sameTown(town, getTown(chunkTowns, x - 1, z, chunkWidth, chunkHeight))) {
                    edges.add(new ChunkEdge(blockX, blockZ, blockX, blockZ + CHUNK_SIZE, town));
                }
            }
        }

        ArrayList<LabelAnchor> labelAnchors = new ArrayList<>(anchors.values());
        labelAnchors.sort(Comparator.comparingInt(LabelAnchor::count).reversed()
                .thenComparing(LabelAnchor::name, String.CASE_INSENSITIVE_ORDER));
        return new VisibleRenderData(chunkTowns, List.copyOf(fillCells), List.copyOf(edges), List.copyOf(labelAnchors));
    }

    private static void closeOneChunkHoles(TownData[] chunkTowns, int width, int height) {
        TownData[] patched = chunkTowns.clone();
        for (int z = 1; z < height - 1; z++) {
            for (int x = 1; x < width - 1; x++) {
                int i = index(x, z, width);
                if (chunkTowns[i] != null) continue;

                TownData left = chunkTowns[index(x - 1, z, width)];
                TownData right = chunkTowns[index(x + 1, z, width)];
                TownData up = chunkTowns[index(x, z - 1, width)];
                TownData down = chunkTowns[index(x, z + 1, width)];
                TownData fill = null;
                if (sameTown(left, right)) fill = left;
                else if (sameTown(up, down)) fill = up;
                else fill = majorityTown(left, right, up, down);
                if (fill != null) patched[i] = fill;
            }
        }
        System.arraycopy(patched, 0, chunkTowns, 0, chunkTowns.length);
    }

    private static TownData majorityTown(TownData a, TownData b, TownData c, TownData d) {
        TownData[] values = {a, b, c, d};
        for (TownData candidate : values) {
            if (candidate == null) continue;
            int count = 0;
            for (TownData value : values) {
                if (sameTown(candidate, value)) count++;
            }
            if (count >= 3) return candidate;
        }
        return null;
    }

    private static void fillVisibleTownChunks(DrawContext ctx, List<ChunkCell> fillCells, TownyMapConfig config) {
        for (ChunkCell cell : fillCells) {
            TownData town = cell.town();
            boolean favorite = TownyMapMod.isFavorite(town.name());
            int fillColor = favorite ? FAVORITE_FILL : town.argbColor(config.fillAlpha);
            if ((fillColor >>> 24) == 0) continue;
            ctx.fill(cell.blockX(), cell.blockZ(), cell.blockX() + CHUNK_SIZE, cell.blockZ() + CHUNK_SIZE, fillColor);
        }
    }

    private static void drawVisibleTownEdges(DrawContext ctx, List<ChunkEdge> edges, TownyMapConfig config) {
        for (ChunkEdge edge : edges) {
            boolean favorite = TownyMapMod.isFavorite(edge.town().name());
            int outlineColor = favorite ? FAVORITE_OUTLINE : edge.town().argbColor(config.borderAlpha);
            drawChunkEdge(ctx, edge.x1(), edge.z1(), edge.x2(), edge.z2(), outlineColor);
        }
    }

    private static void drawOptimisticClaimChunks(DrawContext ctx) {
        for (OptimisticClaimChunk chunk : TownyMapMod.optimisticClaimChunks()) {
            int blockX = chunk.blockX();
            int blockZ = chunk.blockZ();
            ctx.fill(blockX, blockZ, blockX + CHUNK_SIZE, blockZ + CHUNK_SIZE, chunk.fillColor());
            drawChunkEdge(ctx, blockX, blockZ, blockX + CHUNK_SIZE, blockZ, chunk.outlineColor());
            drawChunkEdge(ctx, blockX + CHUNK_SIZE, blockZ, blockX + CHUNK_SIZE, blockZ + CHUNK_SIZE, chunk.outlineColor());
            drawChunkEdge(ctx, blockX, blockZ + CHUNK_SIZE, blockX + CHUNK_SIZE, blockZ + CHUNK_SIZE, chunk.outlineColor());
            drawChunkEdge(ctx, blockX, blockZ, blockX, blockZ + CHUNK_SIZE, chunk.outlineColor());
        }
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

    private static boolean sameTown(TownData a, TownData b) {
        if (a == null || b == null) return false;
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
                                        int clipLeft, int clipTop, int clipRight, int clipBottom) {
        double centerX = mapX + size / 2.0;
        double centerY = mapY + size / 2.0;

        List<Label> labels = new ArrayList<>();
        for (LabelAnchor anchor : anchors) {
            if (!shouldShowTownName(anchor, mode, playerX, playerZ)) continue;
            double dx = anchor.centerX() - playerX;
            double dz = anchor.centerZ() - playerZ;
            int x = (int) Math.round(centerX + (dx * cos - dz * sin) * pixelsPerBlock);
            int y = (int) Math.round(centerY + (dx * sin + dz * cos) * pixelsPerBlock);
            if (x < clipLeft || x > clipRight || y < clipTop || y > clipBottom) continue;
            int textWidth = client.textRenderer.getWidth(anchor.name());
            if (textWidth > size * 0.55) continue;
            Label label = new Label(anchor.name(), x, y, textWidth);
            if (overlaps(labels, label)) continue;
            labels.add(label);
            if (labels.size() >= MAX_MINIMAP_LABELS) break;
        }

        ctx.enableScissor(clipLeft, clipTop, clipRight + 1, clipBottom + 1);
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
    public static void renderPlayerIndicator(DrawContext ctx, MinimapSession session,
                                             int mapX, int mapY, int size) {
        TownyMapConfig config = TownyMapMod.getConfig();
        if (config == null || !config.minimapExtensionsEnabled || !config.squaremapBackgroundEnabled) return;
        if (session.getProcessor().isCaveModeDisplayed()) return;

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
        float arrowScale = Math.max(0.2f, Math.min(1.5f, 0.5f * size / minimapSize));
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
        if (config == null || !config.minimapExtensionsEnabled || !config.squaremapBackgroundEnabled) return;
        if (session.getProcessor().isCaveModeDisplayed()) return;
        if (size <= 24) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        double angle = minimapAngle(session, client);
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        double centerX = mapX + size / 2.0;
        double centerY = mapY + size / 2.0;

        drawCompassLetterOnBorder(ctx, client, "N", centerX, centerY, sin, -cos, size);
        drawCompassLetterOnBorder(ctx, client, "E", centerX, centerY, cos, sin, size);
        drawCompassLetterOnBorder(ctx, client, "S", centerX, centerY, -sin, cos, size);
        drawCompassLetterOnBorder(ctx, client, "W", centerX, centerY, -cos, -sin, size);
        ctx.drawDeferredElements();
    }

    private static void drawCompassLetterOnBorder(DrawContext ctx, MinecraftClient client, String letter,
                                                  double centerX, double centerY,
                                                  double dirX, double dirY, int size) {
        double maxComponent = Math.max(Math.abs(dirX), Math.abs(dirY));
        if (maxComponent < 0.0001) return;
        double edgeDistance = size / 2.0 + 1.0;
        double scale = edgeDistance / maxComponent;
        drawCompassLetter(ctx, client, letter, centerX + dirX * scale, centerY + dirY * scale);
    }

    private static void drawCompassLetter(DrawContext ctx, MinecraftClient client, String letter,
                                          double centerX, double centerY) {
        int width = client.textRenderer.getWidth(letter);
        int height = client.textRenderer.fontHeight;
        int x = (int) Math.round(centerX - width / 2.0);
        int y = (int) Math.round(centerY - height / 2.0);
        ctx.drawText(client.textRenderer, letter, x + 1, y + 1, 0xFFFF5ACD, false);
        ctx.drawText(client.textRenderer, letter, x, y, 0xFFFFFFFF, false);
    }

    public static void renderSquaremapBackground(DrawContext ctx, MinimapSession session,
                                                 int mapX, int mapY, int size) {
        TownyMapConfig config = TownyMapMod.getConfig();
        if (config == null || !config.minimapExtensionsEnabled || !config.squaremapBackgroundEnabled) return;
        if (session.getProcessor().isCaveModeDisplayed()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || size <= 12) return;

        double zoom = Math.max(0.25, session.getProcessor().getMinimapZoom());
        double blocksAcross = Math.max(64.0, session.getProcessor().getMinimapSize() * zoom);
        double pixelsPerBlock = size / blocksAcross;
        if (pixelsPerBlock <= 0) return;

        int left = mapX;
        int top = mapY;
        int right = mapX + size - 1;
        int bottom = mapY + size - 1;
        renderSquaremapBackground(ctx, mapX, mapY, size, player.getX(), player.getZ(),
                pixelsPerBlock, minimapAngle(session, client),
                left + MINIMAP_CLIP_INSET,
                top + MINIMAP_CLIP_INSET,
                right - MINIMAP_CLIP_INSET,
                bottom - MINIMAP_CLIP_INSET);
    }

    private static void renderSquaremapBackground(DrawContext ctx,
                                                  int mapX, int mapY, int size,
                                                  double playerX, double playerZ,
                                                  double pixelsPerBlock, double angle,
                                                  int clipLeft, int clipTop,
                                                  int clipRight, int clipBottom) {
        if (clipLeft > clipRight || clipTop > clipBottom) return;
        ctx.enableScissor(clipLeft, clipTop, clipRight + 1, clipBottom + 1);
        Matrix3x2fStack matrices = ctx.getMatrices();
        matrices.pushMatrix();
        try {
            float center = size / 2.0F;
            matrices.translate(mapX + center, mapY + center);
            matrices.rotate((float) angle);
            matrices.translate(-center, -center);
            TownyMapMod.renderSquaremapMinimapViewport(ctx, playerX, playerZ, pixelsPerBlock, size, size);
        } finally {
            matrices.popMatrix();
            ctx.disableScissor();
        }
    }

    private static void renderPlayerDots(DrawContext ctx, List<PlayerMarker> players, String selfName,
                                         int mapX, int mapY, int size,
                                         double playerX, double playerZ, double pixelsPerBlock,
                                         double sin, double cos,
                                         int clipLeft, int clipTop, int clipRight, int clipBottom) {
        if (players.isEmpty()) return;
        double centerX = mapX + size / 2.0;
        double centerY = mapY + size / 2.0;
        int radius = 1;

        ctx.enableScissor(clipLeft, clipTop, clipRight + 1, clipBottom + 1);
        try {
            for (PlayerMarker marker : players) {
                if (marker.name() == null || marker.name().equalsIgnoreCase(selfName)) continue;
                double dx = marker.x() - playerX;
                double dz = marker.z() - playerZ;
                int x = (int) Math.round(centerX + (dx * cos - dz * sin) * pixelsPerBlock);
                int y = (int) Math.round(centerY + (dx * sin + dz * cos) * pixelsPerBlock);
                if (x < clipLeft + radius || x > clipRight - radius
                        || y < clipTop + radius || y > clipBottom - radius) continue;

                int color = TownyMapMod.minimapPlayerDotColor(marker.name());
                if ((color >>> 24) == 0) continue;
                ctx.fill(x - radius, y - radius, x + radius + 1, y + radius + 1, color);
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

    private static boolean isMinimapNorthLocked(MinimapSession session) {
        try {
            return MinimapConfigClientUtils.getEffectiveNorthLocked(
                    session.getProcessor().getMinimapSize() / 2, 0);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
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
    private record ChunkCell(int blockX, int blockZ, TownData town) {}
    private record ChunkEdge(int x1, int z1, int x2, int z2, TownData town) {}
    private record VisibleRenderData(TownData[] chunkTowns, List<ChunkCell> fillCells,
                                     List<ChunkEdge> edges, List<LabelAnchor> labelAnchors) {}
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
