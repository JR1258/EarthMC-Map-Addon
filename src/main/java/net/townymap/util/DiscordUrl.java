package net.townymap.util;

import java.util.Locale;

/**
 * Normalises Discord links pulled from the EarthMC API (which may be a bare
 * invite code, a {@code discord.gg/...} path, or a full URL) into a clickable
 * {@code https://} URL.
 */
public final class DiscordUrl {

    private DiscordUrl() {}

    /** Returns a clickable https URL, or "" if {@code discord} is blank. */
    public static String normalize(String discord) {
        if (discord == null || discord.isBlank()) return "";
        String value = discord.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return value;
        if (lower.startsWith("discord.gg/") || lower.startsWith("discord.com/")
                || lower.startsWith("www.discord.gg/") || lower.startsWith("www.discord.com/")) {
            return "https://" + value;
        }
        if (!value.contains("/") && !value.contains(".")) return "https://discord.gg/" + value;
        return "https://" + value;
    }
}
