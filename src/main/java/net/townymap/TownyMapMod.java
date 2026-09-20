package net.townymap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.level.Level;
import net.townymap.api.EarthMcApiClient;
import net.townymap.api.SquaremapApiClient;
import net.townymap.gui.ChunkCounterOverlay;
import net.townymap.gui.MapToggleOverlay;
import net.townymap.gui.TownHoverOverlay;
import net.townymap.gui.TownInfoOverlay;
import net.townymap.gui.TownSearchOverlay;
import net.townymap.input.TownyMapKeybinds;
import net.townymap.integration.XaeroWaypointBridge;
import net.townymap.model.EarthMcNationData;
import net.townymap.model.EarthMcPlayerData;
import net.townymap.model.MapJumpTarget;
import net.townymap.model.NationBonusProjection;
import net.townymap.model.NationResidentStats;
import net.townymap.model.OptimisticClaimChunk;
import net.townymap.model.PlayerMarker;
import net.townymap.model.TownData;
import net.townymap.model.TownPopupData;
import net.townymap.render.TownyMinimapOverlay;
import net.townymap.render.WorldMapRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xaero.hud.minimap.common.config.MinimapConfigConstants;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Environment(EnvType.CLIENT)
public class TownyMapMod implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("TownyMapAddon");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TOWN_CACHE_TYPE = new TypeToken<Map<String, TownPopupData>>() {}.getType();
    private static final long TOWN_DETAILS_SAVE_DELAY_MS = 2_000L;
    private static final long TOWN_DETAILS_MAX_AGE_MS = 60_000L;
    private static final long DETAIL_REQUEST_DEFER_MS = 2_000L;
    private static final long OPTIMISTIC_CLAIM_TTL_MS = 20_000L;
    private static final long PENDING_CLAIM_TTL_MS = 12_000L;
    // Max visible-town details fetched per 500ms cycle. One bulk /towns request covers up to 100 names,
    // so this is ~2 parallel requests — enough to fill a normal screen at once instead of dribbling.
    private static final int MAX_TOWN_DETAIL_BATCH = 150;
    // Max player details collected per bulk /players request (search rows / visible players) — one batch.
    private static final int PLAYER_BULK_PER_CYCLE = 100;
    private static final ScheduledExecutorService CACHE_SAVE_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "TownyMap-CacheSave");
                t.setDaemon(true);
                return t;
            });

    private static TownyMapConfig     config;
    private static SquaremapApiClient apiClient;
    private static EarthMcApiClient   earthMcApi;
    private static WorldMapRenderer   renderer;
    private static final AtomicLong townLookupId = new AtomicLong();
    private static double lastWorldMapCameraX = Double.NaN;
    private static double lastWorldMapCameraZ = Double.NaN;
    private static double lastWorldMapScale = Double.NaN;
    private static long worldMapMovingUntilMs = 0;
    private static final Map<String, TownPopupData> townDetailsCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> townDetailsFetchedAt = new ConcurrentHashMap<>();
    private static final Set<String> townDetailsLoading = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> townDetailsDeferredAt = new ConcurrentHashMap<>();
    // On-demand active-resident counts (the bulk last-login lookup), kept OFF the mass town/nation
    // fetch so the map's bulk fetches stay cheap. Only the focused/searched town & nation are looked
    // up here. Value -1 = looked up but unavailable (e.g. over the resident cap) — cached to avoid re-firing.
    private static final Map<String, Integer> townActiveCache = new ConcurrentHashMap<>();
    private static final Set<String> townActiveLoading = ConcurrentHashMap.newKeySet();
    private static final Map<String, Integer> nationActiveCache = new ConcurrentHashMap<>();
    private static final Map<String, NationBonusProjection> nationBonusProjCache = new ConcurrentHashMap<>();
    private static final Map<String, net.townymap.model.TownOverclaimProjection> townOverclaimCache = new ConcurrentHashMap<>();
    private static final Set<String> townOverclaimLoading = ConcurrentHashMap.newKeySet();
    private static final Set<String> nationBonusProjLoading = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean townDetailsSaveScheduled = new AtomicBoolean(false);
    private static volatile MapJumpTarget townInfoRouteTarget;
    private static volatile List<EarthMcPlayerData> apiPlayers = List.of();
    private static final Map<String, EarthMcPlayerData> playerDetailsCache = new ConcurrentHashMap<>();
    private static final Set<String> playerDetailsLoading = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> playerDetailsFailedAt = new ConcurrentHashMap<>();
    private static final Map<String, Long> playerDetailsDeferredAt = new ConcurrentHashMap<>();
    private static final List<OptimisticClaimChunk> optimisticClaimChunks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile PendingClaim pendingClaim;
    private static final Set<String> minimapOutsideNationPlayers = ConcurrentHashMap.newKeySet();
    private static volatile List<EarthMcNationData> apiNations = List.of();
    private static final Map<String, EarthMcNationData> nationDetailsCache = new ConcurrentHashMap<>();
    private static final Set<String> nationDetailsLoading = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> nationDetailsDeferredAt = new ConcurrentHashMap<>();
    private static final int NATION_BULK_PER_CYCLE = 100;   // nations warmed per bulk /nations cycle
    private static volatile long lastPlayerIndexAttemptMs = 0;
    private static volatile Set<String> cachedFavoriteTownKeys = Set.of();
    private static volatile int cachedFavoriteTownCount = -1;
    private static volatile long minimapNationAlertFlashUntilMs = 0;
    private static volatile long minimapFrameColorReadAtMs = 0;
    private static volatile int minimapFrameColor = 0xFFFFFFFF;
    private static final ThreadLocal<Boolean> suppressNativeMinimapCompass =
            ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> suppressNativeMinimapWaypoints =
            ThreadLocal.withInitial(() -> false);
    private static final AtomicBoolean nativeCompassSuppressionLogged = new AtomicBoolean(false);
    private static final AtomicBoolean playerIndicatorErrorLogged = new AtomicBoolean(false);
    private static final AtomicBoolean townDetailCacheSaveErrorLogged = new AtomicBoolean(false);
    private static final AtomicBoolean minimapCompassErrorLogged = new AtomicBoolean(false);
    private static final AtomicBoolean minimapWaypointsErrorLogged = new AtomicBoolean(false);
    private static final AtomicBoolean minimapFrameErrorLogged = new AtomicBoolean(false);
    private static final AtomicBoolean minimapOutlinesErrorLogged = new AtomicBoolean(false);
    private static volatile long minimapPlayerDetailWindowMs = 0;
    private static volatile int minimapPlayerDetailRequests = 0;
    private static volatile long lastVisibleTownDetailsRequestMs = 0;
    private static volatile long lastVisiblePlayerDetailsRequestMs = 0;
    private static volatile long lastNationCapitalDetailsRequestMs = 0;
    private static volatile long lastSearchDetailsRequestMs = 0;
    private static volatile String lastSearchDetailsQuery = "";
    private static volatile long lastMinimapNationAlertUpdateMs = 0;
    /** Prevents hammering EarthMC API when nations index fails to load. */
    private static volatile long lastNationIndexAttemptMs = 0;
    // Caches for hot-path string work (recomputed only when the input changes).
    private static String activeServerAddress = null;
    private static boolean activeServerResult = false;
    private static String cachedSelfName = null;
    private static String cachedSelfKey = null;

    @Override
    public void onInitializeClient() {
        LOGGER.info("TownyMap Addon initialising...");

        config     = TownyMapConfig.load();
        apiClient  = new SquaremapApiClient(config);
        earthMcApi = new EarthMcApiClient();
        renderer   = new WorldMapRenderer(config, apiClient);
        ChunkCounterOverlay.loadSelection(config.chunkCounterSelection,
                config.chunkCounterGroups, config.activeChunkCounterGroup);
        loadTownDetailsCache();
        net.townymap.integration.CustomOverlayManager.reload();

        apiClient.start();
        TownyMapKeybinds.register();
        ClientSendMessageEvents.COMMAND.register(TownyMapMod::onCommandSent);
        ClientReceiveMessageEvents.GAME.register(TownyMapMod::onGameMessage);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
                .register(c -> net.townymap.integration.ShopWaypoints.tick());

        LOGGER.info("TownyMap Addon ready; map refreshes are deferred until map/minimap rendering is active ({})", config.squaremapBaseUrl);
    }

    private static void onCommandSent(String command) {
        if (apiClient == null || !isActiveOnCurrentServer()) return;
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (isTownClaimCommand(normalized)) {
            rememberPendingClaim();
        } else if (isTownUnclaimCommand(normalized)) {
            refreshTownClaimsAfterCommand();
        } else if (net.townymap.integration.ShopWaypoints.isShopFindCommand(normalized)) {
            net.townymap.integration.ShopWaypoints.armCapture();
        }
    }

    private static void onGameMessage(Component message, boolean overlay) {
        if (apiClient == null || !isActiveOnCurrentServer() || message == null) return;
        // Shop results are independent of the claim flow, so they have to be read before the
        // pending-claim early return below or they'd only ever parse right after a /town claim.
        if (net.townymap.integration.ShopWaypoints.onGameMessage(message.getString())) return;
        PendingClaim pending = pendingClaim;
        if (pending == null) return;

        long now = System.currentTimeMillis();
        if (pending.expired(now)) {
            pendingClaim = null;
            return;
        }

        String normalized = normalizeChatMessage(message.getString());
        if (isTownClaimSuccessMessage(normalized)) {
            pendingClaim = null;
            if (pending.townName().isBlank()) {
                resolveAndAddOptimisticClaimChunk(pending.chunkX(), pending.chunkZ());
            } else {
                addOptimisticClaimChunk(pending.chunkX(), pending.chunkZ(), pending.townName());
            }
            refreshTownClaimsAfterCommand();
        } else if (isTownClaimFailureMessage(normalized)) {
            pendingClaim = null;
        }
    }

    private static void refreshTownClaimsAfterCommand() {
        if (apiClient == null) return;
        apiClient.forceTownMarkerRefreshDelayed(150);
        apiClient.forceTownMarkerRefreshDelayed(750);
        apiClient.forceTownMarkerRefreshDelayed(2500);
        apiClient.forceTownMarkerRefreshDelayed(6500);
        apiClient.forceTownMarkerRefreshDelayed(12000);
    }

    private static boolean isTownClaimCommand(String normalized) {
        return normalized.equals("t claim") || normalized.startsWith("t claim ")
                || normalized.equals("town claim") || normalized.startsWith("town claim ");
    }

    private static boolean isTownUnclaimCommand(String normalized) {
        return normalized.equals("t unclaim") || normalized.startsWith("t unclaim ")
                || normalized.equals("town unclaim") || normalized.startsWith("town unclaim ");
    }

    /**
     * Your own EarthMC record, for the dashboard's town/nation card. Reuses the same cache the pending
     * claim tracker fills, so in most sessions it is already there; otherwise one lookup for one name.
     */
    public static EarthMcPlayerData selfPlayer() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getUser() == null) return null;
        String selfName = client.getUser().getName();
        EarthMcPlayerData cached = playerDetailsCache.get(townKey(selfName));
        if (cached == null && earthMcApi != null && isActiveOnCurrentServer()) {
            earthMcApi.fetchPlayer(selfName).thenAccept(d -> {
                if (d != null) playerDetailsCache.put(townKey(selfName), d);
            });
        }
        return cached;
    }

    private static volatile net.townymap.model.TownFullData selfTownFull = null;
    private static volatile String selfTownFetching = null;

    /**
     * Your own town's full record, so the dashboard can show EarthMC's own numTownBlocks/maxTownBlocks
     * exactly like the right-click town page does, instead of reconstructing the allowance from a
     * formula. maxTownBlocks already includes the nation bonus and any overrides EarthMC applies.
     */
    public static net.townymap.model.TownFullData selfTownFull() {
        EarthMcPlayerData self = selfPlayer();
        if (self == null || self.townName() == null || self.townName().isBlank()) return null;
        String town = self.townName();
        if (selfTownFull != null && town.equalsIgnoreCase(selfTownFull.name())) return selfTownFull;
        if (earthMcApi != null && !town.equalsIgnoreCase(selfTownFetching)) {
            selfTownFetching = town;
            earthMcApi.fetchTownFull(town).thenAccept(d -> { if (d != null) selfTownFull = d; });
        }
        return null;
    }

    private static void rememberPendingClaim() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.getUser() == null) return;
        String selfName = client.getUser().getName();
        int chunkX = floorToChunk(client.player.getX());
        int chunkZ = floorToChunk(client.player.getZ());

        EarthMcPlayerData cached = playerDetailsCache.get(townKey(selfName));
        pendingClaim = new PendingClaim(chunkX, chunkZ,
                cached != null ? cached.townName() : "",
                System.currentTimeMillis() + PENDING_CLAIM_TTL_MS);
        if (cached != null && !cached.townName().isBlank()) {
            return;
        }

        if (earthMcApi == null) return;
        earthMcApi.fetchPlayer(selfName).thenAccept(data -> {
            if (data == null || data.townName().isBlank()) return;
            playerDetailsCache.put(townKey(selfName), data);
            playerDetailsCache.put(townKey(data.name()), data);
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.execute(() -> {
                    PendingClaim pending = pendingClaim;
                    if (pending != null && pending.chunkX() == chunkX && pending.chunkZ() == chunkZ
                            && !pending.expired(System.currentTimeMillis())) {
                        pendingClaim = new PendingClaim(chunkX, chunkZ, data.townName(), pending.expiresAtMs());
                    }
                });
            }
        });
    }

    private static void resolveAndAddOptimisticClaimChunk(int chunkX, int chunkZ) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getUser() == null || earthMcApi == null) return;
        String selfName = client.getUser().getName();
        EarthMcPlayerData cached = playerDetailsCache.get(townKey(selfName));
        if (cached != null && !cached.townName().isBlank()) {
            addOptimisticClaimChunk(chunkX, chunkZ, cached.townName());
            return;
        }
        earthMcApi.fetchPlayer(selfName).thenAccept(data -> {
            if (data == null || data.townName().isBlank()) return;
            playerDetailsCache.put(townKey(selfName), data);
            playerDetailsCache.put(townKey(data.name()), data);
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.execute(() -> addOptimisticClaimChunk(chunkX, chunkZ, data.townName()));
            }
        });
    }

    private static String normalizeChatMessage(String message) {
        return message == null ? "" : message.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static boolean isTownClaimSuccessMessage(String normalized) {
        return normalized.contains("successfully claimed");
    }

    private static boolean isTownClaimFailureMessage(String normalized) {
        return normalized.contains("already been claimed")
                || normalized.contains("already belongs")
                || normalized.contains("not claimable")
                || normalized.contains("not enough available town blocks")
                || normalized.contains("cannot afford")
                || normalized.contains("can't afford")
                || normalized.contains("too close")
                || normalized.contains("too far")
                || normalized.contains("not attached")
                || normalized.contains("contains no valid plots")
                || normalized.contains("cannot claim")
                || normalized.contains("can't claim")
                || normalized.contains("unable to claim")
                || normalized.contains("not allowed to claim")
                || normalized.contains("must belong to a town")
                || normalized.contains("you don't belong to a town")
                || normalized.contains("you do not have permission to expand your town")
                || normalized.contains("another plugin stopped the claim")
                || normalized.contains("another plugin stopped your claim");
    }

    private static void addOptimisticClaimChunk(int chunkX, int chunkZ, String townName) {
        if (config == null || apiClient == null || townName == null || townName.isBlank()) return;
        TownData town = townByName(townName);
        int fillColor;
        int outlineColor;
        if (isFavorite(townName)) {
            fillColor = 0x44FFE066;
            outlineColor = 0xFFFFE066;
        } else if (town != null) {
            fillColor = town.argbFillColor(Math.max(config.fillAlpha, 90));
            outlineColor = town.argbColor(config.borderAlpha);
        } else {
            int rgb = config.statusHighlightColor & 0x00FFFFFF;
            fillColor = (Math.max(config.fillAlpha, 90) << 24) | rgb;
            outlineColor = 0xFF000000 | rgb;
        }

        long now = System.currentTimeMillis();
        optimisticClaimChunks.removeIf(chunk -> chunk.chunkX() == chunkX && chunk.chunkZ() == chunkZ);
        optimisticClaimChunks.add(new OptimisticClaimChunk(chunkX, chunkZ, townName, fillColor, outlineColor,
                now + OPTIMISTIC_CLAIM_TTL_MS, playerWorldResolved()));
    }

    private static TownData townByName(String townName) {
        if (apiClient == null || townName == null) return null;
        // The player's world: this seeds the colours of a chunk they just claimed, which happens where
        // they stand. Reading the shown world meant claiming on Earth with the map pinned to the Moon
        // found no town and fell back to default colours.
        for (TownData town : apiClient.getTowns(playerWorldResolved())) {
            if (town.name().equalsIgnoreCase(townName)) return town;
        }
        return null;
    }

    private static int floorToChunk(double blockCoord) {
        return Math.floorDiv((int) Math.floor(blockCoord), 16);
    }

    /**
     * Manual refresh from the settings screen. Claims already reload on their own interval, so this is
     * only for not waiting; it exists because removing the keybind and the command left no way to force
     * one. Confirms in chat, since the settings screen gives no visible sign anything happened.
     */
    /** One line of map-data status for the readout under the coordinates: text plus an ARGB colour. */
    public record MapDataStatus(String text, int argb) {}

    private static final int STATUS_OK    = 0xFFC8C8C8;
    private static final int STATUS_STALE = 0xFFE0B040;
    private static final int STATUS_BAD   = 0xFFE2603B;
    private static final int STATUS_ARCHIVE = 0xFF9C7BE0;

    /**
     * What to show under the coordinates. Reports the age of the claims actually on screen -- not of the
     * last attempt -- so a run of failures reads as stale instead of silently looking current, which is
     * exactly how a squaremap outage used to present as "the mod is broken".
     */
    public static MapDataStatus mapDataStatus() {
        if (apiClient == null) return new MapDataStatus("Claims unavailable", STATUS_BAD);
        if (apiClient.isArchiveActive()) return new MapDataStatus("Archive snapshot", STATUS_ARCHIVE);

        long ok = apiClient.lastClaimsSuccessMs();
        if (ok == 0) {
            return apiClient.isClaimsFetchFailing()
                    ? new MapDataStatus("Claims unavailable", STATUS_BAD)
                    : new MapDataStatus("Loading claims...", STATUS_OK);
        }
        long ageSec = Math.max(0, (System.currentTimeMillis() - ok) / 1000L);
        String age = ageSec < 60 ? ageSec + "s ago"
                : ageSec < 3600 ? (ageSec / 60) + "m ago"
                : (ageSec / 3600) + "h ago";

        if (apiClient.isClaimsFetchFailing()) {
            return new MapDataStatus("Claims " + age + " (refresh failed)", STATUS_BAD);
        }
        int refused = renderer != null ? renderer.tileRefusalStatus() : 0;
        if (refused != 0) {
            return new MapDataStatus("Map imagery refused (HTTP " + refused + ")", STATUS_BAD);
        }
        // Claims reload every 60s, so anything past two intervals means something is quietly wrong.
        return new MapDataStatus("Claims " + age, ageSec > 150 ? STATUS_STALE : STATUS_OK);
    }

    /**
     * Y of our status line. Xaero draws its coordinate readout at y=4 with a 12px line pitch (both
     * confirmed by disassembling GuiMap), so 4 + 12*2 clears the coordinate line and the optional
     * biome line beneath it. Fixed rather than measured: knowing whether the biome line was drawn
     * means reading Xaero's DISPLAY_HOVERED_BIOME at runtime, and with it off this just reads as
     * slightly wider spacing.
     */
    private static final int STATUS_LINE_Y = 28;
    private static final String STATUS_REFRESH = " [R]";
    private static int statusBtnX1, statusBtnX2;
    private static boolean statusBtnShown = false;

    /**
     * Draws the data-freshness line under Xaero's coordinate readout, with a click-to-refresh button.
     *
     * <p>Called from a mixin anchored inside Xaero's own readout block, which sits behind its hiddenUI
     * check -- so hiding Xaero's UI hides this too, for free.
     */
    public static void renderMapDataStatus(GuiGraphicsExtractor ctx) {
        statusBtnShown = false;
        if (config == null || !config.dataStatusEnabled) return;
        if (!isActiveOnCurrentServer() || hideChromeForScreenshot()) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getWindow() == null) return;

        net.minecraft.client.gui.Font font = client.font;
        MapDataStatus status = mapDataStatus();
        int textW = font.width(status.text());
        int btnW = font.width(STATUS_REFRESH);
        int total = textW + btnW;
        int x = client.getWindow().getGuiScaledWidth() / 2 - total / 2;

        // Same backdrop Xaero puts behind its own readout, so the two read as one block.
        ctx.fill(x - 2, STATUS_LINE_Y - 1, x + total + 2, STATUS_LINE_Y + 9, 0x66000000);
        ctx.text(font, status.text(), x, STATUS_LINE_Y, status.argb(), false);
        ctx.text(font, STATUS_REFRESH, x + textW, STATUS_LINE_Y, 0xFF7FB8FF, false);
        statusBtnX1 = x + textW;
        statusBtnX2 = x + total;
        statusBtnShown = true;
    }

    /** Handles a click on the refresh button. True if it was consumed. */
    public static boolean clickMapDataStatus(double mouseX, double mouseY) {
        if (!statusBtnShown) return false;
        if (mouseX < statusBtnX1 || mouseX > statusBtnX2) return false;
        if (mouseY < STATUS_LINE_Y - 1 || mouseY > STATUS_LINE_Y + 9) return false;
        refreshTownClaimsFromSettings();
        return true;
    }

    public static void refreshTownClaimsFromSettings() {
        if (config == null || !isActiveOnCurrentServer()) return;
        forceRefreshTownClaims();
        sendFeedback("Refreshing towns and claims from squaremap...", ChatFormatting.WHITE);
    }

    public static void forceRefreshTownClaims() {
        if (apiClient == null) return;
        invalidateTownRenderCaches();
        // Imagery too, not just the claims. Tiles are keyed by world so they never need clearing for
        // correctness any more, which left no way to recover if a tile ever cached badly -- and one did,
        // via the close-while-reachable race fixed alongside this. A deliberate refresh is the right
        // place for that: only this path reaches it, so nothing automatic pays for it.
        if (renderer != null) renderer.clearSquaremapTiles();
        apiClient.forceTownMarkerRefresh();
    }

    /** Which maps the layer was last shown on, so the quick toggle restores that choice rather than
     *  flattening a "minimap only" preference into "both" every time it's switched back on. */
    private static int lastSquaremapBackgroundMode = 3;

    /**
     * Turns the squaremap layer on or off for the world map alone, leaving the minimap's state as it
     * is. The on-map button that calls this only exists on the world map, so it should govern that map
     * rather than flattening a per-map choice back into "both".
     */
    public static void setSquaremapOnWorldMap(boolean on) {
        if (config == null) return;
        config.squaremapBackgroundMode = (on ? 1 : 0) | (config.squaremapOnMinimap() ? 2 : 0);
        if (config.squaremapBackgroundMode != 0) {
            lastSquaremapBackgroundMode = config.squaremapBackgroundMode;
        }
        config.save();
    }

    // Selectable map modes. "Overclaim" (2) is omitted until EarthMC's API exposes active-resident
    // counts — its claim max is wrong without them, so over-claim detection misfires. The case is kept
    // (commented) in the highlight switch and labels so it can be re-enabled later.
    private static final int[] STATUS_MODES = {0, 1, 2, 3, 4, 5, 6};   // 6 = Planning

    /** Advance the map-mode value to the next/previous selectable mode, skipping disabled ones. */
    public static int nextStatusMode(int current, boolean backward) {
        int idx = 0;
        for (int i = 0; i < STATUS_MODES.length; i++) {
            if (STATUS_MODES[i] == current) { idx = i; break; }
        }
        idx = Math.floorMod(idx + (backward ? -1 : 1), STATUS_MODES.length);
        return STATUS_MODES[idx];
    }


    private static String onOff(boolean enabled) {
        return enabled ? "ON" : "OFF";
    }

    private static String borderModeLabel(int mode) {
        return switch (mode) {
            case 1 -> "Countries";
            case 2 -> "States";
            default -> "OFF";
        };
    }

    private static String townStatusModeLabel(int mode) {
        return switch (mode) {
            case 1 -> "Public";
            case 2 -> "Overclaim";
            case 3 -> "Open";
            case 4 -> "Meganations";
            case 5 -> "Alliances";
            default -> "None";
        };
    }

    // ── Meganation / Alliance map layers ─────────────────────────────────────
    // Modes 4 (Meganations) and 5 (Alliances) recolour each town by the alliance its nation belongs to,
    // using data from emcstats.bot.nu. Unlike the single-colour highlight modes (1–3), every alliance has
    // its own colour, so the recolour is applied to the town SOURCE and flows through the whole tile bake.
    private static final net.townymap.api.AllianceClient allianceClient = new net.townymap.api.AllianceClient();
    private static volatile boolean allianceLoading = false;
    private static volatile long allianceLoadedMs = 0;
    private static volatile int allianceDataVersion = 0;
    private static volatile Map<String, int[]> megaByNation = Map.of();       // nation(lower) → {outline,fill}
    private static volatile Map<String, int[]> allianceByNation = Map.of();
    private static volatile Map<String, List<String>> megaNamesByNation = Map.of();     // nation → meganation labels
    private static volatile Map<String, List<String>> allianceNamesByNation = Map.of(); // nation → alliance labels
    // Full records keyed by label (lower-case), so a bloc's own panel can list its roster. Previously only
    // the colours and labels survived the load and the rest of each record was thrown away.
    private static volatile Map<String, net.townymap.api.AllianceClient.Alliance> allianceByName = Map.of();

    /** The towns the map is currently drawing — used to roll a bloc's totals up without any new requests. */
    public static List<TownData> currentTowns() {
        return apiClient == null ? List.of() : apiClient.getTowns();
    }

    /** The nation a town belongs to (from the squaremap tooltips), or null if it is nationless. */
    public static String townNationOf(String townKey) {
        return townNationAt(townKey);
    }

    // ── Clean map screenshot ─────────────────────────────────────────────────
    // A plain F2 grabs the map with all of our chrome on top — buttons, search bar, panels — which is not
    // what anyone wants to paste into Discord. Arming this hides our own UI for one frame and captures the
    // map on its own; the countdown gives the frame time to render before the framebuffer is read.
    private static volatile int cleanShotFrames = 0;
    private static volatile boolean cleanShotReady = false;
    private static volatile long cleanShotArmedAtMs = 0L;
    /** A shot that hasn't completed in this long is abandoned — see armMapScreenshot. */
    private static final long CLEAN_SHOT_TIMEOUT_MS = 2_000L;

    /**
     * Arms a clean map screenshot: our overlays are hidden for the next frames, then the map is captured.
     *
     * <p>The countdown only advances while the world map is drawing, so arming with the map closed would
     * otherwise leave the "hide everything" flag set forever — which is exactly what happened when the
     * default key (P) was pressed in-game. The timestamp lets the state expire on its own instead.
     */
    public static void armMapScreenshot() {
        cleanShotReady = false;
        cleanShotFrames = 3;
        cleanShotArmedAtMs = System.currentTimeMillis();
    }

    /** True when Xaero's world map is the active screen. */
    private static final Object TEXT_INPUT_OWNER = new Object();
    private static boolean textInputWanted = false;
    private static boolean textInputWarned = false;

    /**
     * Tells the game whether one of our hand-drawn text fields has focus.
     *
     * <p>26.3 runs on SDL, where characters only arrive while text input has been started. GLFW sent
     * them unconditionally, so these fields never had to ask and typing worked by accident; under SDL
     * the same code receives nothing at all. Vanilla's EditBox goes through
     * Minecraft.onTextInputFocusChange, which wants a GuiEventListener -- our fields are drawn by hand
     * and are not widgets, so they talk to the manager directly.
     *
     * <p>Called from the fields' render paths every frame rather than on a transition: Gui.setScreen
     * stops text input outright, so a screen rebuilt while the bar is focused (a dimension sync, a
     * resize, one detail page opening another) would otherwise silently kill typing. Both manager
     * calls are no-ops once the state already matches, so repeating them costs nothing.
     */
    public static void setTextInputActive(boolean active) {
        textInputWanted = active;
        try {
            Minecraft client = Minecraft.getInstance();
            if (client == null || client.textInputManager() == null) return;
            if (active) client.textInputManager().startTextInput(TEXT_INPUT_OWNER);
            else client.textInputManager().stopTextInput(TEXT_INPUT_OWNER);
        } catch (Throwable t) {
            if (!textInputWarned) {
                textInputWarned = true;
                LOGGER.warn("[TownyMap] Could not toggle text input -- typing in our search bars may not work", t);
            }
        }
    }

    /**
     * Turns text input back off when the screen that owned a focused field has gone away.
     * The fields only speak up while they render, so a map closed mid-typing would otherwise
     * leave input started for good.
     */
    private static void tickTextInputFocus() {
        if (!textInputWanted) return;
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.gui.screen() instanceof net.townymap.gui.DetailScreen) return;
        if (isWorldMapScreenActive()) return;
        setTextInputActive(false);
    }

    public static boolean isWorldMapOpen() {
        Minecraft client = Minecraft.getInstance();
        return client != null && client.gui.screen() != null
                && client.gui.screen().getClass().getName().startsWith("xaero.map.gui.GuiMap");
    }

    /** True once an armed shot has outlived its window, so the hidden state can never stick. */
    private static boolean cleanShotExpired() {
        if (cleanShotFrames <= 0 && !cleanShotReady) return false;
        if (System.currentTimeMillis() - cleanShotArmedAtMs <= CLEAN_SHOT_TIMEOUT_MS) return false;
        cleanShotFrames = 0;
        cleanShotReady = false;
        return true;
    }

    /**
     * True while a clean shot is pending, so the map's own chrome is skipped this frame.
     *
     * <p>Includes the window between the last armed frame and the capture itself: ticks run at 20/s and
     * frames much faster, so restoring the chrome the moment the countdown ended meant the frame actually
     * captured had the buttons back on it.
     */
    public static boolean hideChromeForScreenshot() {
        if (cleanShotExpired()) return false;
        return cleanShotFrames > 0 || cleanShotReady;
    }

    /** True while the frame being drawn is one that will be captured. */
    public static boolean composingScreenshot() {
        if (cleanShotExpired()) return false;
        return cleanShotFrames > 0 || cleanShotReady;
    }

    /** Whether player dots belong in the screenshot being composed. */
    public static boolean screenshotWantsPlayers() {
        return config == null || config.screenshotPlayers;
    }

    /** Whether nation stars belong in the screenshot being composed. */
    public static boolean screenshotWantsNationStars() {
        return config == null || config.screenshotNationStars;
    }

    /** Whether dimmed (blacked-out) towns should be dropped from the screenshot rather than shot black. */
    public static boolean screenshotHidesDimmedTowns() {
        return config != null && config.screenshotHideDimmedTowns;
    }

    /** Called at the end of the map's own draw: counts down, then marks the frame ready to be captured. */
    public static void captureMapScreenshotIfArmed() {
        if (cleanShotFrames <= 0) return;
        if (--cleanShotFrames > 0) return;
        cleanShotReady = true;   // this frame drew the map without our chrome; grab it between frames
    }

    /**
     * Takes the pending capture, from a client tick rather than mid-render.
     *
     * <p>This build of Minecraft records GUI draws into a render state and only submits them at the end of
     * the frame, so reading the framebuffer during rendering caught Xaero's map but none of our own layers —
     * the squaremap overlay, town borders and player dots were all still queued. Between frames the
     * framebuffer holds the finished picture, which is what we want anyway.
     */
    public static void captureMapScreenshotIfReady() {
        if (!cleanShotReady) return;
        cleanShotReady = false;
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        try {
            // 26.2 exposes neither the main render target nor the chat component, so use the convenience
            // grab that does the whole job. The filename is Minecraft's default rather than ours.
            net.minecraft.client.Screenshot.grab(client, true);
        } catch (Exception e) {
            LOGGER.warn("[TownyMap] Map screenshot failed: {}", e.toString());
        }
    }

    /**
     * Where a player is right now, for jumping to them. Live position first; if they aren't on the feed —
     * offline, or hidden from the public map — their last-seen ghost, which is the last place we saw them.
     */
    public static net.townymap.model.MapJumpTarget playerJumpTarget(String name) {
        if (name == null || name.isBlank() || apiClient == null) return null;
        for (PlayerMarker m : apiClient.getPlayers()) {
            if (m.name() != null && m.name().equalsIgnoreCase(name)) {
                return new net.townymap.model.MapJumpTarget(m.name(), m.x(), m.z());
            }
        }
        for (GhostMarker g : lastSeenGhosts()) {
            if (g.name() != null && g.name().equalsIgnoreCase(name)) {
                return new net.townymap.model.MapJumpTarget(g.name(), (int) g.x(), (int) g.z());
            }
        }
        return null;
    }

    /** Resident count for a town, parsed from its squaremap popup; -1 if unknown. */
    public static int townResidentsOf(String townKey) {
        if (townKey != null && isArchiveMode()) {
            // Same reason as townNationAt: the live resident map isn't swapped for the snapshot, so the
            // search filter (r>30) was matching archived towns against today's counts.
            net.townymap.api.ArchiveClient.ArchiveTown at =
                    archiveTownDetails.get(townKey.toLowerCase(Locale.ROOT));
            if (at != null) return at.residentCount();
        }
        return apiClient == null ? -1 : apiClient.getTownResidents(townKey);
    }

    /** Cached nation record, or null if it hasn't been fetched yet. Honours archive mode. */
    public static EarthMcNationData nationDetails(String nation) {
        if (nation == null || nation.isBlank()) return null;
        return activeNationDetails().get(nation.toLowerCase(Locale.ROOT));
    }

    /** The full record for an alliance/meganation by name, or null if the roster hasn't loaded it. */
    public static net.townymap.api.AllianceClient.Alliance allianceByName(String name) {
        return name == null ? null : allianceByName.get(name.toLowerCase(Locale.ROOT));
    }

    /** Bumps whenever the alliance colour maps change, so the renderer knows to re-bake. */
    public static int allianceDataVersion() { return allianceDataVersion; }

    /** Meganation names the given nation belongs to (usually 0 or 1), or empty. */
    public static List<String> meganationsForNation(String nationBare) {
        if (nationBare == null || nationBare.isBlank()) return List.of();
        List<String> l = megaNamesByNation.get(nationBare.toLowerCase(Locale.ROOT));
        return l == null ? List.of() : l;
    }

    /** Alliance names the given nation belongs to, or empty. */
    public static List<String> alliancesForNation(String nationBare) {
        if (nationBare == null || nationBare.isBlank()) return List.of();
        List<String> l = allianceNamesByNation.get(nationBare.toLowerCase(Locale.ROOT));
        return l == null ? List.of() : l;
    }

    /** Compact membership tag for the search results, e.g. "Meganation: X" / "Alliance: Y" / "" if none. */
    public static String allianceTagForNation(String nationBare) {
        List<String> m = meganationsForNation(nationBare);
        List<String> a = alliancesForNation(nationBare);
        StringBuilder sb = new StringBuilder();
        if (!m.isEmpty()) sb.append(m.size() > 1 ? "Meganations: " : "Meganation: ").append(String.join(", ", m));
        if (!a.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(a.size() > 1 ? "Alliances: " : "Alliance: ").append(String.join(", ", a));
        }
        return sb.toString();
    }

    public static boolean isAllianceMapMode() {
        return config != null && (config.townStatusOverlayMode == 4 || config.townStatusOverlayMode == 5);
    }

    /** Outline+fill RGB for a nation in the active alliance layer, or null if that nation is in none. */
    public static int[] allianceColorsForNation(String nationBare, boolean mega) {
        if (nationBare == null || nationBare.isBlank()) return null;
        Map<String, int[]> m = mega ? megaByNation : allianceByNation;
        return m.get(nationBare.toLowerCase(Locale.ROOT));
    }

    /** A town's nation name with any "Capital of " prefix stripped, for matching against alliance rosters. */
    public static String bareTownNation(String townName) {
        if (apiClient == null || townName == null) return null;
        String n = townNationAt(townKey(townName));
        if (n == null) return null;
        if (n.regionMatches(true, 0, "Capital of ", 0, 11)) n = n.substring(11);
        return n.trim();
    }

    /** Loads the alliance/meganation data if the cache is missing or stale (5 min). Called every frame the
     *  map is open so the map modes, town menus and nation search always have it; self-throttles. */
    public static void ensureAllianceData() {
        if (allianceLoading) return;
        if (allianceDataVersion > 0 && System.currentTimeMillis() - allianceLoadedMs < 300_000L) return;
        allianceLoading = true;
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            List<net.townymap.api.AllianceClient.Alliance> list = allianceClient.fetch();
            Minecraft client = Minecraft.getInstance();
            Runnable apply = () -> {
                allianceLoading = false;
                allianceLoadedMs = System.currentTimeMillis();
                if (list.isEmpty()) return;
                Map<String, int[]> mega = new java.util.HashMap<>(), alli = new java.util.HashMap<>();
                Map<String, List<String>> megaNames = new java.util.HashMap<>(), alliNames = new java.util.HashMap<>();
                for (net.townymap.api.AllianceClient.Alliance a : list) {
                    boolean m = a.mega();
                    Map<String, int[]> colTarget = m ? mega : alli;
                    Map<String, List<String>> nameTarget = m ? megaNames : alliNames;
                    int[] cols = {a.outlineRgb(), a.fillRgb()};
                    String label = a.label() == null || a.label().isBlank() ? a.identifier() : a.label();
                    for (String n : a.nationsLower()) {
                        colTarget.putIfAbsent(n, cols);   // first alliance's colour wins (a town is one colour)
                        List<String> names = nameTarget.computeIfAbsent(n, k -> new java.util.ArrayList<>());
                        if (!names.contains(label)) names.add(label);   // but list every bloc it's part of
                    }
                }
                Map<String, net.townymap.api.AllianceClient.Alliance> byName = new java.util.HashMap<>();
                for (net.townymap.api.AllianceClient.Alliance a : list) {
                    String label = a.label() == null || a.label().isBlank() ? a.identifier() : a.label();
                    byName.putIfAbsent(label.toLowerCase(Locale.ROOT), a);
                }
                allianceByName = Map.copyOf(byName);
                megaByNation = Map.copyOf(mega);
                allianceByNation = Map.copyOf(alli);
                megaNamesByNation = freezeLists(megaNames);
                allianceNamesByNation = freezeLists(alliNames);
                allianceDataVersion++;
                if (renderer != null) renderer.invalidateTownCaches();   // re-bake tiles in alliance colours
                net.townymap.gui.TownSearchOverlay.invalidateResults();  // refresh nation labels with tags
            };
            if (client != null) client.execute(apply); else apply.run();
        });
    }

    private static Map<String, List<String>> freezeLists(Map<String, List<String>> m) {
        Map<String, List<String>> out = new java.util.HashMap<>(m.size());
        for (Map.Entry<String, List<String>> e : m.entrySet()) out.put(e.getKey(), List.copyOf(e.getValue()));
        return Map.copyOf(out);
    }

    public static void onTownMarkersUpdated() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            pruneOptimisticClaimChunks(true);
            invalidateTownRenderCaches();
            return;
        }
        client.execute(() -> {
            pruneOptimisticClaimChunks(false);
            invalidateTownRenderCaches();
        });
    }

    private static void invalidateTownRenderCaches() {
        if (renderer != null) renderer.invalidateTownCaches();
        TownyMinimapOverlay.invalidateTownCache();
        requestMinimapTownHighlightRefresh();
    }

    public static void saveChunkCounterSelection(List<Long> selectedChunks) {
        if (config == null) return;
        config.chunkCounterSelection = new ArrayList<>(selectedChunks);
        config.save();
    }

    public static void saveChunkCounterState(List<Long> selectedChunks, List<List<Long>> selectedGroups,
                                             int activeGroup) {
        if (config == null) return;
        config.chunkCounterSelection = new ArrayList<>(selectedChunks);
        ArrayList<List<Long>> groups = new ArrayList<>();
        if (selectedGroups != null) {
            for (List<Long> group : selectedGroups) {
                groups.add(group == null ? new ArrayList<>() : new ArrayList<>(group));
            }
        }
        config.chunkCounterGroups = groups;
        config.activeChunkCounterGroup = Math.max(0, Math.min(6, activeGroup));
        config.save();
    }

    // ── Last-seen player ghosts ──────────────────────────────────────────────
    /** A player at their last-seen spot after they dropped off the live map feed. */
    public record GhostMarker(String name, String uuid, int x, int z, int alpha) {}

    private static final long GHOST_MAX_AGE_MS = 5 * 60_000L;   // stop showing a ghost 5 min after it vanished
    /**
     * Cached per world. One shared list was fine while ghosts were Earth-only; now that the world map
     * and the minimap can ask about different worlds within the same recompute window, a single entry
     * would hand the second caller the first one's ghosts.
     */
    private static final Map<String, List<GhostMarker>> cachedGhosts = new ConcurrentHashMap<>();
    private static final Map<String, Long> cachedGhostsAt = new ConcurrentHashMap<>();
    private static final long GHOST_RECOMPUTE_MS = 500L;   // ghosts move slowly; no need to rebuild per frame

    /**
     * Players to draw at their last-seen position, for the "last seen" toggle.
     *
     * <p>A player in the live map feed is never a ghost. Dropping off the feed does not mean offline —
     * EarthMC hides players who are in the Nether/End — so the client's own player list is used as the
     * online oracle: it holds every player online on the server, across dimensions. Still in the list but
     * off the feed = online but off-map (Nether/hidden) → show them, with their real head. Gone from the
     * list = logged off → drop them, so a logged-off player is removed rather than left as a headless dot.
     */
    public static List<GhostMarker> lastSeenGhosts() {
        return lastSeenGhosts(activeWorldKey());
    }

    /** Ghosts for one surface's world -- the minimap passes the world the player is standing in. */
    public static List<GhostMarker> lastSeenGhosts(String worldKey) {
        return lastSeenGhostsInner(worldKey == null ? WORLD_OVERWORLD : worldKey);
    }

    private static List<GhostMarker> lastSeenGhostsInner(String worldKey) {
        if (config == null || !config.playerLastSeen || apiClient == null || !isActiveOnCurrentServer()) {
            return List.of();
        }

        // Recompute a few times a second, not every frame: the set of ghosts and their fixed positions
        // change slowly, so rebuilding the feed set and scanning history at 60fps was pure waste.
        Long cachedAt = cachedGhostsAt.get(worldKey);
        long sinceRecompute = cachedAt == null ? Long.MAX_VALUE : System.currentTimeMillis() - cachedAt;
        if (sinceRecompute >= 0 && sinceRecompute < GHOST_RECOMPUTE_MS) {
            return cachedGhosts.getOrDefault(worldKey, List.of());
        }

        Minecraft client = Minecraft.getInstance();
        var handler = client == null ? null : client.getConnection();
        if (handler == null) return List.of();

        long now = System.currentTimeMillis();
        // Both sides read the same world. Comparing one world's history against another's feed would
        // call every player in the first a ghost -- which is exactly what happened before the history
        // knew which world a position came from.
        Set<String> feed = new java.util.HashSet<>();
        for (PlayerMarker m : apiClient.getPlayers(worldKey)) {
            if (m.name() != null) feed.add(m.name().toLowerCase(Locale.ROOT));
        }
        Map<String, net.townymap.model.PlayerHistoryEntry> history = apiClient.getPlayerHistory();

        List<GhostMarker> out = new ArrayList<>();
        for (var e : history.entrySet()) {
            if (feed.contains(e.getKey())) continue;       // still on the map → not a ghost
            if (!worldKey.equals(e.getValue().worldOrDefault())) continue;   // last seen in another world
            var h = e.getValue();
            long age = now - h.lastSeenMs();
            if (age < 0 || age > GHOST_MAX_AGE_MS) continue;
            if (handler.getPlayerInfo(h.name()) == null) continue;   // logged off → remove
            out.add(new GhostMarker(h.name(), h.uuid(), h.x(), h.z(), 0xFF));
        }

        cachedGhosts.put(worldKey, out);
        cachedGhostsAt.put(worldKey, now);
        return out;
    }

    public static List<OptimisticClaimChunk> optimisticClaimChunks() {
        return optimisticClaimChunks(activeWorldKey());
    }

    /** Claims belonging to one world -- the minimap asks for the one the player is standing in. */
    public static List<OptimisticClaimChunk> optimisticClaimChunks(String worldKey) {
        // Empty almost always -- claims live for seconds after claiming -- and this is called three
        // times a frame across the two surfaces, so do not allocate for the common case.
        if (optimisticClaimChunks.isEmpty()) return List.of();
        pruneOptimisticClaimChunks(false);
        if (optimisticClaimChunks.isEmpty()) return List.of();
        List<OptimisticClaimChunk> out = new ArrayList<>();
        for (OptimisticClaimChunk c : optimisticClaimChunks) if (c.inWorld(worldKey)) out.add(c);
        return out;
    }

    private static void pruneOptimisticClaimChunks(boolean clearAll) {
        long now = System.currentTimeMillis();
        if (clearAll || apiClient == null) {
            optimisticClaimChunks.clear();
            return;
        }
        if (optimisticClaimChunks.isEmpty()) return;
        // Checked against the claim's OWN world, not the one being shown. A claim made on Earth while
        // the map was pinned to the Moon was compared with lunar towns, never matched, and so lingered
        // as a pending overlay until its TTL ran out instead of clearing the moment it went live.
        optimisticClaimChunks.removeIf(chunk -> chunk.expired(now)
                || confirmedClaimChunk(chunk, apiClient.getTowns(
                        chunk.world() == null || chunk.world().isEmpty()
                                ? activeWorldKey() : chunk.world())));
    }

    private static boolean confirmedClaimChunk(OptimisticClaimChunk chunk, List<TownData> towns) {
        double centerX = chunk.chunkX() * 16 + 8.0;
        double centerZ = chunk.chunkZ() * 16 + 8.0;
        TownData town = TownHoverOverlay.townAt(centerX, centerZ, towns);
        return town != null && town.name().equalsIgnoreCase(chunk.townName());
    }

    private record PendingClaim(int chunkX, int chunkZ, String townName, long expiresAtMs) {
        private boolean expired(long nowMs) {
            return nowMs >= expiresAtMs;
        }
    }

    /**
     * Called by MixinGuiMap every frame while Xaero's WorldMap is open.
     */
    public static void renderSquaremapBackground(GuiGraphicsExtractor ctx,
                                                 double cameraX, double cameraZ,
                                                 double scale, int screenW, int screenH) {
        if (!isActiveOnCurrentServer()) return;
        if (renderer != null) {
            boolean moving = updateWorldMapMovement(cameraX, cameraZ, scale);
            renderer.renderSquaremapBackground(ctx, cameraX, cameraZ, scale, screenW, screenH, moving);
        }
    }

    // ── Search dismiss ─────────────────────────────────────────────────────────
    // The search bar / result persists across panning; it clears on a fresh map (re)open or when the user
    // clicks AWAY — a left-click on the map that isn't a pan-drag, or a right-click on a new town.
    private static Object lastSearchMapInstance = null;
    private static boolean suppressNextPanClear = false;   // retained: jumpTo() still calls it (now a no-op)

    // A left-click on the map arms a pending dismiss; the next frames decide. If the camera PANS (drag) we
    // keep the result up; otherwise (button released, or a short grace window elapses with no pan) it was a
    // genuine click-away and we dismiss. Camera movement is the drag signal — reliable across MC versions;
    // GLFW is only a fast release hint, with a time fallback so the dismiss always fires.
    private static boolean armedMapDismiss = false;
    private static double armCamX, armCamZ;
    private static long armTimeMs = 0L;
    private static final long DISMISS_DELAY_MS = 200L;   // fallback if the GLFW release read is unavailable

    /** Called once per frame by the GuiMap mixin with the raw camera. Clears the search on a fresh map
     *  (re)open, and resolves an armed click-away dismiss (pan = keep, click = dismiss). */
    public static void onWorldMapFrame(Object mapInstance, double cameraX, double cameraZ) {
        if (mapInstance != lastSearchMapInstance) {       // map (re)opened → start with a fresh bar
            lastSearchMapInstance = mapInstance;
            armedMapDismiss = false;
            TownSearchOverlay.reset();
            // Deliberately does NOT request a dimension sync. Switching makes Xaero rebuild the map,
            // which hands us a fresh GuiMap instance, which looked like the user reopening the map --
            // so we asked for another switch, and it never settled. tickWorldMapOpenState covers the
            // real open/close transitions on its own.
            return;
        }
        if (armedMapDismiss) {
            if (Math.abs(cameraX - armCamX) > 0.5 || Math.abs(cameraZ - armCamZ) > 0.5) {
                armedMapDismiss = false;                  // camera moved → it was a pan-drag → keep the result
            } else {
                Minecraft mc = Minecraft.getInstance();
                boolean released = mc != null && mc.mouseHandler != null && !mc.mouseHandler.isLeftPressed();
                if (released || System.currentTimeMillis() - armTimeMs >= DISMISS_DELAY_MS) {
                    armedMapDismiss = false;
                    dismissOnMapClick();                  // released (or timed out) without panning → click-away
                }
            }
        }
    }

    /** A left-click landed on the map (not on our UI). Arm a dismiss that fires unless the camera pans first,
     *  so dragging to pan leaves the search result / town popup up. */
    public static void armMapClickDismiss(double cameraX, double cameraZ) {
        armCamX = cameraX;
        armCamZ = cameraZ;
        armTimeMs = System.currentTimeMillis();
        armedMapDismiss = true;
    }

    /** The click-away dismiss: clears the search bar/result AND the town popup. */
    public static void dismissOnMapClick() {
        armedMapDismiss = false;
        dismissTownInfo();
        // A filter is a view of the map, not a transient lookup: clicking away should let you pan and zoom
        // around the highlighted towns. Only the bar's focus is dropped, so the dimming survives.
        if (net.townymap.gui.TownSearchOverlay.isFilterActive()) {
            net.townymap.gui.TownSearchOverlay.unfocusKeepingFilter();
            return;
        }
        TownSearchOverlay.reset();
    }

    /** Retained no-op: jumpTo() still calls this, but panning no longer clears the search, so there's
     *  nothing to suppress. */
    public static void suppressNextPanClear() {
        suppressNextPanClear = true;
    }

    public static void renderOnWorldMap(GuiGraphicsExtractor ctx,
                                        double cameraX, double cameraZ,
                                        double scale, int screenW, int screenH) {
        if (!isActiveOnCurrentServer()) return;
        if (renderer != null) {
            apiClient.tickWhileMapOpen();
            refreshPlayerIndexIfNeeded();
            refreshNationIndexIfNeeded();
            requestNationCapitalDetails();
            requestVisibleTownDetails(cameraX, cameraZ, scale, screenW, screenH);
            requestVisiblePlayerDetails(cameraX, cameraZ, scale, screenW, screenH);
            ensureAllianceData();   // keep the alliance/meganation data warm for the map modes, town menus & search
            renderer.render(ctx, cameraX, cameraZ, scale, screenW, screenH,
                    townDetailsCache, playerDetailsCache, activeNationDetails());
            if (config.customOverlaysEnabled) {
                net.townymap.integration.CustomOverlayManager.render(ctx, cameraX, cameraZ, scale, screenW, screenH);
            }
        }
    }

    /** Draws the world-map player dots in the renderPreDropdown pass (after the town-tile batch is flushed),
     *  so they sit above the textured outline tiles instead of being painted over — the cause of the dots
     *  "blinking" at zoom-out. Positions/details are the same live squaremap data used everywhere. */
    public static void renderWorldMapLatePass(GuiGraphicsExtractor ctx, double cameraX, double cameraZ,
                                             double scale, int screenW, int screenH) {
        if (!isActiveOnCurrentServer() || renderer == null || config == null) return;
        // Join-range zone for the selected nation, under the player dots.
        if (config.nationRangeEnabled) {
            // Planning always shows its own nation's zone — that's the whole point of the mode — otherwise
            // it's whatever the info panel's range button has turned on.
            String rangeNation = net.townymap.gui.PlanningOverlay.isActive()
                    && net.townymap.gui.PlanningOverlay.hasNation()
                    ? net.townymap.gui.PlanningOverlay.nation()
                    : net.townymap.gui.TownSearchOverlay.rangeNationName();
            if (rangeNation != null) {
                EarthMcNationData nd = activeNationDetails().get(rangeNation.toLowerCase(Locale.ROOT));
                renderer.renderNationJoinRange(ctx, rangeNation, nd, cameraX, cameraZ, scale, screenW, screenH);
            }
        }
        if (!config.playersEnabled) return;   // this branch gates the player layer here, not inside it
        if (composingScreenshot() && !screenshotWantsPlayers()) return;   // a map picture, not a who's-online
        renderer.renderPlayersLayer(ctx, cameraX, cameraZ, scale, screenW, screenH, playerDetailsCache);
    }

    /**
     * Whether we redraw the player arrow on the world map ourselves.
     *
     * <p>Not gated on the squaremap layer any more. The gate assumed Xaero's own arrow is visible
     * whenever that layer is off, but it isn't: our overlay writes depth, Xaero draws its arrow with
     * depth testing, and the depth clear that used to compensate
     * ({@code xaero.lib.client.graphics.util.TextureUtils.clearRenderTargetDepth}) no longer exists in
     * current Xaero, so that reflective helper silently does nothing. The result was no arrow at all
     * with the layer off. Ours is now the arrow in both cases.
     */
    public static boolean shouldRenderWorldMapIndicatorOverlay() {
        return isActiveOnCurrentServer() && config != null;
    }

    private static boolean updateWorldMapMovement(double cameraX, double cameraZ, double scale) {
        long now = System.currentTimeMillis();
        if (Double.isNaN(lastWorldMapCameraX)) {
            lastWorldMapCameraX = cameraX;
            lastWorldMapCameraZ = cameraZ;
            lastWorldMapScale = scale;
            return false;
        }

        double movedBlocks = Math.hypot(cameraX - lastWorldMapCameraX, cameraZ - lastWorldMapCameraZ);
        double scaleDelta = Math.abs(scale - lastWorldMapScale) / Math.max(0.000001, Math.abs(lastWorldMapScale));
        if (movedBlocks > 0.75 || scaleDelta > 0.003) {
            worldMapMovingUntilMs = now + 175L;
        }
        lastWorldMapCameraX = cameraX;
        lastWorldMapCameraZ = cameraZ;
        lastWorldMapScale = scale;
        return now < worldMapMovingUntilMs;
    }

    private static boolean isWorldMapMoving() {
        return System.currentTimeMillis() < worldMapMovingUntilMs;
    }

    public static void renderHoveredWorldMapChunk(GuiGraphicsExtractor ctx,
                                                  double cameraX, double cameraZ,
                                                  double scale, int screenW, int screenH,
                                                  double worldX, double worldZ) {
        if (!isActiveOnCurrentServer()) return;
        // The hover highlight tracks the mouse — it's a cursor, not map content, so it stays out of shots.
        if (composingScreenshot()) return;
        if (renderer != null) {
            renderer.renderHoveredChunk(ctx, cameraX, cameraZ, scale, screenW, screenH, worldX, worldZ);
        }
    }

    public static void renderSquaremapMinimapViewport(GuiGraphicsExtractor ctx,
                                                      double cameraX, double cameraZ,
                                                      double scale, int width, int height) {
        renderSquaremapMinimapViewport(ctx, cameraX, cameraZ, scale, width, height, false, 0.0);
    }

    public static void renderSquaremapMinimapViewport(GuiGraphicsExtractor ctx,
                                                      double cameraX, double cameraZ,
                                                      double scale, int width, int height,
                                                      boolean circularClip, double circularClipRadius) {
        if (!isActiveOnCurrentServer()) return;
        if (renderer != null) {
            renderer.renderSquaremapMinimapViewport(ctx, cameraX, cameraZ, scale, width, height,
                    true, circularClip ? circularClipRadius : 0.0);
        }
    }

    public static void renderChunkCounter(GuiGraphicsExtractor ctx,
                                          double cameraX, double cameraZ,
                                          double scale, int screenW, int screenH,
                                          double worldX, double worldZ) {
        if (!isActiveOnCurrentServer()) return;
        if (config == null) return;
        if (!config.chunkCounterEnabled) return;
        ChunkCounterOverlay.tickDrag(worldX, worldZ);
        ChunkCounterOverlay.render(ctx, cameraX, cameraZ, scale, screenW, screenH, worldX, worldZ,
                config.chunkCounterEnabled);
    }

    public static void renderMapToggles(GuiGraphicsExtractor ctx, int screenH) {
        if (!isActiveOnCurrentServer()) return;
        if (config != null) {
            MapToggleOverlay.render(ctx, screenH, config,
                    renderer != null && renderer.isSquaremapLoading(),
                    renderer != null && renderer.isBorderLoading());
        }
    }

    /** The Planning-mode town counter, drawn top-left alongside the other map HUD. */
    public static void renderPlanningCounter(GuiGraphicsExtractor ctx, int screenW, int screenH) {
        if (!isActiveOnCurrentServer() || config == null) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        net.townymap.gui.PlanningOverlay.render(ctx, client.font, screenW, screenH);
    }

    /** A click on a Planning counter chip (+ / T#). True if it was consumed. */
    public static boolean onPlanningCounterClick(double screenX, double screenY) {
        return isActiveOnCurrentServer() && config != null
                && net.townymap.gui.PlanningOverlay.handleClick(screenX, screenY);
    }

    /** With "+" armed, a left-click on the map drops a planned town there instead of selecting. */
    public static boolean onPlanningMapClick(double screenX, double screenY,
                                             double camXWorld, double camZWorld, double mapScale,
                                             int sw, int sh) {
        if (!isActiveOnCurrentServer() || config == null || mapScale <= 0) return false;
        if (!net.townymap.gui.PlanningOverlay.isArmed()) return false;
        double worldX = (screenX - sw / 2.0) / mapScale + camXWorld;
        double worldZ = (screenY - sh / 2.0) / mapScale + camZWorld;
        return net.townymap.gui.PlanningOverlay.placeAt(worldX, worldZ);
    }

    public static void updateMinimapNationAlert(double playerX, double playerZ, double visibleBlocks) {
        if (!isActiveOnCurrentServer()) return;
        if (config == null || !config.minimapNationAlertEnabled) {
            minimapOutsideNationPlayers.clear();
            minimapNationAlertFlashUntilMs = 0;
            return;
        }
        if (apiClient == null) return;
        long now = System.currentTimeMillis();
        if (now - lastMinimapNationAlertUpdateMs < 500L) return;
        lastMinimapNationAlertUpdateMs = now;
        // Minimap alert, so it reads the world the player is standing in, not the pinned one.
        List<TownData> towns = apiClient.getTowns(playerWorldResolved());
        if (towns.isEmpty()) {
            minimapOutsideNationPlayers.clear();
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getUser() == null) return;
        String selfName = client.getUser().getName();

        Set<String> currentlyVisibleWilderness = new HashSet<>();
        // Same world as the towns above -- this half still read the shown world, so a Moon-pinned map
        // compared lunar players against Earth claims and flagged them all as outside their nation.
        for (var marker : apiClient.getPlayers(playerWorldResolved())) {
            if (marker.name() == null || marker.name().equalsIgnoreCase(selfName)) continue;
            if (Math.abs(marker.x() - playerX) > visibleBlocks
                    || Math.abs(marker.z() - playerZ) > visibleBlocks) continue;
            if (TownHoverOverlay.townAt(marker.x(), marker.z(), towns) != null) continue;

            String key = townKey(marker.name());
            currentlyVisibleWilderness.add(key);
            if (!minimapOutsideNationPlayers.contains(key)) {
                minimapNationAlertFlashUntilMs = System.currentTimeMillis() + 4_000L;
            }
        }

        minimapOutsideNationPlayers.clear();
        minimapOutsideNationPlayers.addAll(currentlyVisibleWilderness);
    }

    /**
     * Draws a high-contrast player-position indicator at the minimap centre.
     * Only renders when the squaremap background is active and the minimap is enlarged.
     * Must be called AFTER {@code renderOutsidePip} so it composites on top.
     */
    public static void renderMinimapPlayerIndicator(GuiGraphicsExtractor ctx, Object session, int mapX, int mapY, int size) {
        if (!isActiveOnCurrentServer()) return;
        try {
            TownyMinimapOverlay.renderPlayerIndicator(ctx,
                    (xaero.hud.minimap.module.MinimapSession) session, mapX, mapY, size);
        } catch (Exception e) {
            // Warn, not debug: this was debug-level while the indicator was silently failing, so the
            // one signal that would have explained it never reached the log. One-shot to avoid spam.
            if (playerIndicatorErrorLogged.compareAndSet(false, true)) {
                LOGGER.warn("[TownyMap] Failed to render minimap player indicator", e);
            }
        }
    }

    public static void setSuppressNativeMinimapCompass(Object session) {
        suppressNativeMinimapCompass.set(shouldUseCustomEnlargedMinimapCompass(session));
    }

    /**
     * While our overlay is drawing we render waypoints ourselves on top of it, so Xaero's native
     * minimap waypoint pass is suppressed to avoid drawing each one twice.
     *
     * <p>Deliberately mirrors the condition guarding our own redraw, including the cave-mode check:
     * the overlay bails out in cave mode and never redraws, so suppressing there would make waypoints
     * vanish entirely. (That asymmetry is a live bug on the 1.21.11 branch; fixed here rather than
     * carried over.)
     */
    public static void setSuppressNativeMinimapWaypoints(Object session) {
        boolean caveMode = false;
        try {
            xaero.hud.minimap.module.MinimapSession minimapSession =
                    (xaero.hud.minimap.module.MinimapSession) session;
            caveMode = minimapSession.getProcessor() != null
                    && minimapSession.getProcessor().isCaveModeDisplayed();
        } catch (Exception ignored) {
        }
        suppressNativeMinimapWaypoints.set(config != null && config.minimapExtensionsEnabled
                && config.squaremapOnMinimap() && isActiveOnCurrentServer() && !caveMode);
    }

    public static void clearSuppressNativeMinimapWaypoints() {
        suppressNativeMinimapWaypoints.remove();
    }

    public static boolean shouldSuppressNativeMinimapWaypoints() {
        return suppressNativeMinimapWaypoints.get();
    }

    public static void clearSuppressNativeMinimapCompass() {
        suppressNativeMinimapCompass.remove();
    }

    public static boolean shouldSuppressNativeMinimapCompass() {
        boolean suppress = suppressNativeMinimapCompass.get();
        if (suppress && nativeCompassSuppressionLogged.compareAndSet(false, true)) {
            LOGGER.info("[TownyMap] Suppressing Xaero native minimap compass while squaremap overlay is active");
        }
        return suppress;
    }

    private static boolean shouldUseCustomEnlargedMinimapCompass(Object session) {
        if (!isActiveOnCurrentServer()) return false;
        if (config == null || !config.minimapExtensionsEnabled || !config.squaremapOnMinimap()) return false;
        try {
            xaero.hud.minimap.module.MinimapSession minimapSession =
                    (xaero.hud.minimap.module.MinimapSession) session;
            return minimapSession.getProcessor() != null
                    && !minimapSession.getProcessor().isCaveModeDisplayed();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void renderMinimapCompassDirections(GuiGraphicsExtractor ctx, Object session, int mapX, int mapY, int size) {
        if (!isActiveOnCurrentServer()) return;
        try {
            TownyMinimapOverlay.renderCompassDirections(ctx,
                    (xaero.hud.minimap.module.MinimapSession) session, mapX, mapY, size);
        } catch (Exception e) {
            // Warn, not debug: a throw here makes the feature silently vanish, which is exactly
            // how the player-indicator bug stayed invisible. One-shot so it can't spam per frame.
            if (minimapCompassErrorLogged.compareAndSet(false, true)) {
                LOGGER.warn("[TownyMap] Failed to render minimap compass directions", e);
            }
        }
    }


    // Scale for our minimap text so it matches Xaero's own info/coordinate text. Xaero draws that
    // text inside its 1/xaeroScale matrix, i.e. at minimapScale/screenScale of the base font; ours
    // was at 1.0 (too big). The minimap mixin sets this each frame from the live scales.
    private static volatile float minimapTextScale = 1.0f;

    public static void setMinimapTextScale(float scale) {
        minimapTextScale = (scale > 0.05f && scale <= 4.0f) ? scale : 1.0f;
    }

    public static float minimapTextScale() {
        return minimapTextScale;
    }

    /**
     * Renders our info lines centred under the minimap: current town + nation, nearby players
     * (within 100 blocks, with distance, straight off the live squaremap feed), and nearest town when
     * in the wilderness. Per-line config toggles.
     */
    // On-screen vertical bounds of Xaero's own info block (coords/biome/etc.), captured each frame
    // while Xaero draws it so we can stack our lines just outside it without overlapping.
    private static boolean xaeroInfoCaptured = false;
    private static double xaeroInfoTopScreenY = 0;
    private static double xaeroInfoBottomScreenY = 0;

    /** Reset Xaero info-block capture; called right before Xaero renders the minimap info each frame. */
    public static void beginXaeroInfoCapture() {
        xaeroInfoCaptured = false;
    }

    /**
     * Record one of Xaero's info lines. Xaero draws inside a scaled matrix, so we transform the line's
     * (x, y) through the current matrix to get its true on-screen vertical span and accumulate the
     * top/bottom across all lines.
     */
    public static void captureXaeroInfoLine(GuiGraphicsExtractor ctx, int x, int y, int fontHeight) {
        try {
            org.joml.Matrix3x2f m = ctx.pose();
            double topY = (double) m.m01 * x + (double) m.m11 * y + m.m21;
            double botY = (double) m.m01 * x + (double) m.m11 * (y + fontHeight) + m.m21;
            if (!xaeroInfoCaptured) {
                xaeroInfoTopScreenY = topY;
                xaeroInfoBottomScreenY = botY;
                xaeroInfoCaptured = true;
            } else {
                xaeroInfoTopScreenY = Math.min(xaeroInfoTopScreenY, topY);
                xaeroInfoBottomScreenY = Math.max(xaeroInfoBottomScreenY, botY);
            }
        } catch (Throwable ignored) {
        }
    }

    public static int renderMinimapInfoLines(GuiGraphicsExtractor ctx, int mapCenterX, int mapTop, int mapBottom) {
        if (!isActiveOnCurrentServer() || config == null || apiClient == null) return 0;
        if (!config.infoDisplayTownEnabled && !config.infoDisplayNearbyPlayersEnabled
                && !config.infoDisplayNearestTownEnabled) return 0;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.getUser() == null) return 0;

        // EarthMC town/player coordinates are always overworld, so compare in overworld space: in the
        // Nether the player's raw X/Z are 8x too small and every lookup below would pick the wrong town.
        // Distances are divided back down by the same factor so what's shown is blocks the player would
        // actually travel here, not the overworld span.
        double dimScale = dimensionCoordinateScale();
        double px = client.player.getX() * dimScale;
        double pz = client.player.getZ() * dimScale;
        long now = System.currentTimeMillis();
        // Describes where the player physically stands, so it reads their world -- not the one the
        // world map may be pinned to.
        String hudWorld = playerWorldResolved();
        java.util.List<TownData> towns = apiClient.getTowns(hudWorld);
        TownData here = TownHoverOverlay.townAt(px, pz, towns);
        java.util.List<String> lines = new java.util.ArrayList<>();

        if (config.infoDisplayTownEnabled) {
            if (here != null) {
                String key = townKey(here.name());
                requestTownDetails(here.name(), key);
                TownPopupData details = townDetailsCache.get(key);
                String nation = details != null ? details.nationName() : null;
                lines.add(nation != null && !nation.isBlank()
                        ? "§fTown: " + here.name() + " §7(" + nation + ")"
                        : "§fTown: " + here.name());
            } else {
                lines.add("§7Wilderness");
            }
        }

        if (config.infoDisplayNearbyPlayersEnabled) {
            // Keep the nearby list's squaremap positions fresh (~1s) whenever it's shown — independent of
            // the minimap-extensions overlay or the full map being open. tickPlayers is interval-throttled
            // and de-duped, so calling it here is cheap and won't double-fetch.
            apiClient.tickPlayers();
            String self = client.getUser().getName();
            // Live players come straight off the feed (yellow); recently-departed ones are read from the same
            // last-seen ghost data the minimap uses (red). Using the ghost source rather than an ad-hoc
            // retention buffer means the list self-corrects within a refresh and honours the Last Seen toggle.
            record Nearby(String name, double dist, boolean ghost) {}
            java.util.List<Nearby> near = new java.util.ArrayList<>();
            for (PlayerMarker m : apiClient.getPlayers(hudWorld)) {
                if (m.name() == null || m.name().equalsIgnoreCase(self)) continue;
                double d = Math.hypot(m.x() - px, m.z() - pz) / dimScale;
                if (d <= 100.0) near.add(new Nearby(m.name(), d, false));
            }
            // Recently-departed players at their last-seen position (red), exactly the minimap's ghost data —
            // so you can still see roughly where someone was after they drop off the live feed.
            for (GhostMarker g : lastSeenGhosts(hudWorld)) {
                if (g.name() == null || g.name().equalsIgnoreCase(self)) continue;
                double d = Math.hypot(g.x() - px, g.z() - pz) / dimScale;
                if (d <= 100.0) near.add(new Nearby(g.name(), d, true));
            }
            near.sort(java.util.Comparator.comparingDouble(Nearby::dist));
            java.util.List<String> entries = new java.util.ArrayList<>();
            for (Nearby n : near) {
                entries.add((n.ghost() ? "§c" : "§e") + n.name() + " §7(" + (int) Math.round(n.dist()) + "m)");
            }
            if (!entries.isEmpty()) {
                // One player per line (a column); cap the list and summarise the rest.
                int cap = 5;
                int shown = Math.min(cap, entries.size());
                for (int i = 0; i < shown; i++) {
                    lines.add(i == 0 ? "§fNearby: " + entries.get(i) : entries.get(i));
                }
                if (entries.size() > cap) {
                    lines.add("§7+" + (entries.size() - cap) + " more");
                }
            }
        }

        if (config.infoDisplayNearestTownEnabled && here == null && !towns.isEmpty()) {
            TownData nearest = null;
            double best = Double.MAX_VALUE;
            for (TownData t : towns) {
                double d = Math.hypot(t.centerX() - px, t.centerZ() - pz) / dimScale;
                if (d < best) { best = d; nearest = t; }
            }
            if (nearest != null) {
                lines.add("§fNearest: " + nearest.name() + " §7(" + (int) Math.round(best) + "m)");
            }
        }

        if (lines.isEmpty()) return 0;
        // Scale our text to match Xaero's own info/coordinate text; all layout below is in scaled
        // (on-screen) units so the block stays correctly placed and centred under the minimap.
        float s = minimapTextScale();
        float lineH = (client.font.lineHeight + 1) * s;
        float totalH = lines.size() * lineH;

        float maxW = 0;
        for (String line : lines) maxW = Math.max(maxW, client.font.width(line) * s);

        int screenW = client.getWindow().getGuiScaledWidth();
        int screenH = client.getWindow().getGuiScaledHeight();
        int pad = 2;

        // Anchor below Xaero's whole info block (coords + biome + whatever else is enabled), using the
        // on-screen bounds we captured while Xaero drew it — so we never overlap regardless of how many
        // lines it shows or its info-display scale. Xaero puts that block just below the minimap normally,
        // or just above it near the screen bottom; we match whichever side and stack just outside it.
        float top = 0;
        boolean usedCapture = false;
        if (xaeroInfoCaptured && xaeroInfoBottomScreenY >= xaeroInfoTopScreenY) {
            int xTop = (int) Math.floor(xaeroInfoTopScreenY);
            int xBot = (int) Math.ceil(xaeroInfoBottomScreenY);
            double mapCenter = (mapTop + mapBottom) / 2.0;
            double xCenter = (xTop + xBot) / 2.0;
            // Decide which side Xaero's block is on by its centre, and only follow it if it's near the
            // minimap (its usual spot) rather than chasing info placed elsewhere on screen.
            if (xCenter >= mapCenter && xBot >= mapTop && xBot <= mapBottom + 220) {
                top = xBot + 3;                 // Xaero info is below the minimap -> stack under it
                usedCapture = true;
            } else if (xCenter < mapCenter && xTop <= mapBottom && xTop >= mapTop - 220) {
                top = xTop - 3 - totalH;        // Xaero info is above the minimap -> stack above it
                usedCapture = true;
            }
        }
        if (!usedCapture) {
            // Couldn't measure Xaero's block (info disabled, or drawn elsewhere): fall back to a
            // minimap-relative offset, flipping above if ours would run off the bottom.
            top = mapBottom + lineH + 12;
            if (top + totalH > screenH - pad) {
                top = mapTop - (lineH + 12) - totalH;
            }
        }
        top = Math.max(pad, Math.min(top, screenH - pad - totalH));

        // Center the column on the minimap, then clamp the whole block onto the screen.
        float blockCenterX = Math.max(pad + maxW / 2f,
                Math.min((float) mapCenterX, screenW - pad - maxW / 2f));

        boolean scaled = net.townymap.gui.UiScale.active();
        if (scaled) net.townymap.gui.UiScale.push(ctx, blockCenterX, top);   // shrink around the block's top-centre
        float cy = top + lineH / 2f;
        for (String line : lines) {
            TownyMinimapOverlay.drawScaledLabelCentered(ctx, client.font, line, blockCenterX, cy,
                    0xFFFFFFFF, 0, true);
            cy += lineH;
        }
        if (scaled) net.townymap.gui.UiScale.pop(ctx);
        return (int) Math.ceil(totalH);
    }

    public static void renderMinimapNationAlert(GuiGraphicsExtractor ctx, Object session, int x, int y, int size) {
        if (!isActiveOnCurrentServer()) return;
        if (config == null || !config.minimapNationAlertEnabled) return;
        long remaining = minimapNationAlertFlashUntilMs - System.currentTimeMillis();
        if (remaining <= 0 || size <= 8) return;

        double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 95.0);
        int alpha = 120 + (int) Math.round(110.0 * pulse);
        int color = ((alpha & 0xFF) << 24) | (minimapFrameColor() & 0x00FFFFFF);
        int thickness = 3;
        try {
            xaero.hud.minimap.module.MinimapSession minimapSession =
                    (xaero.hud.minimap.module.MinimapSession) session;
            if (TownyMinimapOverlay.isCircularMinimap(minimapSession)) {
                TownyMinimapOverlay.renderCircularOutline(ctx, x, y, size, color, 0, thickness);
                return;
            }
        } catch (Exception ignored) {
        }
        ctx.fill(x, y, x + size, y + thickness, color);
        ctx.fill(x, y + size - thickness, x + size, y + size, color);
        ctx.fill(x, y, x + thickness, y + size, color);
        ctx.fill(x + size - thickness, y, x + size, y + size, color);
    }

    public static void renderMinimapWaypointsOnTop(GuiGraphicsExtractor ctx, Object session, int mapX, int mapY, int size) {
        if (!isActiveOnCurrentServer()) return;
        try {
            TownyMinimapOverlay.renderWaypointsOnTop(ctx,
                    (xaero.hud.minimap.module.MinimapSession) session, mapX, mapY, size);
        } catch (Exception e) {
            // Warn, not debug: a throw here makes the feature silently vanish, which is exactly
            // how the player-indicator bug stayed invisible. One-shot so it can't spam per frame.
            if (minimapWaypointsErrorLogged.compareAndSet(false, true)) {
                LOGGER.warn("[TownyMap] Failed to redraw minimap waypoints", e);
            }
        }
    }

    public static void renderMinimapFrame(GuiGraphicsExtractor ctx, Object session, int x, int y, int size) {
        if (!isActiveOnCurrentServer()) return;
        if (config == null || !config.minimapExtensionsEnabled || !config.squaremapOnMinimap()) return;
        if (size <= 8) return;
        try {
            xaero.hud.minimap.module.MinimapSession minimapSession =
                    (xaero.hud.minimap.module.MinimapSession) session;
            if (minimapSession.getProcessor() == null) return;
            if (minimapSession.getProcessor().isCaveModeDisplayed()) return;
            int color = 0xFF000000 | (minimapFrameColor() & 0x00FFFFFF);
            int shadow = 0xAA000000;
            int thickness = 1;
            if (TownyMinimapOverlay.isCircularMinimap(minimapSession)) {
                // Thicker smooth ring on the circular minimap: it sits on top of the squaremap and
                // covers its stepped clip edge, so the visible boundary is this clean circle.
                TownyMinimapOverlay.renderCircularOutline(ctx, x, y, size, color, shadow, 2);
                return;
            }
            ctx.fill(x - 1, y - 1, x + size + 1, y, shadow);
            ctx.fill(x - 1, y + size, x + size + 1, y + size + 1, shadow);
            ctx.fill(x - 1, y, x, y + size, shadow);
            ctx.fill(x + size, y, x + size + 1, y + size, shadow);
            ctx.fill(x, y, x + size, y + thickness, color);
            ctx.fill(x, y + size - thickness, x + size, y + size, color);
            ctx.fill(x, y, x + thickness, y + size, color);
            ctx.fill(x + size - thickness, y, x + size, y + size, color);
        } catch (Exception e) {
            // Warn, not debug: a throw here makes the feature silently vanish, which is exactly
            // how the player-indicator bug stayed invisible. One-shot so it can't spam per frame.
            if (minimapFrameErrorLogged.compareAndSet(false, true)) {
                LOGGER.warn("[TownyMap] Failed to render minimap frame overlay", e);
            }
        }
    }

    public static int minimapFrameColor() {
        long now = System.currentTimeMillis();
        if (now - minimapFrameColorReadAtMs < 1_000L) return minimapFrameColor;
        minimapFrameColorReadAtMs = now;

        int colorIndex = 15;
        Path path = FabricLoader.getInstance().getConfigDir()
                .resolve("xaero/minimap/profiles/default.cfg");
        try {
            if (Files.exists(path)) {
                for (String line : Files.readAllLines(path)) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("minimap_frame_color")) continue;
                    int equals = trimmed.indexOf('=');
                    if (equals >= 0) {
                        colorIndex = Integer.parseInt(trimmed.substring(equals + 1).trim());
                    }
                    break;
                }
            }
        } catch (Exception ignored) {
        }

        int[] colors = MinimapConfigConstants.COLORS;
        if (colorIndex < 0 || colorIndex >= colors.length) colorIndex = 15;
        minimapFrameColor = colors[colorIndex];
        return minimapFrameColor;
    }

    public static boolean shouldHideMinimap() {
        if (!isActiveOnCurrentServer()) return false;
        if (config == null || !config.hideMinimapInNether) return false;
        Minecraft client = Minecraft.getInstance();
        return client != null
                && client.level != null
                && client.level.dimension() == Level.NETHER;
    }

    /**
     * Coordinate multiplier for our overlay on the world map given the dimension and "EarthMC Map In
     * Nether" setting: 1.0 = render normally, 8.0 = convert Nether coords to overworld (the caller
     * also divides its block-scale by this so the overworld overlay lines up over the real tiles),
     * 0.0 = hide.
     */
    /**
     * Multiply a coordinate in the player's current dimension by this to get the matching EarthMC
     * (overworld) coordinate; divide by it to go the other way. 8 in the Nether, 1 everywhere else.
     *
     * <p>Unlike {@link #worldMapOverlayScale()} this is a pure unit conversion: it never returns 0 and
     * carries no "hide the overlay" signal, so callers can multiply or divide by it unconditionally.
     * Every place where EarthMC town/nation/player coordinates (always overworld) meet the player's
     * own position or Xaero's camera (always current-dimension) has to go through this, or the two
     * sides end up a factor of 8 apart in the Nether.
     */
    // ── Squaremap worlds (Terra Nostra / Moon) ───────────────────────────────
    public static final String WORLD_OVERWORLD = "minecraft_overworld";
    public static final String WORLD_MOON = "earthmc_moon";

    /** squaremap world key -> display name, from /tiles/settings.json. Empty until fetched. */
    private static volatile java.util.Map<String, String> squaremapWorlds = java.util.Map.of();
    private static volatile boolean squaremapWorldsFetching = false;
    private static volatile long lastWorldsAttemptMs = 0;
    private static volatile String lastActiveWorld = WORLD_OVERWORLD;
    private static volatile String loggedUnknownWorld = null;

    /**
     * The worlds squaremap publishes, fetched once. Used to check that a dimension actually has map
     * data before we try to show it, so an unknown dimension falls back instead of 404ing every tile.
     */
    public static java.util.Map<String, String> squaremapWorlds() {
        // Retry on failure rather than latching. Latching meant one failed fetch left the list empty for
        // the session, and everything keyed off it silently misbehaved.
        long now = System.currentTimeMillis();
        if (squaremapWorlds.isEmpty() && !squaremapWorldsFetching
                && now - lastWorldsAttemptMs > 60_000L && config != null && apiClient != null) {
            squaremapWorldsFetching = true;
            lastWorldsAttemptMs = now;
            apiClient.fetchWorlds().thenAccept(m -> {
                if (m != null && !m.isEmpty()) {
                    squaremapWorlds = m;
                    LOGGER.info("[TownyMap] squaremap worlds: {}", m);
                }
                squaremapWorldsFetching = false;
            }).exceptionally(t -> { squaremapWorldsFetching = false; return null; });
        }
        return squaremapWorlds;
    }

    /**
     * The squaremap world key for the dimension the player is standing in, or null if squaremap has no
     * map for it.
     *
     * <p>squaremap names worlds "namespace_path", which is exactly the dimension id with the colon
     * swapped -- minecraft:overworld is minecraft_overworld, earthmc:moon is earthmc_moon. Deriving it
     * means a world EarthMC adds later works with no code change.
     */
    public static String playerWorldKey() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null) return null;
        try {
            var id = client.level.dimension().identifier();
            String key = id.getNamespace() + "_" + id.getPath();
            if (squaremapWorlds().containsKey(key)) return key;
            // Say so once. The mapping from dimension id to squaremap world is derived, not hardcoded,
            // so if EarthMC names the Moon dimension something unexpected this line is what reveals it.
            if (!key.equals(loggedUnknownWorld) && !squaremapWorlds().isEmpty()) {
                loggedUnknownWorld = key;
                LOGGER.info("[TownyMap] Dimension {} has no squaremap world (known: {})",
                        id, squaremapWorlds().keySet());
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static final int WORLD_MODE_AUTO  = 0;
    public static final int WORLD_MODE_EARTH = 1;
    public static final int WORLD_MODE_MOON  = 2;

    /** Auto mode's answer, resolved on the client tick so fetch threads never touch the level. */
    private static volatile String autoResolvedWorld = WORLD_OVERWORLD;
    private static volatile String resolvedPlayerWorld = WORLD_OVERWORLD;
    private static volatile String pendingRecentreWorld = null;
    /** Where the camera was last left in each world, so switching back returns to it. */
    private static final Map<String, double[]> lastCameraByWorld = new ConcurrentHashMap<>();

    /** Records the world-map camera for the world being shown. World coordinates, not Xaero's units. */
    public static void noteWorldMapCamera(double worldX, double worldZ) {
        // Not while a switch is still waiting to be applied: the camera is then still sitting at the
        // PREVIOUS world's coordinates, and recording those against the new world would both poison its
        // memory and satisfy the very lookup that is about to read it.
        if (pendingRecentreWorld != null) return;
        lastCameraByWorld.put(activeWorldKey(), new double[]{worldX, worldZ});
    }

    /**
     * True while the world map shows somewhere the player is not, so Xaero must not drag the camera onto
     * them. Their position is a coordinate in a different world; on the Moon it is simply a place they
     * have never been, and the further from origin they stand the further off the map it pulls.
     */
    public static boolean pinWorldMapCamera() {
        return isActiveOnCurrentServer() && viewingOtherWorld();
    }

    /**
     * Where the world map should re-aim after a world switch, or null if it should stay put.
     *
     * <p>Returns null while the target world's claims are still loading, so the request survives until
     * there is something to aim at -- the first switch to a world usually lands before its markers do.
     */
    public static double[] consumeWorldMapRecentre() {
        String world = pendingRecentreWorld;
        if (world == null) return null;
        if (world.equals(resolvedPlayerWorld)) {
            Minecraft client = Minecraft.getInstance();
            if (client == null || client.player == null) return null;
            pendingRecentreWorld = null;
            return new double[]{client.player.getX(), client.player.getZ()};
        }
        // Where they left it last, so switching back and forth does not keep yanking the view; the
        // claim centre is only the opening position for a world not visited yet this session.
        double[] remembered = lastCameraByWorld.get(world);
        if (remembered != null) {
            pendingRecentreWorld = null;
            return remembered;
        }
        double[] centre = worldClaimCentre(world);
        if (centre == null) return null;   // markers not in yet; try again next frame
        pendingRecentreWorld = null;
        return centre;
    }

    /** Middle of a world's claimed area, or null if its markers have not arrived. */
    private static double[] worldClaimCentre(String world) {
        if (apiClient == null) return null;
        List<TownData> towns = apiClient.getTowns(world);
        if (towns.isEmpty()) return null;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (TownData t : towns) {
            minX = Math.min(minX, t.centerX()); maxX = Math.max(maxX, t.centerX());
            minZ = Math.min(minZ, t.centerZ()); maxZ = Math.max(maxZ, t.centerZ());
        }
        return new double[]{(minX + maxX) / 2.0, (minZ + maxZ) / 2.0};
    }

    /**
     * The squaremap world the player is standing in, resolved on the client tick.
     *
     * <p>Safe from any thread, unlike {@link #playerMapWorld()}. The minimap always shows this world --
     * it shows where the player actually is -- while the world map shows {@link #activeWorldKey()}.
     */
    public static String playerWorldResolved() { return resolvedPlayerWorld; }
    private static volatile String loggedDimensionAlias = null;

    /**
     * The squaremap world the player is standing in. Never null -- Earth is the fallback.
     *
     * <p>The dimension id is NOT assumed to equal squaremap's world name for it. EarthMC's lunar
     * dimension is referred to as "space" in-game while squaremap publishes the world as
     * {@code earthmc_moon}, and a derived {@code namespace_path} match would silently never fire. So:
     * exact match first, then the vanilla dimensions (all of which project onto Earth), then the single
     * non-Earth world squaremap publishes -- which is right however EarthMC names the dimension.
     *
     * <p>Client thread only; {@link #activeWorldKey()} reads the resolved value instead.
     */
    public static String playerMapWorld() {
        String raw = rawWorldKey();
        if (raw == null) return WORLD_OVERWORLD;
        Map<String, String> worlds = squaremapWorlds();
        if (worlds.containsKey(raw)) return raw;                    // earthmc_moon, minecraft_overworld
        if (raw.startsWith("minecraft_")) return WORLD_OVERWORLD;   // overworld, nether and end
        // EarthMC has two lunar dimensions -- earthmc:space and earthmc:moon -- but squaremap publishes
        // one world for them, earthmc_moon. So space has no world of its own and would otherwise fall
        // back to Earth, putting Terra Nostra's map up while the player is off it.
        // Deliberately not conditional on the fetched world list: that list is retried on a 60s timer,
        // and requiring it meant stepping onto the Moon before it landed resolved to Earth and left
        // Terra Nostra on screen until the retry.
        if (raw.startsWith("earthmc_")) return WORLD_MOON;
        String only = null;
        for (String k : worlds.keySet()) {
            if (WORLD_OVERWORLD.equals(k)) continue;
            if (only != null) return WORLD_OVERWORLD;   // more than one candidate: refuse to guess
            only = k;
        }
        if (only == null) return WORLD_OVERWORLD;
        if (!raw.equals(loggedDimensionAlias)) {
            loggedDimensionAlias = raw;
            LOGGER.info("[TownyMap] Dimension {} is not a squaremap world name; treating it as {}",
                    raw, only);
        }
        return only;
    }

    /** The squaremap world the map should currently show. */
    public static String activeWorldKey() {
        if (config == null) return WORLD_OVERWORLD;
        return switch (config.mapWorldMode) {
            case WORLD_MODE_EARTH -> WORLD_OVERWORLD;
            case WORLD_MODE_MOON  -> WORLD_MOON;
            // Auto: whatever the last client tick resolved. Read from fetch threads, so it must not
            // reach into the level here.
            default -> autoResolvedWorld;
        };
    }

    /**
     * True when the map is showing Terra Nostra.
     *
     * <p>Guards anything positioned from an EarthMC API coordinate -- nation spawns and the like are
     * Earth coordinates, and the two worlds' coordinates overlap numerically, so using one off Earth
     * plants a marker somewhere plausible-looking and wrong.
     */
    public static boolean viewingEarth() {
        return WORLD_OVERWORLD.equals(activeWorldKey());
    }

    /**
     * True when Xaero's own player arrow should be left undrawn on the world map.
     *
     * <p>The arrow marks the player's position, which means nothing on a world they are not standing in
     * -- on the Moon it would plant them somewhere in the lunar landscape they have never been.
     */
    public static boolean hideWorldMapPlayerArrow() {
        return hidePlayerWorldMarkers();
    }

    /**
     * True when anything belonging to the world the PLAYER is in must stay off the world map, because
     * the map is showing a different one. Their arrow, and their waypoints with the "Waypoints: x"
     * banner that names the set -- all of it describes somewhere the map is not looking at.
     */
    public static boolean hidePlayerWorldMarkers() {
        return isActiveOnCurrentServer() && viewingOtherWorld();
    }

    /** True when the map shows a world the player is not in -- markers there are not where they are. */
    public static boolean viewingOtherWorld() {
        // The tick-resolved value, not playerMapWorld(): that one rebuilds the key from the dimension
        // id with a string concatenation on every call, and this is read several times a frame by the
        // camera pin and both arrow suppressions. It is also the same source of truth every other
        // caller uses, so the two can no longer disagree mid-frame.
        return !playerWorldResolved().equals(activeWorldKey());
    }

    /** The town's claim polygon in the world currently shown, or null if it has none there. */
    public static net.townymap.model.TownData townPolygon(String townName) {
        if (apiClient == null || townName == null || townName.isBlank()) return null;
        String key = townKey(townName);
        for (net.townymap.model.TownData t : apiClient.getTowns()) {
            if (t.key().equals(key)) return t;
        }
        return null;
    }

    /**
     * The dimension Xaero's WORLD MAP is currently drawing, which is not always the one the player is in.
     *
     * <p>Xaero's map has its own dimension toggle (the button that cycles Overworld/Nether/End/...): it
     * sets {@code MapWorld.customDimensionId} and {@code getCurrentDimension()} follows it, so someone
     * standing on Earth can be looking at Nether or Moon terrain. Every decision about what to draw ON
     * the world map belongs to this dimension, not {@code client.level.dimension()}. The minimap has no
     * such toggle and always follows the player, which is why it still reads the level directly.
     *
     * <p>Null when Xaero is not loaded far enough to say. Wrapped because Xaero moves internals between
     * versions and a hard failure here would take the whole overlay down (see the zoom-hook history).
     */
    public static net.minecraft.resources.ResourceKey<Level> xaeroViewedDimension() {
        try {
            var session = xaero.map.core.XaeroWorldMapCore.currentSession;
            if (session == null) return null;
            var proc = session.getMapProcessor();
            if (proc == null) return null;
            var mw = proc.getMapWorld();
            if (mw == null) return null;
            var dim = mw.getCurrentDimension();
            return dim == null ? null : dim.getDimId();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The squaremap world key matching the dimension Xaero's world map is drawing, or null if unknown.
     * Same "namespace_path" derivation as {@link #playerWorldKey()}, but for the VIEWED dimension.
     */
    /**
     * Points Xaero's world map at the dimension matching the world we are showing, so its terrain and
     * our claims agree instead of Moon borders sitting on Terra Nostra's ground.
     *
     * <p>Does exactly what Xaero's own dimension button does -- setCustomDimensionId then
     * checkForWorldUpdate -- with null meaning "follow the player", which is what that button stores
     * when the target is the dimension the player is already in.
     *
     * <p>Only ever targets a dimension Xaero already lists. GuiMap calls
     * getCurrentDimension().getDimId() with no null check, so naming a dimension it has never seen
     * (a player who has not been to the Moon) would crash inside Xaero's own render.
     */
    private static volatile boolean pendingXaeroSync = false;
    private static volatile long lastXaeroSyncAttemptMs = 0;
    private static volatile boolean loggedXaeroNotReady = false;

    private static volatile long lastWorldMapInjectMs = 0;
    private static volatile boolean warnedInjectsDead = false;
    private static volatile long mapOpenSinceMs = 0;

    /** Called from each GuiMap inject, so we can tell whether they are actually running. */
    public static void noteWorldMapInjectAlive() {
        lastWorldMapInjectMs = System.currentTimeMillis();
    }

    /**
     * Says so when our hooks into Xaero's world map are not running.
     *
     * <p>Every inject carries require = 0 so an incompatible Xaero cannot crash the game -- but that
     * also means it fails in complete silence, and the mod looks broken with no way to tell why. Three
     * separate reports this month were some version of that. If the map screen is up and neither inject
     * has fired for two seconds, the hooks did not take.
     */
    private static void tickWorldMapInjectHealth() {
        if (warnedInjectsDead) return;
        if (!isWorldMapScreenActive()) { mapOpenSinceMs = 0; return; }
        long now = System.currentTimeMillis();
        if (mapOpenSinceMs == 0) mapOpenSinceMs = now;
        // Give the screen two seconds to draw a frame before judging it.
        if (now - mapOpenSinceMs < 2000L) return;
        if (lastWorldMapInjectMs != 0 && now - lastWorldMapInjectMs < 2000L) return;
        warnedInjectsDead = true;
        String ver = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("xaeroworldmap")
                .map(m -> m.getMetadata().getVersion().getFriendlyString())
                .orElse("not installed");
        LOGGER.warn("[TownyMap] Our world map hooks are not running - Xaero's World Map {} is not "
                + "compatible with this build. Update Xaero's World Map.", ver);
        sendFeedback("Could not hook Xaero's World Map (" + ver + "). Update it, or report this.",
                ChatFormatting.RED);
    }

    private static volatile boolean worldMapWasOpen = false;

    /**
     * Applies the dimension override only while the world map is on screen, and hands Xaero back its own
     * dimension the moment it closes.
     *
     * <p>The override exists so Xaero's terrain matches the world we are drawing claims for, and the
     * world map is the only surface that shows it -- but MapProcessor.updateWorldSynced, which runs on
     * every switch, pauses the map writer and moves a FileLock onto that dimension's folder. Left set
     * after the map closes, Xaero spends the rest of the session holding a lock on the Moon's folder
     * while the player walks around Terra Nostra, and features anchored to the current dimension --
     * the footsteps trail among them -- stop behaving.
     */
    /**
     * True for the world map and the screens it opens on top of itself -- settings, dimension options,
     * cave mode. Deliberately looser than {@link #isWorldMapOpen()}: those siblings are still "the map
     * is up", and treating a hop into settings as a close would release and re-apply the dimension
     * override, each of which pauses Xaero's writer and moves a FileLock.
     */
    private static boolean isWorldMapScreenActive() {
        Minecraft client = Minecraft.getInstance();
        return client != null && client.gui.screen() != null
                && client.gui.screen().getClass().getName().startsWith("xaero.map.gui.");
    }

    private static volatile long worldMapClosedSinceMs = 0;

    private static void tickWorldMapOpenState() {
        boolean open = isWorldMapScreenActive();
        if (open) {
            worldMapClosedSinceMs = 0;
            if (!worldMapWasOpen) {
                worldMapWasOpen = true;
                requestXaeroDimensionSync();
            }
            return;
        }
        if (!worldMapWasOpen) return;
        // Held for a moment before believing the map has closed. Switching the dimension makes Xaero
        // tear down and rebuild the map screen, and during that rebuild there is briefly no map screen
        // at all -- which read as a close, released the dimension, and set the whole thing swinging.
        long now = System.currentTimeMillis();
        if (worldMapClosedSinceMs == 0) { worldMapClosedSinceMs = now; return; }
        if (now - worldMapClosedSinceMs < 1500L) return;
        worldMapWasOpen = false;
        worldMapClosedSinceMs = 0;
        pendingXaeroSync = false;
        releaseXaeroDimension();
    }

    /** Gives Xaero back control of its own dimension (null = follow the player). */
    private static void releaseXaeroDimension() {
        try {
            var session = xaero.map.core.XaeroWorldMapCore.currentSession;
            var proc = session == null ? null : session.getMapProcessor();
            var mapWorld = proc == null ? null : proc.getMapWorld();
            if (mapWorld == null || mapWorld.getCustomDimensionId() == null) return;
            mapWorld.setCustomDimensionId(null);
            proc.checkForWorldUpdate();
            LOGGER.info("[TownyMap] Xaero map dimension released back to the player's own");
        } catch (Throwable t) {
            LOGGER.debug("[TownyMap] Could not release Xaero map dimension", t);
        }
    }

    /** Ask for Xaero's map dimension to be brought in line; retried until it takes. */
    public static void requestXaeroDimensionSync() {
        pendingXaeroSync = true;
        loggedXaeroNotReady = false;
        lastXaeroSyncAttemptMs = 0;
    }

    /**
     * Retries the dimension sync until Xaero is in a state to accept it.
     *
     * <p>XaeroWorldMapCore.currentSession is null for the first moments after joining, and the map world
     * is resolved from config well before that -- so the switch that matters most, the one at login,
     * always missed. Driven from the client tick, and cheap while waiting: three null checks.
     */
    private static void tickXaeroDimensionSync() {
        if (!pendingXaeroSync) return;
        long now = System.currentTimeMillis();
        if (now - lastXaeroSyncAttemptMs < 500L) return;
        lastXaeroSyncAttemptMs = now;
        if (syncXaeroDimension()) pendingXaeroSync = false;
    }

    /** @return true once Xaero has actually been pointed at a dimension; false to retry later. */
    private static boolean syncXaeroDimension() {
        try {
            var session = xaero.map.core.XaeroWorldMapCore.currentSession;
            var proc = session == null ? null : session.getMapProcessor();
            var mapWorld = proc == null ? null : proc.getMapWorld();
            Minecraft client = Minecraft.getInstance();
            if (mapWorld == null || client == null || client.level == null) {
                if (!loggedXaeroNotReady) {
                    loggedXaeroNotReady = true;
                    LOGGER.info("[TownyMap] Xaero not ready for a dimension switch yet "
                            + "(session={}, world={}) - will retry", session != null, mapWorld != null);
                }
                return false;
            }

            var target = knownXaeroDimensionFor(mapWorld, activeWorldKey());
            if (target == null) {
                // Xaero only lists dimensions it has created this session, so a world the player has
                // been to before but not since logging in is absent -- and its own dimension button
                // would not offer it either. getDimension() is a plain lookup that returns null (which
                // GuiMap would then dereference), so the entry has to be created before it can be shown.
                // Creating it makes Xaero load that dimension's saved regions from disk, which is the
                // whole point: seeing where you have already been up there.
                target = createXaeroDimension(mapWorld, activeWorldKey());
                if (target == null) {
                    LOGGER.info("[TownyMap] No Xaero dimension for {}; leaving its map where it is",
                            activeWorldKey());
                    return true;   // nothing more we can do; do not spin on it
                }
            }
            var own = client.level.dimension();
            var want = target.equals(own) ? null : target;
            // Already there: do nothing at all. checkForWorldUpdate takes a file lock and pauses the map
            // writer, so re-applying a dimension Xaero is already on kept restarting its "Preparing
            // World Map" work, and it never got to finish.
            var current = mapWorld.getCustomDimensionId();
            if (java.util.Objects.equals(current, want)) return true;
            mapWorld.setCustomDimensionId(want);
            proc.checkForWorldUpdate();
            LOGGER.info("[TownyMap] Xaero map dimension -> {} (known: {})", target.identifier(),
                    mapWorld.getDimensionsList().stream()
                            .filter(d -> d != null && d.getDimId() != null)
                            .map(d -> d.getDimId().identifier().toString()).toList());
            return true;
        } catch (Throwable t) {
            // Was DEBUG, which meant a failure here looked identical to the feature simply not running:
            // the log showed a world switch and then nothing at all. Once per reason is enough.
            if (loggedSyncFailure == null || !loggedSyncFailure.equals(t.toString())) {
                loggedSyncFailure = t.toString();
                LOGGER.warn("[TownyMap] Could not sync Xaero map dimension", t);
            }
            return true;   // a thrown failure will not fix itself by repeating
        }
    }

    private static volatile String loggedSyncFailure = null;

    /**
     * A dimension Xaero already knows that belongs to the given squaremap world, or null if it knows
     * none. EarthMC's two lunar dimensions share one squaremap world, so either satisfies the Moon --
     * preferring whichever the player is standing in, then the exact world-name match.
     */
    /**
     * Creates and registers the Xaero map dimension for a squaremap world it has not seen this session,
     * returning its key, or null if we cannot name one.
     */
    private static net.minecraft.resources.ResourceKey<Level> createXaeroDimension(
            xaero.map.world.MapWorld mapWorld, String worldKey) {
        net.minecraft.resources.ResourceKey<Level> key = dimensionKeyFor(worldKey);
        if (key == null) return null;
        try {
            var created = mapWorld.createDimensionUnsynced(key);
            if (created == null) return null;
            LOGGER.info("[TownyMap] Created Xaero map dimension {} for {}", key.identifier(), worldKey);
            return key;
        } catch (Throwable t) {
            LOGGER.warn("[TownyMap] Could not create Xaero map dimension for {}: {}", worldKey, t.toString());
            return null;
        }
    }

    /** The Minecraft dimension a squaremap world corresponds to. */
    private static net.minecraft.resources.ResourceKey<Level> dimensionKeyFor(String worldKey) {
        if (WORLD_OVERWORLD.equals(worldKey)) return Level.OVERWORLD;
        if (WORLD_MOON.equals(worldKey)) {
            // Terrain the player walked is written under the dimension they were in. EarthMC has two
            // lunar ones and squaremap publishes a single world for them, so prefer the surface.
            return net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    net.minecraft.resources.Identifier.fromNamespaceAndPath("earthmc", "moon"));
        }
        int us = worldKey.indexOf('_');
        if (us <= 0 || us >= worldKey.length() - 1) return null;
        return net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.Identifier.fromNamespaceAndPath(
                        worldKey.substring(0, us), worldKey.substring(us + 1)));
    }

    /**
     * The dimension Xaero already lists for a squaremap world, or null if it does not have it yet.
     *
     * <p>Matched exactly against {@link #dimensionKeyFor}, never by namespace. A loose "any earthmc:
     * dimension will do" match picked earthmc:space -- the rocket you sit in for ten minutes on the way
     * up, which nobody explores and whose dimension type Xaero could not even resolve, so it reported
     * "Currently unknown dimension type! The map functions are limited." over empty ground. The Moon
     * means earthmc:moon and nothing else.
     */
    private static net.minecraft.resources.ResourceKey<Level> knownXaeroDimensionFor(
            xaero.map.world.MapWorld mapWorld, String worldKey) {
        net.minecraft.resources.ResourceKey<Level> want = dimensionKeyFor(worldKey);
        if (want == null) return null;
        for (var dim : mapWorld.getDimensionsList()) {
            if (dim != null && want.equals(dim.getDimId())) return want;
        }
        return null;   // not listed yet; the caller creates it
    }

    public static String xaeroViewedWorldKey() {
        var key = xaeroViewedDimension();
        if (key == null) return null;
        try {
            var id = key.identifier();
            return id.getNamespace() + "_" + id.getPath();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "namespace_path" for the dimension the player is in, with no validation against squaremap. */
    private static String rawWorldKey() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null) return null;
        try {
            var id = client.level.dimension().identifier();
            return id.getNamespace() + "_" + id.getPath();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Display name for the active world, for labels. */
    public static String activeWorldName() {
        return worldDisplayName(activeWorldKey());
    }

    /** squaremap's display name for a world key ("Terra Nostra", "Moon"), falling back to the key. */
    public static String worldDisplayName(String key) {
        if (key == null || key.isBlank()) return "this world";
        String name = squaremapWorlds().get(key);
        return name != null ? name : key;
    }

    /**
     * Detects a world switch and resets what belonged to the old one.
     *
     * <p>Driven from the client tick on purpose. activeWorldKey() is read from fetch threads (markers and
     * every tile URL), and clearing the tile cache there could close a NativeImage another thread is
     * still decoding. On the tick it happens once, on the main thread, between frames.
     */
    public static void tickWorldChange() {
        // Auto mode is resolved here, on the client tick, so activeWorldKey() stays safe to call from
        // the fetch threads that read it.
        // Resolved every tick, not only in Auto: the minimap and the marker fetcher both need the
        // player's world from threads that must not touch the level.
        resolvedPlayerWorld = playerMapWorld();
        if (config != null && config.mapWorldMode == WORLD_MODE_AUTO) {
            autoResolvedWorld = resolvedPlayerWorld;
        }
        tickPlayerDimensionChange();
        tickWorldMapInjectHealth();
        tickWorldMapOpenState();
        tickXaeroDimensionSync();
        tickTextInputFocus();
        String key = activeWorldKey();
        if (key.equals(lastActiveWorld)) return;
        lastActiveWorld = key;
        onActiveWorldChanged(key);
    }

    private static volatile String lastPlayerWorld = null;
    private static volatile String lastAnnouncedWorld = null;

    /**
     * Resets what belonged to the dimension the player just left.
     *
     * <p>Separate from the map-world toggle: travelling Earth -> Moon changes nothing about which world
     * the MAP shows, but it does invalidate anything anchored to the player's own coordinates. Also the
     * one place that can tell someone their map is still pointed at the world they came from.
     */
    private static void tickPlayerDimensionChange() {
        String pk = rawWorldKey();
        if (pk == null || pk.equals(lastPlayerWorld)) return;
        boolean first = lastPlayerWorld == null;
        lastPlayerWorld = pk;
        if (first) return;   // joining a world is not a transition
        // Chunk coordinates from the dimension just left; nothing marks which one they came from.
        optimisticClaimChunks.clear();
        minimapOutsideNationPlayers.clear();
        cachedGhosts.clear();
        cachedGhostsAt.clear();
        net.townymap.integration.ShopWaypoints.onDimensionChanged();
        if (!isOnEarthMcServer()) return;
        String world = playerMapWorld();
        if (config != null && config.mapWorldMode == WORLD_MODE_AUTO) {
            // The switch itself happens above; this is only the notice. Say nothing when the world did
            // not actually change -- a Nether trip resolves to Earth just like the overworld does.
            if (!world.equals(lastAnnouncedWorld)) {
                lastAnnouncedWorld = world;
                sendFeedback("Map switched to " + worldDisplayName(world) + ".", ChatFormatting.GREEN);
            }
            return;
        }
        // Pinned by hand: leave it alone, but do not let the map silently disagree with where they are.
        if (!world.equals(activeWorldKey())) {
            sendFeedback("You are on " + worldDisplayName(world) + " - the map is pinned to "
                    + activeWorldName() + ".", ChatFormatting.YELLOW);
        }
    }

    private static void onActiveWorldChanged(String key) {
        LOGGER.info("[TownyMap] Map world -> {}", key);
        // getTowns() hands the archive snapshot out whatever world is shown, so a snapshot loaded for one
        // world would paint its borders over the other's terrain. Switching worlds closes it rather than
        // silently mixing the two; the new world can be archived on its own from its own captures.
        if (isArchiveMode() && !key.equals(archiveWorldKey)) {
            exitArchive();
            sendFeedback("Archive closed - it covered " + worldDisplayName(archiveWorldKey) + ".",
                    ChatFormatting.YELLOW);
        }
        // Moon and Terra Nostra coordinates overlap numerically, so nothing cached for one world may be
        // reused for the other. Towns and tiles are keyed by world and so survive; what follows is the
        // state that carries no world of its own.
        if (apiClient != null) apiClient.onWorldChanged();
        cachedGhosts.clear();
        cachedGhostsAt.clear();
        // Optimistic claims are NOT dropped here any more: they belong to the world the player claimed
        // in and carry it, so pinning the map elsewhere must not wipe them off the minimap. The
        // dimension-change hook still clears them.
        townInfoRouteTarget = null;   // points into the world we just left
        // The camera is sitting at coordinates that meant something in the world we just left. Re-aim it:
        // at the player when the map is back on their own world, at the new world's claims otherwise --
        // the Moon's sit around x 3600 z 250, so the player's Earth position there is empty ground.
        pendingRecentreWorld = key;
        // The result cache is keyed on collection SIZES, and 5,500 Earth towns against 47 lunar ones will
        // always differ - but nothing guarantees that, so retire the results explicitly.
        net.townymap.gui.TownSearchOverlay.invalidateResults();
        if (renderer != null) {
            renderer.invalidateTownCaches();
            // Tiles are keyed by world now, so the world being switched away from keeps its cache
            // instead of being thrown away -- the minimap is still drawing it. Wiping here meant
            // pinning the world map to the Moon stripped the minimap's Terra Nostra imagery.
        }
        // Requested, not done here: at login the saved map world resolves before Xaero has built its
        // world-map session, and a one-shot attempt at that moment was simply lost. Only while the map
        // is actually open -- see tickWorldMapOpenState for why it must not linger past that.
        if (isWorldMapScreenActive()) requestXaeroDimensionSync();
    }

    /**
     * Coordinate scale of the dimension the PLAYER is in. The minimap and waypoints live here: both are
     * anchored to the player, and neither can be pointed at another dimension.
     */
    public static double dimensionCoordinateScale() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null) return 1.0;
        // The game's own ratio (8 for the Nether) rather than a hardcoded 8, so this stays right if a
        // server runs a non-standard scale. Guarded because a 0 here would blow up every caller.
        double scale = client.level.dimensionType().coordinateScale();
        return scale > 0 ? scale : 1.0;
    }

    /**
     * Coordinate scale of the dimension Xaero's WORLD MAP is drawing, which its dimension toggle can
     * point somewhere the player is not. Only the player's own dimension has a loaded DimensionType to
     * read, so a dimension viewed from elsewhere falls back to the vanilla ratios.
     */
    public static double worldMapCoordinateScale() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null) return 1.0;
        var viewed = xaeroViewedDimension();
        if (viewed != null && viewed != client.level.dimension()) {
            return viewed == Level.NETHER ? 8.0 : 1.0;
        }
        return dimensionCoordinateScale();
    }

    public static double worldMapOverlayScale() {
        if (!isActiveOnCurrentServer() || config == null) return 1.0;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null) return 1.0;
        // Xaero's world map has its own dimension toggle, so the terrain on screen is not always the
        // dimension the player is standing in. What we draw over it has to follow the terrain. With the
        // toggle untouched Xaero reports the player's own dimension, so this changes nothing by default.
        var viewed = xaeroViewedDimension();
        var dim = viewed != null ? viewed : client.level.dimension();
        if (dim == Level.OVERWORLD) return 1.0;
        if (config.netherMode == 2 && dim == Level.NETHER) return 8.0;   // Overworld Coords
        // Xaero is drawing a world squaremap maps and our toggle selects that same world: 1:1, like
        // Earth. This used to fall through to the hide below, so stepping onto the Moon blanked the
        // overlay even though squaremap has full tiles and claims for it.
        String vk = xaeroViewedWorldKey();
        if (vk != null && vk.equals(activeWorldKey())) return 1.0;
        // Deliberately looking at a different world than the one you are in. Keep rendering -- that is
        // the point of the switch - and the player markers are hidden separately.
        if (viewingOtherWorld() || !viewingEarth()) return 1.0;
        // The overworld-only hide exists because EarthMC's map covers only its overworld, so raw X/Z
        // from another dimension would put the overlay in the wrong place relative to the player.
        // Off EarthMC there is no such correspondence to protect, and hiding would blank the whole
        // overlay on every server whose world isn't registered as minecraft:overworld (hubs,
        // skyblock, minigames) - leaving only the buttons. Render anyway there.
        if (!isOnEarthMcServer()) return 1.0;
        return 0.0;                                                       // Hidden
    }

    public static int playerDotColor(String playerName) {
        return playerDotColor(playerName, townKey(playerName));
    }

    /**
     * @param playerKey the lowercase player name.  Callers in the per-frame render
     *                  loop pass {@link net.townymap.model.PlayerMarker#key()} so no
     *                  lowercase string is allocated per player per frame.
     */
    public static int playerDotColor(String playerName, String playerKey) {
        if (!isActiveOnCurrentServer() || config == null || !config.playersEnabled) return 0;
        if (earthMcApi == null || playerName == null || playerName.isBlank()) return 0xFFFFFFFF;

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getUser() == null) return 0xFFFFFFFF;
        String selfName = client.getUser().getName();
        if (playerName.equalsIgnoreCase(selfName)) return 0;

        EarthMcPlayerData self = playerDetailsCache.get(sessionSelfKey(selfName));
        if (self == null) {
            requestMinimapPlayerDetails(selfName);
            return 0xFFFFFFFF;
        }

        EarthMcPlayerData other = playerDetailsCache.get(playerKey);
        if (other == null) {
            requestMinimapPlayerDetails(playerName);
            return 0xFFFFFFFF;
        }

        if (!self.townName().isBlank()
                && !other.townName().isBlank()
                && self.townName().equalsIgnoreCase(other.townName())) {
            return 0xFF35F2FF;
        }
        if (!self.nationName().isBlank()
                && !other.nationName().isBlank()
                && self.nationName().equalsIgnoreCase(other.nationName())) {
            return 0xFFFFE066;
        }
        return 0xFFFFFFFF;
    }

    /** Lowercase key of the local player's name, cached for the session. */
    private static String sessionSelfKey(String selfName) {
        if (!selfName.equals(cachedSelfName)) {
            cachedSelfName = selfName;
            cachedSelfKey = townKey(selfName);
        }
        return cachedSelfKey;
    }

    public static int minimapPlayerDotColor(String playerName) {
        return playerDotColor(playerName);
    }

    private static void requestMinimapPlayerDetails(String name) {
        long now = System.currentTimeMillis();
        if (now - minimapPlayerDetailWindowMs >= 1_000L) {
            minimapPlayerDetailWindowMs = now;
            minimapPlayerDetailRequests = 0;
        }
        if (minimapPlayerDetailRequests >= 4) return;
        if (requestPlayerDetails(name)) minimapPlayerDetailRequests++;
    }

    /**
     * Ensures a map-marker player's town/nation is being fetched, for their label. Live players already
     * get this via {@link #playerDotColor}, but a last-seen (red) player has a fixed colour and never went
     * through that path — so without this its town/nation stayed blank. Rate-limited and de-duped, and
     * keyed the same ({@link #townKey}) as the lookup the label does, so a fetched result is found.
     */
    public static void requestPlayerLabelDetails(String name) {
        if (config != null && earthMcApi != null && isActiveOnCurrentServer()) {
            requestMinimapPlayerDetails(name);
        }
    }

    public static void renderOnMinimap(GuiGraphicsExtractor ctx, Object session, int x, int y, int size) {
        if (!isActiveOnCurrentServer()) return;
        try {
            TownyMinimapOverlay.render(ctx,
                    (xaero.hud.minimap.module.MinimapSession) session,
                    x, y, size);
        } catch (Exception e) {
            // Warn, not debug: a throw here makes the feature silently vanish, which is exactly
            // how the player-indicator bug stayed invisible. One-shot so it can't spam per frame.
            if (minimapOutlinesErrorLogged.compareAndSet(false, true)) {
                LOGGER.warn("[TownyMap] Failed to render minimap town outlines", e);
            }
        }
    }

    private static int archiveBannerX1, archiveBannerY1, archiveBannerX2, archiveBannerY2;
    private static boolean archiveBannerVisible;
    private static volatile long lastArchiveErrorMs;
    private static final int ARCHIVE_BANNER_Y = 34;   // top offset, shared by the render and the click hit-test
    // Date-step buttons drawn under the banner: jump the snapshot by ±1 / ±10 days (clamped to MIN_DATE..today).
    private static final int[] ARCHIVE_NAV_DELTAS = {-10, -1, 1, 10};
    private static final String[] ARCHIVE_NAV_LABELS = {"«10", "«1", "1»", "10»"};
    private static final int[] archiveNavX1 = new int[4];
    private static final int[] archiveNavX2 = new int[4];
    private static final boolean[] archiveNavEnabled = new boolean[4];
    private static int archiveNavY1, archiveNavY2;
    private static boolean archiveNavVisible;

    /** Top-centre banner: loading progress, the active-archive status (click to exit), or a recent error \u2014
     *  the only feedback there is, since entry is via the search bar. */
    public static void renderArchiveBanner(GuiGraphicsExtractor ctx, int screenW) {
        archiveBannerVisible = false;
        archiveNavVisible = false;
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;

        boolean errorFresh = lastArchiveError != null && !lastArchiveError.isBlank()
                && System.currentTimeMillis() - lastArchiveErrorMs < 8_000L;
        String text;
        int accent, fg;
        boolean active = false;   // the "click to exit" banner (not loading / error) \u2192 shows the date arrows
        if (archiveLoading) {
            text = "Loading archive\u2026"; accent = 0xFFC79A3A; fg = 0xFFF3E0B0;
        } else if (isArchiveMode()) {
            text = archiveStatus; accent = 0xFFB8474A; fg = 0xFFF3C0C0; active = true;
        } else if (errorFresh) {
            text = "Archive: " + lastArchiveError; accent = 0xFFB8474A; fg = 0xFFF3C0C0;
        } else {
            return;
        }
        if (text == null || text.isBlank()) return;

        int w = client.font.width(text);
        int x = (screenW - w) / 2;
        int y = ARCHIVE_BANNER_Y;   // below Xaero's top-centre coordinate readout so they don't overlap
        archiveBannerX1 = x - 8; archiveBannerY1 = y - 4; archiveBannerX2 = x + w + 8; archiveBannerY2 = y + 11;
        archiveBannerVisible = isArchiveMode();
        boolean scaled = net.townymap.gui.UiScale.active();
        if (scaled) net.townymap.gui.UiScale.push(ctx, screenW / 2f, ARCHIVE_BANNER_Y - 4);   // shrink around top-centre
        ctx.fill(archiveBannerX1, archiveBannerY1, archiveBannerX2, archiveBannerY2, 0xE0141414);
        ctx.fill(archiveBannerX1, archiveBannerY1, archiveBannerX2, archiveBannerY1 + 1, accent);
        ctx.text(client.font, text, x, y, fg, false);
        if (active) renderArchiveNav(ctx, client, screenW);
        if (scaled) net.townymap.gui.UiScale.pop(ctx);
    }

    /** The \u00b11 / \u00b110 day buttons under the banner. Left arrows disable at MIN_DATE, right arrows at today. */
    private static void renderArchiveNav(GuiGraphicsExtractor ctx, Minecraft client, int screenW) {
        net.minecraft.client.gui.Font tr = client.font;
        int req = archiveRequestedDate > 0 ? archiveRequestedDate : archiveActualDate;
        boolean atMin = req <= net.townymap.api.ArchiveClient.minDateFor(archiveWorldKey);
        boolean atMax = req >= todayInt();

        int padX = 6, gap = 4, h = 13, y = archiveBannerY2 + 3;
        int[] widths = new int[4];
        int total = gap * 3;
        for (int i = 0; i < 4; i++) { widths[i] = tr.width(ARCHIVE_NAV_LABELS[i]) + padX * 2; total += widths[i]; }
        int mouseX = (int) (client.mouseHandler.xpos() * screenW / client.getWindow().getWidth());
        int mouseY = (int) (client.mouseHandler.ypos() * client.getWindow().getGuiScaledHeight() / client.getWindow().getHeight());

        archiveNavY1 = y - 1; archiveNavY2 = y + h; archiveNavVisible = true;
        int cx = (screenW - total) / 2;
        for (int i = 0; i < 4; i++) {
            boolean enabled = ARCHIVE_NAV_DELTAS[i] < 0 ? !atMin : !atMax;
            archiveNavEnabled[i] = enabled;
            archiveNavX1[i] = cx; archiveNavX2[i] = cx + widths[i];
            boolean hover = enabled && mouseX >= cx && mouseX <= cx + widths[i] && mouseY >= archiveNavY1 && mouseY <= archiveNavY2;
            int bg = !enabled ? 0x90141414 : hover ? 0xF0343036 : 0xE0141414;
            int fg = !enabled ? 0xFF5A5A5A : 0xFFF3C0C0;
            ctx.fill(cx, archiveNavY1, cx + widths[i], archiveNavY2, bg);
            ctx.text(tr, ARCHIVE_NAV_LABELS[i], cx + padX, y + 2, fg, false);
            cx += widths[i] + gap;
        }
    }

    /** Left-click a date-step arrow. Returns true if it consumed the click. */
    public static boolean onArchiveNavClick(double mx, double my) {
        if (!archiveNavVisible || !isArchiveMode()) return false;
        if (net.townymap.gui.UiScale.active()) {
            Minecraft c = Minecraft.getInstance();
            if (c != null) mx = net.townymap.gui.UiScale.unscale(mx, c.getWindow().getGuiScaledWidth() / 2.0);
            my = net.townymap.gui.UiScale.unscale(my, ARCHIVE_BANNER_Y - 4);
        }
        if (my < archiveNavY1 || my > archiveNavY2) return false;
        for (int i = 0; i < 4; i++) {
            if (archiveNavEnabled[i] && mx >= archiveNavX1[i] && mx <= archiveNavX2[i]) {
                shiftArchive(ARCHIVE_NAV_DELTAS[i]);
                return true;
            }
        }
        return false;
    }

    /** Today's date as yyyymmdd \u2014 the upper bound for archive navigation (no future snapshots exist). */
    private static int todayInt() {
        java.time.LocalDate d = java.time.LocalDate.now();
        return d.getYear() * 10000 + d.getMonthValue() * 100 + d.getDayOfMonth();
    }

    /** Re-enters archive at the current requested date shifted by {@code deltaDays}, clamped to MIN_DATE..today. */
    public static void shiftArchive(int deltaDays) {
        if (!isArchiveMode() || archiveLoading) return;
        int base = archiveRequestedDate > 0 ? archiveRequestedDate : archiveActualDate;
        if (base <= 0) return;
        java.time.LocalDate d = java.time.LocalDate.of(base / 10000, (base / 100) % 100, base % 100).plusDays(deltaDays);
        int target = d.getYear() * 10000 + d.getMonthValue() * 100 + d.getDayOfMonth();
        target = Math.max(net.townymap.api.ArchiveClient.minDateFor(archiveWorldKey),
                Math.min(todayInt(), target));
        if (target == base) return;   // already at the clamp bound in that direction
        enterArchive(target);
    }

    /**
     * Left-click on the archive banner exits archive mode. Returns true if it consumed the click.
     * Self-contained (recomputes the banner box from the current state) so it still works even if the banner
     * render was skipped for a frame — the earlier bug where exiting archive appeared to do nothing.
     */
    public static boolean onArchiveBannerClick(double mx, double my) {
        if (!isArchiveMode()) return false;                 // only the active "click to exit" banner is clickable
        Minecraft client = Minecraft.getInstance();
        if (client == null || archiveStatus == null || archiveStatus.isBlank()) return false;
        int w = client.font.width(archiveStatus);
        int screenW = client.getWindow().getGuiScaledWidth();
        int x = (screenW - w) / 2;
        if (net.townymap.gui.UiScale.active()) {
            mx = net.townymap.gui.UiScale.unscale(mx, screenW / 2.0);
            my = net.townymap.gui.UiScale.unscale(my, ARCHIVE_BANNER_Y - 4);
        }
        if (mx < x - 8 || mx > x + w + 8 || my < ARCHIVE_BANNER_Y - 4 || my > ARCHIVE_BANNER_Y + 11) {
            return false;
        }
        exitArchive();
        return true;
    }

    public static void renderTownSearch(GuiGraphicsExtractor ctx, int screenW, int screenH) {
        if (!isActiveOnCurrentServer()) return;
        if (apiClient != null) {
            apiClient.tickWhileMapOpen();
            refreshPlayerIndexIfNeeded();
            refreshNationIndexIfNeeded();
            requestSearchDetailsIfNeeded();
            TownSearchOverlay.render(ctx, screenW, screenH, apiClient.getTowns(), apiClient.getPlayers(),
                    townDetailsCache, apiPlayers, playerDetailsCache, apiClient.getPlayerHistory(),
                    activeNationList(), activeNationDetails(), config.favoriteTowns);
        }
    }

    public static void renderTownInfo(GuiGraphicsExtractor ctx, int screenW, int screenH) {
        if (!isActiveOnCurrentServer()) return;
        TownPopupData data = TownInfoOverlay.currentData();
        if (data != null && data != TownPopupData.WILDERNESS) {
            requestTownActiveResidents(data.townName(), townKey(data.townName()));
            if (data.nationName() != null && !data.nationName().isBlank()) {
                requestNationDetails(data.nationName());
            }
        }
        TownInfoOverlay.render(ctx, screenW, screenH,
                data != null && isFavorite(data.townName()), activeNationDetails());
    }

    public static void renderTownHover(GuiGraphicsExtractor ctx, int mouseX, int mouseY,
                                       double worldX, double worldZ, int screenW, int screenH) {
        if (!isActiveOnCurrentServer()) return;
        if (config != null && config.chunkCounterEnabled) return;
        if (apiClient != null && config != null && config.townsEnabled) {
            TownData town = TownHoverOverlay.townAt(worldX, worldZ, apiClient.getTowns());
            if (town == null) return;

            String key = townKey(town.name());
            TownPopupData details = townDetailsCache.get(key);
            requestTownDetails(town.name(), key);
            // In archive mode the live markers' mayor/nation are today's, not that date's — pass null so the
            // hover uses only the archived popup detail (which carries the historical mayor/nation).
            boolean arch = isArchiveMode();
            TownHoverOverlay.render(ctx, mouseX, mouseY, screenW, screenH, town, details,
                    arch ? null : apiClient.getTownMayor(key),
                    arch ? archiveNationLabel(town, details) : apiClient.getTownNation(key));
        }
    }

    public static boolean onMapToggleClick(double mouseX, double mouseY, int screenH) {
        return onMapToggleClick(mouseX, mouseY, screenH, false);
    }

    public static boolean onMapToggleClick(double mouseX, double mouseY, int screenH, boolean backward) {
        if (!isActiveOnCurrentServer()) return false;
        return config != null && MapToggleOverlay.handleClick(mouseX, mouseY, screenH, config, backward);
    }

    public static boolean onSettingsButtonClick(double mouseX, double mouseY, int screenH) {
        return MapToggleOverlay.handleSettingsClick(mouseX, mouseY, screenH);
    }

    public static boolean onChunkCounterClick(double worldX, double worldZ) {
        if (!isActiveOnCurrentServer()) return false;
        if (config == null || !config.chunkCounterEnabled) return false;
        return ChunkCounterOverlay.handleRightClick(worldX, worldZ);
    }

    public static boolean isChunkCounterActive() {
        return isActiveOnCurrentServer() && config != null && config.chunkCounterEnabled;
    }

    public static void openConfigScreen() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        // Pass the current screen (GuiMap) as parent so closing config returns to the map.
        client.gui.setScreen(new net.townymap.gui.TownyMapConfigScreen(client.gui.screen()));
    }

    public static TownSearchOverlay.ClickResult onTownSearchClick(double mouseX, double mouseY,
                                                                  int screenW, int screenH) {
        if (!isActiveOnCurrentServer()) return TownSearchOverlay.ClickResult.none();
        if (apiClient == null) return TownSearchOverlay.ClickResult.none();
        return TownSearchOverlay.click(mouseX, mouseY, screenW, apiClient.getTowns(), apiClient.getPlayers(),
                townDetailsCache, apiPlayers, playerDetailsCache, apiClient.getPlayerHistory(),
                activeNationList(), activeNationDetails(),
                config != null ? config.favoriteTowns : List.of());
    }

    public static TownSearchOverlay.ClickResult onTownSearchKeyPressed(int keyCode) {
        if (!isActiveOnCurrentServer()) return TownSearchOverlay.ClickResult.none();
        if (apiClient == null) return TownSearchOverlay.ClickResult.none();
        return TownSearchOverlay.keyPressed(keyCode, apiClient.getTowns(), apiClient.getPlayers(),
                townDetailsCache, apiPlayers, playerDetailsCache, apiClient.getPlayerHistory(),
                activeNationList(), activeNationDetails());
    }

    public static boolean onTownSearchCharTyped(char chr) {
        if (!isActiveOnCurrentServer()) return false;
        return TownSearchOverlay.charTyped(chr);
    }

    /** The user's info-panel scale multiplier (1.0 = current sizing), for the town popup's own scaling. */
    public static float infoPanelScale() {
        return config == null ? 1.0f : config.infoPanelScale;
    }

    public static TownInfoOverlay.ActionResult onTownInfoClick(double mouseX, double mouseY) {
        if (!isActiveOnCurrentServer()) return TownInfoOverlay.ActionResult.none();
        TownInfoOverlay.ActionResult result = TownInfoOverlay.handleClick(mouseX, mouseY);
        if (result.action() == TownInfoOverlay.Action.FAVORITE) {
            toggleFavorite(result.townName());
        } else if (result.action() == TownInfoOverlay.Action.EXPAND) {
            openTownDetail(result.townName());
        } else if (result.action() == TownInfoOverlay.Action.DISCORD) {
            TownInfoOverlay.openDiscord(result.url());
        } else if (result.action() == TownInfoOverlay.Action.ROUTE) {
            createXaeroRoute(townInfoRouteTarget);
        } else if (result.action() == TownInfoOverlay.Action.SEARCH) {
            // Clicking a name in the popup hands off to the search info panel.
            TownInfoOverlay.dismiss();
            TownSearchOverlay.openSearch(result.searchType(), result.searchName());
        }
        return result;
    }

    /**
     * Opens an expanded panel for a town, nation or player. The full record is fetched on demand rather
     * than held for every visible entity: those payloads carry entire resident/town/friend lists, which
     * would be a lot of memory to keep for thousands of entities to serve a panel showing one at a time.
     *
     * <p>{@code parent} becomes the panel's back target, so following names from town to player to nation
     * walks back the way it came.
     */
    /** Opens the info panel, from a keybind or a button. Same panel the detail pages use. */
    public static void openStatsPanel() {
        // Warm the two slow sources the moment the panel opens, rather than when their tab is clicked.
        // Both return immediately and load in the background, so by the time anyone reaches Players the
        // data is usually already there -- that wait was the whole delay, not the rendering.
        warmInfoPanelData();
        openDetail(net.townymap.gui.DetailScreen.Kind.STATS, "Info Panel", null);
    }

    /** Kicks off the online-player and outlaw/trusted loads if their caches are cold. Cheap when warm. */
    public static void warmInfoPanelData() {
        try {
            // Warm ONLY what a visible tab reads: nationStats() is ~4 batches and backs Gold, Outlaws
            // and Founded on the Nations tab.
            //
            // outlawTrustedCounts() used to be warmed here and was by far the biggest cost in the mod --
            // a ~56-batch sweep of every town, fired on every panel open, whose only consumer sits in the
            // hidden Players tab. Nations > Outlaws reads nationStats(), not that sweep, so nothing
            // visible ever used the result. allPlayerStats() (~600 batches) is likewise not warmed.
            nationStats();
        } catch (RuntimeException ignored) {
            // Warming is best-effort; the tabs still load on demand.
        }
    }

    public static void openDetail(net.townymap.gui.DetailScreen.Kind kind, String name,
                                  net.minecraft.client.gui.screens.Screen parent) {
        if (isAccessBlocked()) return;   // every panel funnels through here, including the keybind
        if (name == null || name.isBlank()) return;
        // Opening the expanded panel replaces what you were looking at, so drop the join-range zone with it.
        net.townymap.gui.TownSearchOverlay.showNationRange(null);
        // Archive mode: towns, nations AND players are built from the snapshot instead of fetched live, so
        // following any link stays in that date's data (a player shows only what residency the archive knows).
        if (isArchiveMode()) {
            if (kind == net.townymap.gui.DetailScreen.Kind.TOWN) { openArchiveTownDetail(name, parent); return; }
            if (kind == net.townymap.gui.DetailScreen.Kind.NATION) { openArchiveNationDetail(name, parent); return; }
            if (kind == net.townymap.gui.DetailScreen.Kind.PLAYER) { openArchivePlayerDetail(name, parent); return; }
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        net.minecraft.client.gui.screens.Screen from =
                parent != null ? parent : client.gui.screen();

        // Stats are derived from the claim data already in memory, so like alliances they open directly
        // instead of routing through a fetch. This also means they work offline and inside an archive.
        if (kind == net.townymap.gui.DetailScreen.Kind.STATS) {
            net.townymap.gui.DetailScreen screen = new net.townymap.gui.DetailScreen(from, name);
            client.gui.setScreen(screen);
            // Dashboard is tab 0 and DetailScreen starts on tab 0, so the panel has to open on the
            // dashboard page. Opening on stats() left the Dashboard tab highlighted over Statistics
            // content, and clicking Dashboard did nothing because the screen already thought it was there.
            screen.setPage(net.townymap.gui.DetailPages.dashboard());
            return;
        }

        // Alliances are already in memory (the roster the map colours towns from), so open the panel
        // directly rather than routing through a fetch that would have nothing to fetch.
        if (kind == net.townymap.gui.DetailScreen.Kind.ALLIANCE) {
            net.townymap.api.AllianceClient.Alliance a = allianceByName(name);
            net.townymap.gui.DetailScreen screen = new net.townymap.gui.DetailScreen(from, name);
            client.gui.setScreen(screen);
            if (a == null) screen.markFailed();
            else screen.setPage(net.townymap.gui.DetailPages.alliance(a));
            return;
        }
        if (earthMcApi == null) return;

        java.util.concurrent.CompletableFuture<net.townymap.gui.DetailScreen.Page> future = switch (kind) {
            case TOWN -> earthMcApi.fetchTownFull(name)
                    .thenApply(d -> d == null ? null : net.townymap.gui.DetailPages.town(d));
            case PLAYER -> earthMcApi.fetchPlayerFull(name)
                    .thenApply(d -> d == null ? null : net.townymap.gui.DetailPages.player(d));
            case NATION -> earthMcApi.fetchNationFull(name)
                    .thenApply(d -> d == null ? null : net.townymap.gui.DetailPages.nation(d));
            case ALLIANCE, STATS -> throw new IllegalStateException("handled above, from memory");
        };

        // Show the panel immediately in a loading state. Waiting for the fetch before opening anything made
        // a click look like it had simply done nothing, which is exactly how a slow or failed lookup read.
        net.townymap.gui.DetailScreen screen = new net.townymap.gui.DetailScreen(from, name);
        client.gui.setScreen(screen);
        future.thenAccept(page -> client.execute(() -> {
            if (client.gui.screen() != screen) return;   // navigated away while the fetch was in flight
            if (page == null) {
                // API opt-outs never resolve. Town rosters have no opt-out, so show what they do carry
                // rather than a bare "not found" -- same fallback the map popup uses.
                net.townymap.gui.DetailScreen.Page fallback = rosterPlayerPage(kind, name);
                if (fallback != null) screen.setPage(fallback);
                else screen.markFailed();
            } else {
                screen.setPage(page);
            }
        }));
    }

    /** Roster-derived page for a player the API has nothing for, or null if they are on no roster. */
    private static net.townymap.gui.DetailScreen.Page rosterPlayerPage(
            net.townymap.gui.DetailScreen.Kind kind, String name) {
        if (kind != net.townymap.gui.DetailScreen.Kind.PLAYER || apiClient == null) return null;
        String town = apiClient.townOfResident(name);
        if (town == null) return null;
        String nation = apiClient.getTownNation(town.toLowerCase(Locale.ROOT));
        return net.townymap.gui.DetailPages.playerFromRoster(name, town, nation);
    }

    /** Convenience for the map popup's Expand button. */
    public static void openTownDetail(String townName) {
        openDetail(net.townymap.gui.DetailScreen.Kind.TOWN, townName, null);
    }

    /** Expand for an archived town: build the panel straight from the snapshot's data (no live fetch), so it
     *  shows that date's residents/councillors/etc. and omits every field the archive doesn't record. */
    private static void openArchiveTownDetail(String townName, net.minecraft.client.gui.screens.Screen parent) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || townName == null) return;
        net.minecraft.client.gui.screens.Screen from = parent != null ? parent : client.gui.screen();
        net.townymap.gui.DetailScreen screen = new net.townymap.gui.DetailScreen(from, townName);
        client.gui.setScreen(screen);
        net.townymap.api.ArchiveClient.ArchiveTown at = archiveTownDetails.get(townName.toLowerCase(Locale.ROOT));
        if (at == null) screen.markFailed();
        else screen.setPage(net.townymap.gui.DetailPages.archiveTown(at));
    }

    /**
     * Expand for an archived nation: derived entirely from the snapshot's towns — its member towns AND their
     * residents/chunks as they were on that date. Nation-level data the markers don't carry (king, founded,
     * bank, allies, spawn, over-claim…) is omitted rather than guessed.
     */
    private static void openArchiveNationDetail(String nationName, net.minecraft.client.gui.screens.Screen parent) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || nationName == null) return;
        net.minecraft.client.gui.screens.Screen from = parent != null ? parent : client.gui.screen();
        net.townymap.gui.DetailScreen screen = new net.townymap.gui.DetailScreen(from, nationName);
        client.gui.setScreen(screen);

        // Gather this nation's towns from the snapshot, in claim-size order (capital first), aggregating
        // residents (each resident is in exactly one town) and chunks.
        List<net.townymap.api.ArchiveClient.ArchiveTown> towns = new ArrayList<>();
        String capital = "";
        for (net.townymap.api.ArchiveClient.ArchiveTown t : archiveTownDetails.values()) {
            if (nationName.equalsIgnoreCase(t.nation())) {
                towns.add(t);
                if (t.capital()) capital = t.name();
            }
        }
        if (towns.isEmpty()) { screen.markFailed(); return; }
        towns.sort((a, b) -> Integer.compare(b.chunks(), a.chunks()));

        List<String> townNames = new ArrayList<>();
        java.util.LinkedHashSet<String> residents = new java.util.LinkedHashSet<>();
        int chunks = 0;
        for (net.townymap.api.ArchiveClient.ArchiveTown t : towns) {
            townNames.add(t.name());
            residents.addAll(t.residents());
            chunks += t.chunks();
        }
        screen.setPage(net.townymap.gui.DetailPages.archiveNation(
                nationName, capital, townNames, new ArrayList<>(residents), chunks));
    }

    /** Expand for an archived player: only what the snapshot reveals — the town they resided in, its nation,
     *  and their rank in it. Nothing live (last online, balance, registered date…) is shown. */
    private static void openArchivePlayerDetail(String playerName, net.minecraft.client.gui.screens.Screen parent) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || playerName == null) return;
        net.minecraft.client.gui.screens.Screen from = parent != null ? parent : client.gui.screen();
        net.townymap.gui.DetailScreen screen = new net.townymap.gui.DetailScreen(from, playerName);
        client.gui.setScreen(screen);
        ArchivePlayerInfo info = archivePlayerInfo(playerName);
        if (info == null) screen.markFailed();   // not a resident of any town on that date
        else screen.setPage(net.townymap.gui.DetailPages.archivePlayer(playerName, info.town(), info.nation(), info.role()));
    }

    /**
     * Called by MixinGuiMap when the player right-clicks the map.
     * Shows a loading indicator immediately, then fills in data asynchronously.
     */
    /** Screen-pixel radius within which a left-click counts as hitting a player marker. Covers both the
     *  small dot and the larger head, with a little slack so the tiny dot is still easy to click. */
    private static final double PLAYER_CLICK_RADIUS = 8.0;

    /**
     * Left-click hit-test against the player markers (live dots/heads and last-seen ghosts). On a hit it
     * opens the small player info panel — which carries the Expand button to the full panel — and returns
     * true so the click is consumed. {@code camXWorld/camZWorld} and {@code mapScale} are the overlay's
     * own camera and scale, so the projection matches exactly where the markers were drawn.
     */
    public static boolean onMapPlayerClick(double screenX, double screenY,
                                           double camXWorld, double camZWorld, double mapScale,
                                           int sw, int sh) {
        if (!isActiveOnCurrentServer() || config == null || apiClient == null
                || !config.playersEnabled || mapScale <= 0 || isArchiveMode()) return false;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getUser() == null) return false;
        String selfName = client.getUser().getName();

        double bestDist = PLAYER_CLICK_RADIUS + 1;
        String bestName = null;
        for (PlayerMarker m : apiClient.getPlayers()) {
            if (m.name() == null || m.name().equalsIgnoreCase(selfName)) continue;
            double d = markerScreenDist(m.x(), m.z(), camXWorld, camZWorld, mapScale, sw, sh, screenX, screenY);
            if (d < bestDist) { bestDist = d; bestName = m.name(); }
        }
        if (config.playerLastSeen) {
            for (GhostMarker g : lastSeenGhosts()) {
                if (g.name().equalsIgnoreCase(selfName)) continue;
                double d = markerScreenDist(g.x(), g.z(), camXWorld, camZWorld, mapScale, sw, sh, screenX, screenY);
                if (d < bestDist) { bestDist = d; bestName = g.name(); }
            }
        }
        if (bestName == null || bestDist > PLAYER_CLICK_RADIUS) return false;
        TownSearchOverlay.openSearch("player", bestName);
        return true;
    }

    /**
     * Left-click hit-test against the nation capital stars. On a hit it opens the small nation info panel
     * (which carries the Expand button), mirroring the player-dot behaviour. Star screen positions are
     * recorded by the renderer as they're drawn, so the hit-test matches exactly and needs no re-projection.
     */
    public static boolean onMapNationStarClick(double screenX, double screenY) {
        if (renderer == null || config == null || !config.nationStarsEnabled) return false;
        String nation = renderer.nationStarAt(screenX, screenY);
        if (nation == null || nation.isBlank()) return false;
        TownSearchOverlay.openSearch("nation", nation);
        return true;
    }

    private static double markerScreenDist(int worldX, int worldZ, double camXWorld, double camZWorld,
                                           double mapScale, int sw, int sh, double screenX, double screenY) {
        double mx = (worldX - camXWorld) * mapScale + sw / 2.0;
        double my = (worldZ - camZWorld) * mapScale + sh / 2.0;
        return Math.hypot(mx - screenX, my - screenY);
    }

    // ── Archive mode (Wayback Machine historical claims) ─────────────────────
    private static final net.townymap.api.ArchiveClient archiveClient = new net.townymap.api.ArchiveClient();
    private static volatile boolean archiveLoading = false;
    private static volatile String archiveStatus = "";     // banner text while active/loading, else blank
    private static volatile int archiveActualDate = 0;
    /** Which world the loaded snapshot describes, so a world switch knows whether it still applies. */
    private static volatile String archiveWorldKey = WORLD_OVERWORLD;
    private static volatile int archiveRequestedDate = 0;   // last date asked for; the ± arrows step from this
    // Nations as they were on the archived date, synthesized from the snapshot's town tooltips. Swapped in
    // for the live nation data while archive mode is active so stars/search/hover show that date's nations.
    /**
     * Which nation a town belonged to, honouring archive mode.
     *
     * <p>{@code setArchiveTowns} swaps only the town list; the client's town-to-nation map stays live.
     * Asking it directly while viewing an archive answers with *today's* membership, so a nation that
     * has since gained or lost towns gets the wrong set -- and one that didn't exist yet still matches
     * whichever towns carry its name now. The snapshot records each town's nation at the time, so use
     * that when it's loaded.
     */
    public static String townNationAt(String townKey) {
        if (townKey == null) return null;
        if (isArchiveMode()) {
            // archiveTownDetails is keyed by lower-cased name, which is exactly what townKey() produces.
            net.townymap.api.ArchiveClient.ArchiveTown at =
                    archiveTownDetails.get(townKey.toLowerCase(Locale.ROOT));
            return at == null ? null : at.nation();
        }
        return apiClient == null ? null : apiClient.getTownNation(townKey);
    }

    private static volatile Map<String, EarthMcNationData> archiveNations = null;
    private static volatile List<EarthMcNationData> archiveNationList = List.of();
    // Full archived town info (residents/councillors/etc. as they were), keyed lower-case, for the Expand panel.
    private static volatile Map<String, net.townymap.api.ArchiveClient.ArchiveTown> archiveTownDetails = Map.of();
    // Reverse index: player (lower-case) → the town they resided in on the snapshot date.
    private static volatile Map<String, String> archivePlayerTowns = Map.of();

    /** What the archive knows about a player on the snapshot date — all derived from town residencies. */
    public record ArchivePlayerInfo(String town, String nation, String role) {}

    /** The archived residency of a player, or null if they weren't a resident of any town on that date. */
    public static ArchivePlayerInfo archivePlayerInfo(String name) {
        if (name == null) return null;
        String townName = archivePlayerTowns.get(name.toLowerCase(Locale.ROOT));
        if (townName == null) return null;
        net.townymap.api.ArchiveClient.ArchiveTown t = archiveTownDetails.get(townName.toLowerCase(Locale.ROOT));
        if (t == null) return null;
        String role = t.mayor() != null && t.mayor().equalsIgnoreCase(name) ? "Mayor"
                : containsIgnoreCase(t.councillors(), name) ? "Councillor" : "Resident";
        return new ArchivePlayerInfo(t.name(), t.nation() == null ? "" : t.nation(), role);
    }

    private static boolean containsIgnoreCase(List<String> list, String s) {
        if (list == null) return false;
        for (String e : list) if (e.equalsIgnoreCase(s)) return true;
        return false;
    }

    private static Map<String, String> buildArchivePlayerTowns(
            Map<String, net.townymap.api.ArchiveClient.ArchiveTown> towns) {
        Map<String, String> out = new java.util.HashMap<>();
        for (net.townymap.api.ArchiveClient.ArchiveTown t : towns.values()) {
            for (String r : t.residents()) out.putIfAbsent(r.toLowerCase(Locale.ROOT), t.name());
        }
        return out;
    }

    /** The nation data the map should show right now — archived nations in archive mode, else live. */
    private static Map<String, EarthMcNationData> activeNationDetails() {
        return archiveNations != null ? archiveNations : nationDetailsCache;
    }
    /** The nation index for the info panel: archive-aware, and already cached for the search bar. */
    public static List<EarthMcNationData> apiNationIndex() {
        refreshNationIndexIfNeeded();
        return activeNationList();
    }

    private static List<EarthMcNationData> activeNationList() {
        return archiveNations != null ? archiveNationList : apiNations;
    }

    /** The hover's nation label for an archived town: "Capital of X" if it was that nation's capital on the
     *  snapshot date, else "X", or null when the town had no nation then (so the hover omits it entirely). */
    private static String archiveNationLabel(TownData town, TownPopupData details) {
        String nation = details == null ? null : details.nationName();
        if (nation == null || nation.isBlank()) return null;
        EarthMcNationData nd = archiveNations == null ? null : archiveNations.get(nation.toLowerCase(Locale.ROOT));
        boolean capital = nd != null && town.name().equalsIgnoreCase(nd.capitalName());
        return capital ? "Capital of " + nation : nation;
    }

    public static boolean isArchiveMode() { return apiClient != null && apiClient.isArchiveActive(); }
    public static boolean isArchiveLoading() { return archiveLoading; }
    public static String archiveStatus() { return archiveStatus; }

    /**
     * Loads the archived town claims nearest {@code yyyymmdd} (Terra Nostra) and switches the map to show
     * them. Players and live refresh pause while archive mode is active. Fetched off-thread; the banner
     * shows progress. {@code exitArchive} restores the live map.
     */
    public static void enterArchive(int yyyymmdd) {
        if (apiClient == null || archiveLoading) return;
        // No longer forced to Terra Nostra. The Moon has its own Wayback captures, so an archive loads
        // for whichever world is on screen and the two are read from separate histories.
        final String archiveWorld = activeWorldKey();
        archiveLoading = true;
        archiveStatus = "Loading archive…";
        lastArchiveError = "";
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            var snap = archiveClient.fetchSnapshot(yyyymmdd, archiveWorld);
            Minecraft client = Minecraft.getInstance();
            Runnable apply = () -> {
                archiveLoading = false;
                if (snap == null || snap.towns().isEmpty()) {
                    if (!isArchiveMode()) archiveStatus = "";
                    lastArchiveError = "No snapshot found near that date.";
                    lastArchiveErrorMs = System.currentTimeMillis();
                    return;
                }
                archiveWorldKey = archiveWorld;
                apiClient.setArchiveTowns(snap.towns());
                // Seed the detail cache with the snapshot's own popup info, so clicking an archived town
                // shows that date's mayor/residents/founded — not today's (live fetches are gated off above).
                townDetailsCache.clear();
                snap.details().forEach(townDetailsCache::put);
                archiveNations = new java.util.HashMap<>(snap.nations());
                archiveNationList = List.copyOf(snap.nations().values());
                archiveTownDetails = new java.util.HashMap<>(snap.fullDetails());
                archivePlayerTowns = buildArchivePlayerTowns(snap.fullDetails());
                if (renderer != null) renderer.invalidateTownCaches();
                archiveActualDate = snap.actualDate();
                archiveRequestedDate = yyyymmdd;   // the ± arrows step from what we asked for, not what Wayback resolved
                archiveStatus = "ARCHIVE · " + formatArchiveDate(snap.actualDate())
                        + " · " + snap.towns().size() + " towns · click to exit";
                lastArchiveError = "";
            };
            if (client != null) client.execute(apply); else apply.run();
        });
    }

    public static void exitArchive() {
        if (apiClient == null) return;
        apiClient.clearArchive();
        townDetailsCache.clear();   // drop the archived popups so live data re-fetches
        archiveNations = null;      // fall back to live nation data for stars/search/hover
        archiveNationList = List.of();
        archiveTownDetails = Map.of();
        archivePlayerTowns = Map.of();
        if (renderer != null) renderer.invalidateTownCaches();
        archiveStatus = "";
        archiveActualDate = 0;
        archiveRequestedDate = 0;
        archiveLoading = false;
    }

    private static volatile String lastArchiveError = "";
    public static String lastArchiveError() { return lastArchiveError; }

    private static String formatArchiveDate(int yyyymmdd) {
        if (yyyymmdd <= 0) return "?";
        int y = yyyymmdd / 10000, m = (yyyymmdd / 100) % 100, d = yyyymmdd % 100;
        String[] mon = {"", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        return d + " " + (m >= 1 && m <= 12 ? mon[m] : "?") + " " + y;
    }

    public static void onMapRightClick(double worldX, double worldZ, int screenX, int screenY) {
        if (!isActiveOnCurrentServer()) return;
        if (earthMcApi == null) return;
        armedMapDismiss = false;
        TownSearchOverlay.reset();   // right-clicking a new town clears any active search bar/result
        TownData clickedTown = null;
        TownPopupData fallback = null;
        MapJumpTarget fallbackTarget = null;
        String fallbackKey = "";
        if (apiClient != null) {
            TownData town = TownHoverOverlay.townAt(worldX, worldZ, apiClient.getTowns());
            if (town != null) {
                clickedTown = town;
                fallbackKey = townKey(town.name());
                fallback = townDetailsCache.get(fallbackKey);
                fallbackTarget = new MapJumpTarget(town.name(), town.centerX(), town.centerZ());
            }
        }
        long lookupId = townLookupId.incrementAndGet();
        if (fallback != null) {
            showLookupResult(fallback, screenX, screenY, worldX, worldZ, fallbackTarget);
            // Archive: the live API doesn't know these historical towns — keep the snapshot popup, never fetch.
            if (isArchiveMode() || isTownDetailsFresh(fallbackKey)) return;
        } else if (isArchiveMode()) {
            TownInfoOverlay.dismiss();   // archived town with no seeded detail — nothing live to fall back to
            return;
        } else {
            TownInfoOverlay.showLoading(screenX, screenY);
        }
        TownPopupData cachedFallback = fallback;
        MapJumpTarget cachedFallbackTarget = fallbackTarget;
        if (clickedTown != null) {
            earthMcApi.fetchTown(clickedTown.name()).thenAccept(data -> {
                Minecraft client = Minecraft.getInstance();
                if (client == null) return;
                client.execute(() -> {
                    if (lookupId != townLookupId.get()) return;
                    showLookupResult(data != null ? data : cachedFallback, screenX, screenY,
                            worldX, worldZ, cachedFallbackTarget);
                });
            });
            return;
        }
        earthMcApi.fetchTownAt(worldX, worldZ).thenAccept(data -> {
            Minecraft client = Minecraft.getInstance();
            if (client == null) return;
            client.execute(() -> {
                if (lookupId != townLookupId.get()) return;
                showLookupResult(data != null ? data : cachedFallback, screenX, screenY,
                        worldX, worldZ, cachedFallbackTarget);
            });
        });
    }

    /**
     * Opens the rich town popup (TownInfoOverlay, the one with the Route button) for a town chosen
     * from the search bar or an in-panel town link, so the search bar and map right-click share one GUI.
     * The popup is anchored at screen centre — a search selection recentres the camera on the town, so
     * that's where it ends up — and the town's details are fetched exactly as a right-click would.
     */
    public static void openTownPopupFromSearch(String townName) {
        if (!isActiveOnCurrentServer() || earthMcApi == null || apiClient == null) return;
        if (townName == null || townName.isBlank()) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        int sx = client.getWindow().getGuiScaledWidth() / 2;
        int sy = client.getWindow().getGuiScaledHeight() / 2;

        TownData clickedTown = null;
        for (TownData town : apiClient.getTowns()) {
            if (town.name().equalsIgnoreCase(townName)) { clickedTown = town; break; }
        }
        double worldX = 0, worldZ = 0;
        MapJumpTarget fallbackTarget = null;
        TownPopupData fallback = null;
        String fallbackKey = "";
        if (clickedTown != null) {
            worldX = clickedTown.centerX();
            worldZ = clickedTown.centerZ();
            fallbackKey = townKey(clickedTown.name());
            fallback = townDetailsCache.get(fallbackKey);
            fallbackTarget = new MapJumpTarget(clickedTown.name(), clickedTown.centerX(), clickedTown.centerZ());
        }

        long lookupId = townLookupId.incrementAndGet();
        if (fallback != null) {
            showLookupResult(fallback, sx, sy, worldX, worldZ, fallbackTarget);
            if (isArchiveMode() || isTownDetailsFresh(fallbackKey)) return;   // archive: keep the snapshot popup
        } else if (isArchiveMode()) {
            TownInfoOverlay.dismiss();
            return;
        } else {
            TownInfoOverlay.showLoading(sx, sy);
        }
        final TownPopupData cachedFallback = fallback;
        final MapJumpTarget cachedFallbackTarget = fallbackTarget;
        final double fWorldX = worldX, fWorldZ = worldZ;
        earthMcApi.fetchTown(townName).thenAccept(data -> {
            Minecraft c = Minecraft.getInstance();
            if (c == null) return;
            c.execute(() -> {
                if (lookupId != townLookupId.get()) return;
                showLookupResult(data != null ? data : cachedFallback, sx, sy,
                        fWorldX, fWorldZ, cachedFallbackTarget);
            });
        });
    }

    public static void dismissTownInfo() {
        townLookupId.incrementAndGet();
        townInfoRouteTarget = null;
        TownInfoOverlay.dismiss();
    }

    private static final net.townymap.api.BlocklistClient blocklistClient =
            new net.townymap.api.BlocklistClient();
    private static volatile net.townymap.api.BlocklistClient.Blocklist blocklist =
            net.townymap.api.BlocklistClient.EMPTY;
    private static volatile boolean blocklistFetched = false;
    private static volatile boolean accessBlocked = false;
    private static volatile boolean blockedNoticeShown = false;

    /**
     * Whether this player is barred from the mod's features.
     *
     * <p>Fetched once per launch and matched on UUID, or on the player's nation. Fails open at every
     * step: no list, no network, no player record — full access. Blocking wrongly is far worse than
     * failing to block, and this is a courtesy check rather than enforcement; it runs on the user's
     * machine and can be removed by anyone willing to edit the jar.
     */
    public static boolean isAccessBlocked() {
        if (!blocklistFetched) {
            blocklistFetched = true;
            blocklistClient.fetch().thenAccept(list -> {
                blocklist = list == null ? net.townymap.api.BlocklistClient.EMPTY : list;
                evaluateAccess();
            });
        }
        if (!blocklist.isEmpty() && !accessBlocked) evaluateAccess();
        return accessBlocked;
    }

    private static volatile boolean evaluatingAccess = false;

    private static void evaluateAccess() {
        // Re-entrancy guard. This calls selfPlayer(), which checks isActiveOnCurrentServer(), which now
        // calls isAccessBlocked() -- straight back into here. Unguarded that recurses until the stack
        // blows, on every frame, which is what stopped the blocked notice ever appearing.
        if (evaluatingAccess) return;
        evaluatingAccess = true;
        try {
            evaluateAccessInner();
        } finally {
            evaluatingAccess = false;
        }
    }

    private static void evaluateAccessInner() {
        if (blocklist.isEmpty()) { accessBlocked = false; return; }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getUser() == null) return;
        String uuid = client.getUser().getProfileId() == null ? ""
                : client.getUser().getProfileId().toString().toLowerCase(Locale.ROOT).replace("-", "");
        boolean blocked = !uuid.isBlank() && blocklist.uuids().contains(uuid);
        if (!blocked) {
            EarthMcPlayerData self = selfPlayer();
            if (self != null && self.nationName() != null && !self.nationName().isBlank()) {
                blocked = blocklist.nations().contains(self.nationName().toLowerCase(Locale.ROOT));
            }
        }
        accessBlocked = blocked;
        if (!accessLogged) {
            accessLogged = true;
            LOGGER.info("[TownyMap] Access check: uuid={} blocked={} (list has {} uuid(s), {} nation(s))",
                    uuid.isBlank() ? "<none>" : uuid, blocked, blocklist.uuids().size(),
                    blocklist.nations().size());
        }
        // Do NOT show it from here: this runs during a fetch callback and often before the player is in
        // a world, where setScreen is immediately replaced by whatever loads next -- which is why the
        // notice never appeared. tickAccessNotice() posts it once the client is idle on a real screen.
    }

    private static volatile boolean accessLogged = false;

    /**
     * Posts the blocked notice once per launch, from the client tick, as soon as there is somewhere for
     * it to go. Called every tick; cheap, and does nothing unless the player is actually blocked.
     */
    public static void tickAccessNotice() {
        if (blockedNoticeShown || !isAccessBlocked()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return;           // wait until a world is loaded
        if (mc.gui.screen() instanceof net.townymap.gui.BlockedScreen) return;
        blockedNoticeShown = true;
        mc.gui.setScreen(new net.townymap.gui.BlockedScreen(mc.gui.screen(), blocklist.message()));
    }

    public static boolean isActiveOnCurrentServer() {
        if (isAccessBlocked()) return false;
        if (config == null) return false;
        if (!config.earthmcOnly) return true;
        return isOnEarthMcServer();
    }

    /**
     * Whether the current server actually is EarthMC, independent of the "EarthMC Only" toggle.
     * Used for behaviour that only makes sense against EarthMC's own world (see
     * {@link #worldMapOverlayScale()}), as opposed to whether the mod is switched on at all.
     */
    public static boolean isOnEarthMcServer() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return false;
        ServerData server = client.getCurrentServer();
        if (server == null || server.ip == null) return false;
        // Cache the toLowerCase().contains() result per server address.  This is
        // called once per online player per frame (playerDotColor), so the string
        // allocation is hot; recompute only when the address actually changes.
        String address = server.ip;
        if (!address.equals(activeServerAddress)) {
            activeServerAddress = address;
            activeServerResult = address.toLowerCase(Locale.ROOT).contains("earthmc.net");
        }
        return activeServerResult;
    }

    private static void showLookupResult(TownPopupData data, int screenX, int screenY,
                                         double clickedWorldX, double clickedWorldZ,
                                         MapJumpTarget fallbackTarget) {
        if (data != null) {
            // Don't persist archived popups into the live (disk-backed) details cache — that would leak
            // historical data into the live map on the next session.
            if (data != TownPopupData.WILDERNESS && !isArchiveMode()) {
                cacheTownDetails(townKey(data.townName()), data);
                scheduleTownDetailsCacheSave();
            }
            townInfoRouteTarget = routeTarget(data, clickedWorldX, clickedWorldZ, fallbackTarget);
            TownInfoOverlay.show(data, screenX, screenY);
        } else {
            townInfoRouteTarget = null;
            TownInfoOverlay.dismiss();
        }
    }

    private static MapJumpTarget routeTarget(TownPopupData data, double clickedWorldX, double clickedWorldZ,
                                             MapJumpTarget fallbackTarget) {
        if (data == null || data == TownPopupData.WILDERNESS) return null;
        String townName = data.townName();
        if (apiClient != null) {
            for (TownData town : apiClient.getTowns()) {
                if (town.name().equalsIgnoreCase(townName)) {
                    return new MapJumpTarget(town.name(), town.centerX(), town.centerZ());
                }
            }
        }
        if (fallbackTarget != null) return fallbackTarget;
        return new MapJumpTarget(townName,
                (int) Math.round(clickedWorldX),
                (int) Math.round(clickedWorldZ));
    }

    public static boolean createXaeroRoute(MapJumpTarget target) {
        if (!isActiveOnCurrentServer() || target == null) return false;
        // Xaero files waypoints under the dimension the PLAYER is in, so a route to a Moon town created
        // from Earth would land in the Earth waypoint set at lunar coordinates -- an arrow pointing at
        // nothing. Refuse it rather than plant a waypoint that quietly lies.
        // One symmetric test covers both directions. The earlier pair only refused when the map was off
        // Earth, and otherwise leaned on Xaero's viewed dimension -- so standing on the Moon while
        // viewing Terra Nostra slipped through whenever that dimension had not been switched, and it
        // also went through the nullable playerWorldKey(), which needs squaremap's fetched world list.
        if (viewingOtherWorld()) {
            sendFeedback("Routes only work for the world you are standing in - the map is showing "
                    + activeWorldName() + ".", ChatFormatting.RED);
            return false;
        }
        try {
            boolean created = XaeroWaypointBridge.createRouteWaypoint(target);
            if (created) {
                sendFeedback("Xaero route set to " + target.label() + ".", ChatFormatting.GREEN);
            } else {
                sendFeedback("Could not create a Xaero route here.", ChatFormatting.RED);
            }
            return created;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("[TownyMap] Failed to create Xaero route waypoint", e);
            sendFeedback("Xaero route creation failed.", ChatFormatting.RED);
            return false;
        }
    }

    private static void sendFeedback(String message, ChatFormatting color) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return;
        client.player.sendSystemMessage(Component.literal("[TownyMap] ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(message).withStyle(color)));
    }

    // Single-town fetch for the hovered/clicked town. Visible-map and search details go through the bulk
    // path (requestTownDetailsBulk); this is only ever called for one town at a time, so it needs no
    // concurrency cap of its own — the loading set just dedupes an in-flight request.
    private static void requestTownDetails(String townName, String key) {
        if (isArchiveMode()) return;   // archive uses the snapshot's own popup data, never live
        long now = System.currentTimeMillis();
        if (earthMcApi == null || isTownDetailsFresh(key) || requestDeferred(townDetailsDeferredAt, key, now)) return;
        if (!townDetailsLoading.add(key)) return;
        earthMcApi.fetchTown(townName).whenComplete((data, error) -> {
            if (data != null && data != TownPopupData.WILDERNESS) {
                cacheTownDetails(key, data);
                scheduleTownDetailsCacheSave();
                townDetailsDeferredAt.remove(key);
            } else if (error != null) {
                deferRequest(townDetailsDeferredAt, key, System.currentTimeMillis());
            }
            townDetailsLoading.remove(key);
        });
    }

    private static boolean requestDeferred(Map<String, Long> deferredAt, String key, long now) {
        Long deferred = deferredAt.get(key);
        if (deferred == null) return false;
        if (now - deferred < DETAIL_REQUEST_DEFER_MS) return true;
        deferredAt.remove(key);
        return false;
    }

    private static void deferRequest(Map<String, Long> deferredAt, String key, long now) {
        if (key == null || key.isBlank()) {
            return;
        }
        deferredAt.put(key, now);
    }

    private static boolean isTownDetailsFresh(String key) {
        if (!townDetailsCache.containsKey(key)) return false;
        Long fetchedAt = townDetailsFetchedAt.get(key);
        return fetchedAt != null && System.currentTimeMillis() - fetchedAt < TOWN_DETAILS_MAX_AGE_MS;
    }

    private static void cacheTownDetails(String requestedKey, TownPopupData data) {
        if (data == null || data == TownPopupData.WILDERNESS) return;
        long now = System.currentTimeMillis();
        String canonicalKey = townKey(data.townName());
        if (requestedKey != null && !requestedKey.isBlank()) {
            townDetailsCache.put(requestedKey, withCachedActive(requestedKey, data));
            townDetailsFetchedAt.put(requestedKey, now);
            townDetailsDeferredAt.remove(requestedKey);
        }
        townDetailsCache.put(canonicalKey, withCachedActive(canonicalKey, data));
        townDetailsFetchedAt.put(canonicalKey, now);
        townDetailsDeferredAt.remove(canonicalKey);
    }

    /** Re-applies a previously looked-up active count when base town data is (re)cached, so the
     *  inactive suffix survives a base refresh and the active-before-base ordering both work. */
    private static TownPopupData withCachedActive(String key, TownPopupData data) {
        Integer a = townActiveCache.get(key);
        return (a != null && a >= 0) ? data.withActiveResidentCount(a) : data;
    }

    /** Looks up the active-resident count for ONE focused town (off the mass-fetch path) and folds
     *  it into the cached detail + the open info panel. Cheap: at most one town's residents. */
    /** Triggers the on-demand active-resident lookup for a focused town (search panel), so it's fetched
     *  only for the town actually opened rather than every visible search row. */
    public static void requestTownActive(String townName) {
        requestTownActiveResidents(townName, townKey(townName));
    }

    private static void requestTownActiveResidents(String townName, String key) {
        if (isArchiveMode()) return;
        if (earthMcApi == null || townName == null || townName.isBlank() || key == null || key.isBlank()) return;
        if (townActiveCache.containsKey(key) || !townActiveLoading.add(key)) return;
        earthMcApi.fetchTownActiveResidents(townName).whenComplete((count, error) -> {
            int c = count != null ? count : -1;
            townActiveCache.put(key, c);   // cache even -1 so this doesn't re-fire every frame
            if (c >= 0) {
                townDetailsCache.computeIfPresent(key, (k, old) -> old.withActiveResidentCount(c));
                TownInfoOverlay.setActiveResidentCount(townName, c);
            }
            townActiveLoading.remove(key);
        });
    }

    /** Looks up the active-resident count for ONE focused nation (off the mass-fetch path). */
    /** ONE fetch for the focused nation yields BOTH the active-resident count and the bonus-drop
     *  projection (was two separate /nations + /players passes — the cause of the 429 storm that hid the
     *  inactive/bonus rows). The projection cache doubles as the loaded-marker. */
    private static void requestNationResidentStats(String nationName) {
        if (earthMcApi == null || nationName == null || nationName.isBlank()) return;
        String key = townKey(nationName);
        if (nationBonusProjCache.containsKey(key) || !nationBonusProjLoading.add(key)) return;
        earthMcApi.fetchNationResidentStats(nationName).whenComplete((stats, error) -> {
            NationResidentStats s = stats != null ? stats : NationResidentStats.NONE;
            nationBonusProjCache.put(key, s.projection());
            if (s.activeCount() >= 0) {
                nationActiveCache.put(key, s.activeCount());
                nationDetailsCache.computeIfPresent(key, (k, old) -> old.withActiveResidentCount(s.activeCount()));
            }
            nationBonusProjLoading.remove(key);
        });
    }

    /** Triggers the combined active+projection lookup on first view (a per-resident timestamp lookup, so
     *  only nations actually opened are looked up) and returns the projection (null until loaded). The
     *  active count lands in the cached nation details, driving the inactive row. */
    /**
     * When the given town becomes overclaimable, or null until the lookup lands. Safe to call every frame:
     * the fetch is deduped by both the result cache and an in-flight set, exactly like the nation bonus
     * projection, so a panel polling this issues at most one request per town.
     */
    public static net.townymap.model.TownOverclaimProjection townOverclaimProjection(String townName) {
        if (townName == null || townName.isBlank() || earthMcApi == null) return null;
        String key = townKey(townName);
        net.townymap.model.TownOverclaimProjection cached = townOverclaimCache.get(key);
        if (cached != null) return cached;
        if (!townOverclaimLoading.add(key)) return null;
        earthMcApi.fetchTownOverclaim(townName).whenComplete((proj, error) -> {
            townOverclaimCache.put(key,
                    proj != null ? proj : net.townymap.model.TownOverclaimProjection.NONE);
            townOverclaimLoading.remove(key);
        });
        return null;
    }

    public static NationBonusProjection nationBonusProjection(String nationName) {
        if (nationName == null) return null;
        NationBonusProjection cached = nationBonusProjCache.get(townKey(nationName));
        if (cached == null) requestNationResidentStats(nationName);
        return cached;
    }

    /**
     * The cached EarthMC player index, kicking off a load if it is not there yet. One request returns
     * every player with balance, join date and friend count, so the leaderboards need no per-player
     * lookups at all -- the whole index is already in memory for the search bar.
     */
    public static List<net.townymap.model.EarthMcPlayerData> apiPlayerIndex() {
        refreshPlayerIndexIfNeeded();
        return apiPlayers;
    }

    private static volatile java.util.Map<String, int[]> outlawTrusted = java.util.Map.of();
    private static volatile boolean outlawTrustedLoading = false;
    private static volatile long outlawTrustedAtMs = 0;

    /**
     * Per-player "outlawed in N towns / trusted in N towns", swept from every town's roster.
     *
     * <p>Unlike the player and nation indexes this is NOT free: it walks all ~5,500 towns in batches of
     * 100. So it runs once on demand, is kept for 10 minutes, and returns whatever it has meanwhile —
     * the panel shows a loading row until the first sweep lands rather than blocking on it.
     */
    public static java.util.Map<String, int[]> outlawTrustedCounts() {
        long now = System.currentTimeMillis();
        if (outlawTrustedLoading || now - outlawTrustedAtMs < 600_000L) return outlawTrusted;
        if (apiClient == null || earthMcApi == null) return outlawTrusted;
        List<String> names = new ArrayList<>();
        for (net.townymap.model.TownData t : apiClient.getTowns()) names.add(t.name());
        if (names.isEmpty()) return outlawTrusted;
        outlawTrustedLoading = true;
        outlawTrustedAtMs = now;
        earthMcApi.fetchOutlawTrustedCounts(names).thenAccept(m -> {
            if (m != null && !m.isEmpty()) {
                outlawTrusted = m;
                LOGGER.info("[TownyMap] Swept {} towns for outlaw/trusted counts: {} players", names.size(), m.size());
            }
            outlawTrustedLoading = false;
        }).exceptionally(t -> { outlawTrustedLoading = false; return null; });
        return outlawTrusted;
    }

    private static volatile java.util.Map<String, net.townymap.model.EarthMcPlayerData> richPlayers = java.util.Map.of();
    private static volatile boolean richPlayersLoading = false;
    private static volatile long richPlayersAtMs = 0;

    /**
     * Online players with their real balance, join date and friend count.
     *
     * <p>GET /players is only a name+uuid index -- balance and the rest come back as zero from it, which
     * is why sorting the whole 60k index produced empty columns. The fields only exist on the queried
     * /players endpoint, and querying all 60k would be ~600 batched calls. So this enriches just the
     * players currently online (usually a few hundred, so one to four batches) and refreshes each minute.
     */
    public static java.util.Map<String, net.townymap.model.EarthMcPlayerData> onlinePlayerStats() {
        long now = System.currentTimeMillis();
        // 2 minutes rather than 1: the online set barely changes in that window, and this is fetched
        // per open, so halving the refresh rate halves the cost of flicking through the tabs.
        if (richPlayersLoading || now - richPlayersAtMs < 120_000L) return richPlayers;
        if (apiClient == null || earthMcApi == null) return richPlayers;
        // Everyone online, not just the world on screen: this is a server-wide leaderboard, and
        // pinning the map to the Moon would otherwise shrink it to whoever happens to be up there.
        List<String> names = new ArrayList<>();
        for (net.townymap.model.PlayerMarker m : apiClient.getAllPlayers()) {
            if (m.name() != null && !m.name().isBlank()) names.add(m.name());
        }
        if (names.isEmpty()) return richPlayers;
        richPlayersLoading = true;
        richPlayersAtMs = now;
        earthMcApi.fetchPlayers(names).thenAccept(m -> {
            if (m != null && !m.isEmpty()) {
                richPlayers = m;
                LOGGER.info("[TownyMap] Loaded full data for {} of {} online players", m.size(), names.size());
            }
            richPlayersLoading = false;
        }).exceptionally(t -> { richPlayersLoading = false; return null; });
        return richPlayers;
    }

    /** EarthMC's own activity window: 42 days without a login and a resident stops counting. */
    private static final long ACTIVE_WINDOW_MS = 42L * 24 * 60 * 60 * 1000;
    private static volatile java.util.Map<String, net.townymap.model.EarthMcPlayerData> allPlayers = java.util.Map.of();
    private static volatile boolean allPlayersLoading = false;
    private static volatile long allPlayersAtMs = 0;

    /**
     * Every player with their real balance, join date and friend count -- not just the ones online.
     *
     * <p>The name index carries none of those fields, so this queries the whole roster through the
     * batched /players endpoint: ~600 batches of 100, throttled by the same gate the town sweep uses.
     * It is the only way to rank the server rather than whoever happens to be logged in. Runs once,
     * kept for 30 minutes, and serves the online-only subset in the meantime so the tab is never blank.
     */
    public static java.util.Map<String, net.townymap.model.EarthMcPlayerData> allPlayerStats() {
        long now = System.currentTimeMillis();
        boolean fresh = now - allPlayersAtMs < 1_800_000L;
        if (!allPlayersLoading && !fresh && earthMcApi != null) {
            List<String> names = new ArrayList<>();
            if (!allPlayers.isEmpty()) {
                // Refresh: only re-query players seen in the last 42 days (EarthMC's own activity
                // window). Someone who has not logged in since is not going to climb a leaderboard, and
                // their balance cannot change while they are gone -- so the repeat sweep is a fraction
                // of the first one. Everyone else keeps the values from the initial pass.
                for (net.townymap.model.EarthMcPlayerData pd : allPlayers.values()) {
                    if (pd.name() == null || pd.name().isBlank()) continue;
                    if (now - pd.lastOnlineMs() <= ACTIVE_WINDOW_MS) names.add(pd.name());
                }
            } else {
                for (net.townymap.model.EarthMcPlayerData pd : apiPlayerIndex()) {
                    if (pd.name() != null && !pd.name().isBlank()) names.add(pd.name());
                }
            }
            if (!names.isEmpty()) {
                allPlayersLoading = true;
                allPlayersAtMs = now;
                LOGGER.info("[TownyMap] Sweeping {} players for balance/join date...", names.size());
                sweepPlayersProgressively(names, 0);
            }
        }
        return allPlayers.isEmpty() ? onlinePlayerStats() : allPlayers;
    }

    /** How many names each slice of the sweep resolves before publishing what it found. */
    private static final int SWEEP_SLICE = 2000;

    /**
     * Sweeps the roster a slice at a time, publishing after each one.
     *
     * <p>fetchPlayers only returns once every batch it was given has finished, so handing it all 60k
     * names meant nothing appeared on screen until the entire sweep completed -- which is what made this
     * feel slow. Slicing it means the first few hundred ranked players show up within a second or two
     * and the list fills in behind them, and a stalled slice no longer holds the whole result hostage.
     */
    private static void sweepPlayersProgressively(List<String> names, int from) {
        if (earthMcApi == null || from >= names.size()) {
            allPlayersLoading = false;
            LOGGER.info("[TownyMap] Player sweep complete: {} records", allPlayers.size());
            return;
        }
        int to = Math.min(names.size(), from + SWEEP_SLICE);
        List<String> slice = new ArrayList<>(names.subList(from, to));
        earthMcApi.fetchPlayers(slice).thenAccept(m -> {
            if (m != null && !m.isEmpty()) {
                java.util.Map<String, net.townymap.model.EarthMcPlayerData> merged =
                        new java.util.HashMap<>(allPlayers);
                merged.putAll(m);
                allPlayers = java.util.Map.copyOf(merged);   // publish what we have so far
            }
            sweepPlayersProgressively(names, to);
        }).exceptionally(t -> {
            sweepPlayersProgressively(names, to);            // a bad slice must not end the sweep
            return null;
        });
    }

    /** Whether a player logged in inside EarthMC's 42-day activity window. */
    public static boolean isRecentlyActive(net.townymap.model.EarthMcPlayerData pd) {
        return pd.lastOnlineMs() > 0 && System.currentTimeMillis() - pd.lastOnlineMs() <= ACTIVE_WINDOW_MS;
    }

    /**
     * A cheap fingerprint of everything the Statistics tab draws from, WITHOUT starting any fetch.
     *
     * <p>The panel polls this and rebuilds when it changes, so a leaderboard that opened on "sweeping
     * towns..." fills itself in the moment the data lands instead of waiting for you to switch filters
     * and back. Deliberately reads the cached fields directly: calling the normal accessors would kick
     * off the town sweep from whichever tab happened to be open.
     */
    public static int statsCacheSignature() {
        return townRanks.size() * 31 + richNations.size() * 7 + allPlayers.size();
    }

    /** True once the full roster sweep has landed, so the panel can say which set it is showing. */
    public static boolean playerSweepComplete() { return !allPlayers.isEmpty(); }

    private static volatile java.util.Map<String, EarthMcNationData> richNations = java.util.Map.of();
    private static volatile boolean richNationsLoading = false;
    private static volatile long richNationsAtMs = 0;

    /**
     * Nations with their real balance, outlaw count and founding date.
     *
     * <p>Like /players, GET /nations is a name+uuid index only -- every numeric field comes back zero,
     * which is why ranking by gold, outlaws or age produced nothing. The queried endpoint has the real
     * values, and with under 400 nations that is only four batches, so unlike the player roster this can
     * simply be fetched whole. Cached for 10 minutes.
     */
    public static java.util.Map<String, EarthMcNationData> nationStats() {
        long now = System.currentTimeMillis();
        if (!richNationsLoading && now - richNationsAtMs >= 600_000L && earthMcApi != null) {
            List<String> names = new ArrayList<>();
            for (EarthMcNationData nd : apiNationIndex()) {
                if (nd.name() != null && !nd.name().isBlank()) names.add(nd.name());
            }
            if (!names.isEmpty()) {
                richNationsLoading = true;
                richNationsAtMs = now;
                earthMcApi.fetchNations(names).thenAccept(m -> {
                    if (m != null && !m.isEmpty()) {
                        richNations = m;
                        LOGGER.info("[TownyMap] Loaded full data for {} nations", m.size());
                    }
                    richNationsLoading = false;
                }).exceptionally(t -> { richNationsLoading = false; return null; });
            }
        }
        return richNations;
    }

    private static volatile java.util.Map<String, net.townymap.api.EarthMcApiClient.TownRank> townRanks =
            java.util.Map.of();
    private static volatile boolean townRanksLoading = false;
    private static volatile long townRanksAtMs = 0;

    /**
     * Balance, outlaw count and founding date for every town.
     *
     * <p>The expensive one: ~56 batches over all 5,500 towns, because none of these three fields is in
     * markers.json. Deliberately NOT warmed -- it runs only when one of the leaderboards that needs it is
     * opened, and is then kept for 30 minutes.
     */
    public static java.util.Map<String, net.townymap.api.EarthMcApiClient.TownRank> townRanks() {
        long now = System.currentTimeMillis();
        if (!townRanksLoading && now - townRanksAtMs >= 1_800_000L
                && apiClient != null && earthMcApi != null) {
            List<String> names = new ArrayList<>();
            for (net.townymap.model.TownData t : apiClient.getTowns()) names.add(t.name());
            if (!names.isEmpty()) {
                townRanksLoading = true;
                townRanksAtMs = now;
                LOGGER.info("[TownyMap] Sweeping {} towns for balance/outlaws/founded...", names.size());
                earthMcApi.fetchTownRanks(names).thenAccept(m -> {
                    if (m != null && !m.isEmpty()) {
                        townRanks = m;
                        LOGGER.info("[TownyMap] Town sweep complete: {} towns", m.size());
                    }
                    townRanksLoading = false;
                }).exceptionally(t -> { townRanksLoading = false; return null; });
            }
        }
        return townRanks;
    }

    private static void refreshPlayerIndex() {
        if (earthMcApi == null) return;
        lastPlayerIndexAttemptMs = System.currentTimeMillis();
        earthMcApi.fetchPlayerIndex().thenAccept(players -> {
            if (players != null && !players.isEmpty()) {
                apiPlayers = players;
                LOGGER.info("[TownyMap] Loaded {} EarthMC player names", players.size());
            }
        });
    }

    private static void refreshPlayerIndexIfNeeded() {
        if (!apiPlayers.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastPlayerIndexAttemptMs < 30_000) return;
        refreshPlayerIndex();
    }

    private static void refreshNationIndex() {
        if (earthMcApi == null) return;
        lastNationIndexAttemptMs = System.currentTimeMillis();
        earthMcApi.fetchNationIndex().thenAccept(nations -> {
            if (nations != null && !nations.isEmpty()) {
                apiNations = nations;
                LOGGER.info("[TownyMap] Loaded {} EarthMC nation names", nations.size());
            }
        });
    }

    /**
     * Retries the nation index fetch if it never populated (e.g. API was down at startup).
     * Limits retries to once every 30 seconds so we don't spam EarthMC.
     */
    private static void refreshNationIndexIfNeeded() {
        if (!apiNations.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastNationIndexAttemptMs < 30_000) return;
        refreshNationIndex();
    }

    private static void requestVisibleTownDetails(double cameraX, double cameraZ, double scale,
                                                  int screenW, int screenH) {
        if (earthMcApi == null || apiClient == null || renderer == null || config == null) return;
        // Only the highlight modes (Public/Overclaim/Open) read per-town details; the alliance layers get
        // everything they need from the markers' nation, so skip the bulk /towns prefetch for them.
        if (config.townStatusOverlayMode < 1 || config.townStatusOverlayMode > 3) return;
        if (scale <= 0) return;
        long now = System.currentTimeMillis();
        if (now - lastVisibleTownDetailsRequestMs < 500L) return;
        lastVisibleTownDetailsRequestMs = now;

        double worldLeft = cameraX - screenW / 2.0 / scale;
        double worldRight = cameraX + screenW / 2.0 / scale;
        double worldTop = cameraZ - screenH / 2.0 / scale;
        double worldBottom = cameraZ + screenH / 2.0 / scale;

        // Collect every visible town still missing details (using the renderer's spatial index and
        // precomputed keys), then fetch them in ONE bulk /towns request instead of dribbling out a POST
        // per town. Bounded so a continental zoom-out can't queue thousands of names in a cycle.
        List<String> names = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        renderer.forEachVisibleTownDetail(worldLeft, worldRight, worldTop, worldBottom, MAX_TOWN_DETAIL_BATCH,
                (name, key) -> {
                    if (townDetailsCache.containsKey(key) || townDetailsLoading.contains(key)
                            || requestDeferred(townDetailsDeferredAt, key, now)) return false;
                    names.add(name);
                    keys.add(key);
                    return true;
                });
        requestTownDetailsBulk(names, keys);
    }

    /**
     * Fetches details for many towns in a single bulk /towns request (≤100 names per query) instead of
     * one POST per town. {@code names} and {@code keys} are parallel lists (keys = townKey(name)). Caches
     * each town the API returns; towns it didn't return are deferred so they don't re-fire every cycle.
     */
    private static void requestTownDetailsBulk(List<String> names, List<String> keys) {
        if (isArchiveMode()) return;   // archive uses the snapshot's own popup data, never live
        if (earthMcApi == null || names.isEmpty()) return;
        // Mark all in-flight up front so the next render cycle skips them.
        townDetailsLoading.addAll(keys);
        earthMcApi.fetchTowns(names).whenComplete((result, error) -> {
            try {
                long doneAt = System.currentTimeMillis();
                boolean cachedAny = false;
                for (String key : keys) {
                    // fetchTowns keys results by lowercase town name, which is exactly townKey(name).
                    TownPopupData data = result == null ? null : result.get(key);
                    if (data != null) {
                        cacheTownDetails(key, data);   // caches under the requested key AND the canonical name
                        cachedAny = true;
                    } else {
                        // Not returned (deleted/renamed, or a failed batch) → defer so it doesn't re-fire every cycle.
                        deferRequest(townDetailsDeferredAt, key, doneAt);
                    }
                }
                if (cachedAny) scheduleTownDetailsCacheSave();
            } finally {
                townDetailsLoading.removeAll(keys);
            }
        });
    }

    private static void requestVisiblePlayerDetails(double cameraX, double cameraZ, double scale,
                                                    int screenW, int screenH) {
        if (earthMcApi == null || apiClient == null || config == null || !config.showPlayerNames) return;
        if (scale < config.playerNameMinScale) return;
        long now = System.currentTimeMillis();
        if (now - lastVisiblePlayerDetailsRequestMs < 500L) return;
        lastVisiblePlayerDetailsRequestMs = now;

        double worldLeft = cameraX - screenW / 2.0 / scale;
        double worldRight = cameraX + screenW / 2.0 / scale;
        double worldTop = cameraZ - screenH / 2.0 / scale;
        double worldBottom = cameraZ + screenH / 2.0 / scale;

        List<String> names = new ArrayList<>();
        for (var marker : apiClient.getPlayers()) {
            if (marker.x() < worldLeft || marker.x() > worldRight
                    || marker.z() < worldTop || marker.z() > worldBottom) continue;
            if (!playerDetailNeeded(townKey(marker.name()), now)) continue;
            names.add(marker.name());
            if (names.size() >= PLAYER_BULK_PER_CYCLE) break;
        }
        requestPlayerDetailsBulk(names);
    }

    private static void requestPlayerDetailsForSearch() {
        if (earthMcApi == null) return;
        long now = System.currentTimeMillis();
        List<String> names = new ArrayList<>();
        for (String name : TownSearchOverlay.visibleApiPlayerMatches(apiPlayers)) {
            if (playerDetailNeeded(townKey(name), now)) names.add(name);
        }
        String exact = TownSearchOverlay.exactPlayerQuery();
        if (!exact.isBlank() && playerDetailNeeded(townKey(exact), now)) names.add(exact);
        requestPlayerDetailsBulk(names);
    }

    private static void requestSearchDetailsIfNeeded() {
        String currentQuery = TownSearchOverlay.query().trim().toLowerCase(Locale.ROOT);
        if (currentQuery.isEmpty()) {
            lastSearchDetailsQuery = "";
            return;
        }
        long now = System.currentTimeMillis();
        if (currentQuery.equals(lastSearchDetailsQuery) && now - lastSearchDetailsRequestMs < 500L) {
            return;
        }
        lastSearchDetailsQuery = currentQuery;
        lastSearchDetailsRequestMs = now;
        requestTownDetailsForSearch();
        requestPlayerDetailsForSearch();
        requestNationDetailsForSearch();
    }

    /** True if this player key still needs a detail fetch (not cached, in-flight, recently-failed, or deferred). */
    private static boolean playerDetailNeeded(String key, long now) {
        if (playerDetailsCache.containsKey(key) || playerDetailsLoading.contains(key)) return false;
        Long failedAt = playerDetailsFailedAt.get(key);
        if (failedAt != null) {
            // A player the API has nothing for is almost always an opt-out, which never resolves. Retrying
            // every 30s meant each one was re-queried twice a minute forever; with several on screen at up
            // to 4 requests a second that was enough to earn a 429, and once rate-limited EVERY lookup
            // fails - which is how opening any town or nation panel started returning "not found".
            //
            // Their town roster already gives us town and nation, so there is nothing left to fetch:
            // back those off hard. Anything else is likely transient, so it keeps a short retry.
            boolean haveRoster = apiClient != null && apiClient.townOfResident(key) != null;
            long backoff = haveRoster ? 1_800_000L : 300_000L;
            if (now - failedAt < backoff) return false;
        }
        return !requestDeferred(playerDetailsDeferredAt, key, now);
    }

    /** Fetches details for many players in one bulk /players request (≤100 names/query) instead of a POST
     *  per player — used by the search rows and visible-player loops. Players the API didn't return
     *  (opted out) are backed off like the single path. */
    private static void requestPlayerDetailsBulk(List<String> names) {
        if (earthMcApi == null || names.isEmpty()) return;
        List<String> keys = new ArrayList<>(names.size());
        for (String name : names) {
            String key = townKey(name);
            keys.add(key);
            playerDetailsLoading.add(key);
        }
        earthMcApi.fetchPlayers(names).whenComplete((result, error) -> {
            try {
                long doneAt = System.currentTimeMillis();
                for (int i = 0; i < keys.size(); i++) {
                    String key = keys.get(i);
                    EarthMcPlayerData data = result == null ? null : result.get(key);
                    if (data != null) {
                        playerDetailsCache.put(key, data);
                        playerDetailsCache.put(townKey(data.name()), data);
                        playerDetailsFailedAt.remove(key);
                        playerDetailsDeferredAt.remove(key);
                    } else {
                        playerDetailsFailedAt.put(key, doneAt);
                    }
                }
            } finally {
                for (String key : keys) playerDetailsLoading.remove(key);
            }
        });
    }

    // Single-player fetch for the minimap trickle (rate-limited to 4/sec) and self lookups. The search and
    // visible-player paths use requestPlayerDetailsBulk, so this needs no concurrency cap of its own.
    private static boolean requestPlayerDetails(String name) {
        if (earthMcApi == null || name == null || name.isBlank()) return false;
        String key = townKey(name);
        long now = System.currentTimeMillis();
        if (!playerDetailNeeded(key, now)) return false;
        if (!playerDetailsLoading.add(key)) return false;
        earthMcApi.fetchPlayer(name).whenComplete((data, error) -> {
            if (data != null) {
                playerDetailsCache.put(key, data);
                playerDetailsCache.put(townKey(data.name()), data);
                playerDetailsFailedAt.remove(key);
                playerDetailsDeferredAt.remove(key);
            } else {
                playerDetailsFailedAt.put(key, System.currentTimeMillis());
            }
            playerDetailsLoading.remove(key);
        });
        return true;
    }

    private static void requestTownDetailsForSearch() {
        if (earthMcApi == null || apiClient == null) return;
        // Town details for the visible search rows go out as one bulk /towns request. The active-resident
        // count is NOT fetched per row — it's on-demand for the selected town only (requestTownActive),
        // matching nations, so a search with many rows doesn't fire a /players lookup for each.
        long now = System.currentTimeMillis();
        List<String> names = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (String name : TownSearchOverlay.visibleTownMatches(apiClient.getTowns())) {
            String key = townKey(name);
            if (townDetailsCache.containsKey(key) || townDetailsLoading.contains(key)
                    || requestDeferred(townDetailsDeferredAt, key, now)) continue;
            names.add(name);
            keys.add(key);
        }
        requestTownDetailsBulk(names, keys);
    }

    private static void requestNationDetailsForSearch() {
        if (isArchiveMode()) return;   // archive nation data comes from the snapshot, not the live API
        if (earthMcApi == null) return;
        // Bulk-fetch details for the visible search nations in one /nations request. The active/projection
        // lookup is NOT fired here — it's on-demand for the selected nation only (nationBonusProjection).
        long now = System.currentTimeMillis();
        List<String> names = new ArrayList<>();
        for (String name : TownSearchOverlay.visibleNationMatches(apiNations)) {
            String key = townKey(name);
            if (nationDetailsCache.containsKey(key) || nationDetailsLoading.contains(key)
                    || requestDeferred(nationDetailsDeferredAt, key, now)) continue;
            names.add(name);
        }
        requestNationDetailsBulk(names);
    }

    private static void requestNationCapitalDetails() {
        if (isArchiveMode()) return;   // archive capital stars come from the snapshot, not the live API
        if (earthMcApi == null || apiNations.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastNationCapitalDetailsRequestMs < 1_000L) return;
        lastNationCapitalDetailsRequestMs = now;
        // Warm capital-star details a whole batch per cycle (one bulk /nations) instead of two singles.
        List<String> names = new ArrayList<>();
        for (EarthMcNationData nation : apiNations) {
            String key = townKey(nation.name());
            // Honour the defer window like every other warm path: without it, nations the API never returns
            // (deleted between index and query, or unparseable) were re-requested every single second.
            if (nationDetailsCache.containsKey(key) || nationDetailsLoading.contains(key)
                    || requestDeferred(nationDetailsDeferredAt, key, now)) continue;
            names.add(nation.name());
            if (names.size() >= NATION_BULK_PER_CYCLE) break;
        }
        requestNationDetailsBulk(names);
    }

    /** Fetches details for many nations in one bulk /nations request (≤100 names/query) instead of one
     *  POST per nation. Applies any cached active count; nations the API didn't return are deferred. */
    private static void requestNationDetailsBulk(List<String> names) {
        if (earthMcApi == null || names.isEmpty()) return;
        List<String> keys = new ArrayList<>(names.size());
        for (String name : names) {
            String key = townKey(name);
            keys.add(key);
            nationDetailsLoading.add(key);
        }
        earthMcApi.fetchNations(names).whenComplete((result, error) -> {
            try {
                long doneAt = System.currentTimeMillis();
                for (int i = 0; i < keys.size(); i++) {
                    String key = keys.get(i);
                    EarthMcNationData data = result == null ? null : result.get(key);
                    if (data != null) {
                        Integer a = nationActiveCache.get(key);
                        EarthMcNationData stored = (a != null && a >= 0) ? data.withActiveResidentCount(a) : data;
                        nationDetailsCache.put(key, stored);
                        nationDetailsCache.put(townKey(data.name()), stored);
                        nationDetailsDeferredAt.remove(key);
                    } else {
                        deferRequest(nationDetailsDeferredAt, key, doneAt);
                    }
                }
            } finally {
                for (String key : keys) nationDetailsLoading.remove(key);
            }
        });
    }

    // Single-nation fetch for one town's nation (hover/selected town). The bulk path handles the stars and
    // search rows, so this only ever runs for one nation at a time and needs no concurrency cap of its own.
    private static boolean requestNationDetails(String name) {
        if (isArchiveMode()) return false;   // don't overwrite archived nation info with today's
        if (earthMcApi == null || name == null || name.isBlank()) return false;
        String key = townKey(name);
        long now = System.currentTimeMillis();
        if (requestDeferred(nationDetailsDeferredAt, key, now)) return false;
        if (nationDetailsCache.containsKey(key) || !nationDetailsLoading.add(key)) return false;
        earthMcApi.fetchNation(name).whenComplete((data, error) -> {
            if (data != null) {
                Integer a = nationActiveCache.get(key);
                EarthMcNationData stored = (a != null && a >= 0) ? data.withActiveResidentCount(a) : data;
                nationDetailsCache.put(key, stored);
                nationDetailsCache.put(townKey(data.name()), stored);
                nationDetailsDeferredAt.remove(key);
            } else if (error != null) {
                deferRequest(nationDetailsDeferredAt, key, System.currentTimeMillis());
            }
            nationDetailsLoading.remove(key);
        });
        return true;
    }

    private static String townKey(String townName) {
        return townName == null ? "" : townName.toLowerCase(Locale.ROOT);
    }

    public static boolean isFavorite(String townName) {
        if (config == null || townName == null) return false;
        return favoriteTownKeys().contains(townKey(townName));
    }

    /** Starred nations/players, kept alongside favourite towns and toggled the same way. */
    public static boolean isFavoriteEntity(String type, String name) {
        if (config == null || name == null || name.isBlank()) return false;
        List<String> list = favoriteListFor(type);
        if (list == null) return false;
        for (String s : list) if (s.equalsIgnoreCase(name)) return true;
        return false;
    }

    public static void toggleFavoriteEntity(String type, String name) {
        if (config == null || name == null || name.isBlank()) return;
        if ("town".equals(type)) { toggleFavorite(name); return; }
        List<String> list = favoriteListFor(type);
        if (list == null) return;
        boolean removed = list.removeIf(s -> s.equalsIgnoreCase(name));
        if (!removed) list.add(name);
        config.save();
    }

    private static List<String> favoriteListFor(String type) {
        if (config == null) return null;
        return switch (type == null ? "" : type) {
            case "town" -> config.favoriteTowns;
            case "nation" -> config.favoriteNations;
            case "player" -> config.favoritePlayers;
            default -> null;
        };
    }

    /** Starred nations, for the favourites dropdown. */
    public static List<String> favoriteNations() {
        return config == null ? List.of() : List.copyOf(config.favoriteNations);
    }

    /** Starred players, for the favourites dropdown. */
    public static List<String> favoritePlayers() {
        return config == null ? List.of() : List.copyOf(config.favoritePlayers);
    }

    private static void toggleFavorite(String townName) {
        if (config == null || townName == null || townName.isBlank()) return;
        String key = townKey(townName);
        boolean removed = config.favoriteTowns.removeIf(name -> townKey(name).equals(key));
        if (!removed) {
            config.favoriteTowns.add(townName);
        }
        cachedFavoriteTownCount = -1;
        config.save();
    }

    public static Set<String> favoriteTownKeys() {
        if (config == null) return Set.of();
        if (cachedFavoriteTownCount == config.favoriteTowns.size()) return cachedFavoriteTownKeys;
        Set<String> keys = ConcurrentHashMap.newKeySet();
        for (String favorite : config.favoriteTowns) {
            keys.add(townKey(favorite));
        }
        cachedFavoriteTownKeys = Set.copyOf(keys);
        cachedFavoriteTownCount = config.favoriteTowns.size();
        return cachedFavoriteTownKeys;
    }

    private static void loadTownDetailsCache() {
        Path path = townDetailsCachePath();
        if (!Files.isRegularFile(path)) return;
        try {
            String json = Files.readString(path);
            if (!json.contains("canOutsidersSpawn")
                    || !json.contains("isOverClaimed")
                    || !json.contains("isOpen")
                    || !json.contains("isForSale")
                    || !json.contains("hasNation")
                    || !json.contains("maxChunks")) {
                LOGGER.info("[TownyMap] Ignoring old town detail cache without status overlay fields");
                return;
            }
            Map<String, TownPopupData> loaded = GSON.fromJson(json, TOWN_CACHE_TYPE);
            if (loaded != null) {
                townDetailsCache.putAll(loaded);
                for (String key : loaded.keySet()) {
                    townDetailsFetchedAt.put(key, 0L);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[TownyMap] Failed to load town detail cache: {}", e.getMessage());
        }
    }

    private static void scheduleTownDetailsCacheSave() {
        if (!townDetailsSaveScheduled.compareAndSet(false, true)) return;
        CACHE_SAVE_EXECUTOR.schedule(() -> {
            try {
                saveTownDetailsCacheNow();
            } finally {
                townDetailsSaveScheduled.set(false);
            }
        }, TOWN_DETAILS_SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static void saveTownDetailsCacheNow() {
        Path path = townDetailsCachePath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(townDetailsCache));
        } catch (Exception e) {
            // Warn, not debug: this one loses data rather than just a frame of rendering, so a
            // silent failure means the cache quietly stops persisting. One-shot to avoid spam.
            if (townDetailCacheSaveErrorLogged.compareAndSet(false, true)) {
                LOGGER.warn("[TownyMap] Failed to save town detail cache", e);
            }
        }
    }

    private static Path townDetailsCachePath() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("townymapaddon")
                .resolve("town-details-cache.json");
    }

    public static void requestMinimapTownHighlightRefresh() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        client.execute(() -> {
            try {
                Object session = Class.forName("xaero.common.XaeroMinimapSession")
                        .getMethod("getCurrentSession")
                        .invoke(null);
                if (session == null) return;
                Object processor = session.getClass()
                        .getMethod("getMinimapProcessor")
                        .invoke(session);
                if (processor == null) return;
                Object writer = processor.getClass()
                        .getMethod("getMinimapWriter")
                        .invoke(processor);
                if (writer == null) return;
                Object handler = writer.getClass()
                        .getMethod("getDimensionHighlightHandler")
                        .invoke(writer);
                if (handler == null) return;
                handler.getClass().getMethod("requestRefresh").invoke(handler);
                LOGGER.debug("[TownyMap] Requested Xaero minimap town highlight refresh");
            } catch (ReflectiveOperationException | LinkageError e) {
                LOGGER.debug("[TownyMap] Xaero minimap highlight refresh unavailable: {}", e.getMessage());
            }
        });
    }

    public static RecruitmentPlayerProfile recruitmentPlayerProfile(String name) {
        if (name == null || name.isBlank()) return null;
        EarthMcPlayerData data = playerDetailsCache.get(townKey(name));
        if (data == null || data.registeredMs() <= 0L) return null;
        return new RecruitmentPlayerProfile(data.name(), data.townName(), data.nationName(), data.registeredMs());
    }

    public static boolean requestRecruitmentPlayerProfile(String name) {
        if (name == null || name.isBlank()) return false;
        String key = townKey(name);
        return playerDetailsCache.containsKey(key) || playerDetailsLoading.contains(key);
    }

    public static TownyMapConfig     getConfig()    { return config;    }
    public static SquaremapApiClient getApiClient() { return apiClient; }

    public record RecruitmentPlayerProfile(String name, String town, String nation, long registeredMs) {}
}
