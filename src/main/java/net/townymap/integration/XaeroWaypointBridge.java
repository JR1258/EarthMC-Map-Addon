package net.townymap.integration;

import net.minecraft.client.Minecraft;
import net.townymap.model.MapJumpTarget;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;

import java.util.ArrayList;
import java.util.List;

public final class XaeroWaypointBridge {

    private static final String ROUTE_PREFIX = "TM: ";

    private XaeroWaypointBridge() {
    }

    public static boolean createRouteWaypoint(MapJumpTarget target) {
        if (target == null) return false;

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return false;

        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (session == null || session.getWorldManager() == null) return false;

        MinimapWorld world = session.getWorldManager().getCurrentWorld();
        if (world == null || world.getCurrentWaypointSet() == null) return false;

        WaypointSet set = world.getCurrentWaypointSet();
        removePreviousTownyRoutes(set);

        int y = client.player.getBlockY();
        String label = ROUTE_PREFIX + cleanLabel(target.label());
        // The waypoint goes into the current dimension's set, so the target's EarthMC (overworld)
        // coordinates have to be brought into that dimension first — otherwise a route created in
        // the Nether points 8x too far away.
        double dimScale = net.townymap.TownyMapMod.dimensionCoordinateScale();
        Waypoint waypoint = new Waypoint(
                (int) Math.round(target.x() / dimScale),
                y,
                (int) Math.round(target.z() / dimScale),
                label,
                symbol(target.label()),
                WaypointColor.PURPLE,
                WaypointPurpose.DESTINATION,
                true,
                false
        );
        waypoint.setOneoffDestination(true);
        set.add(waypoint, true);
        return true;
    }

    /**
     * Adds one temporary shop waypoint. The coordinates are literal in-world positions in the
     * dimension the player is standing in, so unlike {@link #createRouteWaypoint} they are used
     * as-is with no overworld conversion.
     *
     * @param key packed position, embedded in the symbol so {@link #removeShopWaypoints} can find it
     *            again without depending on the (user-visible, truncatable) name
     */
    public static boolean createShopWaypoint(long key, int x, int y, int z, String label) {
        WaypointSet set = currentWaypointSet();
        if (set == null) return false;

        Waypoint waypoint = new Waypoint(
                x, y, z,
                cleanLabel(label),
                // Xaero sizes the icon around this string, so a single character keeps the marker
                // small. Identity comes from the coordinates instead (see removeShopWaypoints).
                "$",
                WaypointColor.GOLD,
                WaypointPurpose.NORMAL,
                true,   // temporary
                // yIncluded. A shop has a real, meaningful Y, so the in-world marker should sit at the
                // chest's height and show the correct vertical offset as you approach from another
                // level. Route waypoints leave this off because their Y is just wherever you stood.
                true
        );
        set.add(waypoint, true);
        return true;
    }

    /** Removes the shop waypoints matching the given packed positions. */
    public static void removeShopWaypoints(java.util.Collection<Long> keys) {
        if (keys == null || keys.isEmpty()) return;
        WaypointSet set = currentWaypointSet();
        if (set == null) return;

        java.util.Set<Long> wanted = new java.util.HashSet<>(keys);
        List<Waypoint> toRemove = new ArrayList<>();
        for (Waypoint waypoint : set.getWaypoints()) {
            // Matched on position rather than an encoded symbol: the symbol has to stay one character
            // to keep the icon small, and the name is user-visible and truncated, so neither is a
            // reliable identity. The prefix check still guarantees we only ever remove our own.
            if (waypoint.isTemporary()
                    && waypoint.getName() != null
                    && waypoint.getName().startsWith(ShopWaypoints.SHOP_PREFIX)
                    && wanted.contains(ShopWaypoints.pack(waypoint.getX(), waypoint.getY(), waypoint.getZ()))) {
                toRemove.add(waypoint);
            }
        }
        if (toRemove.isEmpty()) return;
        for (Waypoint waypoint : toRemove) {
            set.remove(waypoint);
        }
    }

    private static WaypointSet currentWaypointSet() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return null;
        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (session == null || session.getWorldManager() == null) return null;
        MinimapWorld world = session.getWorldManager().getCurrentWorld();
        if (world == null) return null;
        return world.getCurrentWaypointSet();
    }

    private static void removePreviousTownyRoutes(WaypointSet set) {
        List<Waypoint> toRemove = new ArrayList<>();
        for (Waypoint waypoint : set.getWaypoints()) {
            if (waypoint.isTemporary()
                    && waypoint.isDestination()
                    && waypoint.getName() != null
                    && waypoint.getName().startsWith(ROUTE_PREFIX)) {
                toRemove.add(waypoint);
            }
        }
        for (Waypoint waypoint : toRemove) {
            set.remove(waypoint);
        }
    }

    private static String cleanLabel(String label) {
        String cleaned = label == null ? "Target" : label.replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) return "Target";
        if (cleaned.length() > 64) return cleaned.substring(0, 64);
        return cleaned;
    }

    private static String symbol(String label) {
        if (label != null) {
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (Character.isLetterOrDigit(c)) {
                    return Character.toString(Character.toUpperCase(c));
                }
            }
        }
        return "T";
    }
}
