package net.townymap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class TownyMapConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("TownyMapAddon");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "townymapaddon.json";

    // ── Feature toggles ──────────────────────────────────────────────────────
    public boolean townsEnabled   = true;
    public boolean playersEnabled = true;
    public boolean earthmcOnly = true;
    public boolean minimapExtensionsEnabled = true;
    public boolean minimapPlayersEnabled = true;
    public boolean hideMinimapInNether = false;
    public boolean minimapNationAlertEnabled = true;
    public boolean minimapTownNamesEnabled = true;
    /** 0 off, 1 nearby only, 2 major visible towns, 3 all visible towns. */
    public int minimapTownNameMode = 2;
    /** 0 off, 1 always, 2 enlarged minimap only. */
    public int minimapChunkGridMode = 0;
    public boolean chunkCounterEnabled = false;
    /** Legacy field kept for migration; the counter now always uses multi-selection groups. */
    public int chunkCounterMode = 2;
    public int activeChunkCounterGroup = 0;
    public int chunkCounterGroupCount = 1;
    public boolean squaremapBackgroundEnabled = false;
    public boolean nationStarsEnabled = true;
    public boolean chunkGridEnabled = false;
    /** 0 off, 1 public outsider-spawn, 2 overclaimed, 3 open, 4 for sale, 5 no nation. */
    public int townStatusOverlayMode = 0;
    public int borderOverlayMode = 0; // 0 off, 1 countries, 2 states + countries
    /** Multiplier applied to the base border stroke width. Range 0.5 – 3.0, default 0.5. */
    public float borderThicknessMultiplier = 0.5f;
    public List<String> favoriteTowns = new ArrayList<>();
    public List<Long> chunkCounterSelection = new ArrayList<>();
    public List<List<Long>> chunkCounterGroups = new ArrayList<>();

    // ── Visual (towns) ───────────────────────────────────────────────────────
    public int borderAlpha  = 220;
    public int fillAlpha    = 35;
    public int statusHighlightColor = 0xB36BFF;
    public boolean statusHighlightRainbow = true;
    public boolean statusHighlightSettingsInitialized = true;

    // ── Visual (players) ────────────────────────────────────────────────────
    public int playerColor       = 0xFFFFFFFF;
    public int playerLabelColor  = 0xFFFFFF00;
    public boolean showPlayerNames = true;
    public double playerNameMinScale = 0.08;
    public double playerAffiliationMinScale = 0.108;

    // ── Refresh intervals ────────────────────────────────────────────────────
    public int refreshTownsSecs   = 60;
    public int refreshPlayersSecs = 5;
    public boolean refreshSettingsInitialized = true;

    // ── API endpoints ────────────────────────────────────────────────────────
    public String squaremapBaseUrl = "https://map.earthmc.net";
    public String worldKey         = "minecraft_overworld";
    public int squaremapMaxZoom    = 5;

    // ── Computed URLs ────────────────────────────────────────────────────────
    public String markersUrl() {
        return squaremapBaseUrl + "/tiles/" + worldKey + "/markers.json";
    }
    public String playersUrl() {
        return squaremapBaseUrl + "/tiles/players.json";
    }

    // ── Persistence ──────────────────────────────────────────────────────────
    public static TownyMapConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                TownyMapConfig cfg = GSON.fromJson(json, TownyMapConfig.class);
                if (cfg != null) {
                    if (cfg.sanitize()) cfg.save();
                    return cfg;
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to read config, using defaults", e);
            }
        }
        TownyMapConfig defaults = new TownyMapConfig();
        defaults.save();
        return defaults;
    }

    private boolean sanitize() {
        boolean changed = false;

        if (refreshTownsSecs != 60) {
            refreshTownsSecs = 60;
            changed = true;
        }
        if (refreshPlayersSecs < 1) {
            refreshPlayersSecs = 1;
            changed = true;
        }
        if (squaremapMaxZoom < 0) {
            squaremapMaxZoom = 0;
            changed = true;
        } else if (squaremapMaxZoom > 8) {
            squaremapMaxZoom = 8;
            changed = true;
        }
        if (borderOverlayMode < 0 || borderOverlayMode > 2) {
            borderOverlayMode = 0;
            changed = true;
        }
        if (townStatusOverlayMode < 0 || townStatusOverlayMode > 5) {
            townStatusOverlayMode = 0;
            changed = true;
        }
        if (minimapTownNameMode < 0 || minimapTownNameMode > 3) {
            minimapTownNameMode = minimapTownNamesEnabled ? 2 : 0;
            changed = true;
        }
        if (minimapChunkGridMode < 0 || minimapChunkGridMode > 2) {
            minimapChunkGridMode = 0;
            changed = true;
        }
        if (borderThicknessMultiplier < 0.1f || borderThicknessMultiplier > 3.0f
                || Float.isNaN(borderThicknessMultiplier)) {
            borderThicknessMultiplier = 0.5f;
            changed = true;
        }
        if (favoriteTowns == null) {
            favoriteTowns = new ArrayList<>();
            changed = true;
        }
        if (chunkCounterSelection == null) {
            chunkCounterSelection = new ArrayList<>();
            changed = true;
        }
        if (chunkCounterGroups == null) {
            chunkCounterGroups = new ArrayList<>();
            changed = true;
        }
        ArrayList<Long> legacySingleSelection = new ArrayList<>();
        for (Long chunk : chunkCounterSelection) {
            if (chunk != null) legacySingleSelection.add(chunk);
        }
        if (!chunkCounterSelection.equals(legacySingleSelection)) {
            chunkCounterSelection = legacySingleSelection;
            changed = true;
        }
        int oldActiveChunkCounterGroup = activeChunkCounterGroup;
        ArrayList<List<Long>> compactedChunkCounterGroups = new ArrayList<>();
        int mappedActiveChunkCounterGroup = -1;
        int nonEmptyGroupsBeforeOrAtActive = 0;
        for (int i = 0; i < Math.min(7, chunkCounterGroups.size()); i++) {
            List<Long> group = chunkCounterGroups.get(i);
            ArrayList<Long> cleaned = new ArrayList<>();
            if (group != null) {
                for (Long chunk : group) {
                    if (chunk != null) cleaned.add(chunk);
                }
            }
            if (cleaned.isEmpty()) {
                if (group != null && !group.isEmpty()) changed = true;
                continue;
            }
            if (i <= oldActiveChunkCounterGroup) nonEmptyGroupsBeforeOrAtActive++;
            if (i == oldActiveChunkCounterGroup) {
                mappedActiveChunkCounterGroup = compactedChunkCounterGroups.size();
            }
            compactedChunkCounterGroups.add(cleaned);
        }
        if (compactedChunkCounterGroups.isEmpty() && !legacySingleSelection.isEmpty()) {
            compactedChunkCounterGroups.add(new ArrayList<>(legacySingleSelection));
            activeChunkCounterGroup = 0;
            changed = true;
        }
        if (!sameNestedLongLists(chunkCounterGroups, compactedChunkCounterGroups)) {
            chunkCounterGroups = compactedChunkCounterGroups;
            changed = true;
        }
        if (!chunkCounterSelection.isEmpty()) {
            chunkCounterSelection = new ArrayList<>();
            changed = true;
        }
        int oldChunkCounterGroupCount = chunkCounterGroupCount;
        chunkCounterGroupCount = Math.max(1, Math.min(7, compactedChunkCounterGroups.size()));
        changed |= oldChunkCounterGroupCount != chunkCounterGroupCount;
        if (chunkCounterMode != 2) {
            chunkCounterMode = 2;
            changed = true;
        }
        if (mappedActiveChunkCounterGroup >= 0) {
            activeChunkCounterGroup = mappedActiveChunkCounterGroup;
        } else {
            activeChunkCounterGroup = Math.max(0,
                    Math.min(chunkCounterGroupCount - 1, nonEmptyGroupsBeforeOrAtActive));
        }
        changed |= oldActiveChunkCounterGroup != activeChunkCounterGroup;
        double oldPlayerNameMinScale = playerNameMinScale;
        playerNameMinScale = clampDouble(playerNameMinScale, 0.01, 0.30);
        changed |= oldPlayerNameMinScale != playerNameMinScale;
        double oldPlayerAffiliationMinScale = playerAffiliationMinScale;
        playerAffiliationMinScale = clampDouble(playerAffiliationMinScale, 0.01, 0.30);
        changed |= oldPlayerAffiliationMinScale != playerAffiliationMinScale;

        int oldBorderAlpha = borderAlpha;
        int oldFillAlpha = fillAlpha;
        borderAlpha = clampAlpha(borderAlpha);
        fillAlpha = clampAlpha(fillAlpha);
        changed |= oldBorderAlpha != borderAlpha
                || oldFillAlpha != fillAlpha;
        int oldStatusHighlightColor = statusHighlightColor;
        statusHighlightColor &= 0x00FFFFFF;
        changed |= oldStatusHighlightColor != statusHighlightColor;
        if (!statusHighlightSettingsInitialized) {
            statusHighlightRainbow = true;
            statusHighlightSettingsInitialized = true;
            changed = true;
        }
        if (!refreshSettingsInitialized) {
            refreshTownsSecs = 60;
            refreshSettingsInitialized = true;
            changed = true;
        }

        if (squaremapBaseUrl == null || squaremapBaseUrl.isBlank()) {
            squaremapBaseUrl = "https://map.earthmc.net";
            changed = true;
        } else {
            String normalized = squaremapBaseUrl.trim();
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            if (!normalized.equals(squaremapBaseUrl)) {
                squaremapBaseUrl = normalized;
                changed = true;
            }
        }

        if (worldKey == null || worldKey.isBlank()) {
            worldKey = "minecraft_overworld";
            changed = true;
        } else {
            String normalized = worldKey.trim();
            if (!normalized.equals(worldKey)) {
                worldKey = normalized;
                changed = true;
            }
        }

        return changed;
    }

    private static boolean sameNestedLongLists(List<List<Long>> a, List<List<Long>> b) {
        if (a == b) return true;
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            List<Long> left = a.get(i);
            List<Long> right = b.get(i);
            if (left == null) left = List.of();
            if (!left.equals(right)) return false;
        }
        return true;
    }

    private static int clampAlpha(int alpha) {
        if (alpha < 0) return 0;
        if (alpha > 255) return 255;
        return alpha;
    }

    private static double clampDouble(double value, double min, double max) {
        if (Double.isNaN(value)) return min;
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    public void save() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        try {
            Files.writeString(path, GSON.toJson(this));
        } catch (IOException e) {
            LOGGER.warn("Failed to write config", e);
        }
    }
}
