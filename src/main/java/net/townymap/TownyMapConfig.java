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
    /**
     * Kept as a gate throughout the minimap rendering, but no longer user-facing: the settings row was
     * removed, so {@code sanitize()} forces it back on. Without that, anyone whose config had it off
     * would have no way left to turn the minimap overlay back on.
     */
    public boolean minimapExtensionsEnabled = true;
    public boolean minimapPlayersEnabled = true;
    public boolean hideMinimapInNether = false;
    // What our EarthMC overlay does outside the overworld (the EarthMC map is overworld-only, so in
    // the Nether the player's X/Z would otherwise place the overlay at the wrong spot):
    // 0 = show anyway (at the raw coords), 1 = hide our overlay, 2 = convert to overworld coords.
    public int netherMode = 1;
    /**
     * Which minimap background the player indicator is drawn over: 0 = never draw ours, 1 = only when
     * the EMC overlay is on, 2 = only when it's off (Xaero's own terrain), 3 = both. Xaero draws its
     * arrow before our overlay, so the EMC layer hides it and ours stands in; over Xaero's terrain ours
     * lands on its own arrow. Note that 0 leaves no arrow at all while the EMC layer is on, since the
     * one Xaero draws is underneath it.
     */
    public int minimapIndicatorMode = 3;
    public boolean minimapNationAlertEnabled = true;
    public boolean minimapTownNamesEnabled = true;
    // ── Info display (text lines under the minimap, stacked with Xaero's coords) ──
    /** Data-freshness line under Xaero's world-map coordinate readout. */
    public boolean dataStatusEnabled = true;
    /**
     * What the manual claim refresh rebuilds. Off (the default) re-reads the claim data and lets the
     * renderer redraw whatever actually changed -- which is all that is needed to pick up a claim or
     * unclaim, and keeps the map imagery on screen; every 10 minutes one refresh is promoted to the
     * full rebuild anyway. On makes every refresh the full one: it drops every cached outline and map
     * tile, which is the heavier recovery for a tile that cached badly.
     */
    public boolean refreshEntireMap = false;
    public boolean infoDisplayTownEnabled = true;
    public boolean infoDisplayNearbyPlayersEnabled = true;
    public boolean infoDisplayNearestTownEnabled = true;
    /** 0 off, 1 nearby only, 2 major visible towns, 3 all visible towns. */
    public int minimapTownNameMode = 2;
    /** 0 off, 1 always, 2 enlarged minimap only. */
    public int minimapChunkGridMode = 0;
    public boolean chunkCounterEnabled = false;
    /** Chunk-counter "Fill": treat any area fully enclosed by selected chunks as selected too, so drawing
     *  an outline counts the interior. Non-destructive — the painted chunks are what's saved. */
    public boolean chunkCounterFillEnclosed = false;
    /** Legacy field kept for migration; the counter now always uses multi-selection groups. */
    public int chunkCounterMode = 2;
    public int activeChunkCounterGroup = 0;
    public int chunkCounterGroupCount = 1;
    /**
     * Where the EarthMC (squaremap) imagery layer is shown: 0 = off, 1 = world map only, 2 = minimap
     * only, 3 = both. Same numbering as {@link #playerHeadMode}.
     */
    public int squaremapBackgroundMode = 0;
    /**
     * Legacy single on/off flag, superseded by {@link #squaremapBackgroundMode}. Kept so existing
     * configs still migrate ({@code sanitize()} promotes a true value to "both"); nothing reads it.
     */
    public boolean squaremapBackgroundEnabled = false;

    /** Whether the EarthMC imagery layer is drawn on the minimap. */
    public boolean squaremapOnMinimap() {
        return squaremapBackgroundMode == 2 || squaremapBackgroundMode == 3;
    }

    /** Whether the EarthMC imagery layer is drawn on the world map. */
    public boolean squaremapOnWorldMap() {
        return squaremapBackgroundMode == 1 || squaremapBackgroundMode == 3;
    }
    // Darken the squaremap imagery (world map + minimap): 0 = off, 1 = light, 2 = medium, 3 = dark.
    public int squaremapDarken = 0;
    // Render the on-map buttons/panels in a flat dark (near-black) style instead of vanilla textured buttons.
    public boolean darkButtons = false;
    // Zoom the world-map EarthMC overlay out further (the overlay's block-scale is divided by a factor,
    // keeping it centred on the camera) so you can zoom out to see the whole EarthMC map. On by default.
    public boolean worldMapOverview = true;
    public boolean nationStarsEnabled = true;
    /** Show a nation's join-range zone on the world map when it's selected: a 5k circle around the capital
     *  plus a 1.5k circle around every town, whose union is where a town could join that nation. */
    public boolean nationRangeEnabled = true;
    // ── Clean map screenshots ────────────────────────────────────────────────
    /** Include live player dots in a map screenshot. Off: a shareable picture of the map, not of who's on. */
    public boolean screenshotPlayers = false;
    /** Keep nation capital stars in a map screenshot. */
    public boolean screenshotNationStars = true;
    /** Drop the blacked-out towns a filter or alliance layer leaves behind, instead of shooting them black. */
    public boolean screenshotHideDimmedTowns = true;
    /** At far zoom, collapse small towns to a single crisp dot instead of drawing their outline. Off =
     *  always draw the real outline (towns keep their shape until they're literally sub-pixel). */
    public boolean farZoomTownDots = false;
    /** Town border style. On = smooth squaremap-style outlines (simplified into diagonals, baked into
     *  tiles). Off = the original blocky chunk-aligned outlines drawn directly. */
    public boolean smoothTownOutlines = true;
    public boolean chunkGridEnabled = false;
    public boolean customOverlaysEnabled = false;
    /** Draw the name next to each custom-overlay marker. Off leaves just the dots. */
    public boolean customOverlayLabels = true;
    /** Drop temporary Xaero waypoints on the shops returned by QuickShop's {@code /qs find}. */
    public boolean shopWaypointsEnabled = true;
    /** How far from a shop waypoint you can get before it's removed again, in blocks. */
    public int shopWaypointRange = 250;
    /** 0 off, 1 public outsider-spawn, 2 overclaimed, 3 open, 4 for sale, 5 no nation. */
    public int townStatusOverlayMode = 0;
    public int borderOverlayMode = 0; // 0 off, 1 countries, 2 states + countries
    /** Multiplier applied to the base border stroke width. Range 0.5 – 3.0, default 0.5. */
    public float borderThicknessMultiplier = 0.5f;
    public List<String> favoriteTowns = new ArrayList<>();
    /** Starred nations and players. Towns had this from the start; the other two kinds behave the same. */
    public List<String> favoriteNations = new ArrayList<>();
    public List<String> favoritePlayers = new ArrayList<>();
    /**
     * squaremap world the saved selection was drawn in. Chunk keys are raw coordinates with no world
     * attached, so without this a selection made on Terra Nostra reappeared over the Moon. Defaults to
     * the overworld so selections saved before the Moon existed keep working.
     */
    public String chunkCounterWorld = "minecraft_overworld";
    public List<Long> chunkCounterSelection = new ArrayList<>();
    public List<List<Long>> chunkCounterGroups = new ArrayList<>();

    /** UI scale for the mod's GUIs: 1.0 keeps the current sizing; lower shrinks text and gaps. Range 0.7 – 1.0
     *  (below 70% they get unreadable). */
    public float infoPanelScale = 1.0f;

    // ── Visual (towns) ───────────────────────────────────────────────────────
    public int borderAlpha  = 220;
    public int fillAlpha    = 35;
    public int statusHighlightColor = 0xB36BFF;
    public boolean statusHighlightRainbow = true;
    public boolean statusHighlightSettingsInitialized = true;

    // ── Visual (players) ────────────────────────────────────────────────────
    public int playerLabelColor  = 0xFFFFFF00;
    public boolean showPlayerNames = true;
    public double playerNameMinScale = 0.08;
    public double playerAffiliationMinScale = 0.108;
    /** Player heads on the dots: 0 off, 1 world map, 2 minimap, 3 both. */
    public int playerHeadMode = 3;   // heads on (world map + minimap) by default
    /** Zoom threshold (world-map block scale) above which heads are drawn — chosen via a Near/Medium/Far
     *  button. Larger = must be more zoomed in (Near); smaller = heads show from further out (Far). */
    public double playerHeadMinScale = 0.06;
    /** Keep showing players at their last-seen spot (in red) after they drop off the live feed. */
    public boolean playerLastSeen = true;

    // ── Refresh intervals ────────────────────────────────────────────────────
    public int refreshTownsSecs   = 60;
    // Superseded: live player positions now refresh at a fixed ~1s (SquaremapApiClient.PLAYER_REFRESH_MS),
    // matching squaremap's own update cadence. Kept only so old config files still deserialize.
    public int refreshPlayersSecs = 2;
    public boolean refreshSettingsInitialized = true;

    // ── API endpoints ────────────────────────────────────────────────────────
    public String squaremapBaseUrl = "https://map.earthmc.net";
    public String worldKey         = "minecraft_overworld";
    /**
     * Which squaremap world the map shows: 0 = Terra Nostra, 1 = the Moon.
     *
     * <p>Always Terra Nostra unless you switch it, so nothing changes under you when you travel. While
     * the map shows a world you are not standing in, player markers are hidden -- their coordinates
     * belong to the other world, and the two overlap numerically.
     */
    /**
      * 0 = Auto (follow the dimension you are in), 1 = Terra Nostra, 2 = Moon.
      *
      * <p>Auto is the default: EarthMC's earthmc:space and earthmc:moon both show the Moon, everything
      * else shows Terra Nostra. Clicking the World button pins one world and stops the following;
      * cycling back round to Auto resumes it.
      */
    public int mapWorldMode = 0;
    public int squaremapMaxZoom    = 5;

    // ── Computed URLs ────────────────────────────────────────────────────────
    public String markersUrl() {
        return markersUrl(worldKey);
    }

    /** Markers for a specific squaremap world, so the active world can differ from the configured one. */
    public String markersUrl(String world) {
        return squaremapBaseUrl + "/tiles/" + (world == null || world.isBlank() ? worldKey : world)
                + "/markers.json";
    }

    /** The world list squaremap publishes, used to validate a dimension before we try to show it. */
    public String worldsUrl() {
        return squaremapBaseUrl + "/tiles/settings.json";
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

        // Migrate the old single on/off flag: a config written before the per-map modes existed only
        // knew "on", which meant both maps. Clear the legacy flag afterwards so this runs once.
        if (squaremapBackgroundEnabled) {
            if (squaremapBackgroundMode == 0) squaremapBackgroundMode = 3;
            squaremapBackgroundEnabled = false;
            changed = true;
        }
        if (squaremapBackgroundMode < 0 || squaremapBackgroundMode > 3) {
            squaremapBackgroundMode = 0;
            changed = true;
        }
        // No control for this any more, so a config that still has it off would be permanently stuck.
        if (!minimapExtensionsEnabled) {
            minimapExtensionsEnabled = true;
            changed = true;
        }


        if (refreshTownsSecs != 60) {
            refreshTownsSecs = 60;
            changed = true;
        }
        if (refreshPlayersSecs < 1) {
            refreshPlayersSecs = 1;
            changed = true;
        }
        int clampedDarken = Math.max(0, Math.min(3, squaremapDarken));
        if (clampedDarken != squaremapDarken) {
            squaremapDarken = clampedDarken;
            changed = true;
        }
        if (squaremapMaxZoom < 0) {
            squaremapMaxZoom = 0;
            changed = true;
        } else if (squaremapMaxZoom > 8) {
            squaremapMaxZoom = 8;
            changed = true;
        }
        if (mapWorldMode < 0 || mapWorldMode > 2) {
            mapWorldMode = 0;
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
        if (infoPanelScale < 0.7f || infoPanelScale > 1.0f || Float.isNaN(infoPanelScale)) {
            infoPanelScale = Float.isNaN(infoPanelScale) ? 1.0f : Math.max(0.7f, Math.min(1.0f, infoPanelScale));
            changed = true;
        }
        if (favoriteNations == null) favoriteNations = new ArrayList<>();
        if (favoritePlayers == null) favoritePlayers = new ArrayList<>();
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

    /**
     * Fields holding the user's own DATA rather than their preferences. "Reset All" must not touch these:
     * losing a favourites list or a set of counted chunks to a settings reset would be destructive and
     * unrecoverable, and no one pressing "Reset All" on a settings screen expects it.
     */
    private static final java.util.Set<String> PRESERVED_ON_RESET = java.util.Set.of(
            "favoriteTowns", "favoriteNations", "favoritePlayers",
            "chunkCounterSelection", "chunkCounterGroups",
            "activeChunkCounterGroup", "chunkCounterGroupCount");

    /**
     * Copies every persisted field of this config into {@code target}. "Reset All" uses a freshly
     * constructed TownyMapConfig, which holds the field-initialised defaults, so this resets the whole
     * screen without enumerating settings by hand — an explicit list would silently miss any option
     * added later. Mirrors exactly what Gson persists: public, non-static, non-final fields.
     */
    public void copyInto(TownyMapConfig target) {
        for (java.lang.reflect.Field f : TownyMapConfig.class.getDeclaredFields()) {
            if (PRESERVED_ON_RESET.contains(f.getName())) continue;
            int mods = f.getModifiers();
            if (java.lang.reflect.Modifier.isStatic(mods)
                    || java.lang.reflect.Modifier.isFinal(mods)
                    || java.lang.reflect.Modifier.isTransient(mods)
                    || !java.lang.reflect.Modifier.isPublic(mods)) continue;
            try {
                f.set(target, f.get(this));
            } catch (IllegalAccessException ignored) {
                // A field we cannot write is one the user cannot change either; skipping is correct.
            }
        }
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
