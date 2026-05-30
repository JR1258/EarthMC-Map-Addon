package net.townymap.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
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
    private static final long FAILED_RETRY_MS = 60_000;
    private static final long TILE_REFRESH_MS = 20 * 60_000L;
    private static final int QUALITY_ZOOM_BIAS = 2;
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
    private final Map<TileKey, LoadedTile> completedTiles = new ConcurrentHashMap<>();
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
        render(ctx, cameraX, cameraZ, blockScale, sw, sh, worldLeft, worldRight, worldTop, worldBottom,
                moving, NetworkPolicy.WORLD_MAP);
    }

    void renderMinimap(GuiGraphicsExtractor ctx, double cameraX, double cameraZ, double blockScale, int sw, int sh,
                       double worldLeft, double worldRight, double worldTop, double worldBottom,
                       boolean moving) {
        render(ctx, cameraX, cameraZ, blockScale, sw, sh, worldLeft, worldRight, worldTop, worldBottom,
                moving, NetworkPolicy.WORLD_MAP);
    }

    private void render(GuiGraphicsExtractor ctx, double cameraX, double cameraZ, double blockScale, int sw, int sh,
                        double worldLeft, double worldRight, double worldTop, double worldBottom,
                        boolean moving, NetworkPolicy policy) {
        int zoom = chooseTileZoom(blockScale);
        PanDirection panDirection = updatePanDirection(cameraX, cameraZ);
        processCompletedTiles(moving, zoom, worldLeft, worldRight, worldTop, worldBottom);
        if (zoom > 0) {
            renderLayer(ctx, cameraX, cameraZ, blockScale, sw, sh,
                    worldLeft, worldRight, worldTop, worldBottom, 0, moving, true, policy);
        }
        renderLayer(ctx, cameraX, cameraZ, blockScale, sw, sh,
                worldLeft, worldRight, worldTop, worldBottom, zoom, moving, false, policy);
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
                             int zoom, boolean moving, boolean fallbackLayer, NetworkPolicy policy) {
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

                    TileKey key = new TileKey(zoom, tileX, tileY);
                    Identifier texture = textures.get(key);
                    if (texture == null) {
                        if (requested++ < requestBudget) requestTile(key, false, policy.maxConcurrentLoads());
                        renderParentFallback(ctx, key, tileWorldSize, cameraX, cameraZ, blockScale, sw, sh);
                        continue;
                    }
                    if (policy.allowRefresh()) {
                        refreshTileIfStale(key, policy.maxConcurrentLoads());
                    }

                    renderTile(ctx, texture, tileX, tileY, tileWorldSize, cameraX, cameraZ,
                            blockScale, sw, sh);
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
                    TileKey key = new TileKey(zoom, tileX, tileY);
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
                                         int sw, int sh) {
        for (int parentZoom = childKey.zoom() - 1; parentZoom >= 0; parentZoom--) {
            int factor = 1 << (childKey.zoom() - parentZoom);
            TileKey parentKey = new TileKey(parentZoom,
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
                    cameraX, cameraZ, blockScale, sw, sh, u, v, srcSize, srcSize);
            return true;
        }
        return false;
    }

    private void renderTile(GuiGraphicsExtractor ctx, Identifier texture, int tileX, int tileY,
                            double tileWorldSize, double cameraX, double cameraZ,
                            double blockScale, int sw, int sh) {
        double tileWorldX = tileX * tileWorldSize;
        double tileWorldZ = tileY * tileWorldSize;
        int x1 = toScreenX(tileWorldX, cameraX, blockScale, sw);
        int y1 = toScreenY(tileWorldZ, cameraZ, blockScale, sh);
        int x2 = toScreenX(tileWorldX + tileWorldSize, cameraX, blockScale, sw);
        int y2 = toScreenY(tileWorldZ + tileWorldSize, cameraZ, blockScale, sh);

        if (x2 <= 0 || x1 >= sw || y2 <= 0 || y1 >= sh) return;
        int drawW = Math.max(1, x2 - x1);
        int drawH = Math.max(1, y2 - y1);
        ctx.blit(PIPELINE, texture, x1, y1, 0.0F, 0.0F,
                drawW, drawH,
                TILE_PIXELS, TILE_PIXELS, TILE_PIXELS, TILE_PIXELS);
    }

    private void renderTileRegion(GuiGraphicsExtractor ctx, Identifier texture, int tileX, int tileY,
                                  double tileWorldSize, double cameraX, double cameraZ,
                                  double blockScale, int sw, int sh,
                                  int u, int v, int regionW, int regionH) {
        double tileWorldX = tileX * tileWorldSize;
        double tileWorldZ = tileY * tileWorldSize;
        int x1 = toScreenX(tileWorldX, cameraX, blockScale, sw);
        int y1 = toScreenY(tileWorldZ, cameraZ, blockScale, sh);
        int x2 = toScreenX(tileWorldX + tileWorldSize, cameraX, blockScale, sw);
        int y2 = toScreenY(tileWorldZ + tileWorldSize, cameraZ, blockScale, sh);

        if (x2 <= 0 || x1 >= sw || y2 <= 0 || y1 >= sh) return;
        int drawW = Math.max(1, x2 - x1);
        int drawH = Math.max(1, y2 - y1);
        ctx.blit(PIPELINE, texture, x1, y1, (float) u, (float) v,
                drawW, drawH,
                regionW, regionH, TILE_PIXELS, TILE_PIXELS);
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
                Identifier id = Identifier.fromNamespaceAndPath("townymapaddon",
                        "squaremap/" + key.zoom + "/" + key.x + "_" + key.y);
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
        if (loading.size() > maxConcurrentLoads) {
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
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tileUrl(key)))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "TownyMapAddon/1.0 (Fabric Mod)")
                .GET()
                .build();

        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                failedAt.put(key, System.currentTimeMillis());
                return;
            }
            byte[] bytes = response.body();
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

    private void evictOldTextures(Minecraft client) {
        while (textures.size() > MAX_TEXTURES) {
            Map.Entry<TileKey, Identifier> eldest = textures.entrySet().iterator().next();
            client.getTextureManager().release(eldest.getValue());
            textures.remove(eldest.getKey());
            textureLoadedAt.remove(eldest.getKey());
        }
    }

    boolean isLoading() {
        return !loading.isEmpty() || !completedTiles.isEmpty();
    }

    private String tileUrl(TileKey key) {
        return config.squaremapBaseUrl + "/tiles/" + config.worldKey + "/"
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

    private record TileKey(int zoom, int x, int y) {}
    private record LoadedTile(TileKey key, NativeImage image) {}
    private record PanDirection(double x, double z) {}

    private enum NetworkPolicy {
        WORLD_MAP(true, true, MAX_CONCURRENT_LOADS);

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
