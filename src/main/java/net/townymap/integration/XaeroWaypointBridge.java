package net.townymap.integration;

import net.minecraft.client.MinecraftClient;
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

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return false;

        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (session == null || session.getWorldManager() == null) return false;

        MinimapWorld world = session.getWorldManager().getCurrentWorld();
        if (world == null || world.getCurrentWaypointSet() == null) return false;

        WaypointSet set = world.getCurrentWaypointSet();
        removePreviousTownyRoutes(set);

        int y = client.player.getBlockY();
        String label = ROUTE_PREFIX + cleanLabel(target.label());
        Waypoint waypoint = new Waypoint(
                target.x(),
                y,
                target.z(),
                label,
                symbol(target.label()),
                WaypointColor.PURPLE,
                WaypointPurpose.DESTINATION,
                true,
                false
        );
        waypoint.setOneoffDestination(true);
        set.add(waypoint, true);
        session.getWaypointSession().setSetChangedTime(System.currentTimeMillis());
        return true;
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
