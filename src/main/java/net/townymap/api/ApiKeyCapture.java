package net.townymap.api;

import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an EarthMC API key out of chat, inside a short window the player opens by hand.
 *
 * <p>Pasting a 171-character token into a text box is the step people get wrong: it arrives cut
 * short, or lands in the wrong field, or the box is left without submitting. Running
 * {@code /api key create} -- or {@code /api key copy} for a key that already exists -- and having
 * the addon take the key straight out of the reply skips all of that.
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

    /**
     * How long to wait after the command goes out before concluding EarthMC had nothing to hand
     * back. Long enough to cover a round trip to a server that is thinking; short enough that the
     * player has not yet started wondering what went wrong.
     */
    private static final long ANSWER_GRACE_MS = 6_000L;

    private static volatile long armedUntilMs = 0L;
    private static volatile long copySentAtMs = 0L;
    private static volatile boolean copyAnswered = false;

    private ApiKeyCapture() {}

    /** Opens the window. Called only from the settings prompt. */
    public static void arm() {
        armedUntilMs = System.currentTimeMillis() + WINDOW_MS;
        copySentAtMs = 0L;
        copyAnswered = false;
    }

    public static void cancel() { armedUntilMs = 0L; copySentAtMs = 0L; }

    /** Starts the clock on a {@code /api key copy} the player has just sent. */
    public static void noteCopyRequested() {
        if (!armed()) return;
        copySentAtMs = System.currentTimeMillis();
        copyAnswered = false;
    }

    /**
     * True exactly once, when a {@code copy} has gone long enough without producing a key.
     *
     * <p>This is how the addon learns something it cannot ask: whether the player has a key at all.
     * Nothing it can read says so -- a config with no key covers a fresh install, a new machine and
     * a key made for some other tool just as well as it covers a player who has never made one, and
     * no keyless endpoint will answer the question. So it does not guess. It runs the command that
     * is safe either way and lets the absence of a reply be the answer.
     *
     * <p>Deliberately a timeout rather than a match on what EarthMC said. The refusal's wording is
     * not documented, could be translated, and could change; "no key arrived" cannot.
     */
    public static boolean copyWentUnanswered() {
        if (copySentAtMs == 0L || copyAnswered) return false;
        if (!armed()) { copySentAtMs = 0L; return false; }
        if (System.currentTimeMillis() - copySentAtMs < ANSWER_GRACE_MS) return false;
        copyAnswered = true;
        return true;
    }

    public static boolean armed() { return System.currentTimeMillis() < armedUntilMs; }

    public static int secondsLeft() {
        long left = armedUntilMs - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) Math.ceil(left / 1000.0);
    }

    /**
     * The key this message carries, or null if it carries none.
     *
     * <p>Deliberately looks past what is drawn on screen. EarthMC's {@code /api key copy} is named
     * for the way it hands a key over: a line you click, with the key in the click event and
     * something like "click to copy" in the text. A matcher that read only the visible string
     * would find nothing there, so this walks every run of the message and reads the three
     * carriers a key can actually reach a player through -- the text itself, a copy-to-clipboard
     * value, and a shift-click insertion.
     *
     * <p>Run and suggested commands are skipped on purpose. They are things a player sends, not
     * things a player copies, so no server puts a secret in one; reading them would only widen the
     * chance of catching some other long token and closing the window on it.
     *
     * <p>The longest match wins, so a key printed beside an id or a URL fragment is still the one
     * taken. The value is returned, never logged.
     */
    public static String findKey(Text message) {
        if (message == null) return null;
        // One slot rather than a field: the visitor is called inline and the result is read on the
        // same thread before this returns, so there is nothing to share.
        final String[] best = { null };
        message.visit((style, text) -> {
            best[0] = longer(best[0], findKey(text));
            if (style != null) {
                if (style.getClickEvent() instanceof ClickEvent.CopyToClipboard copy) {
                    best[0] = longer(best[0], findKey(copy.value()));
                }
                best[0] = longer(best[0], findKey(style.getInsertion()));
            }
            return Optional.empty();   // never short-circuit; the longest match may be the last run
        }, Style.EMPTY);
        return best[0];
    }

    /** The longest key-shaped run in one string, or null. */
    private static String findKey(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String clean = LEGACY_COLOR.matcher(raw).replaceAll("");
        Matcher m = KEY.matcher(clean);
        String best = null;
        while (m.find()) best = longer(best, m.group());
        return best;
    }

    private static String longer(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.length() > a.length() ? b : a;
    }
}
