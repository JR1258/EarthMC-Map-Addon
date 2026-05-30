package net.townymap.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.townymap.TownyMapMod;
import net.townymap.TownyMapConfig;
import net.townymap.model.PlayerHistoryEntry;
import net.townymap.model.PlayerMarker;
import net.townymap.model.TownData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches town polygon data and online-player positions from EarthMC's squaremap instance.
 */
public class SquaremapApiClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("TownyMapAddon");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type PLAYER_HISTORY_TYPE = new TypeToken<Map<String, PlayerHistoryEntry>>() {}.getType();
    private static final String PLAYER_HISTORY_FILE = "townymapaddon-player-history.json";
    private static final int MAX_PLAYER_HISTORY = 800;
    private static final long TOWN_MARKER_REFRESH_MS = 60_000L;
    private static final long PLAYER_HISTORY_SAVE_DELAY_MS = 2_000L;
    private static final Pattern BOLD_TEXT =
            Pattern.compile("(?is)<b[^>]*>(.*?)</b>");
    private static final Pattern HTML_TAG =
            Pattern.compile("(?is)<[^>]+>");

    private final TownyMapConfig config;
    private final HttpClient http;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService fetchExecutor;

    private volatile List<TownData>     towns        = List.of();
    private volatile List<PlayerMarker> players      = List.of();
    private volatile Map<String, PlayerHistoryEntry> playerHistory = Map.of();

    private final AtomicBoolean markerFetchRunning = new AtomicBoolean(false);
    private final AtomicBoolean playerFetchRunning = new AtomicBoolean(false);
    private final AtomicBoolean playerHistorySaveScheduled = new AtomicBoolean(false);
    private volatile long lastMarkerFetchMs = 0;
    private volatile long lastMarkerTickCheckMs = 0;
    private volatile long lastPlayerFetchMs = 0;

    public SquaremapApiClient(TownyMapConfig config) {
        this.config = config;
        this.fetchExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.http   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(fetchExecutor)
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TownyMap-Scheduler");
            t.setDaemon(true);
            return t;
        });
        loadPlayerHistory();
    }

    public void start() {
        // Refreshes are driven by tickWhileMapOpen() so gameplay outside the map
        // does not stutter from network calls or JSON parsing.
    }

    public void stop() {
        savePlayerHistoryNow();
        scheduler.shutdownNow();
        fetchExecutor.shutdownNow();
    }

    public List<TownData>     getTowns()        { return towns;        }
    public List<PlayerMarker> getPlayers()      { return players;      }
    public Map<String, PlayerHistoryEntry> getPlayerHistory() { return playerHistory; }

    public void tickWhileMapOpen() {
        tickTownMarkers();
        tickPlayers();
    }

    public void tickTownMarkers() {
        tickTownMarkers(TOWN_MARKER_REFRESH_MS);
    }

    public void tickMinimapTownMarkers() {
        tickTownMarkers(TOWN_MARKER_REFRESH_MS);
    }

    private void tickTownMarkers(long refreshMs) {
        long now = System.currentTimeMillis();
        if (!towns.isEmpty() && now - lastMarkerTickCheckMs < 1_000L) return;
        lastMarkerTickCheckMs = now;
        if ((towns.isEmpty() || now - lastMarkerFetchMs >= refreshMs)
                && markerFetchRunning.compareAndSet(false, true)) {
            lastMarkerFetchMs = now;
            fetchExecutor.execute(this::fetchMarkers);
        }
    }

    public void forceTownMarkerRefresh() {
        lastMarkerFetchMs = 0;
        if (markerFetchRunning.compareAndSet(false, true)) {
            lastMarkerFetchMs = System.currentTimeMillis();
            fetchExecutor.execute(this::fetchMarkers);
        }
    }

    public void forceTownMarkerRefreshDelayed(long delayMs) {
        scheduler.schedule(this::forceTownMarkerRefresh, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }

    private void tickPlayers() {
        long now = System.currentTimeMillis();
        if ((players.isEmpty() || now - lastPlayerFetchMs >= config.refreshPlayersSecs * 1000L)
                && playerFetchRunning.compareAndSet(false, true)) {
            lastPlayerFetchMs = now;
            fetchExecutor.execute(this::fetchPlayers);
        }
    }

    // ── Fetchers ─────────────────────────────────────────────────────────────

    private void fetchMarkers() {
        try {
            String json = get(config.markersUrl());
            if (json != null) {
                List<TownData> parsed = parseMarkers(json);
                List<TownData> updated = reuseUnchangedTowns(towns, parsed);
                if (updated == towns) {
                    LOGGER.debug("[TownyMap] Town polygons unchanged");
                    return;
                }
                towns = updated;
                TownyMapMod.onTownMarkersUpdated();
                LOGGER.info("[TownyMap] Loaded {} town polygons", updated.size());
            }
        } finally {
            markerFetchRunning.set(false);
        }
    }

    private static List<TownData> reuseUnchangedTowns(List<TownData> current, List<TownData> parsed) {
        if (parsed.isEmpty()) return List.of();
        if (current.isEmpty()) return List.copyOf(parsed);

        Map<String, TownData> currentByKey = new HashMap<>(Math.max(16, current.size() * 2));
        for (TownData town : current) {
            currentByKey.put(town.key(), town);
        }

        ArrayList<TownData> merged = new ArrayList<>(parsed.size());
        boolean changed = parsed.size() != current.size();
        for (TownData town : parsed) {
            TownData existing = currentByKey.get(town.key());
            if (existing != null && existing.renderSignature() == town.renderSignature()) {
                merged.add(existing);
            } else {
                merged.add(town);
                changed = true;
            }
        }

        if (!changed) {
            for (int i = 0; i < current.size(); i++) {
                if (current.get(i) != merged.get(i)) {
                    changed = true;
                    break;
                }
            }
        }
        return changed ? List.copyOf(merged) : current;
    }

    private void fetchPlayers() {
        try {
            String json = get(config.playersUrl());
            if (json != null) {
                List<PlayerMarker> parsed = List.copyOf(parsePlayers(json));
                players = parsed;
                rememberPlayers(parsed);
            }
        } finally {
            playerFetchRunning.set(false);
        }
    }

    private void rememberPlayers(List<PlayerMarker> parsed) {
        if (parsed.isEmpty()) return;
        long now = System.currentTimeMillis();
        Map<String, PlayerHistoryEntry> updated = new HashMap<>(playerHistory);
        for (PlayerMarker marker : parsed) {
            if (marker.name() == null || marker.name().isBlank() || "?".equals(marker.name())) continue;
            updated.put(marker.name().toLowerCase(Locale.ROOT),
                    new PlayerHistoryEntry(marker.name(), marker.uuid(), marker.x(), marker.z(), now));
        }
        if (updated.size() > MAX_PLAYER_HISTORY) {
            ArrayList<PlayerHistoryEntry> entries = new ArrayList<>(updated.values());
            entries.sort(Comparator.comparingLong(PlayerHistoryEntry::lastSeenMs).reversed());
            updated.clear();
            int limit = Math.min(MAX_PLAYER_HISTORY, entries.size());
            for (int i = 0; i < limit; i++) {
                PlayerHistoryEntry entry = entries.get(i);
                updated.put(entry.name().toLowerCase(Locale.ROOT), entry);
            }
        }
        playerHistory = Map.copyOf(updated);
        schedulePlayerHistorySave();
    }

    private void loadPlayerHistory() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(PLAYER_HISTORY_FILE);
        if (!Files.exists(path)) return;
        try {
            Map<String, PlayerHistoryEntry> loaded = GSON.fromJson(Files.readString(path), PLAYER_HISTORY_TYPE);
            if (loaded != null) playerHistory = Map.copyOf(loaded);
        } catch (Exception e) {
            LOGGER.warn("[TownyMap] Failed to read player history", e);
        }
    }

    private void schedulePlayerHistorySave() {
        if (!playerHistorySaveScheduled.compareAndSet(false, true)) return;
        scheduler.schedule(() -> {
            try {
                savePlayerHistoryNow();
            } finally {
                playerHistorySaveScheduled.set(false);
            }
        }, PLAYER_HISTORY_SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void savePlayerHistoryNow() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(PLAYER_HISTORY_FILE);
        try {
            Files.writeString(path, GSON.toJson(playerHistory));
        } catch (Exception e) {
            LOGGER.warn("[TownyMap] Failed to write player history", e);
        }
    }

    private String get(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "TownyMapAddon/1.0 (Fabric Mod)")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return resp.body();
            LOGGER.warn("[TownyMap] HTTP {} from {}", resp.statusCode(), url);
        } catch (Exception e) {
            LOGGER.warn("[TownyMap] Request failed for {}: {}", url, e.getMessage());
        }
        return null;
    }

    // ── Parsers ──────────────────────────────────────────────────────────────

    /**
     * Parses markers.json town polygons.
     * Points nesting: points [ polygon [ ring [ {x,z}, … ] ] ]
     */
    private List<TownData> parseMarkers(String json) {
        List<TownData> towns = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) {
                LOGGER.warn("[TownyMap] markers.json: expected top-level array");
                return towns;
            }

            for (JsonElement layerEl : root.getAsJsonArray()) {
                if (!layerEl.isJsonObject()) continue;
                JsonObject layer = layerEl.getAsJsonObject();

                JsonArray markers = getArray(layer, "markers");
                if (markers == null) continue;

                for (JsonElement markerEl : markers) {
                    if (!markerEl.isJsonObject()) continue;
                    JsonObject m = markerEl.getAsJsonObject();

                    if (!"polygon".equalsIgnoreCase(getString(m, "type"))) continue;

                    String name = extractMarkerName(getString(m, "tooltip"));
                    if (name == null) name = extractMarkerName(getString(m, "popup"));
                    if (name == null) name = "?";

                    String colorStr = coalesce(getString(m, "color"), getString(m, "fillColor"));
                    int rgb = TownData.parseHexColor(colorStr, 0x3BFF3B);

                    JsonArray outerPoints = getArray(m, "points");
                    if (outerPoints == null) continue;

                    List<int[][]> rings = new ArrayList<>();
                    for (JsonElement polygonEl : outerPoints) {
                        if (!polygonEl.isJsonArray()) continue;
                        for (JsonElement ringEl : polygonEl.getAsJsonArray()) {
                            if (!ringEl.isJsonArray()) continue;
                            int[][] ring = parseRing(ringEl.getAsJsonArray());
                            if (ring != null && ring.length >= 3) rings.add(ring);
                        }
                    }

                    if (!rings.isEmpty()) {
                        towns.add(new TownData(name, rgb, List.copyOf(rings)));
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("[TownyMap] Failed to parse markers.json", e);
        }
        return towns;
    }

    private int[][] parseRing(JsonArray arr) {
        List<int[]> pts = new ArrayList<>();
        for (JsonElement el : arr) {
            if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                if (o.has("x") && o.has("z")) {
                    pts.add(new int[]{o.get("x").getAsInt(), o.get("z").getAsInt()});
                }
            } else if (el.isJsonArray()) {
                JsonArray a = el.getAsJsonArray();
                if (a.size() >= 2) {
                    pts.add(new int[]{a.get(0).getAsInt(), a.get(a.size() - 1).getAsInt()});
                }
            }
        }
        return pts.isEmpty() ? null : pts.toArray(new int[0][]);
    }

    private List<PlayerMarker> parsePlayers(String json) {
        List<PlayerMarker> result = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(json);

            JsonArray arr = null;
            if (root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                if (obj.has("players")) arr = obj.getAsJsonArray("players");
            } else if (root.isJsonArray()) {
                arr = root.getAsJsonArray();
            }
            if (arr == null) return result;

            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject p = el.getAsJsonObject();

                String world = getString(p, "world");
                if (world != null && !world.contains("overworld")) continue;

                boolean hidden = p.has("hidden") && p.get("hidden").getAsBoolean();
                if (hidden) continue;

                String name = getString(p, "name");
                String uuid = getString(p, "uuid");

                int x, z;
                if (p.has("position") && p.get("position").isJsonObject()) {
                    JsonObject pos = p.getAsJsonObject("position");
                    x = pos.get("x").getAsInt();
                    z = pos.get("z").getAsInt();
                } else if (p.has("x") && p.has("z")) {
                    x = p.get("x").getAsInt();
                    z = p.get("z").getAsInt();
                } else {
                    continue;
                }

                result.add(new PlayerMarker(name != null ? name : "?", uuid, x, z));
            }
        } catch (Exception e) {
            LOGGER.error("[TownyMap] Failed to parse players.json", e);
        }
        return result;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String extractMarkerName(String html) {
        String bold = extractBoldText(html);
        if (bold != null) return bold;
        String stripped = stripHtml(html);
        return stripped == null || stripped.isBlank() ? null : stripped;
    }

    private static String extractBoldText(String html) {
        if (html == null) return null;
        Matcher matcher = BOLD_TEXT.matcher(html);
        if (!matcher.find()) return null;
        String text = stripHtml(matcher.group(1));
        return text == null || text.isBlank() ? null : text;
    }

    private static String stripHtml(String html) {
        if (html == null) return null;
        return HTML_TAG.matcher(html)
                .replaceAll("")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .trim();
    }

    private static String getString(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    private static JsonArray getArray(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonArray()) {
            return obj.getAsJsonArray(key);
        }
        return null;
    }

    @SafeVarargs
    private static <T> T coalesce(T... values) {
        for (T v : values) if (v != null) return v;
        return null;
    }
}
