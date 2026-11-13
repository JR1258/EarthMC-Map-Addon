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
        double  balance,
        int     activeResidentCount, // residents active within 42 days; -1 if not looked up
        int     maxChunks,           // EarthMC's own claim max (stats.maxTownBlocks); -1 if absent
        /**
         * Built from the live map alone, with no API answer behind it.
         *
         * <p>Four of the fields above exist only in EarthMC's API -- balance, isOpen, isForSale and
         * maxChunks -- and a record's defaults cannot say "unknown": a false reads as "no" and a 0
         * balance reads as broke. This flag is what lets the card leave those rows out instead of
         * stating something it does not know.
         */
        boolean mapOnly
) {
    public static final TownPopupData WILDERNESS =
            new TownPopupData("Wilderness", "", "", "", "", 0, "", false, false, false, false,
                    false, false, false, 0, 0, -1, -1, false);

    /**
     * A copy carrying the town's TOTAL resident count.
     *
     * <p>The API gives only the active one -- it filters both {@code stats.numResidents} and the
     * roster to the last 42 days -- so the total comes off the squaremap popup, which still lists
     * every member. Everything else is left alone.
     */
    public TownPopupData withTotalResidentCount(int total) {
        return new TownPopupData(townName, nationName, discord, board, mayor, numChunks, founded, pvp,
                isPublic, canOutsidersSpawn, isOverClaimed, isOpen, isForSale, hasNation, total,
                balance, activeResidentCount, maxChunks, mapOnly);
    }
}
