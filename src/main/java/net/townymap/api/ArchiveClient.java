package net.townymap.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.townymap.model.EarthMcNationData;
import net.townymap.model.TownData;
import net.townymap.model.TownPopupData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Loads historical EarthMC town claims from the Internet Archive's Wayback Machine.
 *
 * <p>EarthMC serves a public squaremap markers file of every town's claim polygon; Wayback snapshots it.
 * This asks Wayback for the snapshot nearest a chosen date and parses it into the same {@link TownData}
 * the live map uses, so archive mode reuses the whole renderer. Scoped to the current world (Terra
 * Nostra), whose markers file is exactly today's format — so no schema conversion is needed.
 *
 * <p>A desktop mod has no CORS restriction, so it hits web.archive.org directly with no proxy. Wayback
 * preserves the original gzip encoding, which is decompressed here.
 */
public final class ArchiveClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("TownyMapAddon");
    private static final String UA = "JR1258/EarthMC-Map-Addon archive";
    private static final String MARKERS_URL_FORMAT = "https://map.earthmc.net/tiles/%s/markers.json";

    /** The first date Terra Nostra's map existed — earlier queries have no snapshot to find. */
    public static final int MIN_DATE = 20260417;

    /**
     * The first date the Moon existed. It launched on 2026-08-30, and Wayback holds nothing for it before
     * the first capture was requested by hand — nothing crawls that URL on its own.
     */
    public static final int MIN_DATE_MOON = 20260830;

    /** Earliest date worth asking about for a world, so the search bar can reject the rest up front. */
    public static int minDateFor(String worldKey) {
        return net.townymap.TownyMapMod.WORLD_MOON.equals(worldKey) ? MIN_DATE_MOON : MIN_DATE;
    }

    /**
     * A resolved snapshot: the towns, their historical popup info (mayor/residents/founded/etc. as they
     * were on that date, keyed by lower-case town name), the nations that existed on that date (synthesized
     * from the towns' "Member/Capital of" tooltips, keyed by lower-case nation name), and the actual date
     * Wayback returned.
     */
    public record Snapshot(List<TownData> towns, java.util.Map<String, TownPopupData> details,
                           java.util.Map<String, ArchiveTown> fullDetails,
                           java.util.Map<String, EarthMcNationData> nations, int actualDate) {}

    /**
     * Everything the Wayback popup HTML actually carries for a town on the snapshot date — used by the
     * expanded panel. Fields the archive does NOT record (chunks, bank, open/for-sale, spawn, discord…) are
     * deliberately absent rather than shown as zeroes.
     */
    public record ArchiveTown(String name, String nation, boolean capital, String mayor, String founded,
                              boolean pvp, boolean isPublic, int residentCount, int chunks,
                              List<String> residents, List<String> councillors, String board) {}

    // ALWAYS (not NORMAL): Wayback redirects the partial-date URL to the exact snapshot and can downgrade
    // HTTPS→HTTP on the way, which NORMAL refuses to follow — leaving the fetch stuck on the 302.
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    /**
     * Fetches the archived town claims nearest {@code yyyymmdd}. Blocking — call off the render thread.
     * Returns null if nothing could be fetched or parsed.
     *
     * <p>One request: the partial 8-digit date in the Wayback URL makes Wayback itself redirect to the
     * nearest capture, so there is no separate "resolve" step to rate-limit. (The availability and CDX
     * lookup APIs were both throttling in normal use — this is how the extension does it.) The actual
     * captured date is read back from the final redirected URL.
     */
    public Snapshot fetchSnapshot(int yyyymmdd) {
        return fetchSnapshot(yyyymmdd, net.townymap.TownyMapMod.WORLD_OVERWORLD);
    }

    /** As above, for one squaremap world. The Moon has its own captures and its own history. */
    public Snapshot fetchSnapshot(int yyyymmdd, String worldKey) {
        String markers = String.format(MARKERS_URL_FORMAT, worldKey);
        String url = "https://web.archive.org/web/" + yyyymmdd + "id_/" + markers;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(40))
                    .header("User-Agent", UA)
                    .header("Accept-Encoding", "gzip")
                    .GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                LOGGER.warn("[TownyMap] Archive GET {} → HTTP {}", url, resp.statusCode());
                return null;
            }
            String json = decode(resp);
            if (json == null || json.isBlank()) return null;

            List<TownData> towns = new ArrayList<>();
            java.util.Map<String, TownPopupData> details = new java.util.HashMap<>();
            java.util.Map<String, ArchiveTown> fullDetails = new java.util.HashMap<>();
            java.util.Map<String, EarthMcNationData> nations = new java.util.HashMap<>();
            parseSquaremap(JsonParser.parseString(json), towns, details, fullDetails, nations);
            int actual = dateFromWaybackUri(resp.uri().toString(), yyyymmdd);
            LOGGER.info("[TownyMap] Archive {} → snapshot {} , {} towns, {} nations",
                    yyyymmdd, actual, towns.size(), nations.size());
            if (towns.isEmpty()) return null;
            return new Snapshot(towns, details, fullDetails, nations, actual);
        } catch (Exception e) {
            LOGGER.warn("[TownyMap] Archive fetch failed for {}: {}", yyyymmdd, e.toString());
            return null;
        }
    }

    /** The captured yyyymmdd from the final Wayback URL (.../web/<14 digits>id_/...), or the query date. */
    private static int dateFromWaybackUri(String uri, int fallback) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/web/(\\d{8})\\d{0,6}").matcher(uri);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    /** Decompresses gzip (Wayback keeps the original Content-Encoding) and returns text. */
    private static String decode(HttpResponse<byte[]> resp) throws Exception {
        byte[] bytes = resp.body();
        boolean gzip = (bytes.length > 1 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B)
                || "gzip".equalsIgnoreCase(resp.headers().firstValue("content-encoding").orElse(""));
        if (gzip) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * squaremap schema. Only the "towny" layer is read — a snapshot also carries a "folia-regions" layer
     * of region polygons and chunk-border polylines, which are not towns and must not be drawn. Each town's
     * popup HTML is parsed into a historical {@link TownPopupData} so clicking it shows that date's info.
     */
    private static void parseSquaremap(JsonElement root, List<TownData> towns,
                                       java.util.Map<String, TownPopupData> details,
                                       java.util.Map<String, ArchiveTown> fullDetails,
                                       java.util.Map<String, EarthMcNationData> nations) {
        if (!root.isJsonArray()) return;
        for (JsonElement layerEl : root.getAsJsonArray()) {
            if (!layerEl.isJsonObject()) continue;
            JsonObject layer = layerEl.getAsJsonObject();
            if (!"towny".equalsIgnoreCase(str(layer, "id"))) continue;   // towns only, no regions/borders
            JsonElement markersEl = layer.get("markers");
            if (markersEl == null || !markersEl.isJsonArray()) continue;
            for (JsonElement mEl : markersEl.getAsJsonArray()) {
                if (!mEl.isJsonObject()) continue;
                JsonObject m = mEl.getAsJsonObject();
                if (!"polygon".equalsIgnoreCase(str(m, "type"))) continue;
                String tooltip = str(m, "tooltip"), popup = str(m, "popup");
                String name = markerName(tooltip, popup);
                int rgb = TownData.parseHexColor(firstNonBlank(str(m, "color"), str(m, "fillColor")), 0x3BFF3B);
                int fillRgb = TownData.parseHexColor(firstNonBlank(str(m, "fillColor"), str(m, "color")), rgb);
                List<int[][]> rings = new ArrayList<>();
                JsonElement pts = m.get("points");
                if (pts != null && pts.isJsonArray()) {
                    for (JsonElement grp : pts.getAsJsonArray()) {
                        if (!grp.isJsonArray()) continue;
                        for (JsonElement ringEl : grp.getAsJsonArray()) {
                            if (ringEl.isJsonArray()) addRing(rings, ringEl.getAsJsonArray());
                        }
                    }
                }
                if (rings.isEmpty()) continue;
                towns.add(new TownData(name, rgb, fillRgb, rings));
                String key = name.toLowerCase(java.util.Locale.ROOT);
                ArchiveTown at = parseArchiveTown(name, tooltip, popup, chunkCountFromRings(rings));
                fullDetails.putIfAbsent(key, at);
                details.putIfAbsent(key, toPopupData(at));
            }
        }
        buildNations(fullDetails.values(), nations);
    }

    /**
     * Synthesizes each nation from its member towns — capital, town count, and the residents/chunks summed
     * across them (a resident belongs to exactly one town, so the sum is the nation's resident count). Only
     * nations that actually existed on the snapshot date appear, with that date's totals.
     */
    private static void buildNations(java.util.Collection<ArchiveTown> towns,
                                     java.util.Map<String, EarthMcNationData> nations) {
        java.util.Map<String, int[]> agg = new java.util.HashMap<>();       // key → {towns, residents, chunks}
        java.util.Map<String, String> display = new java.util.HashMap<>();  // key → original-case name
        java.util.Map<String, String> capital = new java.util.HashMap<>();  // key → capital town name
        for (ArchiveTown t : towns) {
            if (t.nation() == null || t.nation().isBlank()) continue;
            String key = t.nation().toLowerCase(java.util.Locale.ROOT);
            display.putIfAbsent(key, t.nation());
            int[] a = agg.computeIfAbsent(key, k -> new int[3]);
            a[0]++;
            a[1] += t.residentCount();
            a[2] += t.chunks();
            if (t.capital()) capital.put(key, t.name());
        }
        for (java.util.Map.Entry<String, int[]> e : agg.entrySet()) {
            int[] a = e.getValue();
            nations.put(e.getKey(), archiveNation(display.get(e.getKey()),
                    capital.getOrDefault(e.getKey(), ""), a[0], a[1], a[2]));
        }
    }

    /** A nation record synthesized from the snapshot: name, capital, and the town/resident/chunk totals for
     *  that date. The rest (king, bank, allies, spawn, bonus…) isn't in the markers, so it stays empty. */
    private static EarthMcNationData archiveNation(String name, String capitalTown,
                                                   int townCount, int residentCount, int chunkCount) {
        return new EarthMcNationData(name, "", "", "", "", capitalTown, "",
                townCount, residentCount, chunkCount, 0, 0, 0, 0.0, false, false, false, false, 0, 0, -1, -1, 0L);
    }

    private static final String DEFAULT_BOARD = "/town set board [msg]";

    /**
     * The town's chunk count derived from its claim polygon. squaremap merges a town's 16×16-block chunk
     * claims into outline rings, so the polygon area is exactly chunks × 256. Rings are wound so holes cancel
     * out (outer positive, holes negative in the signed sum), giving the net claimed area.
     */
    private static int chunkCountFromRings(List<int[][]> rings) {
        double twiceArea = 0;
        for (int[][] ring : rings) {
            int n = ring.length;
            if (n < 3) continue;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                if (ring[i].length < 2 || ring[j].length < 2) continue;
                twiceArea += (double) ring[j][0] * ring[i][1] - (double) ring[i][0] * ring[j][1];
            }
        }
        return (int) Math.round(Math.abs(twiceArea) / 2.0 / 256.0);
    }

    /** Parses everything the archived popup HTML records for a town — the info as it was on that date. */
    private static ArchiveTown parseArchiveTown(String name, String tooltip, String popup, int chunks) {
        String mayor = group(popup, "Mayor:\\s*<b[^>]*>(.*?)</b>");
        String founded = group(popup, "Founded:\\s*<b[^>]*>(.*?)</b>");
        boolean pvp = "true".equalsIgnoreCase(group(popup, "PVP:\\s*<b[^>]*>(.*?)</b>"));
        boolean isPublic = "true".equalsIgnoreCase(group(popup, "Public:\\s*<b[^>]*>(.*?)</b>"));
        int residentCount = parseIntOr(group(popup, "Residents:\\s*<b[^>]*>(\\d+)</b>"), 0);
        List<String> residents = splitNames(group(popup, "</summary>(.*?)</details>"));
        List<String> councillors = splitNames(group(popup, "Councillors:\\s*<b[^>]*>(.*?)</b>"));

        // The <i>…</i> line is the town board; the default placeholder means no board is set.
        String board = group(popup, "<i>(.*?)</i>");
        if (DEFAULT_BOARD.equalsIgnoreCase(board)) board = "";

        String nation = "";
        boolean capital = false;
        java.util.regex.Matcher nm = java.util.regex.Pattern
                .compile("\\(\\s*(Member|Capital) of\\s+(.*?)\\)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(tooltip == null ? "" : tooltip);
        if (nm.find()) { capital = "capital".equalsIgnoreCase(nm.group(1)); nation = nm.group(2).trim(); }

        return new ArchiveTown(name, nation, capital, mayor, founded, pvp, isPublic,
                residentCount, chunks, residents, councillors, board);
    }

    /** The compact {@link TownPopupData} the right-click popup uses; unknown fields stay at their sentinels
     *  (maxChunks stays -1 — the archive knows the claimed count, not the town's claim limit). */
    private static TownPopupData toPopupData(ArchiveTown a) {
        return new TownPopupData(a.name(), a.nation(), "", a.board(), a.mayor(), a.chunks(), a.founded(), a.pvp(),
                a.isPublic(), false, false, false, false, !a.nation().isBlank(), a.residentCount(), 0, -1, -1);
    }

    /** Splits a comma-separated name list, dropping the "None" placeholder and blanks. */
    private static List<String> splitNames(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String part : csv.split(",")) {
            String n = part.replaceAll("<[^>]+>", "").trim();
            if (!n.isEmpty() && !"None".equalsIgnoreCase(n)) out.add(n);
        }
        return out;
    }

    private static String group(String html, String regex) {
        if (html == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL)
                .matcher(html);
        return m.find() ? m.group(1).replaceAll("<[^>]+>", "").trim() : "";
    }

    private static int parseIntOr(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }

    private static void addRing(List<int[][]> rings, JsonArray ringArr) {
        List<int[]> pts = new ArrayList<>();
        for (JsonElement el : ringArr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (o.has("x") && o.has("z")) {
                pts.add(new int[]{intOf(o, "x", 0), intOf(o, "z", 0)});
            }
        }
        if (pts.size() >= 3) rings.add(pts.toArray(new int[pts.size()][]));
    }

    /** Null-safe int read: has() is true for a JSON null, and getAsInt() on that throws. */
    private static int intOf(JsonObject obj, String key, int fallback) {
        try {
            JsonElement el = obj.get(key);
            return el != null && el.isJsonPrimitive() ? el.getAsInt() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : "";
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : (b != null ? b : "");
    }

    private static String markerName(String tooltip, String popup) {
        String n = boldText(tooltip);
        if (n == null) n = boldText(popup);
        return n != null ? n : "?";
    }

    private static String boldText(String html) {
        if (html == null || html.isBlank()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<b>(.*?)</b>", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) {
            String s = m.group(1).replaceAll("<[^>]+>", "").trim();
            if (!s.isBlank()) return s;
        }
        String stripped = html.replaceAll("<[^>]+>", "").trim();
        return stripped.isBlank() ? null : stripped.split("\\n")[0].trim();
    }
}
