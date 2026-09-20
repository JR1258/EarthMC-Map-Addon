package net.townymap.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.townymap.TownyMapConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the real-world country and state borders over the map, as vector polylines.
 *
 * <p>This used to bake the borders into raster tiles — a 26 MB prebuilt PNG atlas plus a runtime rasteriser,
 * texture cache and disk cache — and cap them two zoom levels below the map's maximum. Zooming past that cap
 * stretched a 0.25 px/block texture across the screen, which is what made borders look blurry up close.
 *
 * <p>Lines are geometry, so drawing them directly is sharper at every zoom (there is no fixed resolution to
 * outgrow, and no seams between tiles) and drops all of that machinery. The geometry itself is a compact
 * binary blob built by tools/EncodeBorders — see {@link #decode} for the format.
 */
final class BorderOverlayRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("TownyMapAddon");
    /** Compact border geometry, produced from the source JSONs by tools/EncodeBorders at build time. */
    private static final String BORDERS_BIN = "/assets/townymapaddon/borders/borders.bin";
    private static final byte LAYER_COUNTRY = 1;
    private static final byte LAYER_STATE_ONLY = 2;
    /** Points closer together than this on screen add no visible detail, so they are folded away. */
    private static final double MIN_SEGMENT_PX = 1.0;
    /** Above this many blocks per pixel the view holds too much geometry to draw a quad at a time. */
    private static final double VECTOR_MAX_BLOCKS_PER_PIXEL = 4.0;
    /** Snapshot covers a bit more than the screen, so ordinary panning doesn't force a rebuild. */
    private static final double SNAPSHOT_MARGIN = 1.35;
    private static final int MAX_SNAPSHOT_PX = 4096;
    /** Cap on supersampling; past 2x the extra pixels are not visible but the memory is real. */
    private static final double MAX_SNAPSHOT_DENSITY = 2.0;
    /** One reusable worker: panning can retrigger rebuilds, and a thread per rebuild would churn. */
    private static final java.util.concurrent.ExecutorService SNAPSHOT_WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "TownyMap-BorderSnapshot");
                t.setDaemon(true);
                return t;
            });
    private static final com.mojang.renderpearl.api.pipeline.RenderPipeline SNAPSHOT_PIPELINE =
            net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED;

    private final TownyMapConfig config;
    // Populated off-thread at startup: decoding ~244k points takes a moment and must not stall the first frame.
    private volatile List<BorderLine> countryLines = List.of();
    private volatile List<BorderLine> stateOnlyLines = List.of();
    private volatile boolean loading = true;
    private volatile Snapshot snapshot;
    private volatile PendingSnapshot pending;
    private volatile boolean snapshotBuilding;
    private int snapshotSerial;

    BorderOverlayRenderer(TownyMapConfig config) {
        this.config = config;
        Thread loader = new Thread(this::load, "TownyMap-BorderLoad");
        loader.setDaemon(true);
        loader.start();
    }

    /** True while the border geometry is still being decoded, so the toggle can show a loading hint. */
    boolean isLoading() {
        return loading;
    }

    void render(GuiGraphicsExtractor ctx, double cameraX, double cameraZ, double blockScale,
                int sw, int sh, double worldLeft, double worldRight,
                double worldTop, double worldBottom) {
        int mode = config.borderOverlayMode;
        if (mode == 0 || blockScale <= 0) return;

        float thickness = Math.max(0.1f, Math.min(3.0f, config.borderThicknessMultiplier));
        // Widths are screen pixels now (they used to be tile pixels at a fixed tile resolution), so borders
        // keep the same visual weight at every zoom instead of thickening as a tile is stretched.
        float countryW = Math.max(1.0f, 2.4f * thickness);
        float stateW = Math.max(1.0f, 1.8f * thickness);

        // Zoomed out, the visible geometry runs to ~96k segments — far too many to emit one quad at a time,
        // which is what made the map freeze. Past the threshold the same lines are rasterised ONCE into a
        // screen-resolution snapshot and blitted as a single quad per frame, so cost stops tracking detail.
        // Zoomed in there are only a few hundred segments, so vectors stay direct, crisp and pan instantly.
        if (1.0 / blockScale > VECTOR_MAX_BLOCKS_PER_PIXEL) {
            renderSnapshot(ctx, cameraX, cameraZ, blockScale, sw, sh, mode, thickness, countryW, stateW);
            return;
        }
        drawVector(ctx, cameraX, cameraZ, blockScale, sw, sh,
                worldLeft, worldRight, worldTop, worldBottom, mode, countryW, stateW);
    }

    private void drawVector(GuiGraphicsExtractor ctx, double cameraX, double cameraZ, double blockScale,
                            int sw, int sh, double worldLeft, double worldRight,
                            double worldTop, double worldBottom, int mode,
                            float countryW, float stateW) {
        // States first, so country outlines draw over them where the two run together — the old layer order.
        if (mode == 2) {
            drawLines(ctx, stateOnlyLines, cameraX, cameraZ, blockScale, sw, sh,
                    worldLeft, worldRight, worldTop, worldBottom, 0xEBFFFFFF, stateW);
        }
        drawLines(ctx, countryLines, cameraX, cameraZ, blockScale, sw, sh,
                worldLeft, worldRight, worldTop, worldBottom, 0xFFFFFFFF, countryW);
    }

    /**
     * Projects and draws one layer.
     *
     * <p>Two cheap filters carry the cost at world zoom, where every line is technically on screen: a
     * bounding-box reject per line, then screen-space decimation that folds away points landing on the pixel
     * just drawn. Detail therefore costs nothing until you are zoomed in far enough to actually see it.
     */
    private void drawLines(GuiGraphicsExtractor ctx, List<BorderLine> lines,
                           double cameraX, double cameraZ, double blockScale, int sw, int sh,
                           double worldLeft, double worldRight, double worldTop, double worldBottom,
                           int color, float width) {
        final double minStepSq = MIN_SEGMENT_PX * MIN_SEGMENT_PX;
        final double halfW = sw / 2.0;
        final double halfH = sh / 2.0;

        for (BorderLine line : lines) {
            if (!line.intersects(worldLeft, worldRight, worldTop, worldBottom)) continue;

            double[] xs = line.x();
            double[] zs = line.z();
            double px = (xs[0] - cameraX) * blockScale + halfW;
            double py = (zs[0] - cameraZ) * blockScale + halfH;
            double tailX = px;
            double tailY = py;
            boolean pendingTail = false;   // last point was folded away; keep it if the line ends there

            for (int i = 1; i < xs.length; i++) {
                double cx = (xs[i] - cameraX) * blockScale + halfW;
                double cy = (zs[i] - cameraZ) * blockScale + halfH;
                double dx = cx - px;
                double dy = cy - py;
                if (dx * dx + dy * dy < minStepSq && i < xs.length - 1) {
                    tailX = cx;
                    tailY = cy;
                    pendingTail = true;
                    continue;
                }
                if (onScreen(px, py, cx, cy, sw, sh)) {
                    drawSegment(ctx, px, py, cx, cy, width, color);
                }
                px = cx;
                py = cy;
                pendingTail = false;
            }
            if (pendingTail && onScreen(px, py, tailX, tailY, sw, sh)) {
                drawSegment(ctx, px, py, tailX, tailY, width, color);
            }
        }
    }

    /** Cheap reject for a segment that cannot cross the viewport (both ends off the same edge). */
    private static boolean onScreen(double x1, double y1, double x2, double y2, int sw, int sh) {
        return !((x1 < 0 && x2 < 0) || (x1 > sw && x2 > sw)
                || (y1 < 0 && y2 < 0) || (y1 > sh && y2 > sh));
    }

    /** One segment as a rotated, centred quad — smooth at any angle, unlike an axis-aligned fill. */
    private static void drawSegment(GuiGraphicsExtractor ctx, double x1, double y1, double x2, double y2,
                                    float width, int color) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double length = Math.hypot(dx, dy);
        if (length < 0.01) return;
        org.joml.Matrix3x2fStack m = ctx.pose();
        m.pushMatrix();
        try {
            m.translate((float) x1, (float) y1);
            m.rotate((float) Math.atan2(dy, dx));
            m.translate(0f, -width / 2f);          // centre the quad on the line
            m.scale((float) length, width);
            ctx.fill(0, 0, 1, 1, color);           // unit quad stretched to the exact sub-pixel size
        } finally {
            m.popMatrix();
        }
    }

    // ── Far-zoom snapshot ────────────────────────────────────────────────────

    /**
     * Draws the cached snapshot, rebuilding it when the view has moved off it.
     *
     * <p>The snapshot is rasterised over a slightly larger area than the screen, so ordinary panning just
     * shifts where it is blitted instead of forcing a rebuild. A rebuild runs off-thread and the previous
     * snapshot keeps drawing meanwhile, so the map never stalls waiting for one.
     */
    private void renderSnapshot(GuiGraphicsExtractor ctx, double cameraX, double cameraZ, double blockScale,
                                int sw, int sh, int mode, float thickness,
                                float countryW, float stateW) {
        uploadPendingSnapshot();

        Snapshot snap = snapshot;
        boolean usable = snap != null && snap.matches(blockScale, mode, thickness, sw, sh)
                && snap.covers(cameraX, cameraZ, sw, sh, blockScale);
        if (!usable && !snapshotBuilding) {
            requestSnapshot(cameraX, cameraZ, blockScale, sw, sh, mode, thickness, countryW, stateW);
        }
        if (snap == null) return;   // nothing to show yet; the first build lands within a frame or two

        // Position by world anchor so a stale snapshot still lines up while its replacement renders. If the
        // zoom changed since it was taken, scale it — briefly soft, but never a gap and never a stall.
        double x = (snap.worldX() - cameraX) * blockScale + sw / 2.0;
        double y = (snap.worldZ() - cameraZ) * blockScale + sh / 2.0;
        // Draw at the world footprint's current on-screen size: the texture holds more pixels than that
        // (density supersampling), so it downsamples rather than stretches.
        int drawW = (int) Math.round(snap.worldW() * blockScale);
        int drawH = (int) Math.round(snap.worldH() * blockScale);
        ctx.blit(SNAPSHOT_PIPELINE, snap.texture(),
                (int) Math.round(x), (int) Math.round(y), 0.0F, 0.0F,
                drawW, drawH, snap.width(), snap.height(), snap.width(), snap.height());
    }

    private void requestSnapshot(double cameraX, double cameraZ, double blockScale,
                                 int sw, int sh, int mode, float thickness,
                                 float countryW, float stateW) {
        // Rasterise at the framebuffer's real pixel density, not in GUI pixels. At GUI scale 2 a GUI-sized
        // texture is magnified 2x on screen, which is exactly the softness the old atlas had; the vector path
        // avoided it because the GPU rasterises quads at physical resolution. Trade margin away before
        // density, so the picture stays sharp even when the texture cap bites.
        double density = renderDensity();
        double margin = SNAPSHOT_MARGIN;
        while (margin > 1.0
                && (sw * margin * density > MAX_SNAPSHOT_PX || sh * margin * density > MAX_SNAPSHOT_PX)) {
            margin = Math.max(1.0, margin - 0.05);
        }
        while (density > 1.0
                && (sw * margin * density > MAX_SNAPSHOT_PX || sh * margin * density > MAX_SNAPSHOT_PX)) {
            density = Math.max(1.0, density - 0.25);
        }
        // GUI-space footprint (what it covers on screen) and the pixel buffer backing it.
        double guiW = Math.max(sw, sw * margin);
        double guiH = Math.max(sh, sh * margin);
        int width = Math.max(1, Math.min(MAX_SNAPSHOT_PX, (int) Math.ceil(guiW * density)));
        int height = Math.max(1, Math.min(MAX_SNAPSHOT_PX, (int) Math.ceil(guiH * density)));
        double worldW = guiW / blockScale;
        double worldH = guiH / blockScale;
        double originX = cameraX - worldW / 2.0;
        double originZ = cameraZ - worldH / 2.0;
        // One world block spans this many texture pixels; strokes scale to match so weight is unchanged.
        double pixelsPerBlock = blockScale * density;
        float countryStroke = (float) (countryW * density);
        float stateStroke = (float) (stateW * density);

        snapshotBuilding = true;
        List<BorderLine> countries = countryLines;
        List<BorderLine> states = mode == 2 ? stateOnlyLines : List.of();
        SNAPSHOT_WORKER.execute(() -> {
            try {
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                        width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = img.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL,
                        java.awt.RenderingHints.VALUE_STROKE_PURE);
                if (!states.isEmpty()) {
                    rasterise(g, states, originX, originZ, pixelsPerBlock, stateStroke,
                            new java.awt.Color(255, 255, 255, 235));
                }
                rasterise(g, countries, originX, originZ, pixelsPerBlock, countryStroke, java.awt.Color.WHITE);
                g.dispose();
                pending = new PendingSnapshot(img, width, height, worldW, worldH, originX, originZ,
                        blockScale, mode, thickness, sw, sh);
            } catch (Exception e) {
                LOGGER.warn("[TownyMap] Border snapshot failed: {}", e.toString());
                snapshotBuilding = false;
            }
        });
    }

    private static void rasterise(java.awt.Graphics2D g, List<BorderLine> lines,
                                  double originX, double originZ, double pixelsPerBlock,
                                  float width, java.awt.Color color) {
        g.setColor(color);
        g.setStroke(new java.awt.BasicStroke(width, java.awt.BasicStroke.CAP_ROUND,
                java.awt.BasicStroke.JOIN_ROUND));
        java.awt.geom.Path2D.Double path = new java.awt.geom.Path2D.Double();
        for (BorderLine line : lines) {
            double[] xs = line.x();
            double[] zs = line.z();
            double px = (xs[0] - originX) * pixelsPerBlock;
            double py = (zs[0] - originZ) * pixelsPerBlock;
            path.moveTo(px, py);
            boolean any = false;
            for (int i = 1; i < xs.length; i++) {
                double cx = (xs[i] - originX) * pixelsPerBlock;
                double cy = (zs[i] - originZ) * pixelsPerBlock;
                double dx = cx - px;
                double dy = cy - py;
                if (dx * dx + dy * dy < 1.0 && i < xs.length - 1) continue;   // sub-pixel: nothing to add
                path.lineTo(cx, cy);
                px = cx;
                py = cy;
                any = true;
            }
            if (!any) path.lineTo(px, py);   // degenerate line: a round cap dot still marks it
        }
        g.draw(path);   // one path for the whole layer, so AWT strokes it in a single pass
    }

    /** Moves a finished snapshot onto the GPU. Must run on the render thread, so it happens here. */
    private void uploadPendingSnapshot() {
        PendingSnapshot p = pending;
        if (p == null) return;
        pending = null;
        try {
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            if (client == null) return;
            com.mojang.blaze3d.platform.NativeImage image =
                    new com.mojang.blaze3d.platform.NativeImage(p.width(), p.height(), false);
            int[] argb = p.image().getRGB(0, 0, p.width(), p.height(), null, 0, p.width());
            for (int y = 0, i = 0; y < p.height(); y++) {
                for (int x = 0; x < p.width(); x++, i++) image.setPixel(x, y, argb[i]);
            }
            net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.fromNamespaceAndPath(
                    "townymapaddon", "border-snapshot/" + (snapshotSerial++));
            client.getTextureManager().register(id, new SnapshotTexture(() -> "border snapshot", image));

            Snapshot old = snapshot;
            snapshot = new Snapshot(id, p.width(), p.height(), p.worldW(), p.worldH(),
                    p.worldX(), p.worldZ(), p.blockScale(), p.mode(), p.thickness(),
                    p.screenW(), p.screenH());
            if (old != null) client.getTextureManager().release(old.texture());
        } catch (Exception e) {
            LOGGER.warn("[TownyMap] Border snapshot upload failed: {}", e.toString());
        } finally {
            snapshotBuilding = false;
        }
    }

    private record PendingSnapshot(java.awt.image.BufferedImage image, int width, int height,
                                   double worldW, double worldH,
                                   double worldX, double worldZ, double blockScale,
                                   int mode, float thickness, int screenW, int screenH) {}

    /** A rasterised view of the borders, anchored at a world position so it can be blitted while stale. */
    private record Snapshot(net.minecraft.resources.Identifier texture, int width, int height,
                            double worldW, double worldH,
                            double worldX, double worldZ, double blockScale,
                            int mode, float thickness, int screenW, int screenH) {
        boolean matches(double scale, int m, float t, int sw, int sh) {
            return m == mode && Math.abs(t - thickness) < 1e-4
                    && Math.abs(scale - blockScale) / blockScale < 0.01
                    && sw == screenW && sh == screenH;
        }

        /** True while the visible area still sits inside the rasterised margin. */
        boolean covers(double camX, double camZ, int sw, int sh, double scale) {
            double left = camX - sw / 2.0 / scale;
            double top = camZ - sh / 2.0 / scale;
            double right = camX + sw / 2.0 / scale;
            double bottom = camZ + sh / 2.0 / scale;
            return left >= worldX && top >= worldZ
                    && right <= worldX + worldW && bottom <= worldZ + worldH;
        }
    }

    // ── Geometry loading ─────────────────────────────────────────────────────

    private void load() {
        deleteStaleTileCache();

        List<BorderLine> countries = new ArrayList<>();
        List<BorderLine> stateOnly = new ArrayList<>();
        try (InputStream stream = BorderOverlayRenderer.class.getResourceAsStream(BORDERS_BIN)) {
            if (stream == null) {
                LOGGER.warn("[TownyMap] Missing border resource {}", BORDERS_BIN);
            } else {
                decode(stream.readAllBytes(), countries, stateOnly);
                LOGGER.info("[TownyMap] Loaded {} country and {} state-only border lines",
                        countries.size(), stateOnly.size());
            }
        } catch (Exception e) {
            LOGGER.warn("[TownyMap] Failed to load border resource {}: {}", BORDERS_BIN, e.toString());
        }
        countryLines = List.copyOf(countries);
        stateOnlyLines = List.copyOf(stateOnly);
        loading = false;
    }

    /**
     * Reads the packed blob written by tools/EncodeBorders:
     * <pre>
     *   "TMBL", version, scale, lineCount,
     *   then per line: pointCount, layer byte, and pointCount (dx, dz) zigzag varints
     *   delta-encoded against the previous point.
     * </pre>
     * Each line carries its own layer, so the country/state split is already decided — the old JSON path
     * had to re-derive it on every startup by fingerprinting all 21k polylines.
     */
    private static void decode(byte[] data, List<BorderLine> countries, List<BorderLine> stateOnly) {
        if (data.length < 6 || data[0] != 'T' || data[1] != 'M' || data[2] != 'B' || data[3] != 'L') {
            throw new IllegalStateException("bad border blob header");
        }
        int version = data[4] & 0xFF;
        if (version != 1) throw new IllegalStateException("unsupported border blob version " + version);
        double scale = data[5] & 0xFF;

        int[] pos = {6};
        int lineCount = (int) readVarInt(data, pos);
        for (int l = 0; l < lineCount; l++) {
            int n = (int) readVarInt(data, pos);
            int layer = data[pos[0]++] & 0xFF;
            double[] x = new double[n];
            double[] z = new double[n];
            double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
            long px = 0, pz = 0;
            for (int i = 0; i < n; i++) {
                px += readVarInt(data, pos);
                pz += readVarInt(data, pos);
                x[i] = px / scale;
                z[i] = pz / scale;
                if (x[i] < minX) minX = x[i];
                if (x[i] > maxX) maxX = x[i];
                if (z[i] < minZ) minZ = z[i];
                if (z[i] > maxZ) maxZ = z[i];
            }
            BorderLine line = new BorderLine(x, z, minX, maxX, minZ, maxZ);
            if (layer == LAYER_COUNTRY) countries.add(line);
            else if (layer == LAYER_STATE_ONLY) stateOnly.add(line);
        }
    }

    /**
     * Removes the border tile cache the old raster renderer wrote into the config folder.
     *
     * <p>That renderer cached every rasterised tile to disk, which grew to ~100 MB for an active player. The
     * vector path never reads or writes it, so on first run after updating it is pure dead weight — this is
     * the mod's own generated cache, safe to drop and never regenerated.
     */
    private static void deleteStaleTileCache() {
        try {
            java.nio.file.Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                    .resolve("townymapaddon").resolve("border-cache");
            if (!java.nio.file.Files.isDirectory(dir)) return;

            long[] stats = new long[2];   // {files, bytes}
            try (var walk = java.nio.file.Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        if (java.nio.file.Files.isRegularFile(p)) {
                            stats[0]++;
                            stats[1] += java.nio.file.Files.size(p);
                        }
                        java.nio.file.Files.deleteIfExists(p);
                    } catch (Exception ignored) { /* leave anything we can't remove */ }
                });
            }
            if (stats[0] > 0) {
                LOGGER.info("[TownyMap] Removed {} stale border tile cache files ({} MB) — "
                        + "borders are drawn as vectors now", stats[0], stats[1] / (1024 * 1024));
            }
        } catch (Exception e) {
            LOGGER.debug("[TownyMap] Could not clean the old border cache: {}", e.toString());
        }
    }

    private static long readVarInt(byte[] data, int[] pos) {
        long raw = 0;
        int shift = 0;
        while (true) {
            int b = data[pos[0]++] & 0xFF;
            raw |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return (raw >>> 1) ^ -(raw & 1);   // undo zigzag
    }

    /** Smooth sampling, so the supersampled snapshot resolves cleanly instead of aliasing on downscale. */
    private static final class SnapshotTexture extends net.minecraft.client.renderer.texture.DynamicTexture {
        private SnapshotTexture(java.util.function.Supplier<String> name,
                                com.mojang.blaze3d.platform.NativeImage image) {
            super(name, image);
            this.sampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getSampler(
                    com.mojang.renderpearl.api.textures.AddressMode.CLAMP_TO_EDGE,
                    com.mojang.renderpearl.api.textures.AddressMode.CLAMP_TO_EDGE,
                    com.mojang.renderpearl.api.textures.FilterMode.LINEAR,
                    com.mojang.renderpearl.api.textures.FilterMode.LINEAR,
                    false);
        }
    }

    /** Physical pixels per GUI pixel to rasterise at — the window's scale factor, capped. */
    private static double renderDensity() {
        net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
        if (client == null || client.getWindow() == null) return 1.0;
        return Math.min(MAX_SNAPSHOT_DENSITY, Math.max(1.0, client.getWindow().getGuiScale()));
    }

    private record BorderLine(double[] x, double[] z,
                              double minX, double maxX, double minZ, double maxZ) {
        boolean intersects(double left, double right, double top, double bottom) {
            return maxX >= left && minX <= right && maxZ >= top && minZ <= bottom;
        }
    }
}
