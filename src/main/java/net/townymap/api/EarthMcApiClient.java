package net.townymap.api;

import com.google.gson.*;
import net.townymap.model.EarthMcNationData;
import net.townymap.model.EarthMcPlayerData;
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
        double balance   = 0;
        if (t.has("stats") && t.get("stats").isJsonObject()) {
            JsonObject stats = t.getAsJsonObject("stats");
            if (stats.has("numTownBlocks")) chunks    = stats.get("numTownBlocks").getAsInt();
            if (stats.has("numResidents"))  residents = stats.get("numResidents").getAsInt();
            if (stats.has("balance"))       balance   = stats.get("balance").getAsDouble();
        }

        String founded = "";
        if (t.has("timestamps") && t.get("timestamps").isJsonObject()) {
            JsonObject ts = t.getAsJsonObject("timestamps");
            if (ts.has("registered")) {
                long ms = ts.get("registered").getAsLong();
                founded = Instant.ofEpochMilli(ms)
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate()
                        .format(DATE_FMT);
            }
        }

        boolean pvp = false;
        if (t.has("flags") && t.get("flags").isJsonObject()) {
            JsonObject flags = t.getAsJsonObject("flags");
            if (flags.has("pvp")) pvp = flags.get("pvp").getAsBoolean();
        }

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

        return new TownPopupData(name, nation, discord, board, mayor, chunks, founded, pvp,
                isPublic, canOutsidersSpawn, isOverClaimed, isOpen, isForSale, hasNation, residents, balance);
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
            LOGGER.warn("[TownyMap] EarthMC API {} → HTTP {}", url, resp.statusCode());
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
            if (stats.has("balance")) balance = stats.get("balance").getAsDouble();
            if (stats.has("numFriends")) friends = stats.get("numFriends").getAsInt();
        }

        String lastOnline = "";
        if (p.has("timestamps") && p.get("timestamps").isJsonObject()) {
            JsonObject ts = p.getAsJsonObject("timestamps");
            if (ts.has("lastOnline") && !ts.get("lastOnline").isJsonNull()) {
                long ms = ts.get("lastOnline").getAsLong();
                lastOnline = Instant.ofEpochMilli(ms)
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate()
                        .format(DATE_FMT);
            }
        }

        return new EarthMcPlayerData(name, uuid, town, nation, formatted,
                online, npc, mayor, king, balance, friends, lastOnline);
    }

    private EarthMcNationData parseNation(JsonObject n) {
        String name = str(n, "name", "?");
        String uuid = str(n, "uuid", "");
        String board = str(n, "board", "");
        String king = objectName(n, "king");
        String capital = objectName(n, "capital");
        String founded = "";
        if (n.has("timestamps") && n.get("timestamps").isJsonObject()) {
            JsonObject ts = n.getAsJsonObject("timestamps");
            if (ts.has("registered")) {
                long ms = ts.get("registered").getAsLong();
                founded = Instant.ofEpochMilli(ms)
                        .atZone(ZoneOffset.UTC)
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
        if (n.has("stats") && n.get("stats").isJsonObject()) {
            JsonObject stats = n.getAsJsonObject("stats");
            if (stats.has("numTowns")) towns = stats.get("numTowns").getAsInt();
            if (stats.has("numResidents")) residents = stats.get("numResidents").getAsInt();
            if (stats.has("numTownBlocks")) chunks = stats.get("numTownBlocks").getAsInt();
            if (stats.has("numOutlaws")) outlaws = stats.get("numOutlaws").getAsInt();
            if (stats.has("numAllies")) allies = stats.get("numAllies").getAsInt();
            if (stats.has("numEnemies")) enemies = stats.get("numEnemies").getAsInt();
            if (stats.has("balance")) balance = stats.get("balance").getAsDouble();
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
                if (spawn.has("x") && spawn.has("z")) {
                    spawnX = (int) Math.round(spawn.get("x").getAsDouble());
                    spawnZ = (int) Math.round(spawn.get("z").getAsDouble());
                    hasSpawn = true;
                }
            }
        }

        return new EarthMcNationData(name, uuid, board, king, capital, founded, towns, residents, chunks,
                outlaws, allies, enemies, balance, isPublic, isOpen, isNeutral, hasSpawn, spawnX, spawnZ);
    }

    private static String objectName(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonObject()) {
            return str(obj.getAsJsonObject(key), "name", "");
        }
        return "";
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
