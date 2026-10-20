package net.townymap.integration;

import net.minecraft.client.MinecraftClient;
import net.townymap.TownyMapConfig;
import net.townymap.TownyMapMod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a QuickShop {@code /qs find <item>} result into temporary Xaero waypoints, which are dropped
 * again once you walk away from them.
 *
 * <p>The server prints one chat line per shop, e.g.
 * <pre>Nearby Shops matching netherite_ingot:
 * - Info: Selling 49 Price: 19G each X: 50105 Y: 66 Z: -5435 Distance: 39 block(s)</pre>
 *
 * <p>Results arrive as several separate chat lines, so they are collected over a short window and only
 * turned into waypoints once that window closes. That buffering is what lets nearby lines be merged:
 * a shop's buy and sell chests are listed separately but sit a block or two apart, so marking each line
 * would drop a cluster of overlapping icons on one physical shop. Instead they collapse into a single
 * waypoint labelled with both prices.
 *
 * <p>Unlike everything else in this mod, this reads chat text rather than a structured API, so it is
 * inherently tied to the server's message format: if QuickShop or the server changes its wording, the
 * lines simply stop matching and no waypoints appear. It fails quiet rather than loud on purpose —
 * there is nothing useful a player could do about a format change, and spamming errors into chat every
 * time someone runs an unrelated command would be worse than the feature quietly doing nothing.
 *
 * <p>Coordinates here are literal in-world positions in the dimension the player is standing in, so —
 * unlike EarthMC town data — they must <em>not</em> go through
 * {@link TownyMapMod#dimensionCoordinateScale()}.
 */
public final class ShopWaypoints {

    /** Marks our shop waypoints so we only ever remove our own. */
    static final String SHOP_PREFIX = "QS: ";

    /**
     * How long to wait for the server to answer the command at all.
     *
     * <p>This used to be one flat three seconds measured from the moment the command was sent, which
     * is the wrong thing to measure: it has to cover the round trip and QuickShop's own search across
     * every shop on the server. On a busy server that regularly took longer, the window had already
     * closed by the time the first line arrived, and the search produced nothing -- intermittently,
     * depending only on how loaded the server was.
     */
    private static final long WAIT_FOR_FIRST_MS = 15_000L;

    /** Once lines are arriving, how long a gap means the list has finished. */
    private static final long QUIET_MS = 1_200L;

    /** Absolute cap, so a reply that never comes cannot leave the window open indefinitely. */
    private static final long MAX_CAPTURE_MS = 30_000L;

    /** Cap on waypoints per search, so a common item doesn't bury the minimap. */
    private static final int MAX_SHOPS = 8;

    /** Listings within this many blocks are treated as one shop (buy/sell chests sit side by side). */
    private static final int CLUSTER_RADIUS = 4;

    /** Shop waypoints also time out, so a search you wandered away from doesn't linger indefinitely. */
    private static final long LIFETIME_MS = 150_000L;   // 2.5 minutes

    private static final Pattern HEADER = Pattern.compile(
            "^\\s*Nearby Shops matching\\s+(.+?)\\s*:\\s*$", Pattern.CASE_INSENSITIVE);

    // QuickShop builds this line from the template
    //   "- Info: {0} Price: {1} X: {2} Y: {3} Z: {4} Distance: {5} block(s)"
    // so only the labels and the three coordinates are actually fixed. Everything between "Info:" and
    // "Price:", and between "Price:" and "X:", is whatever the server's own formatters produced.
    //
    // This used to demand the literal word "each" before the X, taken from one observed line. It is
    // not in the template -- it comes from inside the price placeholder, so it appears for some shops
    // and not others, and every line without it was silently skipped. That is why a search would mark
    // a few of its results and quietly drop the rest.
    private static final Pattern SHOP_LINE = Pattern.compile(
            "Info:\\s*(.*?)\\s*Price:\\s*(.*?)\\s*"
                    + "X:\\s*(-?\\d+)\\s+Y:\\s*(-?\\d+)\\s+Z:\\s*(-?\\d+)",
            Pattern.CASE_INSENSITIVE);

    /** Strips legacy section-sign colour codes, which some servers embed in the raw string. */
    private static final Pattern LEGACY_COLOR = Pattern.compile("\u00a7[0-9a-fk-orA-FK-OR]");

    private static long captureUntilMs = 0L;
    /** Hard stop for the current capture, independent of how often lines keep arriving. */
    private static long captureHardStopMs = 0L;
    /** When the current batch times out. All of a search's waypoints are created together, so one
     *  timestamp covers the batch; a new search replaces the batch and restarts the clock. */
    private static long expireAtMs = 0L;
    private static String pendingItem = "";
    private static final List<Listing> pending = new ArrayList<>();

    /** Live shop waypoints, keyed by packed position. */
    private static final Map<Long, Cluster> active = new LinkedHashMap<>();

    private record Listing(boolean selling, String price, int x, int y, int z) {}

    private static final class Cluster {
        final int x;
        final int y;
        final int z;
        String sell;
        String buy;

        Cluster(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        boolean near(Listing l) {
            return Math.abs(l.x() - x) <= CLUSTER_RADIUS
                    && Math.abs(l.z() - z) <= CLUSTER_RADIUS
                    && Math.abs(l.y() - y) <= CLUSTER_RADIUS;
        }
    }

    private ShopWaypoints() {
    }

    /** True for the commands whose output we want to read. */
    public static boolean isShopFindCommand(String normalizedCommand) {
        if (normalizedCommand == null) return false;
        String c = normalizedCommand.trim();
        return c.startsWith("qs find") || c.startsWith("quickshop find");
    }

    /** Opens a short window during which chat lines are treated as shop results. */
    public static void armCapture() {
        long now = System.currentTimeMillis();
        captureUntilMs = now + WAIT_FOR_FIRST_MS;
        captureHardStopMs = now + MAX_CAPTURE_MS;
        pendingItem = "";
        pending.clear();
    }

    /**
     * Keeps the window open while the server is still talking.
     *
     * <p>A long result list can take longer to print than any fixed window would allow, so each line
     * that matches buys a little more time; the batch closes once the lines stop rather than on a
     * deadline set before the reply even started.
     */
    private static void extendCapture() {
        captureUntilMs = Math.min(captureHardStopMs, System.currentTimeMillis() + QUIET_MS);
    }

    /**
     * Feeds one received chat line in. Returns true if it was consumed as a shop result, purely so the
     * caller can skip further parsing — the message is never cancelled or hidden from the player.
     */
    public static boolean onGameMessage(String rawMessage) {
        if (!enabled() || rawMessage == null) return false;
        if (System.currentTimeMillis() > captureUntilMs) return false;

        String line = LEGACY_COLOR.matcher(rawMessage).replaceAll("").trim();
        if (line.isEmpty()) return false;

        Matcher header = HEADER.matcher(line);
        if (header.matches()) {
            pendingItem = shorten(header.group(1));
            extendCapture();   // the reply has started; from here we wait on the lines, not the clock
            return true;
        }

        Matcher m = SHOP_LINE.matcher(line);
        if (!m.find()) return false;

        try {
            // "Selling 49" / "Buying 12" -- only which way round it is matters, and a line that says
            // neither is still worth a waypoint, so it falls back to selling rather than being dropped.
            String info = m.group(1) == null ? "" : m.group(1).toLowerCase(Locale.ROOT);
            boolean selling = !info.contains("buy");
            String price = m.group(2) == null ? "" : m.group(2).trim();
            pending.add(new Listing(selling, price,
                    Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)),
                    Integer.parseInt(m.group(5))));
        } catch (NumberFormatException ignored) {
            // A malformed number means this line isn't the shop line we think it is; skip it.
        }
        extendCapture();
        return true;
    }

    /**
     * Materialises a finished search and expires waypoints the player has walked away from. Cheap
     * enough to call every tick: it does nothing at all until a search has actually produced results.
     */
    /**
     * Drops the bookkeeping for shop waypoints left in another dimension.
     *
     * <p>Xaero files waypoints per dimension, so the Earth ones stay in Earth's set where they belong.
     * What has to go is our own {@code active} map: its keys are packed coordinates, so the expiry pass
     * would measure the player's Moon position against Earth shop coordinates, decide they had walked
     * away, and delete waypoints they never returned to.
     */
    public static void onDimensionChanged() {
        active.clear();
        pending.clear();
    }

    public static void tick() {
        if (!enabled()) {
            if (!active.isEmpty()) clearAll();
            pending.clear();
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            // Left the world. Xaero discards temporary waypoints itself, so just drop our own
            // bookkeeping — otherwise stale keys would linger and the next world would start dirty.
            active.clear();
            pending.clear();
            return;
        }

        if (!pending.isEmpty() && System.currentTimeMillis() > captureUntilMs) {
            flushPending();
        }
        if (active.isEmpty()) return;

        // Whichever comes first: the batch timing out, or you walking out of range of a given shop.
        if (System.currentTimeMillis() > expireAtMs) {
            clearAll();
            return;
        }

        double range = Math.max(16, range());
        double rangeSq = range * range;
        double px = client.player.getX();
        double pz = client.player.getZ();

        List<Long> expired = new ArrayList<>();
        for (Map.Entry<Long, Cluster> e : active.entrySet()) {
            Cluster c = e.getValue();
            double dx = c.x + 0.5 - px;
            double dz = c.z + 0.5 - pz;
            if (dx * dx + dz * dz > rangeSq) expired.add(e.getKey());
        }
        if (expired.isEmpty()) return;
        for (Long key : expired) {
            active.remove(key);
        }
        XaeroWaypointBridge.removeShopWaypoints(expired);
    }

    /** Removes every shop waypoint — used on disconnect and when the feature is switched off. */
    public static void clearAll() {
        if (active.isEmpty()) return;
        List<Long> keys = new ArrayList<>(active.keySet());
        active.clear();
        XaeroWaypointBridge.removeShopWaypoints(keys);
    }

    /** Collapses the buffered listings into one waypoint per physical shop. */
    private static void flushPending() {
        List<Listing> listings = new ArrayList<>(pending);
        pending.clear();
        if (listings.isEmpty()) return;

        // Only replace the previous search now that this one has definitely produced something, so a
        // search that finds nothing leaves the waypoints you already had alone.
        clearAll();
        expireAtMs = System.currentTimeMillis() + LIFETIME_MS;

        List<Cluster> clusters = new ArrayList<>();
        for (Listing l : listings) {
            Cluster target = null;
            for (Cluster c : clusters) {
                if (c.near(l)) { target = c; break; }
            }
            if (target == null) {
                if (clusters.size() >= MAX_SHOPS) continue;
                target = new Cluster(l.x(), l.y(), l.z());
                clusters.add(target);
            }
            // Keep the first price seen per side; QuickShop lists nearest-last, and the first is the
            // one whose coordinates seeded the cluster.
            if (l.selling() && target.sell == null) target.sell = l.price();
            if (!l.selling() && target.buy == null) target.buy = l.price();
        }

        for (Cluster c : clusters) {
            StringBuilder label = new StringBuilder(SHOP_PREFIX);
            if (!pendingItem.isEmpty()) label.append(pendingItem).append(' ');
            if (c.sell != null) label.append("S").append(c.sell);
            if (c.sell != null && c.buy != null) label.append(' ');
            if (c.buy != null) label.append("B").append(c.buy);

            long key = pack(c.x, c.y, c.z);
            if (active.containsKey(key)) continue;
            if (XaeroWaypointBridge.createShopWaypoint(key, c.x, c.y, c.z, label.toString())) {
                active.put(key, c);
            }
        }
    }

    static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | ((long) z & 0x3FFFFFFL);
    }

    private static String shorten(String item) {
        String s = item == null ? "" : item.trim();
        if (s.length() > 24) s = s.substring(0, 24);
        return s;
    }

    private static boolean enabled() {
        TownyMapConfig config = TownyMapMod.getConfig();
        return config != null && config.shopWaypointsEnabled;
    }

    private static int range() {
        TownyMapConfig config = TownyMapMod.getConfig();
        return config == null ? 250 : config.shopWaypointRange;
    }
}
