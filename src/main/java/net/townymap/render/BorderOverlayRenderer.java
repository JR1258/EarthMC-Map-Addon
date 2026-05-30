package net.townymap.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import net.fabricmc.loader.api.FabricLoader;
import net.townymap.TownyMapConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.geom.Path2D;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class BorderOverlayRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("TownyMapAddon");
    private static final String COUNTRIES =
            "/assets/townymapaddon/borders/borders.nostra.countries.json";
    private static final String STATES =
            "/assets/townymapaddon/borders/borders.nostra.states-and-countries.json";
    private static final int TILE_PIXELS = 512;
    private static final int ATLAS_GRID = 4;
    private static final int ATLAS_PIXELS = TILE_PIXELS * ATLAS_GRID;
    private static final int MAX_ATLAS_ZOOM = 3;
    private static final int MAX_NEW_TILES_PER_FRAME = 24;
    private static final int MAX_NEW_COUNTRY_TILES_PER_FRAME = 48;
    private static final int MAX_NEW_STATE_TILES_PER_FRAME = 8;
    private static final int MAX_CONCURRENT_TILES = 16;
    private static final int MAX_CONCURRENT_COUNTRY_TILES = 32;
    private static final int MAX_TEXTURE_UPLOADS_PER_FRAME = 8;
    private static final int MAX_COUNTRY_TEXTURE_UPLOADS_PER_FRAME = 16;
    private static final int MAX_ATLAS_TEXTURE_UPLOADS_PER_FRAME = 1;
    private static final int MAX_TEXTURES = 768;
    private static final int PREFETCH_TILE_MARGIN = 2;
    private static final int QUALITY_ZOOM_BIAS = 2;
    private static final int MAX_BORDER_ZOOM_BELOW_MAX = 2;
    private static final double INDEX_CELL_BLOCKS = 4096.0;
    private static final String CACHE_VERSION = "v6";
    private static final String PREBUILT_ROOT = "/assets/townymapaddon/prebuilt-borders";
    private static final int PREBUILT_THICKNESS_Q = 10;
    private static final int PREBUILT_SQUAREMAP_MAX_ZOOM = 5;
    private static final RenderPipeline PIPELINE = RenderPipelines.GUI_TEXTURED;

    private final TownyMapConfig config;
    private final ExecutorService executor = Executors.newFixedThreadPool(tileWorkerCount(), r -> {
        Thread t = new Thread(r, "TownyMap-BorderTiles");
        t.setDaemon(true);
        return t;
    });
    private final Set<TileKey> loading = ConcurrentHashMap.newKeySet();
    private final Set<TileKey> emptyTiles = ConcurrentHashMap.newKeySet();
    private final Queue<LoadedTile> completedTiles = new ConcurrentLinkedQueue<>();
    private final Set<AtlasKey> atlasLoading = ConcurrentHashMap.newKeySet();
    private final Set<AtlasKey> emptyAtlases = ConcurrentHashMap.newKeySet();
    private final Queue<LoadedTile> completedCountryTiles = new ConcurrentLinkedQueue<>();
    private final Queue<LoadedAtlas> completedAtlases = new ConcurrentLinkedQueue<>();
    private final Queue<LoadedAtlas> completedCountryAtlases = new ConcurrentLinkedQueue<>();
    private final LinkedHashMap<TileKey, Identifier> textures =
            new LinkedHashMap<>(64, 0.75f, true);
    private final LinkedHashMap<AtlasKey, Identifier> atlasTextures =
            new LinkedHashMap<>(64, 0.75f, true);
    private final Map<Integer, BorderLineIndex> lineIndexes = new ConcurrentHashMap<>();
    private final Path diskCacheDir;
    private volatile Set<String> prebuiltTileManifest;
    private volatile boolean prebuiltManifestLoaded;
    private volatile Set<String> prebuiltAtlasManifest;
    private volatile boolean prebuiltAtlasManifestLoaded;

    private List<BorderLine> countryLines;
    private List<BorderLine> stateLines;
    private List<BorderLine> stateOnlyLines;

    BorderOverlayRenderer(TownyMapConfig config) {
        this.config = config;
        this.diskCacheDir = FabricLoader.getInstance().getConfigDir()
                .resolve("townymapaddon")
                .resolve("border-cache")
                .resolve(CACHE_VERSION);
        executor.execute(this::prebuiltManifest);
        executor.execute(this::prebuiltAtlasManifest);
    }

    void render(GuiGraphicsExtractor ctx, double cameraX, double cameraZ, double blockScale,
                int sw, int sh, double worldLeft, double worldRight,
                double worldTop, double worldBottom) {
        int mode = config.borderOverlayMode;
        if (mode == 0 || blockScale <= 0) return;

        processCompletedTiles();
        if (mode == 1) {
            renderLayer(ctx, 1, cameraX, cameraZ, blockScale, sw, sh,
                    worldLeft, worldRight, worldTop, worldBottom, MAX_NEW_COUNTRY_TILES_PER_FRAME);
        } else {
            renderLayer(ctx, 1, cameraX, cameraZ, blockScale, sw, sh,
                    worldLeft, worldRight, worldTop, worldBottom, MAX_NEW_COUNTRY_TILES_PER_FRAME);
            renderLayer(ctx, 2, cameraX, cameraZ, blockScale, sw, sh,
                    worldLeft, worldRight, worldTop, worldBottom, MAX_NEW_STATE_TILES_PER_FRAME);
        }
    }

    private void renderLayer(GuiGraphicsExtractor ctx, int mode, double cameraX, double cameraZ, double blockScale,
                             int sw, int sh, double worldLeft, double worldRight,
                             double worldTop, double worldBottom, int maxNewRequests) {
        int zoom = chooseTileZoom(blockScale);
        double pixelsPerBlock = pixelsPerBlock(zoom);
        double tileWorldSize = TILE_PIXELS / pixelsPerBlock;

        int visibleMinTileX = floorToTile(worldLeft, tileWorldSize);
        int visibleMaxTileX = floorToTile(worldRight, tileWorldSize);
        int visibleMinTileY = floorToTile(worldTop, tileWorldSize);
        int visibleMaxTileY = floorToTile(worldBottom, tileWorldSize);
        int minTileX = visibleMinTileX - PREFETCH_TILE_MARGIN;
        int maxTileX = visibleMaxTileX + PREFETCH_TILE_MARGIN;
        int minTileY = visibleMinTileY - PREFETCH_TILE_MARGIN;
        int maxTileY = visibleMaxTileY + PREFETCH_TILE_MARGIN;
        int centerTileX = floorToTile((worldLeft + worldRight) * 0.5, tileWorldSize);
        int centerTileY = floorToTile((worldTop + worldBottom) * 0.5, tileWorldSize);
        int maxRadius = Math.max(
                Math.max(Math.abs(minTileX - centerTileX), Math.abs(maxTileX - centerTileX)),
                Math.max(Math.abs(minTileY - centerTileY), Math.abs(maxTileY - centerTileY)));

        // Quantise thickness so the key is stable while the slider is between notches.
        int thicknessQ = Math.max(2, Math.min(60, Math.round(config.borderThicknessMultiplier * 20)));

        int requested = 0;
        for (int radius = 0; radius <= maxRadius; radius++) {
            for (int tileY = centerTileY - radius; tileY <= centerTileY + radius; tileY++) {
                for (int tileX = centerTileX - radius; tileX <= centerTileX + radius; tileX++) {
                    if (tileX < minTileX || tileX > maxTileX || tileY < minTileY || tileY > maxTileY) continue;
                    if (Math.max(Math.abs(tileX - centerTileX), Math.abs(tileY - centerTileY)) != radius) continue;

                    TileKey key = new TileKey(mode, zoom, tileX, tileY, thicknessQ);
                    boolean visible = tileX >= visibleMinTileX && tileX <= visibleMaxTileX
                            && tileY >= visibleMinTileY && tileY <= visibleMaxTileY;
                    if (visible && renderAtlasTileIfAvailable(ctx, key, tileWorldSize, cameraX, cameraZ,
                            blockScale, sw, sh)) {
                        continue;
                    }

                    Identifier texture = textures.get(key);
                    if (texture == null) {
                        if (!emptyTiles.contains(key) && requested++ < maxNewRequests) {
                            requestTile(key, tileWorldSize, pixelsPerBlock);
                        }
                        continue;
                    }

                    if (visible) {
                        renderTile(ctx, texture, tileX, tileY, tileWorldSize, cameraX, cameraZ,
                                blockScale, sw, sh);
                    }
                }
            }
        }
    }

    private void requestTile(TileKey key, double tileWorldSize, double pixelsPerBlock) {
        if (textures.containsKey(key) || emptyTiles.contains(key) || !loading.add(key)) return;
        int maxConcurrent = key.mode() == 1 ? MAX_CONCURRENT_COUNTRY_TILES : MAX_CONCURRENT_TILES;
        if (loading.size() > maxConcurrent) {
            loading.remove(key);
            return;
        }

        executor.execute(() -> {
            try {
                NativeImage image = loadPrebuiltTile(key);
                if (image == null && isKnownMissingPrebuiltTile(key)) {
                    emptyTiles.add(key);
                    return;
                }
                if (image == null) {
                    image = loadCachedTile(key);
                }
                if (image == null) {
                    image = rasterizeTile(key, tileWorldSize, pixelsPerBlock);
                }
                if (image == null) {
                    emptyTiles.add(key);
                } else {
                    if (key.mode() == 1) {
                        completedCountryTiles.add(new LoadedTile(key, image));
                    } else {
                        completedTiles.add(new LoadedTile(key, image));
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[TownyMap] Failed to rasterize border tile {}: {}", key, e.getMessage());
            } finally {
                loading.remove(key);
            }
        });
    }

    private boolean renderAtlasTileIfAvailable(GuiGraphicsExtractor ctx, TileKey key, double tileWorldSize,
                                               double cameraX, double cameraZ, double blockScale,
                                               int sw, int sh) {
        if (!canUsePrebuiltAtlas(key)) return false;
        AtlasKey atlasKey = atlasKey(key);
        Identifier atlas = atlasTextures.get(atlasKey);
        if (atlas == null) {
            if (!prebuiltAtlasManifest().contains(prebuiltAtlasRelativePath(atlasKey))) return false;
            requestAtlas(atlasKey);
            return false;
        }
        renderAtlasTile(ctx, atlas, key, tileWorldSize, cameraX, cameraZ, blockScale, sw, sh);
        return true;
    }

    private void requestAtlas(AtlasKey key) {
        if (atlasTextures.containsKey(key) || emptyAtlases.contains(key) || !atlasLoading.add(key)) return;
        executor.execute(() -> {
            try {
                NativeImage image = loadPrebuiltAtlas(key);
                if (image == null) {
                    emptyAtlases.add(key);
                } else {
                    if (key.mode() == 1) {
                        completedCountryAtlases.add(new LoadedAtlas(key, image));
                    } else {
                        completedAtlases.add(new LoadedAtlas(key, image));
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[TownyMap] Failed to load prebuilt border atlas {}: {}", key, e.getMessage());
            } finally {
                atlasLoading.remove(key);
            }
        });
    }

    private NativeImage rasterizeTile(TileKey key, double tileWorldSize, double pixelsPerBlock) {
        List<BorderLine> lines = tileLines(key, tileWorldSize);
        if (lines.isEmpty()) return null;

        double tileWorldX = key.x * tileWorldSize;
        double tileWorldZ = key.y * tileWorldSize;
        double left = tileWorldX - tileWorldSize * 0.02;
        double right = tileWorldX + tileWorldSize * 1.02;
        double top = tileWorldZ - tileWorldSize * 0.02;
        double bottom = tileWorldZ + tileWorldSize * 1.02;

        BufferedImage buffered = new BufferedImage(TILE_PIXELS, TILE_PIXELS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = buffered.createGraphics();
        boolean drew = false;
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setStroke(new BasicStroke(strokeWidth(key),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(key.mode == 1 ? new Color(255, 255, 255, 255) : new Color(255, 255, 255, 235));

            for (BorderLine line : lines) {
                drew |= drawBorderLine(g, line, tileWorldX, tileWorldZ, pixelsPerBlock);
            }
        } finally {
            g.dispose();
        }

        if (!drew) return null;

        saveCachedTile(key, buffered);
        NativeImage image = new NativeImage(TILE_PIXELS, TILE_PIXELS, false);
        for (int y = 0; y < TILE_PIXELS; y++) {
            for (int x = 0; x < TILE_PIXELS; x++) {
                image.setPixel(x, y, buffered.getRGB(x, y));
            }
        }
        return image;
    }

    private NativeImage loadCachedTile(TileKey key) {
        Path path = cachePath(key);
        if (!Files.isRegularFile(path)) return null;
        try (InputStream stream = Files.newInputStream(path)) {
            return NativeImage.read(stream);
        } catch (Exception e) {
            LOGGER.debug("[TownyMap] Ignoring unreadable cached border tile {}: {}", path, e.getMessage());
            return null;
        }
    }

    private NativeImage loadPrebuiltTile(TileKey key) {
        if (!canUsePrebuiltTile(key)) return null;
        String relative = prebuiltRelativePath(key);
        if (!prebuiltManifest().contains(relative)) return null;
        try (InputStream stream = BorderOverlayRenderer.class.getResourceAsStream(PREBUILT_ROOT + "/" + relative)) {
            if (stream == null) return null;
            return NativeImage.read(stream);
        } catch (Exception e) {
            LOGGER.debug("[TownyMap] Ignoring unreadable prebuilt border tile {}: {}", relative, e.getMessage());
            return null;
        }
    }

    private NativeImage loadPrebuiltAtlas(AtlasKey key) {
        String relative = prebuiltAtlasRelativePath(key);
        if (!prebuiltAtlasManifest().contains(relative)) return null;
        try (InputStream stream = BorderOverlayRenderer.class.getResourceAsStream(PREBUILT_ROOT + "/" + relative)) {
            if (stream == null) return null;
            return NativeImage.read(stream);
        } catch (Exception e) {
            LOGGER.debug("[TownyMap] Ignoring unreadable prebuilt border atlas {}: {}", relative, e.getMessage());
            return null;
        }
    }

    private boolean isKnownMissingPrebuiltTile(TileKey key) {
        return canUsePrebuiltTile(key) && prebuiltManifestLoaded && !prebuiltManifest().contains(prebuiltRelativePath(key));
    }

    private boolean canUsePrebuiltTile(TileKey key) {
        return config.squaremapMaxZoom == PREBUILT_SQUAREMAP_MAX_ZOOM
                && key.thicknessQ() == PREBUILT_THICKNESS_Q
                && key.zoom() >= 0
                && key.zoom() <= Math.max(0, PREBUILT_SQUAREMAP_MAX_ZOOM - MAX_BORDER_ZOOM_BELOW_MAX);
    }

    private String prebuiltRelativePath(TileKey key) {
        return key.mode() + "/" + key.zoom() + "/" + key.x() + "_" + key.y() + "_t" + key.thicknessQ() + ".png";
    }

    private boolean canUsePrebuiltAtlas(TileKey key) {
        return canUsePrebuiltTile(key) && key.zoom() <= MAX_ATLAS_ZOOM;
    }

    private AtlasKey atlasKey(TileKey key) {
        return new AtlasKey(key.mode(), key.zoom(), Math.floorDiv(key.x(), ATLAS_GRID),
                Math.floorDiv(key.y(), ATLAS_GRID), key.thicknessQ());
    }

    private String prebuiltAtlasRelativePath(AtlasKey key) {
        return "atlas/" + key.mode() + "/" + key.zoom() + "/"
                + key.pageX() + "_" + key.pageY() + "_t" + key.thicknessQ() + ".png";
    }

    private Set<String> prebuiltManifest() {
        Set<String> manifest = prebuiltTileManifest;
        if (manifest != null) return manifest;
        synchronized (this) {
            if (prebuiltTileManifest != null) return prebuiltTileManifest;
            HashSet<String> loaded = new HashSet<>();
            try (InputStream stream = BorderOverlayRenderer.class.getResourceAsStream(PREBUILT_ROOT + "/manifest.txt")) {
                if (stream != null) {
                    String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    for (String line : text.split("\\R")) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty()) loaded.add(trimmed);
                    }
                    LOGGER.info("[TownyMap] Loaded {} prebuilt border tile entries", loaded.size());
                } else {
                    LOGGER.info("[TownyMap] No bundled prebuilt border tile manifest found; using runtime border cache");
                }
            } catch (Exception e) {
                LOGGER.warn("[TownyMap] Failed to load prebuilt border tile manifest: {}", e.getMessage());
            }
            prebuiltTileManifest = Set.copyOf(loaded);
            prebuiltManifestLoaded = true;
            return prebuiltTileManifest;
        }
    }

    private Set<String> prebuiltAtlasManifest() {
        Set<String> manifest = prebuiltAtlasManifest;
        if (manifest != null) return manifest;
        synchronized (this) {
            if (prebuiltAtlasManifest != null) return prebuiltAtlasManifest;
            HashSet<String> loaded = new HashSet<>();
            try (InputStream stream = BorderOverlayRenderer.class.getResourceAsStream(PREBUILT_ROOT + "/atlas-manifest.txt")) {
                if (stream != null) {
                    String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    for (String line : text.split("\\R")) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty()) loaded.add(trimmed);
                    }
                    LOGGER.info("[TownyMap] Loaded {} prebuilt border atlas entries", loaded.size());
                }
            } catch (Exception e) {
                LOGGER.warn("[TownyMap] Failed to load prebuilt border atlas manifest: {}", e.getMessage());
            }
            prebuiltAtlasManifest = Set.copyOf(loaded);
            prebuiltAtlasManifestLoaded = true;
            return prebuiltAtlasManifest;
        }
    }

    private void saveCachedTile(TileKey key, BufferedImage image) {
        Path path = cachePath(key);
        try {
            Files.createDirectories(path.getParent());
            ImageIO.write(image, "png", path.toFile());
        } catch (IOException e) {
            LOGGER.debug("[TownyMap] Failed to cache border tile {}: {}", path, e.getMessage());
        }
    }

    private Path cachePath(TileKey key) {
        return diskCacheDir
                .resolve(Integer.toString(key.mode()))
                .resolve(Integer.toString(key.zoom()))
                .resolve(key.x() + "_" + key.y() + "_t" + key.thicknessQ() + ".png");
    }

    private static boolean drawBorderLine(Graphics2D g, BorderLine line,
                                          double tileWorldX, double tileWorldZ,
                                          double pixelsPerBlock) {
        Path2D.Double path = new Path2D.Double();
        path.moveTo((line.x[0] - tileWorldX) * pixelsPerBlock,
                (line.z[0] - tileWorldZ) * pixelsPerBlock);
        for (int i = 1; i < line.x.length; i++) {
            path.lineTo((line.x[i] - tileWorldX) * pixelsPerBlock,
                    (line.z[i] - tileWorldZ) * pixelsPerBlock);
        }
        g.draw(path);
        return true;
    }

    private void processCompletedTiles() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;

        processAtlasUploads(client, completedCountryAtlases, MAX_ATLAS_TEXTURE_UPLOADS_PER_FRAME);
        processTileUploads(client, completedCountryTiles, MAX_COUNTRY_TEXTURE_UPLOADS_PER_FRAME);
        processAtlasUploads(client, completedAtlases, MAX_ATLAS_TEXTURE_UPLOADS_PER_FRAME);
        processTileUploads(client, completedTiles, MAX_TEXTURE_UPLOADS_PER_FRAME);
    }

    private void processAtlasUploads(Minecraft client, Queue<LoadedAtlas> queue, int limit) {
        for (int i = 0; i < limit; i++) {
            LoadedAtlas loaded = queue.poll();
            if (loaded == null) return;
            try {
                AtlasKey key = loaded.key();
                if (atlasTextures.containsKey(key)) {
                    loaded.image().close();
                    continue;
                }
                Identifier id = Identifier.fromNamespaceAndPath("townymapaddon",
                        "border_atlas/" + key.mode() + "/" + key.zoom() + "/"
                                + key.pageX() + "_" + key.pageY() + "_t" + key.thicknessQ());
                BorderTileTexture texture =
                        new BorderTileTexture(() -> "TownyMap border atlas " + key, loaded.image());
                client.getTextureManager().register(id, texture);
                atlasTextures.put(key, id);
                evictOldTextures(client);
            } catch (Exception e) {
                loaded.image().close();
                LOGGER.warn("[TownyMap] Failed to upload border atlas {}: {}", loaded.key(), e.getMessage());
            }
        }
    }

    private void processTileUploads(Minecraft client, Queue<LoadedTile> queue, int limit) {
        for (int i = 0; i < limit; i++) {
            LoadedTile loaded = queue.poll();
            if (loaded == null) return;
            try {
                TileKey key = loaded.key();
                if (textures.containsKey(key)) {
                    loaded.image().close();
                    continue;
                }
                Identifier id = Identifier.fromNamespaceAndPath("townymapaddon",
                        "borders/" + key.mode() + "/" + key.zoom() + "/" + key.x() + "_" + key.y() + "_t" + key.thicknessQ());
                BorderTileTexture texture =
                        new BorderTileTexture(() -> "TownyMap border tile " + key, loaded.image());
                client.getTextureManager().register(id, texture);
                textures.put(key, id);
                evictOldTextures(client);
            } catch (Exception e) {
                loaded.image().close();
                LOGGER.warn("[TownyMap] Failed to upload border tile {}: {}", loaded.key(), e.getMessage());
            }
        }
    }

    private List<BorderLine> tileLines(TileKey key, double tileWorldSize) {
        BorderLineIndex index = lineIndexes.computeIfAbsent(key.mode, this::buildLineIndex);
        double tileWorldX = key.x * tileWorldSize;
        double tileWorldZ = key.y * tileWorldSize;
        double padding = tileWorldSize * 0.03;
        double left = tileWorldX - padding;
        double right = tileWorldX + tileWorldSize + padding;
        double top = tileWorldZ - padding;
        double bottom = tileWorldZ + tileWorldSize + padding;

        int minCellX = floorToCell(left);
        int maxCellX = floorToCell(right);
        int minCellY = floorToCell(top);
        int maxCellY = floorToCell(bottom);
        HashSet<BorderLine> candidates = new HashSet<>();

        for (int cellY = minCellY; cellY <= maxCellY; cellY++) {
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                List<BorderLine> lines = index.cells().get(packTile(cellX, cellY));
                if (lines != null) candidates.addAll(lines);
            }
        }

        if (candidates.isEmpty()) return List.of();
        ArrayList<BorderLine> lines = new ArrayList<>();
        for (BorderLine line : candidates) {
            if (line.intersects(left, right, top, bottom)) lines.add(line);
        }
        return lines;
    }

    private BorderLineIndex buildLineIndex(int mode) {
        long started = System.currentTimeMillis();
        Map<Long, ArrayList<BorderLine>> mutable = new HashMap<>();
        List<BorderLine> source = mode == 1 ? countries() : statesOnly();

        for (BorderLine line : source) {
            int minCellX = floorToCell(line.minX);
            int maxCellX = floorToCell(line.maxX);
            int minCellY = floorToCell(line.minZ);
            int maxCellY = floorToCell(line.maxZ);
            for (int cellY = minCellY; cellY <= maxCellY; cellY++) {
                for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                    mutable.computeIfAbsent(packTile(cellX, cellY), ignored -> new ArrayList<>()).add(line);
                }
            }
        }

        Map<Long, List<BorderLine>> index = new HashMap<>(mutable.size());
        for (Map.Entry<Long, ArrayList<BorderLine>> entry : mutable.entrySet()) {
            index.put(entry.getKey(), List.copyOf(entry.getValue()));
        }

        LOGGER.info("[TownyMap] Indexed {} border grid cells for mode {} in {}ms",
                index.size(), mode, System.currentTimeMillis() - started);
        return new BorderLineIndex(Map.copyOf(index));
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
        ctx.blit(PIPELINE, texture, x1 - 1, y1 - 1, 0.0F, 0.0F,
                drawW + 2, drawH + 2,
                TILE_PIXELS, TILE_PIXELS, TILE_PIXELS, TILE_PIXELS);
    }

    private void renderAtlasTile(GuiGraphicsExtractor ctx, Identifier texture, TileKey key,
                                 double tileWorldSize, double cameraX, double cameraZ,
                                 double blockScale, int sw, int sh) {
        double tileWorldX = key.x * tileWorldSize;
        double tileWorldZ = key.y * tileWorldSize;
        int x1 = toScreenX(tileWorldX, cameraX, blockScale, sw);
        int y1 = toScreenY(tileWorldZ, cameraZ, blockScale, sh);
        int x2 = toScreenX(tileWorldX + tileWorldSize, cameraX, blockScale, sw);
        int y2 = toScreenY(tileWorldZ + tileWorldSize, cameraZ, blockScale, sh);

        if (x2 <= 0 || x1 >= sw || y2 <= 0 || y1 >= sh) return;
        int drawW = Math.max(1, x2 - x1);
        int drawH = Math.max(1, y2 - y1);
        int u = Math.floorMod(key.x(), ATLAS_GRID) * TILE_PIXELS;
        int v = Math.floorMod(key.y(), ATLAS_GRID) * TILE_PIXELS;
        ctx.blit(PIPELINE, texture, x1 - 1, y1 - 1, (float) u, (float) v,
                drawW + 2, drawH + 2,
                TILE_PIXELS, TILE_PIXELS, ATLAS_PIXELS, ATLAS_PIXELS);
    }

    private List<BorderLine> countries() {
        if (countryLines == null) countryLines = load(COUNTRIES);
        return countryLines;
    }

    private List<BorderLine> states() {
        if (stateLines == null) stateLines = load(STATES);
        return stateLines;
    }

    private List<BorderLine> statesOnly() {
        if (stateOnlyLines != null) return stateOnlyLines;

        HashSet<LineFingerprint> countryFingerprints = new HashSet<>();
        for (BorderLine line : countries()) {
            countryFingerprints.add(line.fingerprint());
        }

        ArrayList<BorderLine> filtered = new ArrayList<>();
        for (BorderLine line : states()) {
            if (!countryFingerprints.contains(line.fingerprint())) filtered.add(line);
        }

        LOGGER.info("[TownyMap] Split {} state-only border lines from {} combined state/country lines",
                filtered.size(), states().size());
        stateOnlyLines = List.copyOf(filtered);
        return stateOnlyLines;
    }

    private List<BorderLine> load(String resource) {
        List<BorderLine> lines = new ArrayList<>();
        try (InputStream stream = BorderOverlayRenderer.class.getResourceAsStream(resource)) {
            if (stream == null) {
                LOGGER.warn("[TownyMap] Missing border resource {}", resource);
                return lines;
            }

            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement entry : root.asMap().values()) {
                if (!entry.isJsonObject()) continue;
                BorderLine line = parseLine(entry.getAsJsonObject());
                if (line != null) lines.add(line);
            }
            LOGGER.info("[TownyMap] Loaded {} border lines from {}", lines.size(), resource);
        } catch (Exception e) {
            LOGGER.warn("[TownyMap] Failed to load border resource {}: {}", resource, e.getMessage());
        }
        return List.copyOf(lines);
    }

    private BorderLine parseLine(JsonObject obj) {
        JsonArray xs = obj.getAsJsonArray("x");
        JsonArray zs = obj.getAsJsonArray("z");
        if (xs == null || zs == null) return null;
        int n = Math.min(xs.size(), zs.size());
        if (n < 2) return null;

        double[] x = new double[n];
        double[] z = new double[n];
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < n; i++) {
            x[i] = xs.get(i).getAsDouble();
            z[i] = zs.get(i).getAsDouble();
            minX = Math.min(minX, x[i]);
            maxX = Math.max(maxX, x[i]);
            minZ = Math.min(minZ, z[i]);
            maxZ = Math.max(maxZ, z[i]);
        }

        return new BorderLine(x, z, minX, maxX, minZ, maxZ,
                new LineFingerprint(n, java.util.Arrays.hashCode(x), java.util.Arrays.hashCode(z),
                        minX, maxX, minZ, maxZ));
    }

    private void evictOldTextures(Minecraft client) {
        while (textures.size() > MAX_TEXTURES) {
            Map.Entry<TileKey, Identifier> eldest = textures.entrySet().iterator().next();
            client.getTextureManager().release(eldest.getValue());
            textures.remove(eldest.getKey());
        }
        while (atlasTextures.size() > MAX_TEXTURES / 4) {
            Map.Entry<AtlasKey, Identifier> eldest = atlasTextures.entrySet().iterator().next();
            client.getTextureManager().release(eldest.getValue());
            atlasTextures.remove(eldest.getKey());
        }
    }

    boolean isLoading() {
        return !loading.isEmpty() || !completedTiles.isEmpty()
                || !completedCountryTiles.isEmpty()
                || !atlasLoading.isEmpty() || !completedAtlases.isEmpty()
                || !completedCountryAtlases.isEmpty();
    }

    private int chooseTileZoom(double blockScale) {
        int zoom = config.squaremapMaxZoom + (int) Math.ceil(log2(blockScale)) + QUALITY_ZOOM_BIAS;
        if (zoom < 0) return 0;
        int maxBorderZoom = Math.max(0, config.squaremapMaxZoom - MAX_BORDER_ZOOM_BELOW_MAX);
        return Math.min(maxBorderZoom, zoom);
    }

    private double pixelsPerBlock(int zoom) {
        return Math.pow(2.0, zoom - config.squaremapMaxZoom);
    }

    private float strokeWidth(TileKey key) {
        int maxBorderZoom = Math.max(0, config.squaremapMaxZoom - MAX_BORDER_ZOOM_BELOW_MAX);
        int levelsBelowMax = Math.max(0, maxBorderZoom - key.zoom());
        float base;
        if (levelsBelowMax == 0) {
            base = key.mode() == 1 ? 2.4F : 1.8F;
        } else if (levelsBelowMax == 1) {
            base = key.mode() == 1 ? 5.0F : 4.0F;
        } else {
            base = key.mode() == 1 ? 11.0F : 9.0F;
        }
        return base * key.thicknessMultiplier();
    }

    private static int floorToTile(double worldCoord, double tileWorldSize) {
        return (int) Math.floor(worldCoord / tileWorldSize);
    }

    private static int floorToCell(double worldCoord) {
        return (int) Math.floor(worldCoord / INDEX_CELL_BLOCKS);
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }

    private static int tileWorkerCount() {
        return Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
    }

    private static long packTile(int x, int y) {
        return ((long) x << 32) ^ (y & 0xFFFFFFFFL);
    }

    private static int toScreenX(double worldX, double camX, double scale, int sw) {
        return sw / 2 + (int) Math.round((worldX - camX) * scale);
    }

    private static int toScreenY(double worldZ, double camZ, double scale, int sh) {
        return sh / 2 + (int) Math.round((worldZ - camZ) * scale);
    }

    /**
     * Identifies a rasterised border tile.
     * {@code thicknessQ} = {@code round(borderThicknessMultiplier × 20)}, range 2–60.
     * Embedding it in the key means each distinct thickness has its own rasterised
     * tile in both the in-memory LRU cache and the disk cache — no manual invalidation needed.
     */
    private record TileKey(int mode, int zoom, int x, int y, int thicknessQ) {
        /** Recover the actual multiplier from the quantised value. */
        float thicknessMultiplier() { return thicknessQ / 20.0f; }
    }
    private record AtlasKey(int mode, int zoom, int pageX, int pageY, int thicknessQ) {}
    private record LoadedTile(TileKey key, NativeImage image) {}
    private record LoadedAtlas(AtlasKey key, NativeImage image) {}
    private record BorderLineIndex(Map<Long, List<BorderLine>> cells) {}

    private record BorderLine(double[] x, double[] z,
                              double minX, double maxX, double minZ, double maxZ,
                              LineFingerprint fingerprint) {
        boolean intersects(double left, double right, double top, double bottom) {
            return maxX >= left && minX <= right && maxZ >= top && minZ <= bottom;
        }
    }

    private record LineFingerprint(int points, int xHash, int zHash,
                                   double minX, double maxX, double minZ, double maxZ) {}

    private static final class BorderTileTexture extends DynamicTexture {
        private BorderTileTexture(java.util.function.Supplier<String> name, NativeImage image) {
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
