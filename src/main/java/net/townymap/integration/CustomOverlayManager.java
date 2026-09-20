package net.townymap.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Util;
import org.joml.Matrix3x2fStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Renders user-supplied custom map overlays from JSON files dropped into
 *   {@code <config>/townymapaddon/overlays/}
 * onto Xaero's WorldMap: coloured polylines (routes/lines) and labelled point
 * markers (stations/points).
 *
 * The loader is format-agnostic and accepts several common shapes, including the
 * EarthMC Ice Highways Map format (https://xilef2211.github.io/ice-highways-map/).
 * Nothing is bundled — the overlay is off by default and only renders files the
 * user adds. Supported shapes (all keys optional / best-effort):
 *
 * <ul>
 *   <li>Ice-Highways style:
 *       {@code { "lines": { "Company": { "Line": { "color": "rrggbb",
 *       "branches": { "B": { "vertices": [[x,z],...] } } } } },
 *       "stations": [ { "name", "x", "z" } ] }}</li>
 *   <li>Generic object:
 *       {@code { "lines"|"routes": [ { "color", "points"|"vertices"|"coordinates": [[x,z],...] } ],
 *       "markers"|"points"|"stations": [ { "name", "x", "z" } ] }}</li>
 *   <li>GeoJSON {@code FeatureCollection} with {@code LineString}/{@code MultiLineString}/{@code Point}
 *       geometries (coordinates read as {@code [x, z]}); {@code properties.color} / {@code properties.name} honoured.</li>
 *   <li>A bare array of any of the above, or a bare array of {@code [x,z]} pairs (one polyline).</li>
 * </ul>
 */
public final class CustomOverlayManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("TownyMapAddon");
    private static final int DEFAULT_RGB = 0x55D6FF;          // soft cyan fallback
    private static final double MARKER_MIN_SCALE = 0.04;      // dots appear when zoomed in
    private static final double MARKER_LABEL_MIN_SCALE = 0.14;
    private static final int MARKER_COLOR = 0xFFFFFFFF;
    private static final int MARKER_OUTLINE = 0xFF101418;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TownyMap-CustomOverlays");
        t.setDaemon(true);
        return t;
    });

    private static volatile Data data = Data.EMPTY;
    private static volatile boolean loadScheduled = false;

    private CustomOverlayManager() {}

    // ── Data model ────────────────────────────────────────────────────────────

    private record Line(int[] x, int[] z, int argb, int minX, int maxX, int minZ, int maxZ) {}
    private record Marker(String name, int x, int z) {}
    private record Data(List<Line> lines, List<Marker> markers) {
        static final Data EMPTY = new Data(List.of(), List.of());
    }

    // ── Public API ──────────────────────────────────────────────────────────--

    public static Path folder() {
        return FabricLoader.getInstance().getConfigDir().resolve("townymapaddon").resolve("overlays");
    }

    public static boolean hasData() {
        Data d = data;
        return !d.lines().isEmpty() || !d.markers().isEmpty();
    }

    /** Creates the folder (with a README) if needed and opens it in the file browser. */
    public static void openFolder() {
        try {
            ensureFolder();
        } catch (IOException e) {
            LOGGER.warn("[TownyMap] Could not create overlays folder", e);
        }
        com.mojang.blaze3d.Blaze3D.openUri(folder().toUri());
    }

    /** Re-reads every {@code *.json} in the folder on a background thread. */
    public static void reload() {
        if (loadScheduled) return;
        loadScheduled = true;
        EXEC.execute(() -> {
            try {
                data = loadFromDisk();
                LOGGER.info("[TownyMap] Custom overlays: {} lines, {} markers",
                        data.lines().size(), data.markers().size());
            } catch (Exception e) {
                LOGGER.warn("[TownyMap] Failed to load custom overlays", e);
            } finally {
                loadScheduled = false;
            }
        });
    }

    // ── Loading / parsing ─────────────────────────────────────────────────────

    private static void ensureFolder() throws IOException {
        Path dir = folder();
        Files.createDirectories(dir);
        Path readme = dir.resolve("README.txt");
        if (!Files.exists(readme)) {
            Files.writeString(readme,
                    "Drop custom map-overlay JSON files here (any number of *.json files), then use\n"
                    + "\"Reload Overlays\" in the mod settings. Routes draw as coloured lines and points\n"
                    + "as labelled dots on the world map.\n\n"
                    + "Supported shapes (all best-effort):\n"
                    + "  - EarthMC Ice Highways Map files (github.com/xilef2211/ice-highways-map,\n"
                    + "    e.g. aurora/highways.json)\n"
                    + "  - Generic: { \"lines\": [ { \"color\": \"rrggbb\", \"points\": [[x,z],...] } ],\n"
                    + "               \"markers\": [ { \"name\": \"...\", \"x\": 0, \"z\": 0 } ] }\n"
                    + "  - GeoJSON FeatureCollection (LineString / MultiLineString / Point;\n"
                    + "    coordinates read as [x, z]).\n");
        }
    }

    private static Data loadFromDisk() throws IOException {
        Path dir = folder();
        if (!Files.isDirectory(dir)) {
            ensureFolder();
            return Data.EMPTY;
        }
        ArrayList<Line> lines = new ArrayList<>();
        ArrayList<Marker> markers = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                if (!file.toString().toLowerCase().endsWith(".json")) continue;
                try {
                    JsonElement root = JsonParser.parseString(Files.readString(file));
                    parseAny(root, lines, markers);
                } catch (Exception e) {
                    LOGGER.warn("[TownyMap] Skipping malformed overlay file {}: {}",
                            file.getFileName(), e.getMessage());
                }
            }
        }
        return new Data(List.copyOf(lines), List.copyOf(markers));
    }

    /** Dispatches an arbitrary JSON value to the matching shape parser. */
    private static void parseAny(JsonElement root, List<Line> lines, List<Marker> markers) {
        if (root == null) return;
        if (root.isJsonObject()) {
            parseObject(root.getAsJsonObject(), lines, markers);
        } else if (root.isJsonArray()) {
            JsonArray arr = root.getAsJsonArray();
            if (looksLikeCoordList(arr)) {
                addPolyline(arr, DEFAULT_RGB | 0xFF000000, lines);    // a bare polyline
            } else {
                for (JsonElement el : arr) parseAny(el, lines, markers);   // array of features/lines/markers
            }
        }
    }

    private static void parseObject(JsonObject obj, List<Line> lines, List<Marker> markers) {
        // GeoJSON
        String type = optString(obj, "type");
        if (obj.has("features") && obj.get("features").isJsonArray()) {
            for (JsonElement f : obj.getAsJsonArray("features")) parseAny(f, lines, markers);
        }
        if (("Feature".equals(type) || obj.has("geometry")) && obj.has("geometry")
                && obj.get("geometry").isJsonObject()) {
            int argb = 0xFF000000 | colorOf(obj.has("properties") && obj.get("properties").isJsonObject()
                    ? obj.getAsJsonObject("properties") : obj);
            parseGeometry(obj.getAsJsonObject("geometry"), argb, lines, markers,
                    nameOf(obj.has("properties") && obj.get("properties").isJsonObject()
                            ? obj.getAsJsonObject("properties") : obj));
        }
        if (obj.has("geometries") && obj.get("geometries").isJsonArray()) {
            int argb = 0xFF000000 | colorOf(obj);
            for (JsonElement g : obj.getAsJsonArray("geometries")) {
                if (g.isJsonObject()) parseGeometry(g.getAsJsonObject(), argb, lines, markers, "");
            }
        }

        // Lines / routes — object form (nested company→line→branches) or array form
        for (String key : new String[]{"lines", "routes"}) {
            if (!obj.has(key)) continue;
            JsonElement val = obj.get(key);
            if (val.isJsonObject()) {
                parseNestedLines(val.getAsJsonObject(), lines);          // ice-highway style
            } else if (val.isJsonArray()) {
                for (JsonElement el : val.getAsJsonArray()) {
                    if (el.isJsonObject()) parseLineObject(el.getAsJsonObject(), DEFAULT_RGB, lines);
                    else if (el.isJsonArray() && looksLikeCoordList(el.getAsJsonArray())) {
                        addPolyline(el.getAsJsonArray(), 0xFF000000 | DEFAULT_RGB, lines);
                    }
                }
            }
        }

        // Markers / stations / points
        for (String key : new String[]{"markers", "stations", "points", "nodes"}) {
            if (obj.has(key) && obj.get(key).isJsonArray()) {
                for (JsonElement el : obj.getAsJsonArray(key)) {
                    if (!el.isJsonObject()) continue;
                    Marker m = markerOf(el.getAsJsonObject());
                    if (m != null) markers.add(m);
                }
            }
        }
    }

    /** Ice-highway style: {@code { company: { line: { color, branches: { b: { vertices } } } } }}. */
    private static void parseNestedLines(JsonObject companies, List<Line> lines) {
        for (var company : companies.entrySet()) {
            if (!company.getValue().isJsonObject()) continue;
            for (var line : company.getValue().getAsJsonObject().entrySet()) {
                if (line.getValue().isJsonObject()) {
                    parseLineObject(line.getValue().getAsJsonObject(), DEFAULT_RGB, lines);
                }
            }
        }
    }

    /** A single line/route object: a {@code branches} map of vertices, or a direct point list. */
    private static void parseLineObject(JsonObject lineObj, int fallbackRgb, List<Line> lines) {
        int argb = 0xFF000000 | colorOf(lineObj, fallbackRgb);
        if (lineObj.has("branches") && lineObj.get("branches").isJsonObject()) {
            for (var branch : lineObj.getAsJsonObject("branches").entrySet()) {
                if (!branch.getValue().isJsonObject()) continue;
                JsonArray verts = pointArray(branch.getValue().getAsJsonObject());
                if (verts != null) addPolyline(verts, argb, lines);
            }
            return;
        }
        JsonArray verts = pointArray(lineObj);
        if (verts != null) addPolyline(verts, argb, lines);
    }

    private static void parseGeometry(JsonObject geom, int argb, List<Line> lines, List<Marker> markers, String name) {
        String gtype = optString(geom, "type");
        if (!geom.has("coordinates")) return;
        JsonElement coords = geom.get("coordinates");
        switch (gtype) {
            case "LineString" -> {
                if (coords.isJsonArray()) addPolyline(coords.getAsJsonArray(), argb, lines);
            }
            case "MultiLineString", "Polygon" -> {
                if (coords.isJsonArray()) {
                    for (JsonElement part : coords.getAsJsonArray()) {
                        if (part.isJsonArray()) addPolyline(part.getAsJsonArray(), argb, lines);
                    }
                }
            }
            case "Point" -> {
                int[] p = point(coords);
                if (p != null) markers.add(new Marker(name, p[0], p[1]));
            }
            default -> { /* unsupported geometry type */ }
        }
    }

    /** Finds the first present point-list field on an object. */
    private static JsonArray pointArray(JsonObject obj) {
        for (String key : new String[]{"vertices", "points", "coordinates", "path", "coords"}) {
            if (obj.has(key) && obj.get(key).isJsonArray()) return obj.getAsJsonArray(key);
        }
        return null;
    }

    private static void addPolyline(JsonArray pts, int argb, List<Line> lines) {
        int n = pts.size();
        if (n < 2) return;
        int[] x = new int[n], z = new int[n];
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int m = 0;
        for (JsonElement el : pts) {
            int[] p = point(el);
            if (p == null) continue;
            x[m] = p[0]; z[m] = p[1]; m++;
            if (p[0] < minX) minX = p[0];
            if (p[0] > maxX) maxX = p[0];
            if (p[1] < minZ) minZ = p[1];
            if (p[1] > maxZ) maxZ = p[1];
        }
        if (m < 2) return;
        if (m < n) { x = java.util.Arrays.copyOf(x, m); z = java.util.Arrays.copyOf(z, m); }
        lines.add(new Line(x, z, argb, minX, maxX, minZ, maxZ));
    }

    /** Extracts an [x,z] pair from a {@code [x,z]} array or a {@code {x,z}} object. */
    private static int[] point(JsonElement el) {
        if (el.isJsonArray()) {
            JsonArray a = el.getAsJsonArray();
            if (a.size() < 2 || !a.get(0).isJsonPrimitive() || !a.get(1).isJsonPrimitive()) return null;
            return new int[]{(int) Math.round(a.get(0).getAsDouble()), (int) Math.round(a.get(1).getAsDouble())};
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (isNum(o, "x") && isNum(o, "z")) {
                return new int[]{(int) Math.round(o.get("x").getAsDouble()),
                                 (int) Math.round(o.get("z").getAsDouble())};
            }
        }
        return null;
    }

    /** True when the key holds an actual number — has() alone is true for a JSON null, which then throws. */
    private static boolean isNum(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber();
    }

    private static Marker markerOf(JsonObject o) {
        if (isNum(o, "x") && isNum(o, "z")) {
            return new Marker(nameOf(o),
                    (int) Math.round(o.get("x").getAsDouble()), (int) Math.round(o.get("z").getAsDouble()));
        }
        if (o.has("geometry") && o.get("geometry").isJsonObject()) {
            JsonObject g = o.getAsJsonObject("geometry");
            if ("Point".equals(optString(g, "type")) && g.has("coordinates")) {
                int[] p = point(g.get("coordinates"));
                if (p != null) return new Marker(nameOf(o), p[0], p[1]);
            }
        }
        return null;
    }

    private static boolean looksLikeCoordList(JsonArray arr) {
        if (arr.isEmpty()) return false;
        JsonElement first = arr.get(0);
        if (!first.isJsonArray()) return false;
        JsonArray inner = first.getAsJsonArray();
        return inner.size() >= 2 && inner.get(0).isJsonPrimitive() && ((JsonPrimitive) inner.get(0)).isNumber();
    }

    private static int colorOf(JsonObject obj) { return colorOf(obj, DEFAULT_RGB); }

    private static int colorOf(JsonObject obj, int fallbackRgb) {
        for (String key : new String[]{"color", "colour", "stroke"}) {
            if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) continue;
            JsonPrimitive p = obj.getAsJsonPrimitive(key);
            if (p.isNumber()) return p.getAsInt() & 0xFFFFFF;
            String h = p.getAsString().trim();
            if (h.startsWith("#")) h = h.substring(1);
            try { return (int) (Long.parseLong(h, 16) & 0xFFFFFF); } catch (NumberFormatException ignored) {}
        }
        return fallbackRgb;
    }

    private static String nameOf(JsonObject obj) {
        for (String key : new String[]{"name", "label", "title", "id"}) {
            if (obj.has(key) && obj.get(key).isJsonPrimitive()) return obj.getAsJsonPrimitive(key).getAsString();
        }
        return "";
    }

    private static String optString(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : "";
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    public static void render(GuiGraphicsExtractor ctx, double cameraX, double cameraZ,
                              double blockScale, int sw, int sh) {
        Data d = data;
        if (blockScale <= 0 || d.lines().isEmpty() && d.markers().isEmpty()) return;

        double worldLeft   = cameraX - sw / 2.0 / blockScale;
        double worldRight  = cameraX + sw / 2.0 / blockScale;
        double worldTop    = cameraZ - sh / 2.0 / blockScale;
        double worldBottom = cameraZ + sh / 2.0 / blockScale;

        for (Line line : d.lines()) {
            if (line.maxX() < worldLeft || line.minX() > worldRight
                    || line.maxZ() < worldTop || line.minZ() > worldBottom) continue;
            drawLine(ctx, line, cameraX, cameraZ, blockScale, sw, sh);
        }

        if (blockScale >= MARKER_MIN_SCALE && !d.markers().isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            Font tr = mc == null ? null : mc.font;
            boolean labels = blockScale >= MARKER_LABEL_MIN_SCALE && tr != null;
            for (Marker s : d.markers()) {
                if (s.x() < worldLeft || s.x() > worldRight || s.z() < worldTop || s.z() > worldBottom) continue;
                int sx = sw / 2 + (int) Math.round((s.x() - cameraX) * blockScale);
                int sy = sh / 2 + (int) Math.round((s.z() - cameraZ) * blockScale);
                ctx.fill(sx - 2, sy - 2, sx + 2, sy + 2, MARKER_OUTLINE);
                ctx.fill(sx - 1, sy - 1, sx + 1, sy + 1, MARKER_COLOR);
                if (labels && !s.name().isBlank()) {
                    ctx.text(tr, s.name(), sx + 4, sy - 4, 0xFFBFE9FF, true);
                }
            }
        }
    }

    private static void drawLine(GuiGraphicsExtractor ctx, Line line, double cameraX, double cameraZ,
                                 double blockScale, int sw, int sh) {
        int[] wx = line.x(), wz = line.z();
        int prevX = sw / 2 + (int) Math.round((wx[0] - cameraX) * blockScale);
        int prevY = sh / 2 + (int) Math.round((wz[0] - cameraZ) * blockScale);
        int argb = line.argb();
        for (int i = 1; i < wx.length; i++) {
            int cx = sw / 2 + (int) Math.round((wx[i] - cameraX) * blockScale);
            int cy = sh / 2 + (int) Math.round((wz[i] - cameraZ) * blockScale);
            boolean offscreen = (prevX < 0 && cx < 0) || (prevX > sw && cx > sw)
                             || (prevY < 0 && cy < 0) || (prevY > sh && cy > sh);
            if (!offscreen) drawSegment(ctx, prevX, prevY, cx, cy, argb);
            prevX = cx;
            prevY = cy;
        }
    }

    private static void drawSegment(GuiGraphicsExtractor ctx, int x1, int y1, int x2, int y2, int argb) {
        if (y1 == y2) {
            if (x1 != x2) ctx.fill(Math.min(x1, x2), y1, Math.max(x1, x2) + 1, y1 + 1, argb);
            return;
        }
        if (x1 == x2) {
            ctx.fill(x1, Math.min(y1, y2), x1 + 1, Math.max(y1, y2) + 1, argb);
            return;
        }
        double dx = x2 - x1, dy = y2 - y1;
        int length = (int) Math.ceil(Math.hypot(dx, dy));
        Matrix3x2fStack m = ctx.pose();
        m.pushMatrix();
        try {
            m.translate(x1 + 0.5f, y1 + 0.5f);
            m.rotate((float) Math.atan2(dy, dx));
            ctx.fill(0, 0, length + 1, 1, argb);
        } finally {
            m.popMatrix();
        }
    }
}
