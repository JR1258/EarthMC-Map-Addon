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
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;
import net.townymap.api.EarthMcApiClient;
import net.townymap.api.SquaremapApiClient;
import net.townymap.command.TownyMapCommand;
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
import net.townymap.model.OptimisticClaimChunk;
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
    private static final int MAX_TOWN_DETAIL_LOADS = 8;
    private static final int MAX_PLAYER_DETAIL_LOADS = 8;
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
    private static final int MAX_NATION_DETAIL_LOADS = 4;
    private static volatile long lastPlayerIndexAttemptMs = 0;
    private static volatile Set<String> cachedFavoriteTownKeys = Set.of();
    private static volatile int cachedFavoriteTownCount = -1;
    private static volatile long minimapNationAlertFlashUntilMs = 0;
    private static volatile long minimapFrameColorReadAtMs = 0;
    private static volatile int minimapFrameColor = 0xFFFFFFFF;
    private static final ThreadLocal<Boolean> suppressNativeMinimapCompass =
            ThreadLocal.withInitial(() -> false);
    private static final AtomicBoolean nativeCompassSuppressionLogged = new AtomicBoolean(false);
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

        apiClient.start();
        TownyMapCommand.register();
        TownyMapKeybinds.register();
        ClientSendMessageEvents.COMMAND.register(TownyMapMod::onCommandSent);
        ClientReceiveMessageEvents.GAME.register(TownyMapMod::onGameMessage);

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
        }
    }

    private static void onGameMessage(Text message, boolean overlay) {
        if (apiClient == null || !isActiveOnCurrentServer() || message == null) return;
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

    private static void rememberPendingClaim() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.getSession() == null) return;
        String selfName = client.getSession().getUsername();
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
            MinecraftClient mc = MinecraftClient.getInstance();
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
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getSession() == null || earthMcApi == null) return;
        String selfName = client.getSession().getUsername();
        EarthMcPlayerData cached = playerDetailsCache.get(townKey(selfName));
        if (cached != null && !cached.townName().isBlank()) {
            addOptimisticClaimChunk(chunkX, chunkZ, cached.townName());
            return;
        }
        earthMcApi.fetchPlayer(selfName).thenAccept(data -> {
            if (data == null || data.townName().isBlank()) return;
            playerDetailsCache.put(townKey(selfName), data);
            playerDetailsCache.put(townKey(data.name()), data);
            MinecraftClient mc = MinecraftClient.getInstance();
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
            fillColor = town.argbColor(Math.max(config.fillAlpha, 90));
            outlineColor = town.argbColor(config.borderAlpha);
        } else {
            int rgb = config.statusHighlightColor & 0x00FFFFFF;
            fillColor = (Math.max(config.fillAlpha, 90) << 24) | rgb;
            outlineColor = 0xFF000000 | rgb;
        }

        long now = System.currentTimeMillis();
        optimisticClaimChunks.removeIf(chunk -> chunk.chunkX() == chunkX && chunk.chunkZ() == chunkZ);
        optimisticClaimChunks.add(new OptimisticClaimChunk(chunkX, chunkZ, townName, fillColor, outlineColor,
                now + OPTIMISTIC_CLAIM_TTL_MS));
    }

    private static TownData townByName(String townName) {
        if (apiClient == null || townName == null) return null;
        for (TownData town : apiClient.getTowns()) {
            if (town.name().equalsIgnoreCase(townName)) return town;
        }
        return null;
    }

    private static int floorToChunk(double blockCoord) {
        return Math.floorDiv((int) Math.floor(blockCoord), 16);
    }

    public static void forceRefreshTownClaims() {
        if (apiClient == null) return;
        invalidateTownRenderCaches();
        apiClient.forceTownMarkerRefresh();
    }

    public static void toggleSquaremapBackground() {
        if (!canUseKeybindAction()) return;
        config.squaremapBackgroundEnabled = !config.squaremapBackgroundEnabled;
        config.save();
        sendFeedback("Squaremap overlay: " + onOff(config.squaremapBackgroundEnabled),
                config.squaremapBackgroundEnabled ? Formatting.GREEN : Formatting.RED);
    }

    public static void cycleBorderOverlayMode() {
        if (!canUseKeybindAction()) return;
        config.borderOverlayMode = (config.borderOverlayMode + 1) % 3;
        config.save();
        sendFeedback("Borders: " + borderModeLabel(config.borderOverlayMode),
                config.borderOverlayMode == 0 ? Formatting.RED : Formatting.GREEN);
    }

    public static void cycleTownStatusOverlayMode() {
        if (!canUseKeybindAction()) return;
        config.townStatusOverlayMode = (config.townStatusOverlayMode + 1) % 6;
        config.save();
        sendFeedback("Map mode: " + townStatusModeLabel(config.townStatusOverlayMode),
                config.townStatusOverlayMode == 0 ? Formatting.RED : Formatting.GREEN);
    }

    public static void toggleChunkCounter() {
        if (!canUseKeybindAction()) return;
        if (config.chunkCounterEnabled) ChunkCounterOverlay.flushSelection();
        if (!config.chunkCounterEnabled) {
            config.chunkCounterEnabled = true;
            config.chunkCounterMode = 2;
            ChunkCounterOverlay.prepareMultiMode(config);
        } else {
            config.chunkCounterEnabled = false;
            config.chunkCounterMode = 2;
        }
        config.save();
        sendFeedback("Chunk counter: " + ChunkCounterOverlay.toolbarLabel(config),
                config.chunkCounterEnabled ? Formatting.GREEN : Formatting.RED);
    }

    public static void refreshTownClaimsFromKeybind() {
        if (!canUseKeybindAction()) return;
        forceRefreshTownClaims();
        sendFeedback("Refreshing towns and claims from squaremap...", Formatting.WHITE);
    }

    private static boolean canUseKeybindAction() {
        return config != null && isActiveOnCurrentServer();
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
            case 4 -> "For Sale";
            case 5 -> "No Nation";
            default -> "None";
        };
    }

    public static void onTownMarkersUpdated() {
        MinecraftClient client = MinecraftClient.getInstance();
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

    public static List<OptimisticClaimChunk> optimisticClaimChunks() {
        pruneOptimisticClaimChunks(false);
        return List.copyOf(optimisticClaimChunks);
    }

    private static void pruneOptimisticClaimChunks(boolean clearAll) {
        long now = System.currentTimeMillis();
        if (clearAll || apiClient == null) {
            optimisticClaimChunks.clear();
            return;
        }
        List<TownData> towns = apiClient.getTowns();
        optimisticClaimChunks.removeIf(chunk -> chunk.expired(now) || confirmedClaimChunk(chunk, towns));
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
    public static void renderSquaremapBackground(DrawContext ctx,
                                                 double cameraX, double cameraZ,
                                                 double scale, int screenW, int screenH) {
        if (!isActiveOnCurrentServer()) return;
        if (renderer != null) {
            boolean moving = updateWorldMapMovement(cameraX, cameraZ, scale);
            renderer.renderSquaremapBackground(ctx, cameraX, cameraZ, scale, screenW, screenH, moving);
        }
    }

    public static void renderOnWorldMap(DrawContext ctx,
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
            renderer.render(ctx, cameraX, cameraZ, scale, screenW, screenH,
                    townDetailsCache, playerDetailsCache, nationDetailsCache);
        }
    }

    public static boolean shouldRenderWorldMapIndicatorOverlay() {
        return isActiveOnCurrentServer() && config != null && config.squaremapBackgroundEnabled;
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

    public static void renderHoveredWorldMapChunk(DrawContext ctx,
                                                  double cameraX, double cameraZ,
                                                  double scale, int screenW, int screenH,
                                                  double worldX, double worldZ) {
        if (!isActiveOnCurrentServer()) return;
        if (renderer != null) {
            renderer.renderHoveredChunk(ctx, cameraX, cameraZ, scale, screenW, screenH, worldX, worldZ);
        }
    }

    public static void renderSquaremapMinimapViewport(DrawContext ctx,
                                                      double cameraX, double cameraZ,
                                                      double scale, int width, int height) {
        if (!isActiveOnCurrentServer()) return;
        if (renderer != null) {
            renderer.renderSquaremapMinimapViewport(ctx, cameraX, cameraZ, scale, width, height, true);
        }
    }

    public static void renderChunkCounter(DrawContext ctx,
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

    public static void renderMapToggles(DrawContext ctx, int screenH) {
        if (!isActiveOnCurrentServer()) return;
        if (config != null) {
            MapToggleOverlay.render(ctx, screenH, config,
                    renderer != null && renderer.isSquaremapLoading(),
                    renderer != null && renderer.isBorderLoading());
        }
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
        List<TownData> towns = apiClient.getTowns();
        if (towns.isEmpty()) {
            minimapOutsideNationPlayers.clear();
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getSession() == null) return;
        String selfName = client.getSession().getUsername();

        Set<String> currentlyVisibleWilderness = new HashSet<>();
        for (var marker : apiClient.getPlayers()) {
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
    public static void renderMinimapPlayerIndicator(DrawContext ctx, Object session, int mapX, int mapY, int size) {
        if (!isActiveOnCurrentServer()) return;
        try {
            TownyMinimapOverlay.renderPlayerIndicator(ctx,
                    (xaero.hud.minimap.module.MinimapSession) session, mapX, mapY, size);
        } catch (Exception e) {
            LOGGER.debug("[TownyMap] Failed to render minimap player indicator: {}", e.getMessage());
        }
    }

    public static void setSuppressNativeMinimapCompass(Object session) {
        suppressNativeMinimapCompass.set(shouldUseCustomEnlargedMinimapCompass(session));
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
        if (config == null || !config.minimapExtensionsEnabled || !config.squaremapBackgroundEnabled) return false;
        try {
            xaero.hud.minimap.module.MinimapSession minimapSession =
                    (xaero.hud.minimap.module.MinimapSession) session;
            return minimapSession.getProcessor() != null
                    && !minimapSession.getProcessor().isCaveModeDisplayed();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void renderMinimapCompassDirections(DrawContext ctx, Object session, int mapX, int mapY, int size) {
        if (!isActiveOnCurrentServer()) return;
        try {
            TownyMinimapOverlay.renderCompassDirections(ctx,
                    (xaero.hud.minimap.module.MinimapSession) session, mapX, mapY, size);
        } catch (Exception e) {
            LOGGER.debug("[TownyMap] Failed to render minimap compass directions: {}", e.getMessage());
        }
    }

    public static void renderMinimapNationAlert(DrawContext ctx, int x, int y, int size) {
        if (!isActiveOnCurrentServer()) return;
        if (config == null || !config.minimapNationAlertEnabled) return;
        long remaining = minimapNationAlertFlashUntilMs - System.currentTimeMillis();
        if (remaining <= 0 || size <= 8) return;

        double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 95.0);
        int alpha = 120 + (int) Math.round(110.0 * pulse);
        int color = ((alpha & 0xFF) << 24) | (minimapFrameColor() & 0x00FFFFFF);
        int thickness = 3;
        ctx.fill(x, y, x + size, y + thickness, color);
        ctx.fill(x, y + size - thickness, x + size, y + size, color);
        ctx.fill(x, y, x + thickness, y + size, color);
        ctx.fill(x + size - thickness, y, x + size, y + size, color);
    }

    public static void renderMinimapWaypointsOnTop(DrawContext ctx, Object session, int mapX, int mapY, int size) {
        if (!isActiveOnCurrentServer()) return;
        try {
            TownyMinimapOverlay.renderWaypointsOnTop(ctx,
                    (xaero.hud.minimap.module.MinimapSession) session, mapX, mapY, size);
        } catch (Exception e) {
            LOGGER.debug("[TownyMap] Failed to redraw minimap waypoints: {}", e.getMessage());
        }
    }

    public static void renderMinimapFrame(DrawContext ctx, Object session, int x, int y, int size) {
        if (!isActiveOnCurrentServer()) return;
        if (config == null || !config.minimapExtensionsEnabled || !config.squaremapBackgroundEnabled) return;
        if (size <= 8) return;
        try {
            xaero.hud.minimap.module.MinimapSession minimapSession =
                    (xaero.hud.minimap.module.MinimapSession) session;
            if (minimapSession.getProcessor() == null) return;
            if (minimapSession.getProcessor().isCaveModeDisplayed()) return;
            int color = 0xFF000000 | (minimapFrameColor() & 0x00FFFFFF);
            int shadow = 0xAA000000;
            int thickness = 1;
            ctx.fill(x - 1, y - 1, x + size + 1, y, shadow);
            ctx.fill(x - 1, y + size, x + size + 1, y + size + 1, shadow);
            ctx.fill(x - 1, y, x, y + size, shadow);
            ctx.fill(x + size, y, x + size + 1, y + size, shadow);
            ctx.fill(x, y, x + size, y + thickness, color);
            ctx.fill(x, y + size - thickness, x + size, y + size, color);
            ctx.fill(x, y, x + thickness, y + size, color);
            ctx.fill(x + size - thickness, y, x + size, y + size, color);
        } catch (Exception e) {
            LOGGER.debug("[TownyMap] Failed to render minimap frame overlay: {}", e.getMessage());
        }
    }

    private static int minimapFrameColor() {
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
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null
                && client.world != null
                && client.world.getRegistryKey() == World.NETHER;
    }

    public static int playerDotColor(String playerName) {
        if (!isActiveOnCurrentServer() || config == null || !config.playersEnabled) return 0;
        if (earthMcApi == null || playerName == null || playerName.isBlank()) return 0xFFFFFFFF;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getSession() == null) return 0xFFFFFFFF;
        String selfName = client.getSession().getUsername();
        if (playerName.equalsIgnoreCase(selfName)) return 0;

        String selfKey = townKey(selfName);
        EarthMcPlayerData self = playerDetailsCache.get(selfKey);
        if (self == null) {
            requestMinimapPlayerDetails(selfName);
            return 0xFFFFFFFF;
        }

        String key = townKey(playerName);
        EarthMcPlayerData other = playerDetailsCache.get(key);
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

    public static void renderOnMinimap(DrawContext ctx, Object session, int x, int y, int size) {
        if (!isActiveOnCurrentServer()) return;
        try {
            TownyMinimapOverlay.render(ctx,
                    (xaero.hud.minimap.module.MinimapSession) session,
                    x, y, size);
        } catch (Exception e) {
            LOGGER.debug("[TownyMap] Failed to render minimap town outlines: {}", e.getMessage());
        }
    }

    public static void renderTownSearch(DrawContext ctx, int screenW, int screenH) {
        if (!isActiveOnCurrentServer()) return;
        if (apiClient != null) {
            apiClient.tickWhileMapOpen();
            refreshPlayerIndexIfNeeded();
            refreshNationIndexIfNeeded();
            requestSearchDetailsIfNeeded();
            TownSearchOverlay.render(ctx, screenW, screenH, apiClient.getTowns(), apiClient.getPlayers(),
                    townDetailsCache, apiPlayers, playerDetailsCache, apiClient.getPlayerHistory(),
                    apiNations, nationDetailsCache, config.favoriteTowns);
        }
    }

    public static void renderTownInfo(DrawContext ctx, int screenW, int screenH) {
        if (!isActiveOnCurrentServer()) return;
        TownPopupData data = TownInfoOverlay.currentData();
        if (data != null && data != TownPopupData.WILDERNESS && data.nationName() != null && !data.nationName().isBlank()) {
            requestNationDetails(data.nationName());
        }
        TownInfoOverlay.render(ctx, screenW, screenH,
                data != null && isFavorite(data.townName()), nationDetailsCache);
    }

    public static void renderTownHover(DrawContext ctx, int mouseX, int mouseY,
                                       double worldX, double worldZ, int screenW, int screenH) {
        if (!isActiveOnCurrentServer()) return;
        if (config != null && config.chunkCounterEnabled) return;
        if (apiClient != null && config != null && config.townsEnabled) {
            TownData town = TownHoverOverlay.townAt(worldX, worldZ, apiClient.getTowns());
            if (town == null) return;

            String key = townKey(town.name());
            TownPopupData details = townDetailsCache.get(key);
            requestTownDetails(town.name(), key);
            TownHoverOverlay.render(ctx, mouseX, mouseY, screenW, screenH, town, details);
        }
    }

    public static boolean onMapToggleClick(double mouseX, double mouseY, int screenH) {
        if (!isActiveOnCurrentServer()) return false;
        return config != null && MapToggleOverlay.handleClick(mouseX, mouseY, screenH, config);
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
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        // Pass the current screen (GuiMap) as parent so closing config returns to the map.
        client.setScreen(new net.townymap.gui.TownyMapConfigScreen(client.currentScreen));
    }

    public static TownSearchOverlay.ClickResult onTownSearchClick(double mouseX, double mouseY,
                                                                  int screenW, int screenH) {
        if (!isActiveOnCurrentServer()) return TownSearchOverlay.ClickResult.none();
        if (apiClient == null) return TownSearchOverlay.ClickResult.none();
        return TownSearchOverlay.click(mouseX, mouseY, screenW, apiClient.getTowns(), apiClient.getPlayers(),
                townDetailsCache, apiPlayers, playerDetailsCache, apiClient.getPlayerHistory(), apiNations, nationDetailsCache,
                config != null ? config.favoriteTowns : List.of());
    }

    public static TownSearchOverlay.ClickResult onTownSearchKeyPressed(int keyCode) {
        if (!isActiveOnCurrentServer()) return TownSearchOverlay.ClickResult.none();
        if (apiClient == null) return TownSearchOverlay.ClickResult.none();
        return TownSearchOverlay.keyPressed(keyCode, apiClient.getTowns(), apiClient.getPlayers(),
                townDetailsCache, apiPlayers, playerDetailsCache, apiClient.getPlayerHistory(), apiNations, nationDetailsCache);
    }

    public static boolean onTownSearchCharTyped(char chr) {
        if (!isActiveOnCurrentServer()) return false;
        return TownSearchOverlay.charTyped(chr);
    }

    public static TownInfoOverlay.ActionResult onTownInfoClick(double mouseX, double mouseY) {
        if (!isActiveOnCurrentServer()) return TownInfoOverlay.ActionResult.none();
        TownInfoOverlay.ActionResult result = TownInfoOverlay.handleClick(mouseX, mouseY);
        if (result.action() == TownInfoOverlay.Action.FAVORITE) {
            toggleFavorite(result.townName());
        } else if (result.action() == TownInfoOverlay.Action.ROUTE) {
            createXaeroRoute(townInfoRouteTarget);
        }
        return result;
    }

    /**
     * Called by MixinGuiMap when the player right-clicks the map.
     * Shows a loading indicator immediately, then fills in data asynchronously.
     */
    public static void onMapRightClick(double worldX, double worldZ, int screenX, int screenY) {
        if (!isActiveOnCurrentServer()) return;
        if (earthMcApi == null) return;
        TownPopupData fallback = null;
        MapJumpTarget fallbackTarget = null;
        if (apiClient != null) {
            TownData town = TownHoverOverlay.townAt(worldX, worldZ, apiClient.getTowns());
            if (town != null) {
                fallback = townDetailsCache.get(townKey(town.name()));
                fallbackTarget = new MapJumpTarget(town.name(), town.centerX(), town.centerZ());
            }
        }
        long lookupId = townLookupId.incrementAndGet();
        TownInfoOverlay.showLoading(screenX, screenY);
        TownPopupData cachedFallback = fallback;
        MapJumpTarget cachedFallbackTarget = fallbackTarget;
        earthMcApi.fetchTownAt(worldX, worldZ).thenAccept(data -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) return;
            client.execute(() -> {
                if (lookupId != townLookupId.get()) return;
                showLookupResult(data != null ? data : cachedFallback, screenX, screenY,
                        worldX, worldZ, cachedFallbackTarget);
            });
        });
    }

    public static void dismissTownInfo() {
        townLookupId.incrementAndGet();
        townInfoRouteTarget = null;
        TownInfoOverlay.dismiss();
    }

    public static boolean isActiveOnCurrentServer() {
        if (config == null) return false;
        if (!config.earthmcOnly) return true;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return false;
        ServerInfo server = client.getCurrentServerEntry();
        if (server == null || server.address == null) return false;
        return server.address.toLowerCase(Locale.ROOT).contains("earthmc.net");
    }

    private static void showLookupResult(TownPopupData data, int screenX, int screenY,
                                         double clickedWorldX, double clickedWorldZ,
                                         MapJumpTarget fallbackTarget) {
        if (data != null) {
            if (data != TownPopupData.WILDERNESS) {
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
        try {
            boolean created = XaeroWaypointBridge.createRouteWaypoint(target);
            if (created) {
                sendFeedback("Xaero route set to " + target.label() + ".", Formatting.GREEN);
            } else {
                sendFeedback("Could not create a Xaero route here.", Formatting.RED);
            }
            return created;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("[TownyMap] Failed to create Xaero route waypoint", e);
            sendFeedback("Xaero route creation failed.", Formatting.RED);
            return false;
        }
    }

    private static void sendFeedback(String message, Formatting color) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;
        client.player.sendMessage(Text.literal("[TownyMap] ")
                .formatted(Formatting.GOLD)
                .append(Text.literal(message).formatted(color)), false);
    }

    private static void requestTownDetails(String townName, String key) {
        long now = System.currentTimeMillis();
        if (earthMcApi == null || isTownDetailsFresh(key) || requestDeferred(townDetailsDeferredAt, key, now)) return;
        if (townDetailsLoading.size() >= MAX_TOWN_DETAIL_LOADS) {
            deferRequest(townDetailsDeferredAt, key, now);
            return;
        }
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
            townDetailsCache.put(requestedKey, data);
            townDetailsFetchedAt.put(requestedKey, now);
            townDetailsDeferredAt.remove(requestedKey);
        }
        townDetailsCache.put(canonicalKey, data);
        townDetailsFetchedAt.put(canonicalKey, now);
        townDetailsDeferredAt.remove(canonicalKey);
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
        if (earthMcApi == null || apiClient == null || config == null) return;
        if (config.townStatusOverlayMode == 0) return;
        if (scale <= 0) return;
        long now = System.currentTimeMillis();
        if (now - lastVisibleTownDetailsRequestMs < 500L) return;
        lastVisibleTownDetailsRequestMs = now;

        double worldLeft = cameraX - screenW / 2.0 / scale;
        double worldRight = cameraX + screenW / 2.0 / scale;
        double worldTop = cameraZ - screenH / 2.0 / scale;
        double worldBottom = cameraZ + screenH / 2.0 / scale;

        int requested = 0;
        for (TownData town : apiClient.getTowns()) {
            if (!town.intersectsWorld(worldLeft, worldRight, worldTop, worldBottom)) continue;
            String key = townKey(town.name());
            if (townDetailsCache.containsKey(key) || townDetailsLoading.contains(key)) continue;
            requestTownDetails(town.name(), key);
            if (++requested >= 4) return;
        }
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

        int requested = 0;
        for (var marker : apiClient.getPlayers()) {
            if (marker.x() < worldLeft || marker.x() > worldRight
                    || marker.z() < worldTop || marker.z() > worldBottom) continue;
            if (requestPlayerDetails(marker.name()) && ++requested >= 4) return;
        }
    }

    private static void requestPlayerDetailsForSearch() {
        if (earthMcApi == null) return;
        for (String name : TownSearchOverlay.visibleApiPlayerMatches(apiPlayers)) {
            requestPlayerDetails(name);
        }
        String exact = TownSearchOverlay.exactPlayerQuery();
        if (!exact.isBlank()) requestPlayerDetails(exact);
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

    private static boolean requestPlayerDetails(String name) {
        if (earthMcApi == null || name == null || name.isBlank()) return false;
        String key = townKey(name);
        if (playerDetailsCache.containsKey(key) || playerDetailsLoading.contains(key)) return false;
        Long failedAt = playerDetailsFailedAt.get(key);
        long now = System.currentTimeMillis();
        if (failedAt != null && now - failedAt < 30_000) return false;
        if (requestDeferred(playerDetailsDeferredAt, key, now)) return false;
        if (playerDetailsLoading.size() >= MAX_PLAYER_DETAIL_LOADS) {
            deferRequest(playerDetailsDeferredAt, key, now);
            return false;
        }
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
        for (String name : TownSearchOverlay.visibleTownMatches(apiClient.getTowns())) {
            requestTownDetails(name, townKey(name));
        }
    }

    private static void requestNationDetailsForSearch() {
        if (earthMcApi == null) return;
        for (String name : TownSearchOverlay.visibleNationMatches(apiNations)) {
            requestNationDetails(name);
        }
    }

    private static void requestNationCapitalDetails() {
        if (earthMcApi == null || apiNations.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastNationCapitalDetailsRequestMs < 1_000L) return;
        lastNationCapitalDetailsRequestMs = now;
        if (nationDetailsLoading.size() >= MAX_NATION_DETAIL_LOADS) return;
        int requested = 0;
        for (EarthMcNationData nation : apiNations) {
            if (requestNationDetails(nation.name()) && ++requested >= 2) return;
        }
    }

    private static boolean requestNationDetails(String name) {
        if (earthMcApi == null || name == null || name.isBlank()) return false;
        String key = townKey(name);
        long now = System.currentTimeMillis();
        if (requestDeferred(nationDetailsDeferredAt, key, now)) return false;
        if (nationDetailsLoading.size() >= MAX_NATION_DETAIL_LOADS) {
            deferRequest(nationDetailsDeferredAt, key, now);
            return false;
        }
        if (nationDetailsCache.containsKey(key) || !nationDetailsLoading.add(key)) return false;
        earthMcApi.fetchNation(name).whenComplete((data, error) -> {
            if (data != null) {
                nationDetailsCache.put(key, data);
                nationDetailsCache.put(townKey(data.name()), data);
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
                    || !json.contains("hasNation")) {
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
            LOGGER.debug("[TownyMap] Failed to save town detail cache: {}", e.getMessage());
        }
    }

    private static Path townDetailsCachePath() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("townymapaddon")
                .resolve("town-details-cache.json");
    }

    public static void requestMinimapTownHighlightRefresh() {
        MinecraftClient client = MinecraftClient.getInstance();
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

    public static TownyMapConfig     getConfig()    { return config;    }
    public static SquaremapApiClient getApiClient() { return apiClient; }
}
