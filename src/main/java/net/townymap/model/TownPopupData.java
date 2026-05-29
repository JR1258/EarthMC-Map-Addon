package net.townymap.model;

public record TownPopupData(
        String townName,
        String nationName,
        String discord,
        String board,
        String mayor,
        int    numChunks,
        String founded,
        boolean pvp,
        boolean isPublic,
        boolean canOutsidersSpawn,
        boolean isOverClaimed,
        boolean isOpen,
        boolean isForSale,
        boolean hasNation,
        int     residentCount,
        double  balance
) {
    public static final TownPopupData WILDERNESS =
            new TownPopupData("Wilderness", "", "", "", "", 0, "", false, false, false, false,
                    false, false, false, 0, 0);
}
