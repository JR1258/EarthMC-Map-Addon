package net.townymap.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.townymap.model.EarthMcNationData;
import net.townymap.model.EarthMcPlayerData;
import net.townymap.model.MapJumpTarget;
import net.townymap.model.PlayerHistoryEntry;
import net.townymap.model.PlayerMarker;
import net.townymap.model.TownData;
import net.townymap.model.TownPopupData;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class TownSearchOverlay {

    private static final int WIDTH = 180;
    private static final int FAVORITES_WIDTH = 74;
    private static final int ROW_HEIGHT = 20;
    private static final int MAX_RESULTS = 7;
    private static final int MAX_PER_TYPE = 3;
    private static final int MAX_INFO_LINES = 8;
    private static final int BG = 0xD8101010;
    private static final int BORDER = 0xFF333333;
    private static final int ACTIVE_BORDER = 0xFFDDDDDD;
    private static final int HOVER = 0x553BFF3B;

    private static boolean focused;
    private static String query = "";
    private static int selected;
    private static String selectedType = "";
    private static String selectedName = "";
    private static boolean favoritesOpen;
    private static String cachedNeedle = null;
    private static int cachedTownCount = -1;
    private static int cachedTownDetailCount = -1;
    private static int cachedMarkerCount = -1;
    private static int cachedApiPlayerCount = -1;
    private static int cachedPlayerDetailCount = -1;
    private static int cachedPlayerHistoryCount = -1;
    private static int cachedNationCount = -1;
    private static int cachedNationDetailCount = -1;
    private static List<Result> cachedResults = List.of();

    private TownSearchOverlay() {}

    public static void render(DrawContext ctx, int sw, int sh,
                              List<TownData> towns, List<PlayerMarker> players,
                              Map<String, TownPopupData> townDetails,
                              List<EarthMcPlayerData> apiPlayers,
                              Map<String, EarthMcPlayerData> playerDetails,
                              Map<String, PlayerHistoryEntry> playerHistory,
                              List<EarthMcNationData> apiNations,
                              Map<String, EarthMcNationData> nationDetails,
                              List<String> favoriteTowns) {
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        int x = left(sw);
        int y = top();
        int border = focused ? ACTIVE_BORDER : BORDER;

        ctx.fill(x - 1, y - 1, x + WIDTH + 1, y + ROW_HEIGHT + 1, border);
        ctx.fill(x, y, x + WIDTH, y + ROW_HEIGHT, BG);

        String display = query.isEmpty() && !focused ? "Search towns/nations/players" : query;
        int color = query.isEmpty() && !focused ? 0xFFAAAAAA : 0xFFFFFFFF;
        ctx.drawText(tr, display, x + 7, y + 5, color, true);
        renderFavorites(ctx, tr, favoritesX(x), y, sw, towns, favoriteTowns);

        if (!focused && query.isEmpty()) {
            return;
        }

        List<Result> results = results(towns, players, townDetails, apiPlayers,
                playerDetails, playerHistory, apiNations, nationDetails);
        for (int i = 0; i < results.size(); i++) {
            Result result = results.get(i);
            int rowY = resultRowY(y, i);
            ctx.fill(x - 1, rowY - 1, x + WIDTH + 1, rowY + ROW_HEIGHT + 1, BORDER);
            ctx.fill(x, rowY, x + WIDTH, rowY + ROW_HEIGHT, i == selected ? HOVER : BG);
            ctx.drawText(tr, trimToWidth(tr, result.label(), WIDTH - 14), x + 7, rowY + 5, 0xFFFFFFFF, true);
        }
        renderSelectedInfo(ctx, tr, sw, sh,
                towns, players, townDetails, playerDetails, playerHistory, nationDetails);
    }

    public static ClickResult click(double mouseX, double mouseY, int sw,
                                    List<TownData> towns, List<PlayerMarker> players,
                                    Map<String, TownPopupData> townDetails,
                                    List<EarthMcPlayerData> apiPlayers,
                                    Map<String, EarthMcPlayerData> playerDetails,
                                    Map<String, PlayerHistoryEntry> playerHistory,
                                    List<EarthMcNationData> apiNations,
                                    Map<String, EarthMcNationData> nationDetails,
                                    List<String> favoriteTowns) {
        int x = left(sw);
        int y = top();
        ClickResult favoriteClick = favoriteClick(mouseX, mouseY, favoritesX(x), y, towns, favoriteTowns);
        if (favoriteClick.consumed()) return favoriteClick;

        if (inside(mouseX, mouseY, x, y, WIDTH, ROW_HEIGHT)) {
            focused = true;
            selected = 0;
            return ClickResult.consumedResult();
        }

        if (focused || !query.isEmpty()) {
            List<Result> results = results(towns, players, townDetails, apiPlayers,
                    playerDetails, playerHistory, apiNations, nationDetails);
            for (int i = 0; i < results.size(); i++) {
                int rowY = resultRowY(y, i);
                if (inside(mouseX, mouseY, x, rowY, WIDTH, ROW_HEIGHT)) {
                    selected = i;
                    Result result = results.get(i);
                    focused = false;
                    select(result);
                    return ClickResult.jump(result.target());
                }
            }
        }

        focused = false;
        clearSelection();
        return ClickResult.none();
    }

    public static ClickResult keyPressed(int keyCode, List<TownData> towns, List<PlayerMarker> players,
                                         Map<String, TownPopupData> townDetails,
                                         List<EarthMcPlayerData> apiPlayers,
                                         Map<String, EarthMcPlayerData> playerDetails,
                                         Map<String, PlayerHistoryEntry> playerHistory,
                                         List<EarthMcNationData> apiNations,
                                         Map<String, EarthMcNationData> nationDetails) {
        if (!focused) return ClickResult.none();
        List<Result> results = results(towns, players, townDetails, apiPlayers,
                playerDetails, playerHistory, apiNations, nationDetails);

        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            focused = false;
            return ClickResult.consumedResult();
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            if (!query.isEmpty()) query = query.substring(0, query.length() - 1);
            invalidateResults();
            clearSelection();
            selected = Math.min(selected, Math.max(0,
                    results(towns, players, townDetails, apiPlayers,
                            playerDetails, playerHistory, apiNations, nationDetails).size() - 1));
            return ClickResult.consumedResult();
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            if (!results.isEmpty()) selected = Math.min(results.size() - 1, selected + 1);
            return ClickResult.consumedResult();
        }
        if (keyCode == GLFW.GLFW_KEY_UP) {
            selected = Math.max(0, selected - 1);
            return ClickResult.consumedResult();
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (!results.isEmpty()) {
                Result result = results.get(Math.min(selected, results.size() - 1));
                focused = false;
                select(result);
                return ClickResult.jump(result.target());
            }
            return ClickResult.consumedResult();
        }
        return ClickResult.consumedResult();
    }

    public static boolean charTyped(char chr) {
        if (!focused) return false;
        if (Character.isISOControl(chr)) return true;
        query += chr;
        selected = 0;
        invalidateResults();
        clearSelection();
        return true;
    }

    public static String query() {
        return query;
    }

    public static String exactPlayerQuery() {
        String trimmed = query.trim();
        if (trimmed.length() < 3) return "";
        if (!trimmed.matches("[A-Za-z0-9_]{3,16}")) return "";
        return trimmed;
    }

    public static List<String> visibleApiPlayerMatches(List<EarthMcPlayerData> apiPlayers) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.length() < 2) return List.of();

        ArrayList<EarthMcPlayerData> matches = new ArrayList<>();
        for (EarthMcPlayerData player : apiPlayers) {
            if (player.name().toLowerCase(Locale.ROOT).contains(needle)) matches.add(player);
        }
        matches.sort(Comparator
                .comparingInt((EarthMcPlayerData p) -> score(p.name(), needle))
                .thenComparing(EarthMcPlayerData::name, String.CASE_INSENSITIVE_ORDER));

        ArrayList<String> names = new ArrayList<>();
        int limit = Math.min(MAX_RESULTS, matches.size());
        for (int i = 0; i < limit; i++) names.add(matches.get(i).name());
        return List.copyOf(names);
    }

    public static List<String> visibleTownMatches(List<TownData> towns) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.length() < 2) return List.of();

        ArrayList<TownData> matches = new ArrayList<>();
        for (TownData town : towns) {
            String lowerName = town.name().toLowerCase(Locale.ROOT);
            if (lowerName.contains(needle)) matches.add(town);
        }
        matches.sort(Comparator
                .comparingInt((TownData t) -> score(t.name(), needle))
                .thenComparing(TownData::name, String.CASE_INSENSITIVE_ORDER));

        ArrayList<String> names = new ArrayList<>();
        int limit = Math.min(MAX_RESULTS, matches.size());
        for (int i = 0; i < limit; i++) names.add(matches.get(i).name());
        return List.copyOf(names);
    }

    public static List<String> visibleNationMatches(List<EarthMcNationData> apiNations) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.length() < 2) return List.of();

        ArrayList<EarthMcNationData> matches = new ArrayList<>();
        for (EarthMcNationData nation : apiNations) {
            if (nation.name().toLowerCase(Locale.ROOT).contains(needle)) matches.add(nation);
        }
        matches.sort(Comparator
                .comparingInt((EarthMcNationData n) -> score(n.name(), needle))
                .thenComparing(EarthMcNationData::name, String.CASE_INSENSITIVE_ORDER));

        ArrayList<String> names = new ArrayList<>();
        int limit = Math.min(MAX_RESULTS, matches.size());
        for (int i = 0; i < limit; i++) names.add(matches.get(i).name());
        return List.copyOf(names);
    }

    private static List<Result> results(List<TownData> towns, List<PlayerMarker> players,
                                        Map<String, TownPopupData> townDetails,
                                        List<EarthMcPlayerData> apiPlayers,
                                        Map<String, EarthMcPlayerData> playerDetails,
                                        Map<String, PlayerHistoryEntry> playerHistory,
                                        List<EarthMcNationData> apiNations,
                                        Map<String, EarthMcNationData> nationDetails) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return List.of();
        if (needle.equals(cachedNeedle)
                && cachedTownCount == towns.size()
                && cachedTownDetailCount == townDetails.size()
                && cachedMarkerCount == players.size()
                && cachedApiPlayerCount == apiPlayers.size()
                && cachedPlayerDetailCount == playerDetails.size()
                && cachedPlayerHistoryCount == playerHistory.size()
                && cachedNationCount == apiNations.size()
                && cachedNationDetailCount == nationDetails.size()) {
            return cachedResults;
        }

        Comparator<Result> byScore = Comparator
                .comparingInt(Result::score)
                .thenComparing(Result::label, String.CASE_INSENSITIVE_ORDER);
        Map<String, TownData> townIndex = new HashMap<>(Math.max(16, towns.size() * 2));
        for (TownData town : towns) {
            townIndex.put(town.name().toLowerCase(Locale.ROOT), town);
        }
        Map<String, PlayerMarker> markerIndex = new HashMap<>(Math.max(16, players.size() * 2));
        for (PlayerMarker player : players) {
            markerIndex.put(player.name().toLowerCase(Locale.ROOT), player);
        }
        Set<String> apiPlayerNames = new HashSet<>(Math.max(16, apiPlayers.size() * 2));
        for (EarthMcPlayerData player : apiPlayers) {
            apiPlayerNames.add(player.name().toLowerCase(Locale.ROOT));
        }
        Set<String> playerDetailNames = new HashSet<>(Math.max(16, playerDetails.size() * 2));
        for (EarthMcPlayerData player : playerDetails.values()) {
            playerDetailNames.add(player.name().toLowerCase(Locale.ROOT));
        }

        // ── Towns ────────────────────────────────────────────────────────────
        ArrayList<Result> townMatches = new ArrayList<>();
        for (TownData town : towns) {
            String lowerName = town.name().toLowerCase(Locale.ROOT);
            if (lowerName.contains(needle)) {
                townMatches.add(new Result("Town: " + town.name(),
                        new MapJumpTarget(town.name(), town.centerX(), town.centerZ()),
                        score(town.name(), needle), "town", town.name()));
            }
        }
        townMatches.sort(byScore);
        if (townMatches.size() > MAX_PER_TYPE) townMatches.subList(MAX_PER_TYPE, townMatches.size()).clear();

        // ── Nations ──────────────────────────────────────────────────────────
        ArrayList<Result> nationMatches = new ArrayList<>();
        for (EarthMcNationData nation : apiNations) {
            String lowerName = nation.name().toLowerCase(Locale.ROOT);
            if (!lowerName.contains(needle)) continue;

            EarthMcNationData details = nationDetails.get(lowerName);
            String suffix = details == null ? "Checking" : capitalLabel(details);
            nationMatches.add(new Result("Nation: " + nation.name() + " (" + suffix + ")",
                    nationTarget(nation.name(), details, townIndex),
                    score(nation.name(), needle), "nation", nation.name()));
        }
        nationMatches.sort(byScore);
        if (nationMatches.size() > MAX_PER_TYPE) nationMatches.subList(MAX_PER_TYPE, nationMatches.size()).clear();

        // ── Players ──────────────────────────────────────────────────────────
        ArrayList<Result> playerMatches = new ArrayList<>();
        for (EarthMcPlayerData player : apiPlayers) {
            String lowerName = player.name().toLowerCase(Locale.ROOT);
            if (!lowerName.contains(needle)) continue;

            PlayerMarker marker = markerIndex.get(lowerName);
            EarthMcPlayerData details = playerDetails.get(lowerName);
            String status = playerStatus(details, marker);
            PlayerHistoryEntry history = playerHistory.get(lowerName);
            MapJumpTarget target = playerTarget(player.name(), marker, details, history, townIndex);
            playerMatches.add(new Result("Player: " + player.name() + " (" + status + ")",
                    target, score(player.name(), needle), "player", player.name()));
        }
        for (PlayerMarker player : players) {
            String lowerName = player.name().toLowerCase(Locale.ROOT);
            if (lowerName.contains(needle)
                    && !apiPlayerNames.contains(lowerName)) {
                playerMatches.add(new Result("Player: " + player.name() + " (Online)",
                        new MapJumpTarget(player.name(), player.x(), player.z()),
                        score(player.name(), needle), "player", player.name()));
            }
        }
        for (EarthMcPlayerData player : playerDetails.values()) {
            String lowerName = player.name().toLowerCase(Locale.ROOT);
            if (!lowerName.contains(needle)) continue;
            if (apiPlayerNames.contains(lowerName)) continue;
            if (markerIndex.containsKey(lowerName)) continue;
            PlayerMarker marker = markerIndex.get(lowerName);
            PlayerHistoryEntry history = playerHistory.get(lowerName);
            playerMatches.add(new Result("Player: " + player.name() + " (" + playerStatus(player, marker) + ")",
                    playerTarget(player.name(), marker, player, history, townIndex),
                    score(player.name(), needle), "player", player.name()));
        }
        for (PlayerHistoryEntry history : playerHistory.values()) {
            String lowerName = history.name().toLowerCase(Locale.ROOT);
            if (!lowerName.contains(needle)) continue;
            if (apiPlayerNames.contains(lowerName)) continue;
            if (markerIndex.containsKey(lowerName)) continue;
            if (playerDetailNames.contains(lowerName)) continue;
            playerMatches.add(new Result("Player: " + history.name() + " (Last seen)",
                    new MapJumpTarget(history.name(), history.x(), history.z()),
                    score(history.name(), needle), "player", history.name()));
        }
        String exact = exactPlayerQuery();
        String exactKey = exact.toLowerCase(Locale.ROOT);
        if (!exact.isBlank()
                && playerMatches.stream().noneMatch(r -> r.name().equalsIgnoreCase(exact))
                && !apiPlayerNames.contains(exactKey)
                && !markerIndex.containsKey(exactKey)) {
            playerMatches.add(new Result("Player: " + exact + " (Checking)",
                    null, score(exact, needle), "player", exact));
        }
        playerMatches.sort(byScore);
        if (playerMatches.size() > MAX_PER_TYPE) playerMatches.subList(MAX_PER_TYPE, playerMatches.size()).clear();

        // ── Combine: interleave by rank so each type gets fair representation ─
        ArrayList<Result> combined = new ArrayList<>(townMatches.size() + nationMatches.size() + playerMatches.size());
        int ti = 0, ni = 0, pi = 0;
        while (combined.size() < MAX_RESULTS) {
            boolean added = false;
            if (ti < townMatches.size())   { combined.add(townMatches.get(ti++));   added = true; }
            if (combined.size() < MAX_RESULTS && ni < nationMatches.size())  { combined.add(nationMatches.get(ni++));  added = true; }
            if (combined.size() < MAX_RESULTS && pi < playerMatches.size())  { combined.add(playerMatches.get(pi++));  added = true; }
            if (!added) break;
        }
        List<Result> result = List.copyOf(combined);
        cachedNeedle = needle;
        cachedTownCount = towns.size();
        cachedTownDetailCount = townDetails.size();
        cachedMarkerCount = players.size();
        cachedApiPlayerCount = apiPlayers.size();
        cachedPlayerDetailCount = playerDetails.size();
        cachedPlayerHistoryCount = playerHistory.size();
        cachedNationCount = apiNations.size();
        cachedNationDetailCount = nationDetails.size();
        cachedResults = result;
        return result;
    }

    private static void invalidateResults() {
        cachedNeedle = null;
        cachedResults = List.of();
    }

    private static int score(String name, String needle) {
        if (needle.isEmpty()) return 0;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals(needle)) return 0;
        if (lower.startsWith(needle)) return 1;
        return 2 + lower.indexOf(needle);
    }

    private static PlayerMarker visibleMarker(String name, List<PlayerMarker> players) {
        for (PlayerMarker marker : players) {
            if (marker.name().equalsIgnoreCase(name)) return marker;
        }
        return null;
    }

    private static String playerStatus(EarthMcPlayerData details, PlayerMarker marker) {
        if (marker != null) return "Online";
        if (details == null) return "Checking";
        if (details.online()) return "Hidden";
        return "Offline";
    }

    private static MapJumpTarget playerTarget(String name, PlayerMarker marker,
                                              EarthMcPlayerData details, PlayerHistoryEntry history,
                                              Map<String, TownData> townIndex) {
        if (marker != null) return new MapJumpTarget(name, marker.x(), marker.z());
        if (history != null) return new MapJumpTarget(name, history.x(), history.z());
        if (details != null && !details.townName().isBlank()) {
            TownData town = townIndex.get(details.townName().toLowerCase(Locale.ROOT));
            if (town != null) {
                return new MapJumpTarget(name, town.centerX(), town.centerZ());
            }
        }
        return null;
    }

    private static MapJumpTarget nationTarget(String name, EarthMcNationData details, Map<String, TownData> townIndex) {
        if (details == null) return null;
        if (!details.capitalName().isBlank()) {
            TownData town = townIndex.get(details.capitalName().toLowerCase(Locale.ROOT));
            if (town != null) {
                return new MapJumpTarget(name, town.centerX(), town.centerZ());
            }
        }
        if (details.hasSpawn()) return new MapJumpTarget(name, details.spawnX(), details.spawnZ());
        return null;
    }

    private static String capitalLabel(EarthMcNationData details) {
        if (details == null || details.capitalName().isBlank()) return "No capital";
        return "Capital: " + details.capitalName();
    }

    private static void select(Result result) {
        selectedType = result.type();
        selectedName = result.name();
    }

    private static void clearSelection() {
        selectedType = "";
        selectedName = "";
    }

    private static void renderSelectedInfo(DrawContext ctx, TextRenderer tr, int sw, int sh,
                                           List<TownData> towns, List<PlayerMarker> players,
                                           Map<String, TownPopupData> townDetails,
                                           Map<String, EarthMcPlayerData> playerDetails,
                                           Map<String, PlayerHistoryEntry> playerHistory,
                                           Map<String, EarthMcNationData> nationDetails) {
        if (selectedName.isBlank()) return;
        List<String> lines = selectedInfo(towns, players, townDetails, playerDetails,
                playerHistory, nationDetails);
        if (lines.isEmpty()) return;
        if (lines.size() > MAX_INFO_LINES) {
            lines = new ArrayList<>(lines.subList(0, MAX_INFO_LINES));
        }

        int maxW = 0;
        for (String line : lines) maxW = Math.max(maxW, tr.getWidth(line));
        int boxW = Math.min(Math.max(WIDTH, maxW + 16), Math.max(WIDTH, sw - 24));
        int boxH = lines.size() * 12 + 14;
        int x = Math.max(8, sw - boxW - 12);
        int y = Math.max(36, Math.min(sh - boxH - 36, sh / 2 - boxH / 2));

        ctx.fill(x - 1, y - 1, x + boxW + 1, y + boxH + 1, BORDER);
        ctx.fill(x, y, x + boxW, y + boxH, BG);

        int ty = y + 7;
        for (String line : lines) {
            ctx.drawText(tr, trimToWidth(tr, line, boxW - 14), x + 7, ty, 0xFFFFFFFF, true);
            ty += 12;
        }
    }

    private static List<String> selectedInfo(List<TownData> towns, List<PlayerMarker> players,
                                             Map<String, TownPopupData> townDetails,
                                             Map<String, EarthMcPlayerData> playerDetails,
                                             Map<String, PlayerHistoryEntry> playerHistory,
                                             Map<String, EarthMcNationData> nationDetails) {
        if ("town".equals(selectedType)) {
            for (TownData town : towns) {
                if (town.name().equalsIgnoreCase(selectedName)) {
                    TownPopupData details = townDetails.get(selectedName.toLowerCase(Locale.ROOT));
                    return townInfo(town, details, nationDetails);
                }
            }
            return List.of();
        }
        if ("nation".equals(selectedType)) {
            EarthMcNationData details = nationDetails.get(selectedName.toLowerCase(Locale.ROOT));
            ArrayList<String> lines = new ArrayList<>();
            lines.add("§f§lNation: " + selectedName);
            if (details == null) {
                lines.add("§7Details: §fChecking...");
                return List.copyOf(lines);
            }
            if (!details.capitalName().isBlank()) lines.add("§7Capital: §f" + details.capitalName());
            if (!details.kingName().isBlank()) lines.add("§7King: §f" + details.kingName());
            if (!details.founded().isBlank()) lines.add("§7Founded: §f" + details.founded());
            if (details.townCount() > 0) lines.add("§7Towns: §f" + details.townCount());
            if (details.residentCount() > 0) {
                lines.add("§7Residents: §f" + details.residentCount()
                        + " §7(Bonus: §f" + nationBonus(details.residentCount()) + "§7)");
            }
            if (details.chunkCount() > 0) lines.add("§7Chunks: §f" + details.chunkCount());
            lines.add("§7Gold: §f" + formatGold(details.balance()));
            if (details.outlawCount() > 0) lines.add("§7Outlaws: §f" + details.outlawCount());
            if (details.enemyCount() > 0) lines.add("§7Enemies: §f" + details.enemyCount());
            return List.copyOf(lines);
        }
        if (!"player".equals(selectedType)) return List.of();

        PlayerMarker marker = visibleMarker(selectedName, players);
        EarthMcPlayerData details = playerDetails.get(selectedName.toLowerCase(Locale.ROOT));
        PlayerHistoryEntry history = playerHistory.get(selectedName.toLowerCase(Locale.ROOT));
        String status = playerStatus(details, marker);

        ArrayList<String> lines = new ArrayList<>();
        lines.add("§f§lPlayer: " + selectedName);
        lines.add("§7Status: §f" + status);
        if (marker != null) lines.add("§7Location: §f" + marker.x() + ", " + marker.z());
        else if (history != null) {
            lines.add("§7Last seen: §f" + history.x() + ", " + history.z());
            lines.add("§7Seen: §f" + ageLabel(history.lastSeenMs()));
        }
        if (details == null) {
            lines.add("§7Details: §fChecking...");
            return List.copyOf(lines);
        }
        if (!details.townName().isBlank()) lines.add("§7Town: §f" + details.townName());
        if (!details.nationName().isBlank()) lines.add("§7Nation: §f" + details.nationName());
        if (details.king()) {
            lines.add("§7Role: §fKing");
        } else if (details.mayor()) {
            lines.add("§7Role: §fMayor");
        }
        if (!details.lastOnline().isBlank() && marker == null) {
            lines.add("§7Last online: §f" + details.lastOnline());
        }
        return List.copyOf(lines);
    }

    private static List<String> townInfo(TownData town, TownPopupData details,
                                         Map<String, EarthMcNationData> nationDetails) {
        ArrayList<String> lines = new ArrayList<>();
        lines.add("§f§lTown: " + town.name());
        if (details == null) {
            lines.add("§7Chunks: §f" + town.approximateChunks());
            lines.add("§7Details: §fChecking...");
            return List.copyOf(lines);
        }
        if (!details.nationName().isBlank()) lines.add("§7Nation: §f" + details.nationName());
        if (!details.mayor().isBlank()) lines.add("§7Mayor: §f" + details.mayor());
        int possibleChunks = possibleTownChunks(details, nationDetails);
        String sizeColor = details.isOverClaimed() ? "§c" : "§f";
        lines.add("§7Chunks: " + sizeColor + details.numChunks() + " / " + possibleChunks);
        if (!details.founded().isBlank()) lines.add("§7Founded: §f" + details.founded());
        lines.add("§7Residents: §f" + details.residentCount());
        lines.add("§7Gold: §f" + formatGold(details.balance()));
        lines.add("§7Open: §f" + yesNo(details.isOpen()));
        lines.add("§7Public: §f" + yesNo(details.isPublic()));
        return List.copyOf(lines);
    }

    private static String yesNo(boolean value) {
        return value ? "Yes" : "No";
    }

    private static String formatGold(double gold) {
        return Long.toString(Math.round(gold));
    }

    private static int nationBonus(int residents) {
        if (residents >= 200) return 100;
        if (residents >= 120) return 80;
        if (residents >= 80) return 60;
        if (residents >= 60) return 50;
        if (residents >= 40) return 30;
        if (residents >= 20) return 10;
        return 0;
    }

    private static int possibleTownChunks(TownPopupData town, Map<String, EarthMcNationData> nationDetails) {
        int residentChunks = Math.max(0, town.residentCount()) * 12;
        if (town.nationName().isBlank()) return residentChunks;
        EarthMcNationData nation = nationDetails.get(town.nationName().toLowerCase(Locale.ROOT));
        return nation == null ? residentChunks : residentChunks + nationBonus(nation.residentCount());
    }

    private static String ageLabel(long timestampMs) {
        long seconds = Math.max(0, (System.currentTimeMillis() - timestampMs) / 1000);
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 48) return hours + "h ago";
        return (hours / 24) + "d ago";
    }

    private static void renderFavorites(DrawContext ctx, TextRenderer tr, int x, int y, int sw,
                                        List<TownData> towns, List<String> favoriteTowns) {
        String label = "Favorites";
        ButtonWidget button = ButtonWidget.builder(coloredText(label, 0xFFFFFF), ignored -> {})
                .dimensions(x, y, FAVORITES_WIDTH, ROW_HEIGHT)
                .build();
        button.render(ctx, scaledMouseX(), scaledMouseY(), 0.0F);
        if (!favoritesOpen) return;

        List<TownData> favorites = favoriteTowns(towns, favoriteTowns);
        int rows = Math.min(MAX_RESULTS, favorites.size());
        for (int i = 0; i < rows; i++) {
            TownData town = favorites.get(i);
            int ty = resultRowY(y, i);
            ctx.fill(x - 1, ty - 1, x + FAVORITES_WIDTH + 1, ty + ROW_HEIGHT + 1, BORDER);
            ctx.fill(x, ty, x + FAVORITES_WIDTH, ty + ROW_HEIGHT, BG);
            ctx.drawText(tr, trimToWidth(tr, town.name(), FAVORITES_WIDTH - 14), x + 7, ty + 5, 0xFFFFFFFF, true);
        }
        if (favorites.isEmpty()) {
            int rowY = resultRowY(y, 0);
            ctx.fill(x - 1, rowY - 1, x + FAVORITES_WIDTH + 1, rowY + ROW_HEIGHT + 1, BORDER);
            ctx.fill(x, rowY, x + FAVORITES_WIDTH, rowY + ROW_HEIGHT, BG);
            ctx.drawText(tr, trimToWidth(tr, "No favorites", FAVORITES_WIDTH - 14), x + 7, rowY + 5, 0xFFAAAAAA, true);
        }
    }

    private static ClickResult favoriteClick(double mouseX, double mouseY, int x, int y,
                                             List<TownData> towns, List<String> favoriteTowns) {
        if (inside(mouseX, mouseY, x, y, FAVORITES_WIDTH, ROW_HEIGHT)) {
            favoritesOpen = !favoritesOpen;
            focused = false;
            return ClickResult.consumedResult();
        }

        if (favoritesOpen) {
            List<TownData> favorites = favoriteTowns(towns, favoriteTowns);
            int rows = Math.min(MAX_RESULTS, favorites.size());
            for (int i = 0; i < rows; i++) {
                int ty = resultRowY(y, i);
                if (inside(mouseX, mouseY, x, ty, FAVORITES_WIDTH, ROW_HEIGHT)) {
                    TownData town = favorites.get(i);
                    favoritesOpen = false;
                    focused = false;
                    return ClickResult.jump(town);
                }
            }
        }
        return ClickResult.none();
    }

    private static List<TownData> favoriteTowns(List<TownData> towns, List<String> favoriteNames) {
        ArrayList<TownData> favorites = new ArrayList<>();
        for (String name : favoriteNames) {
            TownData town = townByName(towns, name);
            if (town != null) favorites.add(town);
        }
        favorites.sort(Comparator.comparing(TownData::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(favorites);
    }

    private static TownData townByName(List<TownData> towns, String name) {
        for (TownData town : towns) {
            if (town.name().equalsIgnoreCase(name)) return town;
        }
        return null;
    }

    private static String trimToWidth(TextRenderer tr, String text, int width) {
        if (tr.getWidth(text) <= width) return text;
        String ellipsis = "...";
        int max = Math.max(1, width - tr.getWidth(ellipsis));
        return tr.trimToWidth(text, max) + ellipsis;
    }

    private static Text coloredText(String label, int textColor) {
        return Text.literal(label).setStyle(Style.EMPTY.withColor(textColor & 0xFFFFFF));
    }

    private static int scaledMouseX() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return (int) (mc.mouse.getX() * mc.getWindow().getScaledWidth() / mc.getWindow().getWidth());
    }

    private static int scaledMouseY() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return (int) (mc.mouse.getY() * mc.getWindow().getScaledHeight() / mc.getWindow().getHeight());
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
    }

    private static int left(int sw) {
        return Math.max(8, sw / 2 - WIDTH / 2);
    }

    private static int top() {
        MinecraftClient mc = MinecraftClient.getInstance();
        int sh = mc == null ? 360 : mc.getWindow().getScaledHeight();
        return Math.max(36, sh - ROW_HEIGHT - 18);
    }

    private static int favoritesX(int searchX) {
        return Math.max(8, searchX - FAVORITES_WIDTH - 7);
    }

    private static int resultRowY(int searchY, int index) {
        return searchY - 3 - (index + 1) * ROW_HEIGHT;
    }

    private record Result(String label, MapJumpTarget target, int score, String type, String name) {}

    public record ClickResult(boolean consumed, MapJumpTarget target) {
        public static ClickResult none() {
            return new ClickResult(false, null);
        }

        public static ClickResult consumedResult() {
            return new ClickResult(true, null);
        }

        public static ClickResult jump(TownData town) {
            return new ClickResult(true, new MapJumpTarget(town.name(), town.centerX(), town.centerZ()));
        }

        public static ClickResult jump(MapJumpTarget target) {
            return new ClickResult(true, target);
        }
    }
}
