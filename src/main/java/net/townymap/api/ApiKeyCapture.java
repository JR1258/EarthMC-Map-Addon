package net.townymap.api;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an EarthMC API key out of chat, inside a short window the player opens by hand.
 *
 * <p>Pasting a 171-character token into a text box is the step people get wrong: it arrives cut
 * short, or lands in the wrong field, or the box is left without submitting. Running
 * {@code /api key create} and having the addon take the key straight out of the reply skips all of
 * that.
 *
 * <p>Reading chat is not something a mod should do quietly, so this does none of it until the
 * player asks. The window is opened only from the settings button, behind a prompt that says what
 * will be read and for how long; it lasts {@link #WINDOW_MS}; it closes the moment a key is found;
 * and nothing it sees is stored, echoed or logged except the key itself, which goes to the config
 * exactly as a typed one would. Messages are never altered or hidden from the player.
 */
public final class ApiKeyCapture {

    /** Long enough to alt-tab back and type the command, short enough that a forgotten window lapses. */
    public static final long WINDOW_MS = 90_000L;

    /** Legacy section-sign colour codes, which servers embed in the raw string. */
    private static final Pattern LEGACY_COLOR = Pattern.compile("\u00a7[0-9a-fk-orA-FK-OR]");

    /**
     * A key is one unbroken run of the URL-safe base64 alphabet.
     *
     * <p>48 is the floor rather than the 171 a live key measured: pinning it to the length we
     * happen to have seen would miss a shorter one EarthMC might start issuing, while nothing a
     * player or a plugin writes in chat is 48 characters of letters, digits, hyphen and underscore
     * with no space anywhere in it. A dashed UUID is 36, so it cannot collide.
     */
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{48,}");

    private static volatile long armedUntilMs = 0L;

    private ApiKeyCapture() {}

    /** Opens the window. Called only from the settings prompt. */
    public static void arm() { armedUntilMs = System.currentTimeMillis() + WINDOW_MS; }

    public static void cancel() { armedUntilMs = 0L; }

    public static boolean armed() { return System.currentTimeMillis() < armedUntilMs; }

    public static int secondsLeft() {
        long left = armedUntilMs - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) Math.ceil(left / 1000.0);
    }

    /**
     * The key this line carries, or null if it carries none.
     *
     * <p>The longest match wins, so a key printed beside an id or a URL fragment is still the one
     * taken. The value is returned, never logged.
     */
    public static String findKey(String rawMessage) {
        if (rawMessage == null || rawMessage.isEmpty()) return null;
        String clean = LEGACY_COLOR.matcher(rawMessage).replaceAll("");
        Matcher m = KEY.matcher(clean);
        String best = null;
        while (m.find()) {
            String candidate = m.group();
            if (best == null || candidate.length() > best.length()) best = candidate;
        }
        return best;
    }
}
