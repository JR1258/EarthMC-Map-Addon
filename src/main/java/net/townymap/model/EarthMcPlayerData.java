package net.townymap.model;

public record EarthMcPlayerData(
        String name,
        String uuid,
        String townName,
        String nationName,
        String formattedName,
        boolean online,
        boolean npc,
        boolean mayor,
        boolean king,
        double balance,
        int friendCount,
        String lastOnline
) {
    public EarthMcPlayerData(String name, String uuid) {
        this(name, uuid, "", "", "", false, false, false, false, 0, 0, "");
    }
}
