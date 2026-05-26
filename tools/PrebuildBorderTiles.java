import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PrebuildBorderTiles {
    private static final int TILE_PIXELS = 512;
    private static final int ATLAS_GRID = 4;
    private static final int ATLAS_PIXELS = TILE_PIXELS * ATLAS_GRID;
    private static final int SQUAREMAP_MAX_ZOOM = 5;
    private static final int MAX_BORDER_ZOOM = 3;
    private static final int MAX_ATLAS_ZOOM = MAX_BORDER_ZOOM;
    private static final int THICKNESS_Q = 10;
    private static final double INDEX_CELL_BLOCKS = 4096.0;
    private static final Pattern LINE = Pattern.compile(
            "\"x\"\\s*:\\s*\\[(.*?)]\\s*,\\s*\"z\"\\s*:\\s*\\[(.*?)]",
            Pattern.DOTALL);

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: PrebuildBorderTiles <countries.json> <states-and-countries.json> <outputDir>");
        }

        Path countriesPath = Path.of(args[0]);
        Path statesPath = Path.of(args[1]);
        Path outputDir = Path.of(args[2]);
        Path root = outputDir.resolve("assets/townymapaddon/prebuilt-borders");
        Files.createDirectories(root);

        List<BorderLine> countries = load(countriesPath);
        List<BorderLine> states = load(statesPath);
        List<BorderLine> stateOnly = stateOnly(countries, states);

        ArrayList<String> manifest = new ArrayList<>();
        ArrayList<String> atlasManifest = new ArrayList<>();
        prebuildLayer(1, countries, root, manifest);
        prebuildLayer(2, stateOnly, root, manifest);
        prebuildAtlases(root, manifest, atlasManifest);
        removeAtlasCoveredTiles(root, manifest);
        Files.write(root.resolve("manifest.txt"), manifest, StandardCharsets.UTF_8);
        Files.write(root.resolve("atlas-manifest.txt"), atlasManifest, StandardCharsets.UTF_8);
    }

    private static void prebuildLayer(int mode, List<BorderLine> lines, Path root,
                                      ArrayList<String> manifest) throws IOException {
        BorderLineIndex index = buildLineIndex(lines);
        for (int zoom = 0; zoom <= MAX_BORDER_ZOOM; zoom++) {
            double pixelsPerBlock = pixelsPerBlock(zoom);
            double tileWorldSize = TILE_PIXELS / pixelsPerBlock;
            Set<Long> tileKeys = candidateTiles(lines, tileWorldSize);
            int built = 0;
            for (long packed : tileKeys) {
                int tileX = (int) (packed >> 32);
                int tileY = (int) packed;
                if (rasterizeTile(mode, zoom, tileX, tileY, tileWorldSize, pixelsPerBlock, index, root)) {
                    manifest.add(mode + "/" + zoom + "/" + tileX + "_" + tileY + "_t" + THICKNESS_Q + ".png");
                    built++;
                }
            }
            System.out.println("Prebuilt border tiles mode=" + mode + " zoom=" + zoom + " count=" + built);
        }
    }

    private static boolean rasterizeTile(int mode, int zoom, int tileX, int tileY,
                                         double tileWorldSize, double pixelsPerBlock,
                                         BorderLineIndex index, Path root) throws IOException {
        List<BorderLine> lines = tileLines(index, tileX, tileY, tileWorldSize);
        if (lines.isEmpty()) return false;

        double tileWorldX = tileX * tileWorldSize;
        double tileWorldZ = tileY * tileWorldSize;
        BufferedImage image = new BufferedImage(TILE_PIXELS, TILE_PIXELS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        boolean drew = false;
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setStroke(new BasicStroke(strokeWidth(mode, zoom),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(mode == 1 ? new Color(255, 255, 255, 255) : new Color(255, 255, 255, 235));
            for (BorderLine line : lines) {
                drew |= drawBorderLine(g, line, tileWorldX, tileWorldZ, pixelsPerBlock);
            }
        } finally {
            g.dispose();
        }
        if (!drew) return false;

        Path path = root.resolve(mode + "/" + zoom + "/" + tileX + "_" + tileY + "_t" + THICKNESS_Q + ".png");
        Files.createDirectories(path.getParent());
        writeIndexedAlphaPng(image, path);
        return true;
    }

    private static void prebuildAtlases(Path root, List<String> tileManifest,
                                        ArrayList<String> atlasManifest) throws IOException {
        HashMap<AtlasKey, List<TileRef>> pages = new HashMap<>();
        for (String tile : tileManifest) {
            String[] parts = tile.split("/");
            if (parts.length != 3) continue;
            int mode = Integer.parseInt(parts[0]);
            int zoom = Integer.parseInt(parts[1]);
            if (zoom > MAX_ATLAS_ZOOM) continue;
            String file = parts[2].substring(0, parts[2].length() - ".png".length());
            String[] nameParts = file.split("_");
            if (nameParts.length < 3) continue;
            int tileX = Integer.parseInt(nameParts[0]);
            int tileY = Integer.parseInt(nameParts[1]);
            int pageX = Math.floorDiv(tileX, ATLAS_GRID);
            int pageY = Math.floorDiv(tileY, ATLAS_GRID);
            pages.computeIfAbsent(new AtlasKey(mode, zoom, pageX, pageY), ignored -> new ArrayList<>())
                    .add(new TileRef(tile, tileX, tileY));
        }

        for (Map.Entry<AtlasKey, List<TileRef>> entry : pages.entrySet()) {
            AtlasKey key = entry.getKey();
            BufferedImage atlas = new BufferedImage(ATLAS_PIXELS, ATLAS_PIXELS, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = atlas.createGraphics();
            try {
                for (TileRef tile : entry.getValue()) {
                    BufferedImage image = ImageIO.read(root.resolve(tile.path()).toFile());
                    if (image == null) continue;
                    int ox = Math.floorMod(tile.x(), ATLAS_GRID) * TILE_PIXELS;
                    int oy = Math.floorMod(tile.y(), ATLAS_GRID) * TILE_PIXELS;
                    g.drawImage(image, ox, oy, null);
                }
            } finally {
                g.dispose();
            }
            String relative = "atlas/" + key.mode() + "/" + key.zoom() + "/"
                    + key.pageX() + "_" + key.pageY() + "_t" + THICKNESS_Q + ".png";
            Path path = root.resolve(relative);
            Files.createDirectories(path.getParent());
            writeIndexedAlphaPng(atlas, path);
            atlasManifest.add(relative);
        }
        System.out.println("Prebuilt border atlas pages count=" + atlasManifest.size());
    }

    private static void writeIndexedAlphaPng(BufferedImage source, Path path) throws IOException {
        byte[] white = new byte[256];
        byte[] alpha = new byte[256];
        java.util.Arrays.fill(white, (byte) 255);
        for (int i = 0; i < alpha.length; i++) alpha[i] = (byte) i;

        IndexColorModel model = new IndexColorModel(8, 256, white, white, white, alpha);
        BufferedImage indexed = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_BYTE_INDEXED, model);
        WritableRaster raster = indexed.getRaster();

        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                raster.setSample(x, y, 0, source.getRGB(x, y) >>> 24);
            }
        }

        ImageIO.write(indexed, "png", path.toFile());
    }

    private static void removeAtlasCoveredTiles(Path root, ArrayList<String> manifest) throws IOException {
        ArrayList<String> retained = new ArrayList<>();
        int removed = 0;
        for (String tile : manifest) {
            String[] parts = tile.split("/");
            if (parts.length == 3 && Integer.parseInt(parts[1]) <= MAX_ATLAS_ZOOM) {
                Files.deleteIfExists(root.resolve(tile));
                removed++;
            } else {
                retained.add(tile);
            }
        }
        manifest.clear();
        manifest.addAll(retained);
        System.out.println("Removed atlas-covered individual border tiles count=" + removed);
    }

    private static List<BorderLine> tileLines(BorderLineIndex index, int tileX, int tileY, double tileWorldSize) {
        double tileWorldX = tileX * tileWorldSize;
        double tileWorldZ = tileY * tileWorldSize;
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
                List<BorderLine> lines = index.cells().get(pack(cellX, cellY));
                if (lines != null) candidates.addAll(lines);
            }
        }
        if (candidates.isEmpty()) return List.of();
        ArrayList<BorderLine> result = new ArrayList<>();
        for (BorderLine line : candidates) {
            if (line.intersects(left, right, top, bottom)) result.add(line);
        }
        return result;
    }

    private static Set<Long> candidateTiles(List<BorderLine> lines, double tileWorldSize) {
        HashSet<Long> tiles = new HashSet<>();
        for (BorderLine line : lines) {
            int minX = floorToTile(line.minX(), tileWorldSize) - 1;
            int maxX = floorToTile(line.maxX(), tileWorldSize) + 1;
            int minY = floorToTile(line.minZ(), tileWorldSize) - 1;
            int maxY = floorToTile(line.maxZ(), tileWorldSize) + 1;
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    tiles.add(pack(x, y));
                }
            }
        }
        return tiles;
    }

    private static BorderLineIndex buildLineIndex(List<BorderLine> lines) {
        Map<Long, ArrayList<BorderLine>> mutable = new HashMap<>();
        for (BorderLine line : lines) {
            int minCellX = floorToCell(line.minX());
            int maxCellX = floorToCell(line.maxX());
            int minCellY = floorToCell(line.minZ());
            int maxCellY = floorToCell(line.maxZ());
            for (int cellY = minCellY; cellY <= maxCellY; cellY++) {
                for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                    mutable.computeIfAbsent(pack(cellX, cellY), ignored -> new ArrayList<>()).add(line);
                }
            }
        }
        Map<Long, List<BorderLine>> index = new HashMap<>();
        for (Map.Entry<Long, ArrayList<BorderLine>> entry : mutable.entrySet()) {
            index.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return new BorderLineIndex(Map.copyOf(index));
    }

    private static List<BorderLine> stateOnly(List<BorderLine> countries, List<BorderLine> states) {
        HashSet<LineFingerprint> countryFingerprints = new HashSet<>();
        for (BorderLine line : countries) countryFingerprints.add(line.fingerprint());
        ArrayList<BorderLine> result = new ArrayList<>();
        for (BorderLine line : states) {
            if (!countryFingerprints.contains(line.fingerprint())) result.add(line);
        }
        return List.copyOf(result);
    }

    private static List<BorderLine> load(Path path) throws IOException {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        Matcher matcher = LINE.matcher(json);
        ArrayList<BorderLine> lines = new ArrayList<>();
        while (matcher.find()) {
            double[] x = doubles(matcher.group(1));
            double[] z = doubles(matcher.group(2));
            int n = Math.min(x.length, z.length);
            if (n < 2) continue;
            if (x.length != n) x = java.util.Arrays.copyOf(x, n);
            if (z.length != n) z = java.util.Arrays.copyOf(z, n);
            lines.add(line(x, z));
        }
        return List.copyOf(lines);
    }

    private static BorderLine line(double[] x, double[] z) {
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < x.length; i++) {
            minX = Math.min(minX, x[i]);
            maxX = Math.max(maxX, x[i]);
            minZ = Math.min(minZ, z[i]);
            maxZ = Math.max(maxZ, z[i]);
        }
        return new BorderLine(x, z, minX, maxX, minZ, maxZ,
                new LineFingerprint(x.length, java.util.Arrays.hashCode(x), java.util.Arrays.hashCode(z),
                        minX, maxX, minZ, maxZ));
    }

    private static double[] doubles(String csv) {
        String[] parts = csv.split(",");
        double[] values = new double[parts.length];
        int count = 0;
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) values[count++] = Double.parseDouble(trimmed);
        }
        return count == values.length ? values : java.util.Arrays.copyOf(values, count);
    }

    private static boolean drawBorderLine(Graphics2D g, BorderLine line,
                                          double tileWorldX, double tileWorldZ,
                                          double pixelsPerBlock) {
        Path2D.Double path = new Path2D.Double();
        path.moveTo((line.x()[0] - tileWorldX) * pixelsPerBlock,
                (line.z()[0] - tileWorldZ) * pixelsPerBlock);
        for (int i = 1; i < line.x().length; i++) {
            path.lineTo((line.x()[i] - tileWorldX) * pixelsPerBlock,
                    (line.z()[i] - tileWorldZ) * pixelsPerBlock);
        }
        g.draw(path);
        return true;
    }

    private static double pixelsPerBlock(int zoom) {
        return Math.pow(2.0, zoom - SQUAREMAP_MAX_ZOOM);
    }

    private static float strokeWidth(int mode, int zoom) {
        int levelsBelowMax = Math.max(0, MAX_BORDER_ZOOM - zoom);
        float base;
        if (levelsBelowMax == 0) {
            base = mode == 1 ? 2.4F : 1.8F;
        } else if (levelsBelowMax == 1) {
            base = mode == 1 ? 5.0F : 4.0F;
        } else {
            base = mode == 1 ? 11.0F : 9.0F;
        }
        return base * (THICKNESS_Q / 20.0F);
    }

    private static int floorToTile(double worldCoord, double tileWorldSize) {
        return (int) Math.floor(worldCoord / tileWorldSize);
    }

    private static int floorToCell(double worldCoord) {
        return (int) Math.floor(worldCoord / INDEX_CELL_BLOCKS);
    }

    private static long pack(int x, int y) {
        return ((long) x << 32) ^ (y & 0xFFFFFFFFL);
    }

    private record BorderLineIndex(Map<Long, List<BorderLine>> cells) {}
    private record AtlasKey(int mode, int zoom, int pageX, int pageY) {}
    private record TileRef(String path, int x, int y) {}
    private record BorderLine(double[] x, double[] z,
                              double minX, double maxX, double minZ, double maxZ,
                              LineFingerprint fingerprint) {
        boolean intersects(double left, double right, double top, double bottom) {
            return maxX >= left && minX <= right && maxZ >= top && minZ <= bottom;
        }
    }
    private record LineFingerprint(int points, int xHash, int zHash,
                                   double minX, double maxX, double minZ, double maxZ) {}
}
