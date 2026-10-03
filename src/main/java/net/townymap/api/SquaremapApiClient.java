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
    // squaremap regenerates players.json ~every 1.2s (measured: min 0.68s, max 2.07s), so fetching at ~1s
    // captures essentially every update — faster just re-downloads identical data. This is the live player
    // rate, independent of the (coarser, non-UI) refreshPlayersSecs config.
    private static final long PLAYER_REFRESH_MS = 1_000L;
    private static final long PLAYER_HISTORY_SAVE_DELAY_MS = 2_000L;
    private static final Pattern BOLD_TEXT =
            Pattern.compile("(?is)<b[^>]*>(.*?)</b>");
    private static final Pattern HTML_TAG =
            Pattern.compile("(?is)<[^>]+>");
    // The same popups carry "Residents: <b>N</b>". Parsing it here gives a resident count for EVERY town
    // for free, where the /towns API would only cover the handful of towns we have fetched details for.
    private static final Pattern POPUP_RESIDENTS =
            Pattern.compile("(?is)Residents\\s*:?\\s*</?[^>]*>?\\s*(\\d+)");
    // squaremap town popups carry "Mayor: <b>Name</b>" — parse it so hover can show the mayor instantly
    // (no per-town API call). See parseMarkers / getTownMayor.
    private static final Pattern POPUP_MAYOR =
            Pattern.compile("(?is)Mayor:\\s*<b[^>]*>(.*?)</b>");
    // Nation from the tooltip: "<b>Town</b> (Member of X)" or "(Capital of X)". Absent for nationless towns.
    // Group 1 = Member|Capital (kept so capitals can read "Capital of X" like the right-click title).
    private static final Pattern POPUP_NATION =
            Pattern.compile("(?is)\\(\\s*(Member|Capital) of\\s+(.*?)\\)");

    private final TownyMapConfig config;
    private final HttpClient http;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService fetchExecutor;

    /**
     * Town polygons per squaremap world.
     *
     * <p>Two worlds are held at once whenever the map is pinned to a world the player is not in: the
     * world map draws the active world while the minimap draws the one the player is standing in, and
     * a single list could only ever satisfy one of them. Terra Nostra carries ~5,500 towns and the Moon
     * 47, so the second world is nearly free.
     */
    private volatile Map<String, List<TownData>> townsByWorld = Map.of();
    /** Town key -> geometry the API gave us, standing in until the map publishes the same claims. */
    private volatile Map<String, TownData> townGeometryOverrides = Map.of();
    private volatile List<PlayerMarker> players      = List.of();
    private volatile Map<String, PlayerHistoryEntry> playerHistory = Map.of();
    private volatile Map<String, String> townMayors  = Map.of();   // townKey → mayor, parsed from popups
    private volatile Map<String, Integer> townResidents = Map.of(); // townKey → resident count, from popups
    /**
     * Lower-cased player name → the town whose roster lists them, built from the same popups.
     *
     * <p>This is TOWN data, so unlike the EarthMC API it has no opt-out: a player who has opted out of
     * the API still appears on their town's resident list. It is the only way to say anything about
     * those players, and it costs nothing -- we already download and parse these popups.
     */
    private volatile Map<String, String> residentTowns = Map.of();
    private volatile Map<String, String> residentNations = Map.of();
    private volatile Map<String, String> townNations = Map.of();   // townKey → nation, parsed from tooltips

    private final AtomicBoolean markerFetchRunning = new AtomicBoolean(false);
    private final AtomicBoolean playerFetchRunning = new AtomicBoolean(false);
    private final AtomicBoolean playerHistorySaveScheduled = new AtomicBoolean(false);
    private volatile long lastMarkerFetchMs = 0;
    // lastMarkerFetchMs records when a fetch STARTED, so it cannot answer "how old is the data on
    // screen" -- a run of failures keeps bumping it while the claims go stale. These two track the
    // outcome instead: the last time claims actually landed, and whether the newest attempt failed.
    private volatile long lastMarkerSuccessMs = 0;
    /** Last ETag seen per URL, so we can ask the server to skip sending unchanged data. */
    private final Map<String, String> etags = new ConcurrentHashMap<>();
    /** Unique instance: get() returns this when the server said 304, so it is distinct from any body. */
    private static final String NOT_MODIFIED = new String("__not_modified__");
    private volatile boolean markerFetchFailing = false;
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

    /** When non-null, archive mode is active: getTowns() serves this frozen snapshot and live refresh is
     *  paused, so the whole renderer shows the historical claims with no other changes. */
    private volatile List<TownData> archiveTowns = null;
    /** Bumped by onWorldChanged() so responses for the world just left can be recognised and dropped. */
    private volatile int worldGeneration = 0;
    /** Last parsed markers per world, kept so the identity maps can be rebuilt from every world held. */
    private volatile Map<String, ParsedMarkers> markersByWorld = Map.of();

    /** Towns of the world the map is showing. Archive snapshots override, and are Terra Nostra only. */
    public List<TownData> getTowns() { return getTowns(TownyMapMod.activeWorldKey()); }

    /** Towns of one specific world -- the minimap asks for the world the player is standing in. */
    public List<TownData> getTowns(String world) {
        if (archiveTowns != null) return archiveTowns;
        return townsByWorld.getOrDefault(world, List.of());
    }

    /**
     * Replaces one town's outline with the shape the API reports, leaving everything else about the
     * town alone. The map is the source of truth for every town but this one, and reverts to being so
     * for this one as soon as it publishes the same claims.
     */
    public void overrideTownGeometry(String world, TownData replacement) {
        if (replacement == null) return;
        Map<String, TownData> next = new HashMap<>(townGeometryOverrides);
        next.put(replacement.key(), replacement);
        townGeometryOverrides = Map.copyOf(next);
        applyGeometryOverrides(world);
    }

    /** Drops an override, so the town goes back to whatever the map says. */
    public void clearTownGeometryOverride(String world, String townKey) {
        if (townKey == null || !townGeometryOverrides.containsKey(townKey)) return;
        Map<String, TownData> next = new HashMap<>(townGeometryOverrides);
        next.remove(townKey);
        townGeometryOverrides = Map.copyOf(next);
        applyGeometryOverrides(world);
    }

    public TownData townGeometryOverride(String townKey) {
        return townKey == null ? null : townGeometryOverrides.get(townKey);
    }

    /**
     * Retires any override the map has caught up with.
     *
     * <p>An override is only standing in for claims squaremap had not published. Once the freshly
     * parsed town covers every chunk the API gave us, the two agree and the map can have the town back
     * -- which also means an override cannot outlive the lag it was compensating for.
     */
    private void dropSatisfiedOverrides(String world, List<TownData> parsed) {
        Map<String, TownData> overrides = townGeometryOverrides;
        if (overrides.isEmpty()) return;
        List<String> done = new ArrayList<>();
        for (TownData town : parsed) {
            TownData over = overrides.get(town.key());
            if (over == null) continue;
            if (town.minX() <= over.minX() && town.maxX() >= over.maxX()
                    && town.minZ() <= over.minZ() && town.maxZ() >= over.maxZ()
                    && town.approximateChunks() == over.approximateChunks()) {
                done.add(town.key());
            }
        }
        if (done.isEmpty()) return;
        Map<String, TownData> next = new HashMap<>(overrides);
        for (String k : done) next.remove(k);
        townGeometryOverrides = Map.copyOf(next);
    }

    /** Swaps the overridden towns into the published list, so every reader sees one town list. */
    private void applyGeometryOverrides(String world) {
        Map<String, TownData> overrides = townGeometryOverrides;
        List<TownData> current = townsByWorld.getOrDefault(world, List.of());
        if (current.isEmpty()) return;
        List<TownData> updated = new ArrayList<>(current.size());
        boolean changed = false;
        for (TownData town : current) {
            TownData over = overrides.get(town.key());
            if (over != null && over != town) { updated.add(over); changed = true; }
            else updated.add(town);
        }
        if (!changed) return;
        Map<String, List<TownData>> next = new HashMap<>(townsByWorld);
        next.put(world, List.copyOf(updated));
        townsByWorld = Map.copyOf(next);
    }
    public boolean isArchiveActive()            { return archiveTowns != null; }
    /** When claims last actually landed, or 0 if none have yet. Not the same as the last attempt. */
    public long lastClaimsSuccessMs()           { return lastMarkerSuccessMs; }
    /** True if the most recent claims fetch failed; cleared by the next one that succeeds. */
    public boolean isClaimsFetchFailing()       { return markerFetchFailing; }
    public void setArchiveTowns(List<TownData> t) { archiveTowns = t == null ? null : List.copyOf(t); }
    public void clearArchive()                  { archiveTowns = null; lastMarkerFetchMs = 0; }
    /** Resident count parsed from the squaremap popup, or -1 if that town's popup had none. */
    public int getTownResidents(String townKey) {
        if (townKey == null) return -1;
        Integer n = townResidents.get(townKey);
        return n == null ? -1 : n;
    }

    /** Mayor parsed from the squaremap popup for this town key, or null if unknown. */
    public String getTownMayor(String townKey) { return townKey == null ? null : townMayors.get(townKey); }
    public String getTownNation(String townKey) { return townKey == null ? null : townNations.get(townKey); }
    /** Players on the world the map is showing. */
    public List<PlayerMarker> getPlayers() { return getPlayers(TownyMapMod.activeWorldKey()); }

    /** Everyone online, whatever world they are in -- for server-wide counts and leaderboards. */
    public List<PlayerMarker> getAllPlayers() { return players; }

    /** Players on one specific world -- the minimap asks for the world the player is standing in. */
    public List<PlayerMarker> getPlayers(String world) {
        // Memoised per world against the feed it was built from. Both renderers call this every frame,
        // and filtering ~200 markers into a fresh list each time was pure garbage at frame rate; the
        // feed itself only changes once a second.
        List<PlayerMarker> all = players;
        PlayerFilterCache cache = playerFilterCache;
        if (cache.source() != all) {
            cache = new PlayerFilterCache(all, new ConcurrentHashMap<>());
            playerFilterCache = cache;   // a concurrent rebuild is harmless: same input, same output
        }
        return cache.byWorld().computeIfAbsent(world, w -> {
            List<PlayerMarker> out = new ArrayList<>(all.size());
            for (PlayerMarker m : all) if (m.inWorld(w)) out.add(m);
            return List.copyOf(out);
        });
    }

    /** The player feed plus its per-world filters, swapped as one so the two cannot disagree. */
    private record PlayerFilterCache(List<PlayerMarker> source, Map<String, List<PlayerMarker>> byWorld) {}
    private volatile PlayerFilterCache playerFilterCache =
            new PlayerFilterCache(List.of(), new ConcurrentHashMap<>());
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
        if (archiveTowns != null) return;   // archive snapshot is frozen — don't overwrite it with live data
        long now = System.currentTimeMillis();
        // Checked without building the needed-worlds set: this runs from the minimap render, so once a
        // frame, and allocating a set there to answer a question about two strings was wasteful.
        boolean missing = townsByWorld.getOrDefault(TownyMapMod.activeWorldKey(), List.of()).isEmpty()
                || townsByWorld.getOrDefault(TownyMapMod.playerWorldResolved(), List.of()).isEmpty();
        if (!missing && now - lastMarkerTickCheckMs < 1_000L) return;
        lastMarkerTickCheckMs = now;
        if ((missing || now - lastMarkerFetchMs >= refreshMs)
                && markerFetchRunning.compareAndSet(false, true)) {
            lastMarkerFetchMs = now;
            fetchExecutor.execute(this::fetchMarkers);
        }
    }

    /** squaremap's published world list: key -> display name. Empty map on any failure. */
    public java.util.concurrent.CompletableFuture<Map<String, String>> fetchWorlds() {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            Map<String, String> out = new java.util.LinkedHashMap<>();
            try {
                String json = get(config.worldsUrl());
                if (json == null || json == NOT_MODIFIED) return out;
                JsonElement root = JsonParser.parseString(json);
                if (!root.isJsonObject()) return out;
                JsonElement worlds = root.getAsJsonObject().get("worlds");
                if (worlds == null || !worlds.isJsonArray()) return out;
                for (JsonElement el : worlds.getAsJsonArray()) {
                    if (!el.isJsonObject()) continue;
                    JsonObject w = el.getAsJsonObject();
                    String name = getString(w, "name");
                    if (name == null || name.isBlank()) continue;
                    String display = getString(w, "display_name");
                    out.put(name, display == null || display.isBlank() ? name : display);
                }
            } catch (Exception e) {
                LOGGER.warn("[TownyMap] Could not read the squaremap world list: {}", e.getMessage());
            }
            return out;
        }, fetchExecutor);
    }

    /**
     * Drops everything tied to the previous world and refetches.
     *
     * <p>Moon and Terra Nostra coordinates overlap numerically, so a stale town from one would render
     * as a real claim on the other. The ETags go too: they identify a document for a different world.
     */
    /**
     * Claims are filed per world now, so a switch throws nothing away -- both worlds stay valid and the
     * one being switched to is usually already loaded. Only the player list, which carries no world of
     * its own, has to go.
     */
    public void onWorldChanged() {
        worldGeneration++;
        // Positions are raw X/Z with no world attached, so the world we just left would keep drawing its
        // players at those coordinates until the next 1s poll lands.
        players = List.of();
        forceTownMarkerRefresh();
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

    /** Refreshes player positions from squaremap's players.json (NOT the EarthMC API). Driven by both the
     *  world map and the minimap at the live PLAYER_REFRESH_MS rate (~1s, matching squaremap's own update
     *  cadence) and de-duped via playerFetchRunning, so positions stay live during normal play (minimap)
     *  and not just while the full map is open. */
    public void tickPlayers() {
        long now = System.currentTimeMillis();
        if ((players.isEmpty() || now - lastPlayerFetchMs >= PLAYER_REFRESH_MS)
                && playerFetchRunning.compareAndSet(false, true)) {
            lastPlayerFetchMs = now;
            fetchExecutor.execute(this::fetchPlayers);
        }
    }

    // ── Fetchers ─────────────────────────────────────────────────────────────

    /**
     * The worlds whose claims have to be in memory right now: the one the map is showing, and the one
     * the player is standing in. Identical unless the map is pinned elsewhere, in which case the second
     * is what the minimap draws.
     */
    private java.util.Set<String> neededWorlds() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        out.add(TownyMapMod.activeWorldKey());
        out.add(TownyMapMod.playerWorldResolved());
        return out;
    }

    private void fetchMarkers() {
        try {
            for (String world : neededWorlds()) fetchMarkersFor(world);
            // Once per cycle, not once per world: the union spans ~50,000 roster entries and rebuilding
            // it after each world did that work twice for a result only the last pass could be right.
            recomposeTownIdentity();
        } finally {
            markerFetchRunning.set(false);
        }
    }

    /**
     * Fetches one world's markers. No generation guard is needed any more: results are filed under the
     * world they were requested for, so a response that lands after a switch is stored correctly rather
     * than being mistaken for the new world's data -- and is worth keeping, not discarding.
     */
    private void fetchMarkersFor(String world) {
        String json = get(config.markersUrl(world));
        if (json == NOT_MODIFIED) {
            // Unchanged is a success: the data on screen is current, there was just nothing to send.
            lastMarkerSuccessMs = System.currentTimeMillis();
            markerFetchFailing = false;
            return;
        }
        if (json == null) {
            markerFetchFailing = true;
            return;
        }
        ParsedMarkers parsed = parseMarkers(json);
        List<TownData> current = townsByWorld.getOrDefault(world, List.of());
        List<TownData> updated = reuseUnchangedTowns(current, parsed.towns());
        if (updated != current) {
            Map<String, List<TownData>> next = new HashMap<>(townsByWorld);
            next.put(world, updated);
            townsByWorld = Map.copyOf(next);
            LOGGER.info("[TownyMap] Loaded {} town polygons for {}", updated.size(), world);
            dropSatisfiedOverrides(world, updated);
            applyGeometryOverrides(world);
        } else {
            LOGGER.debug("[TownyMap] Town polygons unchanged for {}", world);
        }
        Map<String, ParsedMarkers> nextMeta = new HashMap<>(markersByWorld);
        nextMeta.put(world, parsed);
        markersByWorld = Map.copyOf(nextMeta);
        lastMarkerSuccessMs = System.currentTimeMillis();
        markerFetchFailing = false;
        if (updated != current) TownyMapMod.onTownMarkersUpdated();
    }

    /**
     * Rebuilds the town-identity lookups from every world currently held.
     *
     * <p>Mayor, nation, resident count and the resident roster describe the TOWN, not its claim in one
     * world -- the Moon's popups carry a town's whole 437-resident roster, not the handful with an
     * outpost. Rebuilding from all worlds each time keeps the union available while still dropping
     * entries for towns that have disappeared, which a running merge could never do.
     */
    private void recomposeTownIdentity() {
        Map<String, String> mayors = new HashMap<>();
        Map<String, String> nations = new HashMap<>();
        Map<String, Integer> residents = new HashMap<>();
        Map<String, String> residentTown = new HashMap<>();
        for (ParsedMarkers m : markersByWorld.values()) {
            mayors.putAll(m.mayors());
            nations.putAll(m.nations());
            residents.putAll(m.residents());
            residentTown.putAll(m.residentTowns());
        }
        townMayors = Map.copyOf(mayors);
        townNations = Map.copyOf(nations);
        townResidents = Map.copyOf(residents);
        residentTowns = Map.copyOf(residentTown);
        // Resident -> nation, resolved once here rather than per player per frame. The town value is a
        // display name and townNations is keyed lower-case, so this is where the one lower-casing goes.
        Map<String, String> residentNation = new HashMap<>(residentTown.size());
        for (Map.Entry<String, String> e : residentTown.entrySet()) {
            String nation = nations.get(e.getValue().toLowerCase(Locale.ROOT));
            if (nation != null && !nation.isBlank()) residentNation.put(e.getKey(), nation);
        }
        residentNations = Map.copyOf(residentNation);
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
            if (json == NOT_MODIFIED) return;   // same positions as last poll; nothing to re-parse
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
        // Every world is recorded now that the entry carries one. Filtering positions out here was what
        // left the Moon with no last-seen markers at all.
        long now = System.currentTimeMillis();
        Map<String, PlayerHistoryEntry> updated = new HashMap<>(playerHistory);
        for (PlayerMarker marker : parsed) {
            if (marker.name() == null || marker.name().isBlank() || "?".equals(marker.name())) continue;
            updated.put(marker.name().toLowerCase(Locale.ROOT),
                    new PlayerHistoryEntry(marker.name(), marker.uuid(), marker.x(), marker.z(), now,
                            marker.world()));
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

    /**
     * Fetches a squaremap document, asking for gzip.
     *
     * <p>markers.json is ~10.7 MB uncompressed and ~1.4 MB gzipped, and we pull it every 60 seconds.
     * Without this header every client was moving roughly 640 MB an hour instead of 84 MB, which on a
     * marginal connection shows up as "Connection reset" or a timed-out tile part way through -- the
     * transfer simply does not finish. squaremap has always offered gzip; we just never asked.
     */
    private String get(String url) {
        try {
            HttpRequest.Builder reqB = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "TownyMapAddon/1.0 (Fabric Mod)")
                    .header("Accept-Encoding", "gzip")
                    .GET();
            // "Only send it if it changed since the copy I already have." markers.json regenerates every
            // couple of minutes while we poll every 60s, so most polls come back 304 with an empty body
            // instead of 1.4 MB. The ETag must be the one from a gzipped response -- the server issues a
            // different tag per encoding, and mixing them silently defeats the whole thing.
            String prior = etags.get(url);
            if (prior != null) reqB.header("If-None-Match", prior);
            HttpRequest req = reqB.build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 304) return NOT_MODIFIED;
            if (resp.statusCode() == 200) {
                resp.headers().firstValue("ETag").ifPresent(tag -> etags.put(url, tag));
                return decodeBody(resp);
            }
            LOGGER.warn("[TownyMap] HTTP {} from {}", resp.statusCode(), url);
        } catch (Exception e) {
            LOGGER.warn("[TownyMap] Request failed for {}: {}", url, e.getMessage());
        }
        return null;
    }

    /** Inflates the body when the server honoured our gzip request; returns plain text otherwise. */
    private static String decodeBody(HttpResponse<byte[]> resp) throws java.io.IOException {
        boolean gzip = resp.headers().firstValue("Content-Encoding")
                .map(v -> v.toLowerCase(java.util.Locale.ROOT).contains("gzip")).orElse(false);
        byte[] body = resp.body();
        if (!gzip) return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        try (java.util.zip.GZIPInputStream in =
                     new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(body))) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    // ── Parsers ──────────────────────────────────────────────────────────────

    /**
     * Parses markers.json town polygons.
     * Points nesting: points [ polygon [ ring [ {x,z}, … ] ] ]
     */
    /** One world's parsed markers: polygons plus the town-identity maps read out of the popups. */
    private record ParsedMarkers(List<TownData> towns, Map<String, String> mayors,
                                 Map<String, String> nations, Map<String, Integer> residents,
                                 Map<String, String> residentTowns) {}

    private ParsedMarkers parseMarkers(String json) {
        List<TownData> towns = new ArrayList<>();
        Map<String, String> mayors = new HashMap<>();
        Map<String, Integer> residents = new HashMap<>();
        Map<String, String> residentTown = new HashMap<>();
        int markerFailures = 0;
        Map<String, String> nations = new HashMap<>();
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) {
                LOGGER.warn("[TownyMap] markers.json: expected top-level array");
                return new ParsedMarkers(towns, Map.of(), Map.of(), Map.of(), Map.of());
            }

            for (JsonElement layerEl : root.getAsJsonArray()) {
                if (!layerEl.isJsonObject()) continue;
                JsonObject layer = layerEl.getAsJsonObject();

                JsonArray markers = getArray(layer, "markers");
                if (markers == null) continue;

                for (JsonElement markerEl : markers) {
                    if (!markerEl.isJsonObject()) continue;
                    JsonObject m = markerEl.getAsJsonObject();
                    try {

                    if (!"polygon".equalsIgnoreCase(getString(m, "type"))) continue;

                    String tooltip = getString(m, "tooltip");
                    String name = extractMarkerName(tooltip);
                    if (name == null) name = extractMarkerName(getString(m, "popup"));
                    if (name == null) name = "?";

                    String mayor = extractPopupMayor(getString(m, "popup"));
                    if (mayor != null && !name.equals("?")) {
                        mayors.put(name.toLowerCase(Locale.ROOT), mayor);
                    }

                    int res = extractPopupResidents(getString(m, "popup"));
                    if (res >= 0 && !name.equals("?")) {
                        residents.put(name.toLowerCase(Locale.ROOT), res);
                    }

                    if (!name.equals("?")) {
                        for (String r : extractPopupResidentNames(getString(m, "popup"))) {
                            residentTown.putIfAbsent(r.toLowerCase(Locale.ROOT), name);
                        }
                    }

                    String nation = extractNation(tooltip);
                    if (nation != null && !name.equals("?")) {
                        nations.put(name.toLowerCase(Locale.ROOT), nation);
                    }

                    // squaremap ships the stroke colour ("color") and the interior colour ("fillColor")
                    // separately — on EarthMC they're the nation's two-colour scheme and differ for most
                    // towns — so keep both instead of collapsing them into one.
                    String colorStr = coalesce(getString(m, "color"), getString(m, "fillColor"));
                    String fillStr  = coalesce(getString(m, "fillColor"), getString(m, "color"));
                    int rgb = TownData.parseHexColor(colorStr, 0x3BFF3B);
                    int fillRgb = TownData.parseHexColor(fillStr, rgb);

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
                        towns.add(new TownData(name, rgb, fillRgb, List.copyOf(rings)));
                    }
                    } catch (Exception e) {
                        // One malformed marker must not discard the rest of the map — this loop
                        // carries all 5,600 towns, and the outer catch would have dropped them all.
                        markerFailures++;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("[TownyMap] Failed to parse markers.json", e);
        }
        if (markerFailures > 0) {
            LOGGER.warn("[TownyMap] Skipped {} unparseable town markers", markerFailures);
        }
        return new ParsedMarkers(towns, Map.copyOf(mayors), Map.copyOf(nations),
                Map.copyOf(residents), Map.copyOf(residentTown));
    }

    private int[][] parseRing(JsonArray arr) {
        List<int[]> pts = new ArrayList<>();
        for (JsonElement el : arr) {
            if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                if (o.has("x") && o.has("z")) {
                    pts.add(new int[]{intOf(o, "x", 0), intOf(o, "z", 0)});
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

                // Kept on the marker, not filtered away here: the world map wants the world it is
                // showing and the minimap wants the one the player is standing in, and those differ
                // whenever the map is pinned. Callers choose with getPlayers(world).
                String world = getString(p, "world");

                boolean hidden = p.has("hidden") && p.get("hidden").getAsBoolean();
                if (hidden) continue;

                String name = getString(p, "name");
                String uuid = getString(p, "uuid");

                int x, z;
                if (p.has("position") && p.get("position").isJsonObject()) {
                    JsonObject pos = p.getAsJsonObject("position");
                    x = intOf(pos, "x", 0);
                    z = intOf(pos, "z", 0);
                } else if (p.has("x") && p.has("z")) {
                    x = intOf(p, "x", 0);
                    z = intOf(p, "z", 0);
                } else {
                    continue;
                }

                float yaw = 0f;
                if (p.has("yaw") && p.get("yaw").isJsonPrimitive()) {
                    try { yaw = p.get("yaw").getAsFloat(); } catch (Exception ignored) {}
                }

                String pname = name != null ? name : "?";
                result.add(new PlayerMarker(pname, uuid, x, z, yaw,
                        pname.toLowerCase(Locale.ROOT), world == null ? "" : world));
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

    /** Null-safe int read: has() is true for a JSON null, and getAsInt() on that throws. */
    private static int intOf(JsonObject obj, String key, int fallback) {
        try {
            JsonElement el = obj.get(key);
            return el != null && el.isJsonPrimitive() ? el.getAsInt() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    /** The town whose roster lists this player, or null. Works for API opt-outs. */
    public String townOfResident(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        return residentTowns.get(playerName.toLowerCase(Locale.ROOT));
    }

    /**
     * Town of a resident, by their already-lower-cased name -- for the per-frame render loop, which
     * has the key to hand and must not allocate a string per player per frame.
     */
    public String townOfResidentKey(String playerKey) {
        return playerKey == null ? null : residentTowns.get(playerKey);
    }

    /** Nation of a resident, by their already-lower-cased name. */
    public String nationOfResidentKey(String playerKey) {
        return playerKey == null ? null : residentNations.get(playerKey);
    }

    /**
     * True once a town roster has been parsed. Until then a null from the lookups above means "not
     * loaded yet"; afterwards it means the player really is in no town.
     */
    public boolean hasResidentRoster() { return !residentTowns.isEmpty(); }

    /**
     * Resident names from a town popup. They follow the "Residents: <b>N</b></summary>" line as a plain
     * comma-separated run, so we take everything up to the next tag and split it.
     */
    private static List<String> extractPopupResidentNames(String popupHtml) {
        if (popupHtml == null) return List.of();
        int marker = popupHtml.indexOf("Residents:");
        if (marker < 0) return List.of();
        int end = popupHtml.indexOf("</summary>", marker);
        if (end < 0) return List.of();
        int from = end + "</summary>".length();
        int stop = popupHtml.indexOf('<', from);
        String block = stop < 0 ? popupHtml.substring(from) : popupHtml.substring(from, stop);
        List<String> out = new ArrayList<>();
        for (String part : block.split(",")) {
            String n = part.trim();
            // Names are 3-16 characters of the usual Minecraft alphabet; anything else is stray markup.
            if (n.length() >= 3 && n.length() <= 16 && n.chars().allMatch(
                    c -> Character.isLetterOrDigit(c) || c == '_')) {
                out.add(n);
            }
        }
        return out;
    }

    private static int extractPopupResidents(String popupHtml) {
        if (popupHtml == null) return -1;
        Matcher m = POPUP_RESIDENTS.matcher(popupHtml);
        if (!m.find()) return -1;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String extractPopupMayor(String popupHtml) {
        if (popupHtml == null) return null;
        Matcher m = POPUP_MAYOR.matcher(popupHtml);
        if (!m.find()) return null;
        String mayor = stripHtml(m.group(1));
        if (mayor == null || mayor.isBlank() || mayor.equalsIgnoreCase("None")) return null;
        return mayor;
    }

    private static String extractNation(String tooltipHtml) {
        if (tooltipHtml == null) return null;
        Matcher m = POPUP_NATION.matcher(tooltipHtml);
        if (!m.find()) return null;
        String nation = stripHtml(m.group(2));
        if (nation == null || nation.isBlank() || nation.equalsIgnoreCase("None")) return null;
        nation = nation.trim();
        // Match the right-click title: capitals read "Capital of X", members just "X".
        return "Capital".equalsIgnoreCase(m.group(1)) ? "Capital of " + nation : nation;
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
