package net.townymap.api;

import com.google.gson.*;
import net.townymap.model.EarthMcNationData;
import net.townymap.model.EarthMcPlayerData;
import net.townymap.model.NationBonusProjection;
import net.townymap.model.NationResidentStats;
import net.townymap.model.NationFullData;
import net.townymap.model.PlayerFullData;
import net.townymap.model.TownFullData;
import net.townymap.model.TownOverclaimProjection;
import net.townymap.model.TownPopupData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * One-shot async lookups against the EarthMC v4 REST API.
 *
 *  Location  POST https://api.earthmc.net/v4/location
 *            body: {"query": [[x, z]]}
 *
 *  Towns     POST https://api.earthmc.net/v4/towns
 *            body: {"query": ["TownName"]}
 */
public class EarthMcApiClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("TownyMapAddon");
    private static final String BASE = "https://api.earthmc.net/v4";
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMM d yyyy");

    private final HttpClient http;
    private final ExecutorService executor;
    // Caps concurrent /players activity-batch POSTs. The API 429s past ~4 simultaneous requests, so we
    // fire a big entity's 100-id batches in parallel but throttled to this many at once (the safe max).
    // Base town/nation fetches are NOT gated (they have their own load cap), so the map stays responsive.
    private final java.util.concurrent.Semaphore activeBatchGate = new java.util.concurrent.Semaphore(4);
    /** One 429 warning per session; being rate-limited fails every request, not just one. */
    private final java.util.concurrent.atomic.AtomicBoolean rateLimitLogged =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** Batches that exhausted their retries; surfaced after a sweep so silent loss cannot hide. */
    private final java.util.concurrent.atomic.AtomicInteger droppedPlayerBatches =
            new java.util.concurrent.atomic.AtomicInteger();

    // Per-resident activity cache (id → {removalDate, fetchedAt}), so a huge nation like France (1000+
    // residents = ~11 /players calls) is fetched once, then repeat views and other nations that share
    // residents resolve with zero API calls. removalDate is absolute (lastOnline + 42d), so it stays
    // correct as time passes; a short TTL picks up players who log on/off.
    private final java.util.concurrent.ConcurrentHashMap<String, long[]> residentActivityCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long RESIDENT_ACTIVITY_TTL_MS = 15L * 60 * 1000;

    public EarthMcApiClient() {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.http = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .executor(executor)
                .build();
    }

    /**
     * Looks up the town at (worldX, worldZ) block coordinates.
     * Returns null for wilderness or on error.
     */
    public CompletableFuture<TownPopupData> fetchTownAt(double worldX, double worldZ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Step 1 — location lookup
                JsonObject locBody = new JsonObject();
                JsonArray query = new JsonArray();
                JsonArray coord = new JsonArray();
                coord.add(Math.round(worldX));
                coord.add(Math.round(worldZ));
                query.add(coord);
                locBody.add("query", query);

                String locJson = post(BASE + "/location", locBody.toString());
                if (locJson == null) return null;

                JsonArray locArr = JsonParser.parseString(locJson).getAsJsonArray();
                if (locArr.isEmpty()) return null;

                JsonObject loc = locArr.get(0).getAsJsonObject();
                boolean wilderness = loc.has("isWilderness") && loc.get("isWilderness").getAsBoolean();
                if (wilderness) return TownPopupData.WILDERNESS;

                if (!loc.has("town") || loc.get("town").isJsonNull()) return TownPopupData.WILDERNESS;
                String townName = loc.getAsJsonObject("town").get("name").getAsString();

                return fetchTownNow(townName);

            } catch (Exception e) {
                LOGGER.warn("[TownyMap] EarthMC API lookup failed: {}", e.getMessage());
                return null;
            }
        }, executor);
    }

    public CompletableFuture<TownPopupData> fetchTown(String townName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return fetchTownNow(townName);
            } catch (Exception e) {
                LOGGER.warn("[TownyMap] EarthMC town lookup failed for {}: {}", townName, e.getMessage());
                return null;
            }
        }, executor);
    }

    /** /towns accepts up to 100 names per query. */
    private static final int TOWN_QUERY_BATCH = 100;

    /**
     * Bulk town-detail lookup. Instead of one POST per visible town, this collapses a whole screen into
     * ⌈N/100⌉ parallel (gated) requests. Returns a map keyed by lowercase town name; any name the API
     * didn't return (deleted/renamed, or a failed batch) is simply absent from the map.
     */
    public CompletableFuture<java.util.Map<String, TownPopupData>> fetchTowns(List<String> names) {
        return CompletableFuture.supplyAsync(() -> {
            java.util.Map<String, TownPopupData> out = new ConcurrentHashMap<>();
            if (names == null || names.isEmpty()) return out;
            // Dedupe (preserve order) so repeated names don't waste query slots.
            java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
            for (String n : names) if (n != null && !n.isBlank()) unique.add(n);
            List<String> list = new ArrayList<>(unique);

            // Fire the 100-name batches in parallel; activeBatchGate throttles them to a rate the API
            // tolerates, exactly like the resident-activity batches.
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < list.size(); i += TOWN_QUERY_BATCH) {
                List<String> batch = new ArrayList<>(list.subList(i, Math.min(list.size(), i + TOWN_QUERY_BATCH)));
                futures.add(CompletableFuture.runAsync(() -> fetchTownsBatch(batch, out), executor));
            }
            for (CompletableFuture<Void> f : futures) {
                try { f.join(); } catch (RuntimeException ignored) { /* a failed batch just yields fewer entries */ }
            }
            return out;
        }, executor);
    }

    /**
     * Counts, for every player, how many towns list them as an outlaw and how many trust them.
     *
     * <p>Uses the same batched /towns endpoint as {@link #fetchTowns}, but reads the outlaw and trusted
     * arrays straight out of the JSON instead of going through TownPopupData, which does not model them.
     * One sweep of ~56 batched calls covers the whole server; the caller caches the result.
     *
     * @return player display name -> {outlawedIn, trustedIn}
     */
    public CompletableFuture<java.util.Map<String, int[]>> fetchOutlawTrustedCounts(List<String> names) {
        return CompletableFuture.supplyAsync(() -> {
            java.util.Map<String, int[]> out = new ConcurrentHashMap<>();
            if (names == null || names.isEmpty()) return out;
            java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
            for (String n : names) if (n != null && !n.isBlank()) unique.add(n);
            List<String> list = new ArrayList<>(unique);

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < list.size(); i += TOWN_QUERY_BATCH) {
                List<String> batch = new ArrayList<>(list.subList(i, Math.min(list.size(), i + TOWN_QUERY_BATCH)));
                futures.add(CompletableFuture.runAsync(() -> countRosterBatch(batch, out), executor));
            }
            for (CompletableFuture<Void> f : futures) {
                try { f.join(); } catch (RuntimeException ignored) { /* a failed batch just means fewer towns */ }
            }
            return out;
        }, executor);
    }

    private void countRosterBatch(List<String> batch, java.util.Map<String, int[]> out) {
        JsonObject body = new JsonObject();
        JsonArray q = new JsonArray();
        batch.forEach(q::add);
        body.add("query", q);

        String json = null;
        for (int attempt = 0; attempt < 3 && json == null; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(120L * attempt); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
            json = postGated(BASE + "/towns", body.toString());
        }
        if (json == null) return;
        JsonArray arr;
        try { arr = JsonParser.parseString(json).getAsJsonArray(); }
        catch (RuntimeException e) { return; }

        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject town = el.getAsJsonObject();
            tally(town, "outlaws", 0, out);
            tally(town, "trusted", 1, out);
        }
    }

    /** Adds one town's roster to the tally. Entries may be plain strings or {name, uuid} objects. */
    private static void tally(JsonObject town, String field, int slot, java.util.Map<String, int[]> out) {
        JsonElement el = town.get(field);
        if (el == null || !el.isJsonArray()) return;
        for (JsonElement e : el.getAsJsonArray()) {
            String name = null;
            if (e.isJsonPrimitive()) name = e.getAsString();
            else if (e.isJsonObject() && e.getAsJsonObject().has("name")) {
                JsonElement n = e.getAsJsonObject().get("name");
                if (n.isJsonPrimitive()) name = n.getAsString();
            }
            if (name == null || name.isBlank()) continue;
            out.computeIfAbsent(name, k -> new int[2])[slot]++;
        }
    }

    /** Balance, outlaw count and founding time for one town, for the Towns leaderboards. */
    public record TownRank(String name, double balance, int outlaws, long foundedMs) {}

    /**
     * Sweeps every town for the three fields the Towns leaderboards need.
     *
     * <p>Reads them straight out of the batched /towns response rather than widening TownPopupData,
     * which is used all over the mod. ~56 batches for the whole server, so the caller must cache this
     * and only run it on demand -- never on panel open.
     */
    public CompletableFuture<java.util.Map<String, TownRank>> fetchTownRanks(List<String> names) {
        return CompletableFuture.supplyAsync(() -> {
            java.util.Map<String, TownRank> out = new ConcurrentHashMap<>();
            if (names == null || names.isEmpty()) return out;
            java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
            for (String n : names) if (n != null && !n.isBlank()) unique.add(n);
            List<String> list = new ArrayList<>(unique);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < list.size(); i += TOWN_QUERY_BATCH) {
                List<String> batch = new ArrayList<>(list.subList(i, Math.min(list.size(), i + TOWN_QUERY_BATCH)));
                futures.add(CompletableFuture.runAsync(() -> rankBatch(batch, out), executor));
            }
            for (CompletableFuture<Void> f : futures) {
                try { f.join(); } catch (RuntimeException ignored) { /* a lost batch just means fewer rows */ }
            }
            return out;
        }, executor);
    }

    private void rankBatch(List<String> batch, java.util.Map<String, TownRank> out) {
        JsonObject body = new JsonObject();
        JsonArray q = new JsonArray();
        batch.forEach(q::add);
        body.add("query", q);
        String json = null;
        for (int attempt = 0; attempt < 5 && json == null; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(400L * (1L << (attempt - 1))); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
            json = postGated(BASE + "/towns", body.toString());
        }
        if (json == null) return;
        JsonArray arr;
        try { arr = JsonParser.parseString(json).getAsJsonArray(); }
        catch (RuntimeException e) { return; }
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject t = el.getAsJsonObject();
            String name = str(t, "name", "");
            if (name.isBlank()) continue;
            double bal = 0;
            if (t.has("stats") && t.get("stats").isJsonObject()) {
                JsonObject st = t.getAsJsonObject("stats");
                if (st.has("balance") && st.get("balance").isJsonPrimitive()) bal = st.get("balance").getAsDouble();
            }
            int outlaws = 0;
            if (t.has("outlaws") && t.get("outlaws").isJsonArray()) outlaws = t.getAsJsonArray("outlaws").size();
            long founded = 0;
            if (t.has("timestamps") && t.get("timestamps").isJsonObject()) {
                founded = ts(t.getAsJsonObject("timestamps"), "registered");
            }
            out.put(name.toLowerCase(java.util.Locale.ROOT), new TownRank(name, bal, outlaws, founded));
        }
    }

    /** Looks up one ≤100-name batch and drops each parsed town into {@code out}, keyed by lowercase name. */
    private void fetchTownsBatch(List<String> batch, java.util.Map<String, TownPopupData> out) {
        JsonObject body = new JsonObject();
        JsonArray q = new JsonArray();
        batch.forEach(q::add);
        body.add("query", q);

        String json = null;
        for (int attempt = 0; attempt < 3 && json == null; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(120L * attempt); }   // backoff before retrying a transient failure
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
            json = postGated(BASE + "/towns", body.toString());
        }
        if (json == null) return;
        JsonArray arr;
        try {
            arr = JsonParser.parseString(json).getAsJsonArray();
        } catch (RuntimeException e) {
            return;
        }
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            try {   // one malformed town must not discard the rest of the batch
                TownPopupData data = parseTown(el.getAsJsonObject());
                if (data != null && data.townName() != null && !data.townName().isBlank()) {
                    out.put(data.townName().toLowerCase(java.util.Locale.ROOT), data);
                }
            } catch (RuntimeException e) {
                LOGGER.warn("[TownyMap] Skipped an unparseable town record: {}", e.toString());
            }
        }
    }

    public CompletableFuture<List<EarthMcPlayerData>> fetchPlayerIndex() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = get(BASE + "/players");
                if (json == null) return List.of();
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                List<EarthMcPlayerData> players = new ArrayList<>();
                for (JsonElement element : arr) {
                    if (!element.isJsonObject()) continue;
                    JsonObject obj = element.getAsJsonObject();
                    String name = str(obj, "name", "");
                    if (name.isBlank()) continue;
                    players.add(new EarthMcPlayerData(name, str(obj, "uuid", "")));
                }
                return List.copyOf(players);
            } catch (Exception e) {
                LOGGER.warn("[TownyMap] EarthMC player index lookup failed: {}", e.getMessage());
                return List.of();
            }
        }, executor);
    }

    public CompletableFuture<List<EarthMcNationData>> fetchNationIndex() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = get(BASE + "/nations");
                if (json == null) return List.of();
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                List<EarthMcNationData> nations = new ArrayList<>();
                for (JsonElement element : arr) {
                    if (!element.isJsonObject()) continue;
                    JsonObject obj = element.getAsJsonObject();
                    String name = str(obj, "name", "");
                    if (name.isBlank()) continue;
                    nations.add(new EarthMcNationData(name, str(obj, "uuid", "")));
                }
                return List.copyOf(nations);
            } catch (Exception e) {
                LOGGER.warn("[TownyMap] EarthMC nation index lookup failed: {}", e.getMessage());
                return List.of();
            }
        }, executor);
    }

    public CompletableFuture<EarthMcPlayerData> fetchPlayer(String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject body = new JsonObject();
                JsonArray query = new JsonArray();
                query.add(playerName);
                body.add("query", query);

                String json = post(BASE + "/players", body.toString());
                if (json == null) return null;
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                if (arr.isEmpty()) return null;
                return parsePlayer(arr.get(0).getAsJsonObject());
            } catch (Exception e) {
                LOGGER.warn("[TownyMap] EarthMC player lookup failed for {}: {}", playerName, e.getMessage());
                return null;
            }
        }, executor);
    }

    /**
     * Bulk player-detail lookup (≤100 names/query, parallel + gated), mirroring {@link #fetchTowns} /
     * {@link #fetchNations}. Replaces the one-POST-per-player pattern for search rows and visible players.
     * Returns a map keyed by lowercase player name; names the API didn't return (opted out) are absent.
     */
    public CompletableFuture<java.util.Map<String, EarthMcPlayerData>> fetchPlayers(List<String> names) {
        return CompletableFuture.supplyAsync(() -> {
            java.util.Map<String, EarthMcPlayerData> out = new ConcurrentHashMap<>();
            if (names == null || names.isEmpty()) return out;
            java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
            for (String n : names) if (n != null && !n.isBlank()) unique.add(n);
            List<String> list = new ArrayList<>(unique);

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < list.size(); i += TOWN_QUERY_BATCH) {
                List<String> batch = new ArrayList<>(list.subList(i, Math.min(list.size(), i + TOWN_QUERY_BATCH)));
                futures.add(CompletableFuture.runAsync(() -> fetchPlayersBatch(batch, out), executor));
            }
            for (CompletableFuture<Void> f : futures) {
                try { f.join(); } catch (RuntimeException ignored) { /* a failed batch just yields fewer entries */ }
            }
            int dropped = droppedPlayerBatches.getAndSet(0);
            if (dropped > 0) {
                LOGGER.warn("[TownyMap] {} player batches gave up after retries -- up to {} players are"
                        + " missing from this sweep", dropped, dropped * TOWN_QUERY_BATCH);
            }
            return out;
        }, executor);
    }

    private void fetchPlayersBatch(List<String> batch, java.util.Map<String, EarthMcPlayerData> out) {
        JsonObject body = new JsonObject();
        JsonArray q = new JsonArray();
        batch.forEach(q::add);
        body.add("query", q);

        // A whole-roster sweep is ~600 batches, so a batch that gives up loses 100 players silently.
        // 120ms/240ms was never going to outlast a rate limiter: five attempts with exponential backoff
        // (0.4s, 0.8s, 1.6s, 3.2s) costs nothing when the API is healthy and recovers the batches that
        // were quietly vanishing from the leaderboards.
        String json = null;
        for (int attempt = 0; attempt < 5 && json == null; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(400L * (1L << (attempt - 1))); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
            json = postGated(BASE + "/players", body.toString());
        }
        if (json == null) {
            droppedPlayerBatches.incrementAndGet();
            return;
        }
        JsonArray arr;
        try {
            arr = JsonParser.parseString(json).getAsJsonArray();
        } catch (RuntimeException e) {
            return;
        }
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            try {   // one malformed player must not discard the rest of the batch
                EarthMcPlayerData data = parsePlayer(el.getAsJsonObject());
                if (data != null && data.name() != null && !data.name().isBlank()) {
                    out.put(data.name().toLowerCase(java.util.Locale.ROOT), data);
                }
            } catch (RuntimeException e) {
                LOGGER.warn("[TownyMap] Skipped an unparseable player record: {}", e.toString());
            }
        }
    }

    public CompletableFuture<EarthMcNationData> fetchNation(String nationName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject body = new JsonObject();
                JsonArray query = new JsonArray();
                query.add(nationName);
                body.add("query", query);

                String json = post(BASE + "/nations", body.toString());
                if (json == null) return null;
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                if (arr.isEmpty()) return null;
                return parseNation(arr.get(0).getAsJsonObject());
            } catch (Exception e) {
                LOGGER.warn("[TownyMap] EarthMC nation lookup failed for {}: {}", nationName, e.getMessage());
                return null;
            }
        }, executor);
    }

    /**
     * Bulk nation-detail lookup (≤100 names/query, parallel + gated), mirroring {@link #fetchTowns}. Used
     * to warm the capital stars and search rows in a couple of requests instead of one POST per nation.
     * Returns a map keyed by lowercase nation name; names the API didn't return are absent.
     */
    public CompletableFuture<java.util.Map<String, EarthMcNationData>> fetchNations(List<String> names) {
        return CompletableFuture.supplyAsync(() -> {
            java.util.Map<String, EarthMcNationData> out = new ConcurrentHashMap<>();
            if (names == null || names.isEmpty()) return out;
            java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
            for (String n : names) if (n != null && !n.isBlank()) unique.add(n);
            List<String> list = new ArrayList<>(unique);

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < list.size(); i += TOWN_QUERY_BATCH) {
                List<String> batch = new ArrayList<>(list.subList(i, Math.min(list.size(), i + TOWN_QUERY_BATCH)));
                futures.add(CompletableFuture.runAsync(() -> fetchNationsBatch(batch, out), executor));
            }
            for (CompletableFuture<Void> f : futures) {
                try { f.join(); } catch (RuntimeException ignored) { /* a failed batch just yields fewer entries */ }
            }
            return out;
        }, executor);
    }

    private void fetchNationsBatch(List<String> batch, java.util.Map<String, EarthMcNationData> out) {
        JsonObject body = new JsonObject();
        JsonArray q = new JsonArray();
        batch.forEach(q::add);
        body.add("query", q);

        String json = null;
        for (int attempt = 0; attempt < 3 && json == null; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(120L * attempt); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
            json = postGated(BASE + "/nations", body.toString());
        }
        if (json == null) return;
        JsonArray arr;
        try {
            arr = JsonParser.parseString(json).getAsJsonArray();
        } catch (RuntimeException e) {
            return;
        }
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            // Parse per record: one malformed nation must not discard the other 99 in this batch (that
            // dropped most stars and made the warm loop re-request the rest every second, forever).
            try {
                EarthMcNationData data = parseNation(el.getAsJsonObject());
                if (data != null && data.name() != null && !data.name().isBlank()) {
                    out.put(data.name().toLowerCase(java.util.Locale.ROOT), data);
                }
            } catch (RuntimeException e) {
                LOGGER.warn("[TownyMap] Skipped an unparseable nation record: {}", e.toString());
            }
        }
    }

    private TownPopupData fetchTownNow(String townName) {
        if (townName == null || townName.isBlank()) return null;

        JsonObject townBody = new JsonObject();
        JsonArray tq = new JsonArray();
        tq.add(townName);
        townBody.add("query", tq);

        String townJson = post(BASE + "/towns", townBody.toString());
        if (townJson == null) return null;

        JsonArray townArr = JsonParser.parseString(townJson).getAsJsonArray();
        if (townArr.isEmpty()) return null;

        return parseTown(townArr.get(0).getAsJsonObject());
    }

    private TownPopupData parseTown(JsonObject t) {
        String name    = str(t, "name", "?");
        String discord = str(t, "discord", "");
        String board   = str(t, "board", "");

        String mayor = "?";
        if (t.has("mayor") && t.get("mayor").isJsonObject()) {
            mayor = str(t.getAsJsonObject("mayor"), "name", "?");
        }

        String nation = "";
        if (t.has("nation") && t.get("nation").isJsonObject()) {
            nation = str(t.getAsJsonObject("nation"), "name", "");
        }

        int    chunks    = 0;
        int    residents = 0;
        int    maxChunks = -1;
        double balance   = 0;
        if (t.has("stats") && t.get("stats").isJsonObject()) {
            JsonObject stats = t.getAsJsonObject("stats");
            chunks = intOf(stats, "numTownBlocks", chunks);
            residents = intOf(stats, "numResidents", residents);
            maxChunks = intOf(stats, "maxTownBlocks", maxChunks);
            balance = dblOf(stats, "balance", balance);
        }

        String founded = "";
        if (t.has("timestamps") && t.get("timestamps").isJsonObject()) {
            JsonObject ts = t.getAsJsonObject("timestamps");
            long ms = ts(ts, "registered");
            if (ms > 0) {
                founded = Instant.ofEpochMilli(ms)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                        .format(DATE_FMT);
            }
        }

        // The permission flags live under perms.flags, NOT a top-level "flags" object. This read the
        // latter, which the API has never returned, so pvp was silently false for every town — and the
        // PvP status-overlay mode consequently never highlighted anything.
        boolean pvp = permsFlag(t, "pvp");

        boolean isPublic = false;
        boolean canOutsidersSpawn = false;
        boolean isOverClaimed = false;
        boolean isOpen = false;
        boolean isForSale = false;
        boolean hasNation = !nation.isBlank();
        if (t.has("status") && t.get("status").isJsonObject()) {
            JsonObject status = t.getAsJsonObject("status");
            if (status.has("isPublic")) isPublic = status.get("isPublic").getAsBoolean();
            if (status.has("canOutsidersSpawn")) canOutsidersSpawn = status.get("canOutsidersSpawn").getAsBoolean();
            if (status.has("isOverClaimed")) isOverClaimed = status.get("isOverClaimed").getAsBoolean();
            if (status.has("isOpen")) isOpen = status.get("isOpen").getAsBoolean();
            if (status.has("isForSale")) isForSale = status.get("isForSale").getAsBoolean();
            if (status.has("hasNation")) hasNation = status.get("hasNation").getAsBoolean();
        }

        // NOTE: the active-resident lookup is intentionally NOT done here. parseTown runs for every
        // visible town when a status overlay is active (requestVisibleTownDetails), so a per-resident
        // bulk POST here would fire hundreds of extra calls, get rate-limited, and corrupt both the
        // inactive count AND this town's own data. It's looked up on demand for the focused town only
        // (fetchTownActiveResidents). -1 = "not looked up".
        return new TownPopupData(name, nation, discord, board, mayor, chunks, founded, pvp,
                isPublic, canOutsidersSpawn, isOverClaimed, isOpen, isForSale, hasNation, residents, balance,
                -1, maxChunks);
    }

    /** On-demand for ONE focused nation: BOTH the active-resident count and the bonus-drop projection from
     *  a single /nations fetch + a single resident-timestamp pass (was two separate fetches). The /nations
     *  request is gated to avoid the 429 storms that hid the inactive/bonus rows. */
    public CompletableFuture<NationResidentStats> fetchNationResidentStats(String nationName) {
        return CompletableFuture.supplyAsync(() -> {
            if (nationName == null || nationName.isBlank()) return NationResidentStats.NONE;
            try {
                JsonObject body = new JsonObject();
                JsonArray q = new JsonArray();
                q.add(nationName);
                body.add("query", q);
                String json = postGated(BASE + "/nations", body.toString());
                if (json == null) return NationResidentStats.NONE;
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                if (arr.isEmpty()) return NationResidentStats.NONE;
                return computeResidentStats(arr.get(0).getAsJsonObject());
            } catch (RuntimeException e) {
                return NationResidentStats.NONE;
            }
        }, executor);
    }

    /** One resident-timestamp pass → both the active count and the bonus-drop projection.
     *  active ⟺ removalDate &gt; now (removalDate = lastOnline + 42d, or now+42d if online). */
    private NationResidentStats computeResidentStats(JsonObject obj) {
        java.util.List<String> ids = extractResidentIds(obj);
        int n = ids.size();
        if (n == 0) return new NationResidentStats(0, NationBonusProjection.NONE);
        if (n > MAX_NATION_RESIDENT_LOOKUP) return NationResidentStats.NONE;   // too many → skip both
        long now = System.currentTimeMillis();
        java.util.List<Long> dates = collectRemovalDates(ids, now);
        if (dates == null) return NationResidentStats.NONE;                    // a batch failed → unreliable
        int returned = dates.size();
        int activeReturned = 0;
        for (long d : dates) if (d > now) activeReturned++;
        int active = activeReturned + (n - returned);   // opted-out residents are omitted → counted active
        int authBonus = -1;
        if (obj.has("stats") && obj.get("stats").isJsonObject()) {
            JsonObject st = obj.getAsJsonObject("stats");
            authBonus = intOf(st, "nationBonus", authBonus);
        }
        NationBonusProjection proj = computeProjectionFrom(authBonus, active, dates, now);
        return new NationResidentStats(active, proj);
    }

    /** A town's claimed chunks, as packed {@code (chunkX, chunkZ)} keys, straight from the API. */
    public record TownClaims(String name, java.util.Set<Long> chunks) {}

    /** Packs a chunk coordinate the same way the claim overlays key their chunks. */
    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * The live claim shape of ONE town, from {@code coordinates.townBlocks}.
     *
     * <p>This is the one thing squaremap cannot give us quickly: it regenerates markers.json on its own
     * schedule, so a chunk claimed seconds ago is not on the map yet but is already here. The list is
     * exactly {@code stats.numTownBlocks} long and each entry is a {@code [chunkX, chunkZ]} pair.
     *
     * <p>Returns null when the town cannot be read, so callers can leave what is on screen alone.
     */
    public CompletableFuture<TownClaims> fetchTownClaims(String townName) {
        return CompletableFuture.supplyAsync(() -> {
            if (townName == null || townName.isBlank()) return null;
            try {
                JsonObject body = new JsonObject();
                JsonArray q = new JsonArray();
                q.add(townName);
                body.add("query", q);
                String json = postGated(BASE + "/towns", body.toString());
                if (json == null) return null;
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                if (arr.isEmpty()) return null;
                JsonObject town = arr.get(0).getAsJsonObject();
                JsonObject coords = town.getAsJsonObject("coordinates");
                if (coords == null || !coords.has("townBlocks")) return null;
                JsonArray blocks = coords.getAsJsonArray("townBlocks");
                java.util.Set<Long> chunks = new java.util.HashSet<>(Math.max(16, blocks.size() * 2));
                for (int i = 0; i < blocks.size(); i++) {
                    JsonArray pair = blocks.get(i).getAsJsonArray();
                    if (pair.size() < 2) continue;
                    chunks.add(chunkKey(pair.get(0).getAsInt(), pair.get(1).getAsInt()));
                }
                String name = town.has("name") ? town.get("name").getAsString() : townName;
                return new TownClaims(name, chunks);
            } catch (RuntimeException e) {
                return null;
            }
        }, executor);
    }

    /** On-demand active-resident count for ONE focused town: one /towns fetch + one resident-timestamp pass
     *  (shared/cached with the nation lookups). Gated to avoid the 429 storms that would hide the Inactive row.
     *  Returns -1 if it can't be determined (caller falls back to the raw resident count). */
    public CompletableFuture<Integer> fetchTownActiveResidents(String townName) {
        return CompletableFuture.supplyAsync(() -> {
            if (townName == null || townName.isBlank()) return -1;
            try {
                JsonObject body = new JsonObject();
                JsonArray q = new JsonArray();
                q.add(townName);
                body.add("query", q);
                String json = postGated(BASE + "/towns", body.toString());
                if (json == null) return -1;
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                if (arr.isEmpty()) return -1;
                return computeTownActive(arr.get(0).getAsJsonObject());
            } catch (RuntimeException e) {
                return -1;
            }
        }, executor);
    }

    /**
     * When this town becomes overclaimable, from its residents' purge dates.
     *
     * <p>The claim limit is residents x perResident + bonusBlocks + nationBonus, so every purge costs one
     * resident's worth of chunks. perResident is derived from the town's own numbers rather than assuming
     * EarthMC's current 12, so this keeps working if that value is ever retuned.
     */
    public CompletableFuture<TownOverclaimProjection> fetchTownOverclaim(String townName) {
        return CompletableFuture.supplyAsync(() -> {
            if (townName == null || townName.isBlank()) return TownOverclaimProjection.NONE;
            try {
                JsonObject body = new JsonObject();
                JsonArray q = new JsonArray();
                q.add(townName);
                body.add("query", q);
                String json = postGated(BASE + "/towns", body.toString());
                if (json == null) return TownOverclaimProjection.NONE;
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                if (arr.isEmpty()) return TownOverclaimProjection.NONE;
                return computeTownOverclaim(arr.get(0).getAsJsonObject());
            } catch (RuntimeException e) {
                return TownOverclaimProjection.NONE;
            }
        }, executor);
    }

    private TownOverclaimProjection computeTownOverclaim(JsonObject t) {
        JsonObject stats = t.has("stats") && t.get("stats").isJsonObject()
                ? t.getAsJsonObject("stats") : new JsonObject();
        int claimed = intOf(stats, "numTownBlocks", -1);
        int max = intOf(stats, "maxTownBlocks", -1);
        int residents = intOf(stats, "numResidents", 0);
        int bonusBlocks = intOf(stats, "bonusBlocks", 0);
        int nationBonus = intOf(stats, "nationBonus", 0);
        if (claimed < 0 || max < 0 || residents <= 0) return TownOverclaimProjection.NONE;

        double perResident = (max - bonusBlocks - nationBonus) / (double) residents;
        if (perResident <= 0) return TownOverclaimProjection.NONE;

        java.util.List<String> ids = extractResidentIds(t);
        if (ids.isEmpty() || ids.size() > MAX_RESIDENT_LOOKUP) return TownOverclaimProjection.NONE;
        long now = System.currentTimeMillis();
        java.util.List<Long> dates = collectRemovalDates(ids, now);
        if (dates == null) return TownOverclaimProjection.NONE;

        // Soonest purge first. Residents the API omits (opted out) never appear, so they are treated as
        // never purging — that keeps the estimate conservative rather than inventing an earlier date.
        java.util.List<Long> future = new java.util.ArrayList<>();
        for (long d : dates) if (d > now) future.add(d);
        java.util.Collections.sort(future);

        for (int lost = 1; lost <= future.size(); lost++) {
            int remaining = residents - lost;
            int maxAfter = (int) Math.floor(remaining * perResident) + bonusBlocks + nationBonus;
            if (claimed > maxAfter) {
                long at = future.get(lost - 1);
                Countdown c = countdownTo(at, now);
                return new TownOverclaimProjection(c.dropAtMs(), c.date(), c.days(), lost);
            }
        }
        return TownOverclaimProjection.NONE;   // stays safe for as long as we can see
    }

    private int computeTownActive(JsonObject t) {
        java.util.List<String> ids = extractResidentIds(t);
        int n = ids.size();
        if (n == 0) return 0;
        if (n > MAX_RESIDENT_LOOKUP) return -1;
        long now = System.currentTimeMillis();
        java.util.List<Long> dates = collectRemovalDates(ids, now);
        if (dates == null) return -1;                    // a batch failed → unreliable
        int returned = dates.size();
        int activeReturned = 0;
        for (long d : dates) if (d > now) activeReturned++;
        return activeReturned + (n - returned);          // opted-out residents are omitted → counted active
    }

    private static java.util.List<String> extractResidentIds(JsonObject obj) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        if (!obj.has("residents") || !obj.get("residents").isJsonArray()) return ids;
        for (com.google.gson.JsonElement el : obj.getAsJsonArray("residents")) {
            if (el.isJsonObject()) {
                JsonObject r = el.getAsJsonObject();
                String id = str(r, "uuid", null);
                if (id == null || id.isBlank()) id = str(r, "name", null);
                if (id != null && !id.isBlank()) ids.add(id);
            } else if (el.isJsonPrimitive()) {
                ids.add(el.getAsString());
            }
        }
        return ids;
    }

    private static final long INACTIVE_THRESHOLD_MS = 42L * 24 * 3600 * 1000;
    /** /players returns at most 100 entries per query — batch at the max, then run the batches in
     *  parallel (throttled by activeBatchGate) so even a big entity resolves in ~1-2s. */
    private static final int RESIDENT_QUERY_BATCH = 100;
    /** Cap the on-demand active lookup (per focused town). Parallel batching makes this cheap, so it can
     *  cover essentially every real town; above it the inactive count is omitted. */
    private static final int MAX_RESIDENT_LOOKUP = 2000;
    /** Nations span many towns, so allow a much larger (still bounded) lookup before falling back. */
    private static final int MAX_NATION_RESIDENT_LOOKUP = 3000;

    // ── Nation bonus-drop projection ──────────────────────────────────────────
    // EarthMC's nation chunk bonus is tiered by resident count. Residents are purged 42 days after they
    // were last online, so as inactivity accrues the count falls and eventually crosses a tier threshold,
    // dropping the bonus. We project the next drop from each resident's lastOnline.
    private static final int[] BONUS_TIER_FLOOR = { 200, 120, 80, 60, 40, 20 };
    private static final int[] BONUS_TIER_VALUE = { 100,  80, 60, 50, 30, 10 };

    private static int nationBonusFor(int residents) {
        for (int i = 0; i < BONUS_TIER_FLOOR.length; i++) {
            if (residents >= BONUS_TIER_FLOOR[i]) return BONUS_TIER_VALUE[i];
        }
        return 0;
    }
    private static int nationBonusFloor(int residents) {
        for (int floor : BONUS_TIER_FLOOR) if (residents >= floor) return floor;
        return 0;
    }

    /** Projects the next bonus drop. EarthMC bases the bonus on the ACTIVE resident count (inactive members
     *  stay in the nation but don't earn bonus and aren't removed for a long time), so the bonus falls when
     *  enough currently-active residents go inactive — NOT when residents are purged. We therefore count
     *  down from the active count using only FUTURE go-inactive dates (lastOnline + 42d). {@code currentBonus}
     *  is EarthMC's authoritative value (or -1 to derive the tier from the active count). */
    private NationBonusProjection computeProjectionFrom(int currentBonus, int activeCount,
                                                        java.util.List<Long> dates, long now) {
        int bonus = currentBonus >= 0 ? currentBonus : nationBonusFor(activeCount);
        if (bonus <= 0) return NationBonusProjection.NONE;       // already at the lowest tier
        int tier = tierIndexForBonus(bonus);
        if (tier < 0) return NationBonusProjection.NONE;         // unrecognised bonus value
        int currentFloor = BONUS_TIER_FLOOR[tier];
        int nextBonus = tier + 1 < BONUS_TIER_VALUE.length ? BONUS_TIER_VALUE[tier + 1] : 0;
        int drops = Math.max(1, activeCount - currentFloor + 1); // active residents that must go inactive to drop

        // Only currently-active residents (a FUTURE go-inactive date) can lower the active count from here;
        // already-inactive residents are excluded from the bonus, opted-out ones are unknown (far future).
        java.util.List<Long> future = new java.util.ArrayList<>();
        for (long d : dates) if (d > now) future.add(d);
        while (future.size() < activeCount) future.add(Long.MAX_VALUE);
        future.sort(null);
        if (drops > future.size()) return NationBonusProjection.NONE;
        long rawDrop = future.get(drops - 1);
        if (rawDrop == Long.MAX_VALUE) return NationBonusProjection.NONE;         // drop driven by unknown residents

        Countdown c = countdownTo(rawDrop, now);
        return new NationBonusProjection(nextBonus, c.days(), c.hours(), c.minutes(), c.date(), c.dropAtMs());
    }

    private record Countdown(int days, int hours, int minutes, String date, long dropAtMs) {}

    /** Anchors a raw 42-day mark to EarthMC's ~12:00 Europe/Berlin daily purge and expresses the wait as
     *  local calendar days plus absolute-instant hours/minutes — identical treatment to the nation bonus
     *  projection, so every countdown in the mod agrees. Days come from the viewer's local calendar (so the
     *  day count and shown date match); hours/minutes come from the absolute instant (already offset-correct
     *  vs German time). */
    private static Countdown countdownTo(long rawDrop, long now) {
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        ZonedDateTime raw = Instant.ofEpochMilli(rawDrop).atZone(berlin);
        ZonedDateTime purge = raw.toLocalDate().atTime(12, 0).atZone(berlin);
        if (raw.isAfter(purge)) purge = purge.plusDays(1);
        long dropMs = purge.toInstant().toEpochMilli();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        LocalDate dropDate = Instant.ofEpochMilli(dropMs).atZone(zone).toLocalDate();
        int days = (int) Math.max(0, dropDate.toEpochDay() - today.toEpochDay());
        int hours = (int) Math.max(0, Math.ceil((dropMs - now) / 3_600_000.0));
        int minutes = (int) Math.max(0, Math.ceil((dropMs - now) / 60_000.0));
        return new Countdown(days, hours, minutes, dropDate.format(DATE_FMT), dropMs);
    }

    private static int tierIndexForBonus(int bonus) {
        for (int i = 0; i < BONUS_TIER_VALUE.length; i++) if (BONUS_TIER_VALUE[i] == bonus) return i;
        return -1;
    }

    /** Each resident's removal date = (lastOnline, or now if online) + 42 days. Returns the dates for the
     *  residents the API returned (opted-out ones are absent), or null if any batch failed. Residents whose
     *  activity is already cached (within TTL) skip the API entirely — so a re-viewed or overlapping nation
     *  costs nothing. */
    private java.util.List<Long> collectRemovalDates(java.util.List<String> ids, long now) {
        java.util.List<Long> all = new java.util.ArrayList<>();
        java.util.List<String> toFetch = new java.util.ArrayList<>();
        for (String id : ids) {
            long[] c = residentActivityCache.get(id);
            if (c != null && now - c[1] < RESIDENT_ACTIVITY_TTL_MS) all.add(c[0]);
            else toFetch.add(id);
        }
        if (toFetch.isEmpty()) return all;      // whole nation served from cache → zero API calls

        java.util.List<CompletableFuture<java.util.List<Long>>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < toFetch.size(); i += RESIDENT_QUERY_BATCH) {
            java.util.List<String> batch =
                    new java.util.ArrayList<>(toFetch.subList(i, Math.min(toFetch.size(), i + RESIDENT_QUERY_BATCH)));
            futures.add(CompletableFuture.supplyAsync(() -> removalDatesBatch(batch, now), executor));
        }
        for (CompletableFuture<java.util.List<Long>> f : futures) {
            java.util.List<Long> part;
            try { part = f.join(); } catch (RuntimeException e) { return null; }
            if (part == null) return null;
            all.addAll(part);
        }
        return all;
    }

    private java.util.List<Long> removalDatesBatch(java.util.List<String> batch, long now) {
        JsonObject body = new JsonObject();
        JsonArray q = new JsonArray();
        batch.forEach(q::add);
        body.add("query", q);
        // Include name+uuid so each returned player's activity can be cached by id for reuse.
        JsonObject template = new JsonObject();
        template.addProperty("name", true);
        template.addProperty("uuid", true);
        template.addProperty("timestamps", true);
        template.addProperty("status", true);
        body.add("template", template);

        String json = null;
        for (int attempt = 0; attempt < 3 && json == null; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(120L * attempt); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
            }
            json = postGated(BASE + "/players", body.toString());
        }
        if (json == null) return null;
        JsonArray arr;
        try {
            arr = JsonParser.parseString(json).getAsJsonArray();
        } catch (RuntimeException e) {
            return null;
        }
        java.util.List<Long> out = new java.util.ArrayList<>();
        for (com.google.gson.JsonElement pe : arr) {
            if (!pe.isJsonObject()) continue;
            JsonObject p = pe.getAsJsonObject();
            boolean online = false;
            if (p.has("status") && p.get("status").isJsonObject()) {
                JsonObject st = p.getAsJsonObject("status");
                if (st.has("isOnline")) online = st.get("isOnline").getAsBoolean();
            }
            long lastOnline = 0;
            if (p.has("timestamps") && p.get("timestamps").isJsonObject()) {
                JsonObject ts = p.getAsJsonObject("timestamps");
                if (ts.has("lastOnline") && !ts.get("lastOnline").isJsonNull()) {
                    lastOnline = ts.get("lastOnline").getAsLong();
                }
            }
            long base = online ? now : (lastOnline > 0 ? lastOnline : now);
            long removal = base + INACTIVE_THRESHOLD_MS;
            out.add(removal);
            // Cache by both uuid and name so a later lookup keyed by either form hits.
            long[] entry = { removal, now };
            String uuid = str(p, "uuid", null);
            String pname = str(p, "name", null);
            if (uuid != null && !uuid.isBlank()) residentActivityCache.put(uuid, entry);
            if (pname != null && !pname.isBlank()) residentActivityCache.put(pname, entry);
        }
        return out;
    }

    /** post() throttled by activeBatchGate, for the parallel activity-batch lookups only. */
    private String postGated(String url, String body) {
        try {
            activeBatchGate.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
        try {
            return post(url, body);
        } finally {
            activeBatchGate.release();
        }
    }

    private String post(String url, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "TownyMapAddon/1.0 (Fabric Mod)")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return resp.body();
            // 404 just means "no such entity" (an opt-out, usually) and is expected; logging one line per
            // lookup buried the log. 429 means we are rate-limited and every other request is failing too,
            // so say that once rather than several hundred times.
            if (resp.statusCode() == 404) {
                LOGGER.debug("[TownyMap] EarthMC API {} -> 404", url);
            } else if (resp.statusCode() == 429) {
                if (rateLimitLogged.compareAndSet(false, true)) {
                    LOGGER.warn("[TownyMap] EarthMC API is rate-limiting us (HTTP 429). Lookups will fail"
                            + " until it clears.");
                }
            } else {
                LOGGER.warn("[TownyMap] EarthMC API {} -> HTTP {}", url, resp.statusCode());
            }
        } catch (Exception e) {
            LOGGER.warn("[TownyMap] EarthMC API request failed: {}", e.getMessage());
        }
        return null;
    }

    private String get(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(20))
                    .header("User-Agent", "TownyMapAddon/1.0 (Fabric Mod)")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return resp.body();
            LOGGER.warn("[TownyMap] EarthMC API {} -> HTTP {}", url, resp.statusCode());
        } catch (Exception e) {
            LOGGER.warn("[TownyMap] EarthMC API request failed: {}", e.getMessage());
        }
        return null;
    }

    private EarthMcPlayerData parsePlayer(JsonObject p) {
        String name = str(p, "name", "?");
        String uuid = str(p, "uuid", "");
        String formatted = str(p, "formattedName", "");
        String town = objectName(p, "town");
        String nation = objectName(p, "nation");

        boolean online = false;
        boolean npc = false;
        boolean mayor = false;
        boolean king = false;
        if (p.has("status") && p.get("status").isJsonObject()) {
            JsonObject status = p.getAsJsonObject("status");
            online = bool(status, "isOnline");
            npc = bool(status, "isNPC");
            mayor = bool(status, "isMayor");
            king = bool(status, "isKing");
        }

        double balance = 0;
        int friends = 0;
        if (p.has("stats") && p.get("stats").isJsonObject()) {
            JsonObject stats = p.getAsJsonObject("stats");
            balance = dblOf(stats, "balance", balance);
            friends = intOf(stats, "numFriends", friends);
        }

        String lastOnline = "";
        long lastOnlineMs = 0L;
        long registeredMs = 0L;
        String registered = "";
        if (p.has("timestamps") && p.get("timestamps").isJsonObject()) {
            JsonObject ts = p.getAsJsonObject("timestamps");
            if (ts.has("lastOnline") && !ts.get("lastOnline").isJsonNull()) {
                lastOnlineMs = ts.get("lastOnline").getAsLong();
                lastOnline = Instant.ofEpochMilli(lastOnlineMs)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                        .format(DATE_FMT);
            }
            if (ts.has("registered") && !ts.get("registered").isJsonNull()) {
                long regMs = ts.get("registered").getAsLong();
                if (regMs > 0) {
                    registeredMs = regMs;
                    registered = Instant.ofEpochMilli(regMs)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                            .format(DATE_FMT);
                }
            }
        }

        return new EarthMcPlayerData(name, uuid, town, nation, formatted,
                online, npc, mayor, king, balance, friends, lastOnline, lastOnlineMs, registeredMs, registered);
    }

    private EarthMcNationData parseNation(JsonObject n) {
        String name = str(n, "name", "?");
        String uuid = str(n, "uuid", "");
        String discord = str(n, "discord", "");
        String board = str(n, "board", "");
        String king = objectName(n, "king");
        String capital = objectName(n, "capital");
        String founded = "";
        long foundedMs = 0L;
        if (n.has("timestamps") && n.get("timestamps").isJsonObject()) {
            JsonObject ts = n.getAsJsonObject("timestamps");
            long ms = ts(ts, "registered");
            foundedMs = ms;
            if (ms > 0) {
                founded = Instant.ofEpochMilli(ms)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                        .format(DATE_FMT);
            }
        }

        int towns = 0;
        int residents = 0;
        int chunks = 0;
        int outlaws = 0;
        int allies = 0;
        int enemies = 0;
        double balance = 0;
        int nationBonusVal = -1;
        if (n.has("stats") && n.get("stats").isJsonObject()) {
            JsonObject stats = n.getAsJsonObject("stats");
            towns = intOf(stats, "numTowns", towns);
            residents = intOf(stats, "numResidents", residents);
            chunks = intOf(stats, "numTownBlocks", chunks);
            outlaws = intOf(stats, "numOutlaws", outlaws);
            allies = intOf(stats, "numAllies", allies);
            enemies = intOf(stats, "numEnemies", enemies);
            balance = dblOf(stats, "balance", balance);
            nationBonusVal = intOf(stats, "nationBonus", nationBonusVal);
        }

        boolean isPublic = false;
        boolean isOpen = false;
        boolean isNeutral = false;
        if (n.has("status") && n.get("status").isJsonObject()) {
            JsonObject status = n.getAsJsonObject("status");
            isPublic = bool(status, "isPublic");
            isOpen = bool(status, "isOpen");
            isNeutral = bool(status, "isNeutral");
        }

        boolean hasSpawn = false;
        int spawnX = 0;
        int spawnZ = 0;
        if (n.has("coordinates") && n.get("coordinates").isJsonObject()) {
            JsonObject coords = n.getAsJsonObject("coordinates");
            if (coords.has("spawn") && coords.get("spawn").isJsonObject()) {
                JsonObject spawn = coords.getAsJsonObject("spawn");
                // A nation with no spawn set returns the key with a JSON null value ({"x": null, …}), so
                // has() alone isn't enough — getAsDouble() on that null threw and killed the whole batch.
                if (spawn.has("x") && spawn.get("x").isJsonPrimitive()
                        && spawn.has("z") && spawn.get("z").isJsonPrimitive()) {
                    spawnX = (int) Math.round(spawn.get("x").getAsDouble());
                    spawnZ = (int) Math.round(spawn.get("z").getAsDouble());
                    hasSpawn = true;
                }
            }
        }

        // Active count looked up on demand for the focused nation only (fetchNationResidentStats),
        // not here — parseNation runs from the nation index/search en masse. -1 = "not looked up".
        return new EarthMcNationData(name, uuid, discord, board, king, capital, founded, towns, residents, chunks,
                outlaws, allies, enemies, balance, isPublic, isOpen, isNeutral, hasSpawn, spawnX, spawnZ,
                nationBonusVal, -1, foundedMs);
    }

    private static String objectName(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonObject()) {
            return str(obj.getAsJsonObject(key), "name", "");
        }
        return "";
    }

    /** Reads one of the four town permission flags from perms.flags. */
    private static boolean permsFlag(JsonObject town, String flag) {
        if (!town.has("perms") || !town.get("perms").isJsonObject()) return false;
        JsonObject perms = town.getAsJsonObject("perms");
        if (!perms.has("flags") || !perms.get("flags").isJsonObject()) return false;
        JsonObject flags = perms.getAsJsonObject("flags");
        return flags.has(flag) && !flags.get(flag).isJsonNull() && flags.get(flag).getAsBoolean();
    }

    /** Collects the "name" of each entry in a list of {name, uuid} objects. */
    private static List<String> namesOf(JsonObject obj, String key) {
        List<String> out = new ArrayList<>();
        if (!obj.has(key) || !obj.get(key).isJsonArray()) return out;
        for (JsonElement el : obj.getAsJsonArray(key)) {
            if (el.isJsonObject()) {
                String n = str(el.getAsJsonObject(), "name", "");
                if (!n.isBlank()) out.add(n);
            } else if (el.isJsonPrimitive()) {
                out.add(el.getAsString());
            }
        }
        return out;
    }

    private static long ts(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return 0L;
        try { return obj.get(key).getAsLong(); } catch (Exception e) { return 0L; }
    }

    private static int intOf(JsonObject obj, String key, int fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try { return obj.get(key).getAsInt(); } catch (Exception e) { return fallback; }
    }

    private static double dblOf(JsonObject obj, String key, double fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try { return obj.get(key).getAsDouble(); } catch (Exception e) { return fallback; }
    }

    /**
     * Full town record for the expanded panel. Parsed from the SAME /towns response the map already
     * fetches, so opening the panel costs no additional request.
     */
    private static TownFullData parseTownFull(JsonObject t) {
        JsonObject status = t.has("status") && t.get("status").isJsonObject() ? t.getAsJsonObject("status") : new JsonObject();
        JsonObject stats  = t.has("stats")  && t.get("stats").isJsonObject()  ? t.getAsJsonObject("stats")  : new JsonObject();
        JsonObject times  = t.has("timestamps") && t.get("timestamps").isJsonObject() ? t.getAsJsonObject("timestamps") : new JsonObject();

        String mayor = t.has("mayor") && t.get("mayor").isJsonObject()
                ? str(t.getAsJsonObject("mayor"), "name", "?") : "?";
        String nation = t.has("nation") && t.get("nation").isJsonObject()
                ? str(t.getAsJsonObject("nation"), "name", "") : "";

        int sx = 0, sy = 0, sz = 0;
        if (t.has("coordinates") && t.get("coordinates").isJsonObject()) {
            JsonObject coords = t.getAsJsonObject("coordinates");
            if (coords.has("spawn") && coords.get("spawn").isJsonObject()) {
                JsonObject sp = coords.getAsJsonObject("spawn");
                sx = (int) Math.round(dblOf(sp, "x", 0));
                sy = (int) Math.round(dblOf(sp, "y", 0));
                sz = (int) Math.round(dblOf(sp, "z", 0));
            }
        }

        java.util.LinkedHashMap<String, List<String>> ranks = new java.util.LinkedHashMap<>();
        if (t.has("ranks") && t.get("ranks").isJsonObject()) {
            for (var e : t.getAsJsonObject("ranks").entrySet()) {
                List<String> holders = new ArrayList<>();
                if (e.getValue().isJsonArray()) {
                    for (JsonElement el : e.getValue().getAsJsonArray()) {
                        if (el.isJsonObject()) holders.add(str(el.getAsJsonObject(), "name", ""));
                        else if (el.isJsonPrimitive()) holders.add(el.getAsString());
                    }
                }
                ranks.put(e.getKey(), List.copyOf(holders));
            }
        }

        List<TownFullData.Warp> warps = new ArrayList<>();
        if (t.has("warps") && t.get("warps").isJsonArray()) {
            for (JsonElement el : t.getAsJsonArray("warps")) {
                if (!el.isJsonObject()) continue;
                JsonObject w = el.getAsJsonObject();
                int wx = 0, wy = 0, wz = 0;
                if (w.has("location") && w.get("location").isJsonObject()) {
                    JsonObject loc = w.getAsJsonObject("location");
                    wx = (int) Math.round(dblOf(loc, "x", 0));
                    wy = (int) Math.round(dblOf(loc, "y", 0));
                    wz = (int) Math.round(dblOf(loc, "z", 0));
                }
                warps.add(new TownFullData.Warp(str(w, "name", "?"), str(w, "access", ""),
                        str(w, "createdBy", ""), ts(w, "createdAt"), wx, wy, wz));
            }
        }

        return new TownFullData(
                str(t, "name", "?"), str(t, "board", ""), str(t, "founder", ""),
                str(t, "wiki", ""), str(t, "discord", ""), mayor, nation,
                ts(times, "registered"), ts(times, "joinedNationAt"), ts(times, "ruinedAt"),
                bool(status, "isPublic"), bool(status, "isOpen"), bool(status, "isNeutral"),
                bool(status, "isCapital"), bool(status, "isOverClaimed"), bool(status, "isRuined"),
                bool(status, "isForSale"), bool(status, "hasNation"), bool(status, "canOutsidersSpawn"),
                bool(status, "canPassiveMobsSpawn"), bool(status, "hasSnowAccumulation"),
                bool(status, "hasFriendlyFire"),
                intOf(stats, "numTownBlocks", 0), intOf(stats, "maxTownBlocks", -1),
                intOf(stats, "bonusBlocks", 0), intOf(stats, "nationBonus", 0),
                intOf(stats, "numResidents", 0), intOf(stats, "numTrusted", 0),
                intOf(stats, "numOutlaws", 0), dblOf(stats, "balance", 0),
                dblOf(stats, "forSalePrice", -1),
                permsFlag(t, "pvp"), permsFlag(t, "explosion"), permsFlag(t, "fire"), permsFlag(t, "mobs"),
                sx, sy, sz,
                namesOf(t, "residents"), namesOf(t, "trusted"), namesOf(t, "outlaws"),
                namesOf(t, "quarters"), Map.copyOf(ranks), List.copyOf(warps));
    }

    private static List<String> strList(JsonObject obj, String key) {
        List<String> out = new ArrayList<>();
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) return out;
        for (JsonElement el : obj.getAsJsonArray(key)) {
            if (el.isJsonObject()) {
                String n = str(el.getAsJsonObject(), "name", "");
                if (!n.isBlank()) out.add(n);
            } else if (el.isJsonPrimitive()) {
                out.add(el.getAsString());
            }
        }
        return out;
    }

    private static PlayerFullData parsePlayerFull(JsonObject pl) {
        JsonObject status = pl.has("status") && pl.get("status").isJsonObject() ? pl.getAsJsonObject("status") : new JsonObject();
        JsonObject stats  = pl.has("stats")  && pl.get("stats").isJsonObject()  ? pl.getAsJsonObject("stats")  : new JsonObject();
        JsonObject times  = pl.has("timestamps") && pl.get("timestamps").isJsonObject() ? pl.getAsJsonObject("timestamps") : new JsonObject();
        String town = pl.has("town") && pl.get("town").isJsonObject() ? str(pl.getAsJsonObject("town"), "name", "") : "";
        String nation = pl.has("nation") && pl.get("nation").isJsonObject() ? str(pl.getAsJsonObject("nation"), "name", "") : "";

        List<String> townRanks = List.of(), nationRanks = List.of();
        if (pl.has("ranks") && pl.get("ranks").isJsonObject()) {
            JsonObject r = pl.getAsJsonObject("ranks");
            townRanks = List.copyOf(strList(r, "townRanks"));
            nationRanks = List.copyOf(strList(r, "nationRanks"));
        }
        return new PlayerFullData(
                str(pl, "name", "?"), str(pl, "title", ""), str(pl, "surname", ""),
                str(pl, "formattedName", ""), str(pl, "about", ""), str(pl, "discord", ""),
                town, nation,
                ts(times, "registered"), ts(times, "joinedTownAt"), ts(times, "lastOnline"),
                bool(status, "isOnline"), bool(status, "isNPC"), bool(status, "isMayor"),
                bool(status, "isKing"), bool(status, "hasTown"), bool(status, "hasNation"),
                dblOf(stats, "balance", 0), intOf(stats, "numFriends", 0),
                List.copyOf(strList(pl, "friends")), townRanks, nationRanks);
    }

    private static NationFullData parseNationFull(JsonObject n) {
        JsonObject status = n.has("status") && n.get("status").isJsonObject() ? n.getAsJsonObject("status") : new JsonObject();
        JsonObject stats  = n.has("stats")  && n.get("stats").isJsonObject()  ? n.getAsJsonObject("stats")  : new JsonObject();
        JsonObject times  = n.has("timestamps") && n.get("timestamps").isJsonObject() ? n.getAsJsonObject("timestamps") : new JsonObject();
        String king = n.has("king") && n.get("king").isJsonObject() ? str(n.getAsJsonObject("king"), "name", "") : "";
        String capital = n.has("capital") && n.get("capital").isJsonObject() ? str(n.getAsJsonObject("capital"), "name", "") : "";

        int sx = 0, sy = 0, sz = 0;
        if (n.has("coordinates") && n.get("coordinates").isJsonObject()) {
            JsonObject c = n.getAsJsonObject("coordinates");
            if (c.has("spawn") && c.get("spawn").isJsonObject()) {
                JsonObject sp = c.getAsJsonObject("spawn");
                sx = (int) Math.round(dblOf(sp, "x", 0));
                sy = (int) Math.round(dblOf(sp, "y", 0));
                sz = (int) Math.round(dblOf(sp, "z", 0));
            }
        }

        java.util.LinkedHashMap<String, List<String>> ranks = new java.util.LinkedHashMap<>();
        if (n.has("ranks") && n.get("ranks").isJsonObject()) {
            for (var e : n.getAsJsonObject("ranks").entrySet()) {
                List<String> holders = new ArrayList<>();
                if (e.getValue().isJsonArray()) {
                    for (JsonElement el : e.getValue().getAsJsonArray()) {
                        if (el.isJsonObject()) holders.add(str(el.getAsJsonObject(), "name", ""));
                        else if (el.isJsonPrimitive()) holders.add(el.getAsString());
                    }
                }
                ranks.put(e.getKey(), List.copyOf(holders));
            }
        }

        List<NationFullData.Pact> pacts = new ArrayList<>();
        List<String> embOwn = new ArrayList<>(), embAgainst = new ArrayList<>();
        if (n.has("pacts") && n.get("pacts").isJsonObject()) {
            JsonObject pj = n.getAsJsonObject("pacts");
            for (String key : new String[]{"active", "pending"}) {
                if (!pj.has(key) || !pj.get(key).isJsonArray()) continue;
                for (JsonElement el : pj.getAsJsonArray(key)) {
                    if (!el.isJsonObject()) continue;
                    JsonObject p = el.getAsJsonObject();
                    // createdAt / expiresAt / duration are nested under "stats"; reading them off the pact
                    // itself silently produced zeros, which is why every pact rendered with no dates.
                    JsonObject ps = p.has("stats") && p.get("stats").isJsonObject()
                            ? p.getAsJsonObject("stats") : new JsonObject();
                    pacts.add(new NationFullData.Pact(str(p, "sender", ""), str(p, "receiver", ""),
                            str(p, "status", key.toUpperCase(java.util.Locale.ROOT)),
                            ts(ps, "createdAt"), ts(ps, "expiresAt"), ts(ps, "duration")));
                }
            }
        }
        if (n.has("embargoes") && n.get("embargoes").isJsonObject()) {
            JsonObject ej = n.getAsJsonObject("embargoes");
            embOwn = strList(ej, "own");
            embAgainst = strList(ej, "against");
        }

        return new NationFullData(
                str(n, "name", "?"), str(n, "board", ""), str(n, "wiki", ""), str(n, "discord", ""),
                king, capital, ts(times, "registered"),
                bool(status, "isPublic"), bool(status, "isOpen"), bool(status, "isNeutral"),
                intOf(stats, "nationBonus", 0), intOf(stats, "numTownBlocks", 0),
                intOf(stats, "numResidents", 0), intOf(stats, "numTowns", 0),
                intOf(stats, "numOutlaws", 0), intOf(stats, "numAllies", 0),
                intOf(stats, "numEnemies", 0), dblOf(stats, "balance", 0),
                sx, sy, sz,
                List.copyOf(strList(n, "towns")), List.copyOf(strList(n, "residents")),
                List.copyOf(strList(n, "outlaws")), List.copyOf(strList(n, "allies")),
                List.copyOf(strList(n, "enemies")), List.copyOf(strList(n, "sanctioned")),
                Map.copyOf(ranks), List.copyOf(pacts),
                List.copyOf(embOwn), List.copyOf(embAgainst));
    }

    /** Full record for ONE player, for the expanded panel. */
    public CompletableFuture<PlayerFullData> fetchPlayerFull(String playerName) {
        return fetchOne(BASE + "/players", playerName, EarthMcApiClient::parsePlayerFull);
    }

    /** Full record for ONE nation, for the expanded panel. */
    public CompletableFuture<NationFullData> fetchNationFull(String nationName) {
        return fetchOne(BASE + "/nations", nationName, EarthMcApiClient::parseNationFull);
    }

    /** One-name query against any of the v4 collection endpoints, parsed by {@code parser}. */
    private <T> CompletableFuture<T> fetchOne(String url, String name,
                                              java.util.function.Function<JsonObject, T> parser) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject body = new JsonObject();
                JsonArray q = new JsonArray();
                q.add(name);
                body.add("query", q);
                JsonElement root = JsonParser.parseString(postGated(url, body.toString()));
                if (!root.isJsonArray() || root.getAsJsonArray().isEmpty()) return null;
                JsonElement first = root.getAsJsonArray().get(0);
                return first.isJsonObject() ? parser.apply(first.getAsJsonObject()) : null;
            } catch (Exception e) {
                LOGGER.warn("[TownyMap] Full lookup failed for {}: {}", name, e.getMessage());
                return null;
            }
        }, executor);
    }

    /** Fetches the full record for ONE town, for the expanded panel. */
    public CompletableFuture<TownFullData> fetchTownFull(String townName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject body = new JsonObject();
                JsonArray q = new JsonArray();
                q.add(townName);
                body.add("query", q);
                String json = postGated(BASE + "/towns", body.toString());
                JsonElement root = JsonParser.parseString(json);
                if (!root.isJsonArray() || root.getAsJsonArray().isEmpty()) return null;
                JsonElement first = root.getAsJsonArray().get(0);
                return first.isJsonObject() ? parseTownFull(first.getAsJsonObject()) : null;
            } catch (Exception e) {
                LOGGER.warn("[TownyMap] Failed to fetch full town data for {}: {}", townName, e.getMessage());
                return null;
            }
        }, executor);
    }

    private static boolean bool(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() && obj.get(key).getAsBoolean();
    }

    private static String str(JsonObject obj, String key, String fallback) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsString();
        }
        return fallback;
    }
}
