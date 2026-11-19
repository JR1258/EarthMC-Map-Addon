package net.townymap.model;

/**
 * The town record EarthMC serves with no API key, from {@code GET /map/v1/town/{name}}.
 *
 * <p>Added by EarthMC on 2026-11-19, the day every {@code /v4} endpoint began refusing unkeyed
 * requests. It carries "only what is visible on the web map", which in practice is most of what a
 * town card shows -- and two figures the keyed API does not give at all:
 *
 * <ul>
 *   <li>{@link #residents()} is the TOTAL roll. {@code /v4/towns} filters both its count and its
 *       roster to the last 42 days, so this is the only clean source for the number EarthMC's own
 *       map prints. It replaces a regex over the squaremap popup's HTML.</li>
 *   <li>{@link #claims()} is the authoritative chunk count, replacing a count derived from the claim
 *       polygon's geometry.</li>
 * </ul>
 *
 * <p>Costs nothing against the key's budget, so it is worth fetching even when a key is set. Town
 * only: there is no nation or player equivalent, no bulk form, and no listing.
 */
public record MapTownData(
        String name,
        String mayor,
        String nation,
        String board,
        String discord,
        int residents,
        int claims,
        double balance,
        /** Already formatted for display, from {@code stats.founded} (epoch ms). */
        String founded,
        boolean pvp,
        boolean isPublic
) {}
