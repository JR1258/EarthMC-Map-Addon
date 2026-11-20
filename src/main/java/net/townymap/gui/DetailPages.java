package net.townymap.gui;

import net.townymap.gui.DetailScreen.Chips;
import net.townymap.gui.DetailScreen.Col;
import net.townymap.gui.DetailScreen.Cols;
import net.townymap.gui.DetailScreen.Kind;
import net.townymap.gui.DetailScreen.LineList;
import net.townymap.gui.DetailScreen.NameList;
import net.townymap.gui.DetailScreen.Page;
import net.townymap.gui.DetailScreen.Ref;
import net.townymap.gui.DetailScreen.Rule;
import net.townymap.api.ArchiveClient;
import net.townymap.model.EarthMcNationData;
import net.townymap.model.NationFullData;
import net.townymap.model.PlayerFullData;
import net.townymap.model.TownData;
import net.townymap.model.TownFullData;
import net.townymap.TownyMapMod;
import net.townymap.util.DiscordUrl;

import java.util.Locale;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds the block list for each kind of expanded panel. Layout lives in {@link DetailScreen}. */
public final class DetailPages {

    private DetailPages() {}

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault());

    private static String date(long ms) {
        return ms > 0 ? DATE.format(Instant.ofEpochMilli(ms)) : "—";
    }

    /** "3 months ago" / "4 minutes ago" — the relative form the Discord embeds use. */
    private static String ago(long ms) {
        if (ms <= 0) return "—";
        Duration d = Duration.between(Instant.ofEpochMilli(ms), Instant.now());
        long days = d.toDays();
        if (days >= 365) { long y = days / 365; return y + (y == 1 ? " year ago" : " years ago"); }
        if (days >= 30)  { long m = days / 30;  return m + (m == 1 ? " month ago" : " months ago"); }
        if (days >= 1)   return days + (days == 1 ? " day ago" : " days ago");
        long h = d.toHours();
        if (h >= 1) return h + (h == 1 ? " hour ago" : " hours ago");
        long m = Math.max(1, d.toMinutes());
        return m + (m == 1 ? " minute ago" : " minutes ago");
    }

    /** Rough human duration for a pact term. */
    private static String humanDuration(long ms) {
        long days = Duration.ofMillis(ms).toDays();
        if (days >= 365) { long y = days / 365; return y + (y == 1 ? " year" : " years"); }
        if (days >= 30)  { long m = days / 30;  return m + (m == 1 ? " month" : " months"); }
        if (days >= 1)   return days + (days == 1 ? " day" : " days");
        long h = Math.max(1, Duration.ofMillis(ms).toHours());
        return h + (h == 1 ? " hour" : " hours");
    }

    private static String money(double v) {
        return (v == Math.rint(v) ? String.valueOf((long) v) : String.format("%.2f", v)) + "g";
    }

    private static List<Ref> refs(List<String> names, Kind kind) {
        List<Ref> out = new ArrayList<>(names.size());
        for (String n : names) out.add(new Ref(kind, n));
        return out;
    }

    private static void addNames(List<DetailScreen.Block> blocks, String title, List<String> names, Kind kind) {
        if (names != null && !names.isEmpty()) blocks.add(new NameList(title, refs(names, kind)));
    }

    /**
     * Adds "Meganation" / "Alliances" rows for a nation, if it belongs to any (from the alliance roster).
     * Each bloc is a link through to its own panel, so a nation's memberships are explorable rather than
     * being a dead end of text.
     */
    private static void addAllianceBlocks(List<DetailScreen.Block> b, String nation) {
        if (nation == null || nation.isBlank()) return;
        addBlocRefs(b, TownyMapMod.meganationsForNation(nation), "Meganation", "Meganations");
        addBlocRefs(b, TownyMapMod.alliancesForNation(nation), "Alliance", "Alliances");
    }

    private static void addBlocRefs(List<DetailScreen.Block> b, List<String> names,
                                    String singular, String plural) {
        if (names.isEmpty()) return;
        List<DetailScreen.RefLine> lines = new ArrayList<>(names.size());
        for (String name : names) {
            net.townymap.api.AllianceClient.Alliance a = TownyMapMod.allianceByName(name);
            String suffix = a == null ? "" : "  " + a.nations().size() + " nations";
            lines.add(new DetailScreen.RefLine(new Ref(Kind.ALLIANCE, name), suffix));
        }
        b.add(new DetailScreen.RefLineList(names.size() > 1 ? plural : singular, lines));
    }

    // ── Alliance / meganation ─────────────────────────────────────────────────

    /**
     * The Expand panel for an alliance or meganation. Everything here comes from data already in memory —
     * the BreakTheBot roster plus the town list the map is drawing — so opening it costs no requests.
     */
    public static Page alliance(net.townymap.api.AllianceClient.Alliance a) {
        List<DetailScreen.Block> b = new ArrayList<>();
        List<String> nations = a.nations();

        // Roll the bloc's totals up from the towns already on the map, so they're complete even when the
        // per-nation detail fetches haven't caught up.
        int towns = 0;
        int chunks = 0;
        for (TownData t : TownyMapMod.currentTowns()) {
            String n = TownyMapMod.townNationOf(t.key());
            if (n == null || !a.nationsLower().contains(n.toLowerCase(Locale.ROOT))) continue;
            towns++;
            chunks += t.approximateChunks();
        }
        // Residents only exist in the nation records, which warm in the background; sum what has arrived.
        int residents = 0;
        boolean partial = false;
        for (String n : nations) {
            EarthMcNationData nd = TownyMapMod.nationDetails(n);
            if (nd == null) { partial = true; continue; }
            residents += nd.residentCount();
        }

        b.add(new Cols(List.of(
                new Col("Type", a.mega() ? "Meganation" : "Alliance", null),
                new Col("Tag", orDash(a.identifier()), null),
                new Col("Nations", String.valueOf(nations.size()), null))));
        b.add(new Cols(List.of(
                new Col("Towns", towns > 0 ? String.valueOf(towns) : "—", null),
                new Col("Chunks", chunks > 0 ? String.format("%,d", chunks) : "—", null),
                new Col("Residents", residents > 0 ? String.format("%,d", residents)
                        + (partial ? "+" : "") : "—", null))));
        b.add(new DetailScreen.Rule());

        List<DetailScreen.RefLine> lines = new ArrayList<>(nations.size());
        for (String n : nations) {
            EarthMcNationData nd = TownyMapMod.nationDetails(n);
            String suffix = nd == null ? "" : "  " + nd.townCount() + " towns";
            lines.add(new DetailScreen.RefLine(new Ref(Kind.NATION, n), suffix));
        }
        b.add(new DetailScreen.RefLineList("Members", lines));

        return new Page(Kind.ALLIANCE, a.label() == null || a.label().isBlank() ? a.identifier() : a.label(),
                (a.mega() ? "Meganation" : "Alliance") + " · " + nations.size() + " nations",
                b, "", "");
    }

    // ── Town ──────────────────────────────────────────────────────────────────

    /**
     * Leaderboards built entirely from the claim data already in memory -- no EarthMC API calls, so this
     * opens instantly, works while the API is down, and reflects an archive snapshot when one is loaded
     * (getTowns() returns the archive list in that case, which is the behaviour these views want).
     *
     * <p>Every entry goes through addNames, so the names stay clickable and lead to the normal town or
     * nation page. That is the whole point of putting stats in this panel rather than a separate screen.
     */
    /** The info panel's landing tab: a short overview, with the detail left to the Statistics tab. */
    public static Page dashboard() {
        List<DetailScreen.Block> b = new ArrayList<>();
        net.townymap.api.SquaremapApiClient api = TownyMapMod.getApiClient();
        List<net.townymap.model.TownData> towns = api == null ? List.of() : api.getTowns();

        int residents = 0;
        Map<String, Integer> nations = new java.util.HashMap<>();
        for (net.townymap.model.TownData t : towns) {
            residents += api.getTownResidents(t.key());
            String n = api.getTownNation(t.key());
            if (n != null && !n.isBlank()) nations.merge(n, 1, Integer::sum);
        }

        // Your own town and nation first -- the one thing here nobody else's dashboard would show.
        net.townymap.model.EarthMcPlayerData self = TownyMapMod.selfPlayer();
        if (self != null && self.townName() != null && !self.townName().isBlank()) {
            String myTown = self.townName();
            net.townymap.model.TownData mine = null;
            for (net.townymap.model.TownData t : towns) {
                if (t.name().equalsIgnoreCase(myTown)) { mine = t; break; }
            }
            b.add(new Cols(List.of(
                    new Col("Your town", myTown, new Ref(Kind.TOWN, myTown)),
                    new Col("Nation", self.nationName() == null || self.nationName().isBlank()
                            ? "-" : self.nationName(),
                            self.nationName() == null || self.nationName().isBlank()
                                    ? null : new Ref(Kind.NATION, self.nationName())),
                    new Col("Your balance", money(self.balance())))));
            net.townymap.model.TownFullData full = TownyMapMod.selfTownFull();
            if (full != null) {
                // Identical to the right-click town page (DetailPages.town): EarthMC's own claimed/max,
                // which already accounts for the nation bonus and any server-side overrides. Deriving it
                // here from residents x 12 gave a different number to the rest of the mod.
                b.add(new Cols(List.of(
                        new Col("Residents", String.valueOf(full.residents() == null ? 0 : full.residents().size())),
                        new Col("Chunks", full.numTownBlocks() + " / "
                                + (full.maxTownBlocks() >= 0 ? full.maxTownBlocks() : "?")),
                        new Col("Can still claim", full.maxTownBlocks() >= 0
                                ? String.valueOf(Math.max(0, full.maxTownBlocks() - full.numTownBlocks()))
                                : "?"))));
            }
            b.add(new Rule());
        }

        b.add(new Cols(List.of(
                new Col("Towns", String.valueOf(towns.size())),
                new Col("Nations", String.valueOf(nations.size())),
                new Col("Residents", String.valueOf(residents)))));
        // Live so the age keeps counting up while the panel stays open.
        b.add(new Cols(List.of(Col.live("Claim data", () -> TownyMapMod.mapDataStatus().text()))));
        b.add(new Rule());

        // favoriteTownKeys() holds lower-cased keys; resolve them back to the town's real casing so the
        // list reads like the rest of the panel. Falls back to the key if the town is not loaded.
        java.util.Set<String> favKeys = TownyMapMod.favoriteTownKeys();
        List<String> favourites = new ArrayList<>();
        if (!favKeys.isEmpty()) {
            Map<String, String> byKey = new java.util.HashMap<>();
            for (net.townymap.model.TownData t : towns) byKey.put(t.key(), t.name());
            for (String k : favKeys) favourites.add(byKey.getOrDefault(k, k));
            favourites.sort(String.CASE_INSENSITIVE_ORDER);
        }
        addNames(b, "Favourites", favourites, Kind.TOWN);

        String subtitle = api != null && api.isArchiveActive()
                ? "From the loaded archive snapshot" : "Live from squaremap";
        return new Page(Kind.STATS, "Info Panel", subtitle, b, null, null);
    }

    /** Chunks a town claims: shoelace area of its rings (first = outer, rest = holes) over 16x16. */
    private static int chunkCount(net.townymap.model.TownData t) {
        double area = 0;
        List<int[][]> rings = t.polygonRings();
        for (int r = 0; r < rings.size(); r++) {
            int[][] ring = rings.get(r);
            double a = 0;
            for (int i = 0, n = ring.length; i < n; i++) {
                int[] p1 = ring[i], p2 = ring[(i + 1) % n];
                if (p1.length < 2 || p2.length < 2) continue;
                a += (double) p1[0] * p2[1] - (double) p2[0] * p1[1];
            }
            a = Math.abs(a) / 2.0;
            area += (r == 0) ? a : -a;   // rings after the first are unclaimed pockets
        }
        return (int) Math.round(Math.max(0, area) / 256.0);
    }

    public static Page stats() { return stats(0, 0); }
    public static Page stats(int sub) { return stats(sub, 0); }

    /** Filter labels per sub-tab, mirrored by DetailScreen so the strip and the data agree. */
    public static String[] filtersFor(int sub) {
        return switch (sub) {
            case 1 -> new String[]{ "Towns", "Residents", "Chunks", "Gold", "Outlaws", "Founded" };
            case 2 -> new String[]{ "Gold", "Joined", "Friends", "Outlawed", "Trusted" };
            default -> new String[]{ "Residents", "Chunks", "Gold", "Outlaws", "Founded" };
        };
    }

    /** How deep the ranked lists go. The panel scrolls, so this is about usefulness, not fitting. */
    private static final int RANK_DEPTH = 100;

    /**
     * A ranked list: one row per entry, "12. Name" with the value on the right, name clickable.
     * RefLineList renders a flat list rather than the collapsible dropdown addNames produces, which is
     * what makes this read as a leaderboard instead of a folded-up roster.
     */
    private static void rankList(List<DetailScreen.Block> blocks, String title,
                                 List<String[]> rows, String unit, Kind kind) {
        List<DetailScreen.RefLine> lines = new ArrayList<>();
        for (int i = 0; i < rows.size() && i < RANK_DEPTH; i++) {
            String[] row = rows.get(i);
            // Rank is drawn by the renderer from the row index; the suffix carries only the value, so
            // the numbers right-align into a readable column.
            lines.add(new DetailScreen.RefLine(new Ref(kind, row[0]),
                    row[1] + (unit.isEmpty() || row[1].isEmpty() ? "" : " " + unit)));
        }
        if (!lines.isEmpty()) blocks.add(new DetailScreen.RefLineList(title, lines, true));
    }

    /** Map to descending {name, value} rows. */
    private static List<String[]> rowsOf(Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> e = new ArrayList<>(counts.entrySet());
        e.sort((x, y) -> y.getValue() - x.getValue());
        List<String[]> out = new ArrayList<>();
        for (Map.Entry<String, Integer> en : e) out.add(new String[]{en.getKey(), String.valueOf(en.getValue())});
        return out;
    }

    /** sub: 0 = towns, 1 = nations, 2 = players. filter indexes filtersFor(sub). */
    public static Page stats(int sub, int filter) {
        List<DetailScreen.Block> b = new ArrayList<>();
        net.townymap.api.SquaremapApiClient api = TownyMapMod.getApiClient();
        List<net.townymap.model.TownData> towns = api == null ? List.of() : api.getTowns();
        // getTowns() only ever holds the world being shown, so off Earth these leaderboards silently rank
        // outposts alone. Say so rather than passing Moon-only totals off as server-wide ones.
        if (!TownyMapMod.viewingEarth()) {
            b.add(new DetailScreen.Wide("Scope", TownyMapMod.activeWorldName()
                    + " only - outpost claims, not whole towns"));
        }

        if (sub == 1) {
            Map<String, Integer> townCount = new java.util.HashMap<>();
            Map<String, Integer> residents = new java.util.HashMap<>();
            Map<String, Integer> chunks = new java.util.HashMap<>();
            for (net.townymap.model.TownData t : towns) {
                String n = api.getTownNation(t.key());
                if (n == null || n.isBlank()) continue;
                townCount.merge(n, 1, Integer::sum);
                residents.merge(n, api.getTownResidents(t.key()), Integer::sum);
                chunks.merge(n, chunkCount(t), Integer::sum);
            }
            b.add(new Cols(List.of(new Col("Nations", String.valueOf(townCount.size())))));
            b.add(new Rule());
            if (filter == 5) {
                // Oldest first. The index carries a formatted date string only, so sorting on it would
                // order alphabetically; foundedMs is the raw registration time the parser now keeps.
                List<net.townymap.model.EarthMcNationData> idx =
                        new ArrayList<>(TownyMapMod.nationStats().values());
                idx.removeIf(nd -> nd.foundedMs() <= 0);
                if (idx.isEmpty()) {
                    b.add(new Cols(List.of(new Col("Nations", "loading..."))));
                    return new Page(Kind.STATS, "Info Panel", "fetching the nation index", b, null, null);
                }
                idx.sort((x, y) -> Long.compare(x.foundedMs(), y.foundedMs()));
                List<String[]> rows = new ArrayList<>();
                for (var nd : idx) {
                    if (rows.size() >= RANK_DEPTH) break;
                    rows.add(new String[]{nd.name(), date(nd.foundedMs())});
                }
                b.add(new Cols(List.of(new Col("Nations", String.valueOf(idx.size())))));
                rankList(b, "Oldest nations", rows, "", Kind.NATION);
                return new Page(Kind.STATS, "Info Panel", "oldest first", b, null, null);
            }
            if (filter >= 3) {
                // Gold and outlaw counts come from the nation index -- one request for every nation,
                // already cached for the search bar, and archive-aware like everything else here.
                List<net.townymap.model.EarthMcNationData> idx =
                        new ArrayList<>(TownyMapMod.nationStats().values());
                if (idx.isEmpty()) {
                    b.add(new Cols(List.of(new Col("Nations", "loading..."))));
                    return new Page(Kind.STATS, "Info Panel", "fetching the nation index", b, null, null);
                }
                boolean gold = filter == 3;
                List<net.townymap.model.EarthMcNationData> sorted = new ArrayList<>(idx);
                sorted.sort(gold ? (x, y) -> Double.compare(y.balance(), x.balance())
                                 : (x, y) -> Integer.compare(y.outlawCount(), x.outlawCount()));
                List<String[]> rows = new ArrayList<>();
                for (net.townymap.model.EarthMcNationData nd : sorted) {
                    if (rows.size() >= RANK_DEPTH) break;
                    rows.add(new String[]{nd.name(),
                            gold ? money(nd.balance()) : String.valueOf(nd.outlawCount())});
                }
                b.add(new Cols(List.of(new Col("Nations", String.valueOf(idx.size())))));
                rankList(b, gold ? "Nations by gold" : "Nations by outlaws",
                        rows, gold ? "" : "outlaws", Kind.NATION);
                return new Page(Kind.STATS, "Info Panel", idx.size() + " nations", b, null, null);
            }
            Map<String, Integer> src = filter == 1 ? residents : filter == 2 ? chunks : townCount;
            String unit = filter == 1 ? "residents" : filter == 2 ? "chunks" : "towns";
            rankList(b, "Nations by " + unit, rowsOf(src), unit, Kind.NATION);
            return new Page(Kind.STATS, "Info Panel", townCount.size() + " nations", b, null, null);
        }

        if (sub == 2) {
            if (filter >= 3) {
                boolean outlawed = filter == 3;
                Map<String, int[]> counts = TownyMapMod.outlawTrustedCounts();
                if (counts.isEmpty()) {
                    b.add(new Cols(List.of(new Col("Players", "sweeping towns..."))));
                    return new Page(Kind.STATS, "Info Panel",
                            "reading every town roster, this one takes a moment", b, null, null);
                }
                List<String[]> rows = new ArrayList<>();
                List<Map.Entry<String, int[]>> es = new ArrayList<>(counts.entrySet());
                int slot = outlawed ? 0 : 1;
                es.sort((x, y) -> y.getValue()[slot] - x.getValue()[slot]);
                for (Map.Entry<String, int[]> e : es) {
                    if (rows.size() >= RANK_DEPTH) break;
                    if (e.getValue()[slot] <= 0) break;
                    rows.add(new String[]{e.getKey(), String.valueOf(e.getValue()[slot])});
                }
                b.add(new Cols(List.of(new Col("Players", String.valueOf(counts.size())))));
                rankList(b, outlawed ? "Outlawed in the most towns" : "Trusted in the most towns",
                        rows, "towns", Kind.PLAYER);
                return new Page(Kind.STATS, "Info Panel",
                        counts.size() + " players on town rosters", b, null, null);
            }
            java.util.Map<String, net.townymap.model.EarthMcPlayerData> stats =
                    TownyMapMod.allPlayerStats();
            if (stats.isEmpty()) {
                b.add(new Cols(List.of(new Col("Players", "loading..."))));
                return new Page(Kind.STATS, "Info Panel", "fetching online player data", b, null, null);
            }
            List<net.townymap.model.EarthMcPlayerData> sorted = new ArrayList<>(stats.values());
            if (filter == 1) sorted.sort((x, y) -> Long.compare(x.registeredMs(), y.registeredMs()));
            else if (filter == 2) sorted.sort((x, y) -> Integer.compare(y.friendCount(), x.friendCount()));
            else sorted.sort((x, y) -> Double.compare(y.balance(), x.balance()));

            List<String[]> rows = new ArrayList<>();
            for (net.townymap.model.EarthMcPlayerData pd : sorted) {
                if (rows.size() >= RANK_DEPTH) break;
                if (pd.name() == null || pd.name().isBlank() || pd.npc()) continue;
                // Drop players outside EarthMC's 42-day activity window from the value rankings: they
                // clutter the top of the gold list with balances nobody can move. Join date is a
                // historical fact, so that list keeps everyone.
                if (filter != 1 && !TownyMapMod.isRecentlyActive(pd)) continue;
                rows.add(new String[]{pd.name(), filter == 1 ? date(pd.registeredMs())
                        : filter == 2 ? String.valueOf(pd.friendCount())
                        : money(pd.balance())});
            }
            boolean full = TownyMapMod.playerSweepComplete();
            b.add(new Cols(List.of(new Col("Players", String.valueOf(stats.size())))));
            rankList(b, "Players by " + (filter == 1 ? "join date" : filter == 2 ? "friends" : "gold"),
                    rows, filter == 2 ? "friends" : "", Kind.PLAYER);
            return new Page(Kind.STATS, "Info Panel",
                    full ? stats.size() + " players" : "online players so far, full sweep running",
                    b, null, null);
        }

        int totalResidents = 0, totalChunks = 0;
        List<String[]> byResidents = new ArrayList<>();
        List<String[]> byChunks = new ArrayList<>();
        for (net.townymap.model.TownData t : towns) {
            int res = api.getTownResidents(t.key());
            int ch = chunkCount(t);
            totalResidents += res;
            totalChunks += ch;
            byResidents.add(new String[]{t.name(), String.valueOf(res)});
            byChunks.add(new String[]{t.name(), String.valueOf(ch)});
        }
        b.add(new Cols(List.of(
                new Col("Towns", String.valueOf(towns.size())),
                new Col("Residents", String.valueOf(totalResidents)),
                new Col("Chunks", String.valueOf(totalChunks)))));
        b.add(new Rule());
        byResidents.sort((x, y) -> Integer.parseInt(y[1]) - Integer.parseInt(x[1]));
        byChunks.sort((x, y) -> Integer.parseInt(y[1]) - Integer.parseInt(x[1]));
        if (filter >= 2) {
            // These three are the only leaderboards that need the town sweep, so it is triggered here
            // rather than warmed -- open one and it starts; never open them and it never runs.
            var ranks = TownyMapMod.townRanks();
            if (ranks.isEmpty()) {
                b.add(new Cols(List.of(new Col("Towns", "sweeping towns..."))));
                return new Page(Kind.STATS, "Info Panel",
                        "reading every town, this one takes a moment", b, null, null);
            }
            List<net.townymap.api.EarthMcApiClient.TownRank> list = new ArrayList<>(ranks.values());
            String title, unit;
            if (filter == 2) {
                list.sort((x, y) -> Double.compare(y.balance(), x.balance()));
                title = "Richest towns"; unit = "";
            } else if (filter == 3) {
                list.sort((x, y) -> Integer.compare(y.outlaws(), x.outlaws()));
                title = "Towns by outlaws"; unit = "outlaws";
            } else {
                list.removeIf(r -> r.foundedMs() <= 0);
                list.sort((x, y) -> Long.compare(x.foundedMs(), y.foundedMs()));
                title = "Oldest towns"; unit = "";
            }
            List<String[]> rows = new ArrayList<>();
            for (var r : list) {
                if (rows.size() >= RANK_DEPTH) break;
                if (filter == 3 && r.outlaws() <= 0) break;
                rows.add(new String[]{r.name(), filter == 2 ? money(r.balance())
                        : filter == 3 ? String.valueOf(r.outlaws())
                        : date(r.foundedMs())});
            }
            b.add(new Cols(List.of(new Col("Towns", String.valueOf(ranks.size())))));
            rankList(b, title, rows, unit, Kind.TOWN);
            return new Page(Kind.STATS, "Info Panel", ranks.size() + " towns", b, null, null);
        }

        rankList(b, filter == 1 ? "Towns by chunks" : "Towns by residents",
                filter == 1 ? byChunks : byResidents,
                filter == 1 ? "chunks" : "residents", Kind.TOWN);

        String subtitle = api != null && api.isArchiveActive()
                ? "From the loaded archive snapshot" : towns.size() + " towns";
        return new Page(Kind.STATS, "Info Panel", subtitle, b, null, null);
    }

    /** Top n rows of {name, value}, labelled "name - value" so the ranking is readable in the list. */
    private static List<String> top(List<String[]> rows, int n) {
        List<String> out = new ArrayList<>();
        for (String[] row : rows) {
            if (out.size() >= n) break;
            out.add(row[0]);
        }
        return out;
    }

    private static List<String> topOfMap(Map<String, Integer> counts, int n) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((x, y) -> y.getValue() - x.getValue());
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : entries) {
            if (out.size() >= n) break;
            out.add(e.getKey());
        }
        return out;
    }

    /**
     * The same panel from the keyless record, for when {@code /v4} will not answer.
     *
     * <p>Without a key the expanded panel used to say "Nothing found" for a town whose popup --
     * still on screen behind it -- was happily showing mayor, board, bank and claims. The popup had
     * a fallback to {@code /map/v1/town} and this path did not, so the same town was both known and
     * missing depending on which way you opened it.
     *
     * <p>What /map/v1 does not carry is left out rather than drawn blank: no resident roll, no
     * trusted or outlaw lists, no spawn, no claim limit, no founder, and no flags beyond pvp and
     * public. A reader gets what EarthMC will give away for free and is not told the rest is empty.
     */
    public static Page town(net.townymap.model.MapTownData m) {
        List<DetailScreen.Block> b = new ArrayList<>();
        boolean hasNation = m.nation() != null && !m.nation().isBlank();
        boolean hasMayor = m.mayor() != null && !m.mayor().isBlank() && !m.mayor().equals("?");

        b.add(new Cols(List.of(
                new Col("Mayor", hasMayor ? m.mayor() : "—",
                        hasMayor ? new Ref(Kind.PLAYER, m.mayor()) : null),
                new Col("Founded", m.founded() == null || m.founded().isBlank() ? "—" : m.founded()),
                new Col("Nation", hasNation ? m.nation() : "—",
                        hasNation ? new Ref(Kind.NATION, m.nation()) : null))));
        addAllianceBlocks(b, hasNation ? m.nation() : "");

        if (m.board() != null && !m.board().isBlank()) b.add(new DetailScreen.Wide("Board", m.board()));
        b.add(new Rule());

        // No claim limit here, unlike the keyed page: /map/v1 gives the count and not the maximum,
        // and "12 / ?" reads like a failure rather than a thing EarthMC withholds.
        b.add(new Cols(List.of(
                new Col("Size", m.claims() + " claims"),
                new Col("Bank", money(m.balance())),
                new Col("Residents", String.valueOf(m.residents())))));
        b.add(new Rule());
        b.add(chips("public", m.isPublic(), "pvp", m.pvp()));

        return new Page(Kind.TOWN, m.name(), hasNation ? m.nation() : "no nation", b,
                DiscordUrl.normalize(m.discord()), null);
    }

    public static Page town(TownFullData t) {
        List<DetailScreen.Block> b = new ArrayList<>();

        b.add(new Cols(List.of(
                new Col("Mayor", t.mayor(), new Ref(Kind.PLAYER, t.mayor())),
                new Col("Founded", date(t.registeredMs())),
                new Col("Founder", t.founder().isBlank() ? "—" : t.founder(),
                        t.founder().isBlank() ? null : new Ref(Kind.PLAYER, t.founder())))));

        if (t.hasNation() && !t.nation().isBlank()) {
            b.add(new Cols(List.of(
                    new Col("Nation", t.nation(), new Ref(Kind.NATION, t.nation())),
                    new Col("Joined nation", ago(t.joinedNationAtMs())),
                    new Col("Spawn", t.spawnX() + ", " + t.spawnY() + ", " + t.spawnZ()))));
        } else {
            b.add(new Cols(List.of(
                    new Col("Nation", "—"),
                    new Col("Spawn", t.spawnX() + ", " + t.spawnY() + ", " + t.spawnZ()))));
        }
        addAllianceBlocks(b, t.hasNation() ? t.nation() : "");

        if (!t.board().isBlank()) b.add(new DetailScreen.Wide("Board", t.board()));
        b.add(new Rule());

        String size = t.numTownBlocks() + " / " + (t.maxTownBlocks() >= 0 ? t.maxTownBlocks() : "?")
                + (t.bonusBlocks() > 0 ? "  (+" + t.bonusBlocks() + ")" : "");
        b.add(new Cols(List.of(
                new Col("Size", size),
                new Col("Bank", money(t.balance())),
                new Col("Nation bonus", String.valueOf(t.nationBonus())))));
        // No residents/trusted/outlawed counts here: the collapsible lists below already show them on
        // the right, and repeating them at the top just spent a row saying the same thing twice.
        if (t.isForSale() && t.forSalePrice() >= 0) {
            b.add(new Cols(List.of(new Col("For sale", money(t.forSalePrice())))));
        }
        b.add(overclaimCols(t));
        b.add(new Rule());

        b.add(chips(
                "public", t.isPublic(), "open", t.isOpen(), "neutral", t.isNeutral(),
                "capital", t.isCapital(), "overclaimed", t.isOverClaimed(), "ruined", t.isRuined(),
                "for sale", t.isForSale(), "outsider spawn", t.canOutsidersSpawn(),
                "pvp", t.pvp(), "explosions", t.explosion(), "fire", t.fire(), "mobs", t.mobs(),
                "passive mobs", t.canPassiveMobsSpawn(), "snow", t.hasSnowAccumulation(),
                "friendly fire", t.hasFriendlyFire()));

        addNames(b, "Residents", t.residents(), Kind.PLAYER);
        addNames(b, "Trusted", t.trusted(), Kind.PLAYER);
        addNames(b, "Outlawed", t.outlaws(), Kind.PLAYER);
        if (!t.quarters().isEmpty()) b.add(new LineList("Quarters", t.quarters()));
        if (!t.warps().isEmpty()) {
            List<String> w = new ArrayList<>();
            for (TownFullData.Warp warp : t.warps()) {
                w.add(warp.name() + " — " + warp.access() + " — " + warp.x() + ", " + warp.y() + ", " + warp.z()
                        + (warp.createdBy().isBlank() ? "" : " (by " + warp.createdBy() + ")"));
            }
            b.add(new LineList("Warps", w));
        }
        addRanks(b, t.occupiedRanks());

        String sub = t.hasNation() && !t.nation().isBlank()
                ? (t.isCapital() ? "capital of " + t.nation() : t.nation()) : "no nation";
        return new Page(Kind.TOWN, t.name(), sub, b, DiscordUrl.normalize(t.discord()), t.wiki());
    }

    /** The Expand panel for an ARCHIVED town — built only from what the Wayback snapshot recorded. Fields the
     *  archive doesn't have (chunks, bank, spawn, open/for-sale, discord, wiki…) are simply not shown. */
    public static Page archiveTown(ArchiveClient.ArchiveTown t) {
        List<DetailScreen.Block> b = new ArrayList<>();

        b.add(new Cols(List.of(
                new Col("Mayor", orDash(t.mayor()), t.mayor() == null || t.mayor().isBlank() ? null : new Ref(Kind.PLAYER, t.mayor())),
                new Col("Founded", orDash(t.founded())),
                new Col("Chunks", String.valueOf(t.chunks())))));

        if (t.nation() != null && !t.nation().isBlank()) {
            // No alliance/meganation rows here: that data is only available live, not for the archived date.
            b.add(new Cols(List.of(new Col("Nation", t.nation(), new Ref(Kind.NATION, t.nation())))));
        }
        if (t.board() != null && !t.board().isBlank()) b.add(new DetailScreen.Wide("Board", t.board()));
        b.add(new Rule());

        b.add(chips("public", t.isPublic(), "pvp", t.pvp()));
        addNames(b, "Residents", t.residents(), Kind.PLAYER);
        addNames(b, "Councillors", t.councillors(), Kind.PLAYER);

        String sub = (t.nation() == null || t.nation().isBlank()) ? "no nation"
                : (t.capital() ? "capital of " + t.nation() : t.nation());
        return new Page(Kind.TOWN, t.name(), sub, b, "", "");
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    /**
     * EarthMC's formattedName is MiniMessage (e.g. "&lt;dark_blue&gt; Maire Name"). Builds a styled MC
     * {@link net.minecraft.network.chat.Component} from it — named colours/formats via {@link net.minecraft.ChatFormatting},
     * and &lt;#rrggbb&gt; hex via {@link net.minecraft.network.chat.TextColor} — so it renders in its true colour.
     */
    static net.minecraft.network.chat.Component miniToText(String s) {
        net.minecraft.network.chat.MutableComponent root = net.minecraft.network.chat.Component.empty();
        if (s == null || s.isEmpty()) return root;
        net.minecraft.network.chat.Style style = net.minecraft.network.chat.Style.EMPTY;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<(/?)(#[0-9a-fA-F]{6}|[a-zA-Z_]+)(:[^>]*)?>").matcher(s);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) root.append(net.minecraft.network.chat.Component.literal(s.substring(last, m.start())).setStyle(style));
            last = m.end();
            if (!m.group(1).isEmpty()) { style = net.minecraft.network.chat.Style.EMPTY; continue; }   // closing → reset
            String tag = m.group(2).toLowerCase(java.util.Locale.ROOT);
            if (tag.startsWith("#") && tag.length() == 7) {
                try {
                    style = style.withColor(net.minecraft.network.chat.TextColor.fromRgb(Integer.parseInt(tag.substring(1), 16)));
                } catch (NumberFormatException ignored) { /* keep style */ }
            } else if (tag.equals("reset")) {
                style = net.minecraft.network.chat.Style.EMPTY;
            } else {
                net.minecraft.ChatFormatting f = chatFormattingByName(tag);
                // 26.2 dropped ChatFormatting.isColor(); colours are the first 16 enum constants (BLACK..WHITE).
                if (f != null) style = f.ordinal() < 16 ? style.withColor(f) : style.applyFormat(f);   // unknown → dropped
            }
        }
        if (last < s.length()) root.append(net.minecraft.network.chat.Component.literal(s.substring(last)).setStyle(style));
        return root;
    }

    /** Name → ChatFormatting. 26.2 removed getByName(String), so resolve by the enum name (dark_blue → DARK_BLUE). */
    private static net.minecraft.ChatFormatting chatFormattingByName(String tag) {
        try {
            return net.minecraft.ChatFormatting.valueOf(tag.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The Expand panel for a player in ARCHIVE mode — only their residency that date (town, its nation, and
     *  their rank in it). No live data (last online, balance, registration…) exists in the snapshot. */
    public static Page archivePlayer(String name, String town, String nation, String role) {
        List<DetailScreen.Block> b = new ArrayList<>();
        List<Col> cols = new ArrayList<>();
        cols.add(new Col("Town", orDash(town), town == null || town.isBlank() ? null : new Ref(Kind.TOWN, town)));
        if (nation != null && !nation.isBlank()) cols.add(new Col("Nation", nation, new Ref(Kind.NATION, nation)));
        cols.add(new Col("Rank", orDash(role)));
        b.add(new Cols(cols));
        String sub = town == null || town.isBlank() ? "" : role.toLowerCase(java.util.Locale.ROOT) + " of " + town;
        return new Page(Kind.PLAYER, name, sub, b, "", "");
    }

    /** The Expand panel for an ARCHIVED nation — derived entirely from that date's member towns: the towns
     *  themselves, their residents and their chunk totals. Nation-level data the snapshot can't give (king,
     *  founded, bank, bonus, over-claim, allies…) is omitted. */
    public static Page archiveNation(String name, String capital, List<String> towns,
                                     List<String> residents, int chunks) {
        List<DetailScreen.Block> b = new ArrayList<>();
        b.add(new Cols(List.of(
                new Col("Capital", orDash(capital), capital == null || capital.isBlank() ? null : new Ref(Kind.TOWN, capital)),
                new Col("Towns", String.valueOf(towns.size())),
                new Col("Residents", String.valueOf(residents.size())))));
        b.add(new Cols(List.of(new Col("Chunks", String.valueOf(chunks)))));
        b.add(new Rule());
        addNames(b, "Towns", towns, Kind.TOWN);
        addNames(b, "Residents", residents, Kind.PLAYER);
        return new Page(Kind.NATION, name, towns.size() + " towns", b, "", "");
    }

    /**
     * Overclaim standing. A town is overclaimable once it holds more chunks than it can support, and that
     * limit already includes the nation bonus — so a bonus drop can tip a currently-safe town over without
     * it claiming anything. When that is what will happen, show the date it happens rather than a bare
     * "no", since the date is the part worth acting on.
     */
    private static Cols overclaimCols(TownFullData t) {
        return new Cols(List.of(Col.live("Overclaimable", () -> overclaimText(t))));
    }

    private static String overclaimText(TownFullData t) {
        if (t.maxTownBlocks() < 0) return "unknown";
        if (t.numTownBlocks() > t.maxTownBlocks()) return "yes, now";

        long now = System.currentTimeMillis();
        long soonest = Long.MAX_VALUE;
        String date = "";

        // Resident purges are the dominant term: each one costs a resident's worth of chunks, whereas the
        // nation bonus is a single smaller step. A town can sit far inside its limit today and still be
        // overclaimable in a few weeks purely because inactive residents drop off.
        var byResidents = TownyMapMod.townOverclaimProjection(t.name());
        if (byResidents != null && byResidents.known()) {
            soonest = byResidents.atMs();
            date = byResidents.date();
        }

        // The nation bonus falling can get there first for a town near its limit.
        if (t.hasNation()) {
            var bonus = TownyMapMod.nationBonusProjection(t.nation());
            if (bonus != null && bonus.nextBonus() < t.nationBonus() && bonus.dropAtMs() > 0) {
                int afterMax = t.maxTownBlocks() - (t.nationBonus() - bonus.nextBonus());
                if (t.numTownBlocks() > afterMax && bonus.dropAtMs() < soonest) {
                    soonest = bonus.dropAtMs();
                    date = bonus.dropDate();
                }
            }
        }

        if (soonest == Long.MAX_VALUE) return "no";
        int d = (int) Math.max(0, Math.ceil((soonest - now) / 86_400_000.0));
        String when = d > 1 ? d + " days" : d == 1 ? "1 day" : "today";
        return (date == null || date.isBlank() ? "" : date + ", ") + when;
    }

    // ── Player ────────────────────────────────────────────────────────────────

    /**
     * A player page built from town rosters, for players who have opted out of the EarthMC API.
     *
     * <p>Rosters are town data and carry no opt-out, so this is all we can honestly show: their town,
     * and the nation through it. Labelled as map-derived rather than presented as a full profile.
     */
    public static Page playerFromRoster(String name, String town, String nation) {
        List<DetailScreen.Block> b = new ArrayList<>();
        b.add(new Cols(List.of(
                new Col("Town", town == null || town.isBlank() ? "-" : town,
                        town == null || town.isBlank() ? null : new Ref(Kind.TOWN, town)),
                new Col("Nation", nation == null || nation.isBlank() ? "-" : nation,
                        nation == null || nation.isBlank() ? null : new Ref(Kind.NATION, nation)))));
        b.add(new Rule());
        b.add(new DetailScreen.Wide("Note",
                "This player is not on the EarthMC API, so only what their town's public resident list "
                + "shows is available here. Balance, join date and rank cannot be read."));
        return new Page(Kind.PLAYER, name, "from map data", b, null, null);
    }

    public static Page player(PlayerFullData p) {
        List<DetailScreen.Block> b = new ArrayList<>();

        b.add(new Cols(List.of(
                new Col("Town", p.hasTown() && !p.town().isBlank() ? p.town() : "—",
                        p.hasTown() && !p.town().isBlank() ? new Ref(Kind.TOWN, p.town()) : null),
                new Col("Nation", p.hasNation() && !p.nation().isBlank() ? p.nation() : "—",
                        p.hasNation() && !p.nation().isBlank() ? new Ref(Kind.NATION, p.nation()) : null),
                new Col("Balance", money(p.balance())))));

        if (!p.formattedName().isBlank() && !p.formattedName().equals(p.name())) {
            b.add(new DetailScreen.LegacyLine("Formatted name", miniToText(p.formattedName())));
        }
        if (!p.about().isBlank()) b.add(new DetailScreen.Wide("About", p.about()));
        b.add(new Rule());

        b.add(new Cols(List.of(
                new Col("Registered", date(p.registeredMs())),
                new Col("Joined town", ago(p.joinedTownAtMs())),
                new Col(p.isOnline() ? "Online" : "Last online",
                        p.isOnline() ? "now" : ago(p.lastOnlineMs())))));
        b.add(new Rule());

        b.add(chips("online", p.isOnline(), "mayor", p.isMayor(), "king", p.isKing(),
                "has town", p.hasTown(), "has nation", p.hasNation(), "npc", p.isNPC()));

        if (!p.townRanks().isEmpty() || !p.nationRanks().isEmpty()) {
            List<String> r = new ArrayList<>();
            if (!p.townRanks().isEmpty()) r.add("Town: " + String.join(", ", p.townRanks()));
            if (!p.nationRanks().isEmpty()) r.add("Nation: " + String.join(", ", p.nationRanks()));
            b.add(new LineList("Ranks", r));
        }
        addNames(b, "Friends", p.friends(), Kind.PLAYER);

        String sub = p.hasTown() && !p.town().isBlank() ? p.town() : "townless";
        return new Page(Kind.PLAYER, p.name(), sub, b, "", "");
    }

    // ── Nation ────────────────────────────────────────────────────────────────

    public static Page nation(NationFullData n) {
        List<DetailScreen.Block> b = new ArrayList<>();

        b.add(new Cols(List.of(
                new Col("King", n.king().isBlank() ? "—" : n.king(),
                        n.king().isBlank() ? null : new Ref(Kind.PLAYER, n.king())),
                new Col("Capital", n.capital().isBlank() ? "—" : n.capital(),
                        n.capital().isBlank() ? null : new Ref(Kind.TOWN, n.capital())),
                new Col("Founded", date(n.registeredMs())))));

        if (!n.board().isBlank()) b.add(new DetailScreen.Wide("Board", n.board()));
        b.add(new Rule());

        b.add(new Cols(List.of(
                new Col("Chunks", String.valueOf(n.numTownBlocks())),
                new Col("Towns", String.valueOf(n.numTowns())),
                new Col("Residents", String.valueOf(n.numResidents())))));
        b.add(new Cols(List.of(
                new Col("Bank", money(n.balance())),
                // Live: the projection is fetched asynchronously and may not exist yet when this page is
                // built, and once it does the countdown has to keep moving while the panel stays open.
                Col.live("Nation bonus", () -> bonusText(n.name(), n.nationBonus())),
                new Col("Spawn", n.spawnX() + ", " + n.spawnY() + ", " + n.spawnZ()))));
        b.add(new Rule());

        b.add(chips("public", n.isPublic(), "open", n.isOpen(), "neutral", n.isNeutral()));

        addNames(b, "Towns", n.towns(), Kind.TOWN);
        addNames(b, "Allies", n.allies(), Kind.NATION);
        addNames(b, "Enemies", n.enemies(), Kind.NATION);
        addNames(b, "Sanctioned", n.sanctioned(), Kind.NATION);
        addNames(b, "Outlaws", n.outlaws(), Kind.PLAYER);
        addNames(b, "Residents", n.residents(), Kind.PLAYER);
        // Blocs sit with the other collapsible lists rather than up in the summary rows.
        addAllianceBlocks(b, n.name());
        addRanks(b, n.occupiedRanks());

        if (!n.pacts().isEmpty()) {
            List<DetailScreen.RefLine> p = new ArrayList<>();
            for (NationFullData.Pact pact : n.pacts()) {
                // A perpetual pact only has a start, so showing "expires —, duration —" was noise. A
                // fixed-term one gets its end and how long it runs.
                String terms = pact.status().toLowerCase(java.util.Locale.ROOT)
                        + " since " + date(pact.createdMs());
                if (pact.forever()) {
                    terms += " — never expires";
                } else {
                    terms += " — until " + date(pact.expiresAtMs());
                    if (pact.durationMs() > 0) terms += " (" + humanDuration(pact.durationMs()) + ")";
                }
                p.add(new DetailScreen.RefLine(
                        new Ref(Kind.NATION, pact.other(n.name())), terms));
            }
            b.add(new DetailScreen.RefLineList("Pacts", p));
        }
        List<String> emb = new ArrayList<>();
        for (String e : n.embargoesOwn()) emb.add("against " + e);
        for (String e : n.embargoesAgainst()) emb.add("from " + e);
        if (!emb.isEmpty()) b.add(new LineList("Embargoes", emb));

        return new Page(Kind.NATION, n.name(), n.numTowns() + " towns", b,
                DiscordUrl.normalize(n.discord()), n.wiki());
    }

    /**
     * "100 → 80 in 4d" once the projection lands, plain "100" until then. Asking for the projection also
     * kicks off the fetch when it is missing; that call is deduped by both an in-flight set and the cache,
     * so evaluating this every frame issues at most one request per nation.
     */
    private static String bonusText(String nation, int currentBonus) {
        var proj = TownyMapMod.nationBonusProjection(nation);
        if (proj == null || proj.nextBonus() >= currentBonus) return String.valueOf(currentBonus);
        long now = System.currentTimeMillis();
        if (proj.daysUntilDropAt(now) < 0) return String.valueOf(currentBonus);
        return currentBonus + " → " + proj.nextBonus() + " in " + countdown(proj, now);
    }

    /** Coarsest sensible unit: days out, then hours, then minutes. */
    private static String countdown(net.townymap.model.NationBonusProjection proj, long now) {
        int d = proj.daysUntilDropAt(now);
        if (d > 1) return d + "d";
        int h = proj.hoursUntilDropAt(now);
        if (h > 1) return h + "h";
        return Math.max(1, proj.minutesUntilDropAt(now)) + "m";
    }

    // ── shared ────────────────────────────────────────────────────────────────

    /** Rank holders are players, so they get the same clickable treatment as any other name list. */
    private static void addRanks(List<DetailScreen.Block> b, Map<String, List<String>> ranks) {
        if (ranks.isEmpty()) return;
        List<DetailScreen.RankGroup> groups = new ArrayList<>();
        ranks.forEach((rank, holders) -> groups.add(
                new DetailScreen.RankGroup(rank, refs(holders, Kind.PLAYER))));
        b.add(new DetailScreen.RankList("Ranks", groups));
    }

    /** chips("label", state, "label", state, …) */
    private static Chips chips(Object... pairs) {
        List<String> labels = new ArrayList<>();
        List<Boolean> states = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            labels.add((String) pairs[i]);
            states.add((Boolean) pairs[i + 1]);
        }
        return new Chips(labels, states);
    }
}
