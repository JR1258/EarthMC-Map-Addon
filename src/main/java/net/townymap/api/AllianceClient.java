package net.townymap.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Fetches EarthMC's meganations and alliances from the BreakTheBot API (breakthebot.sparked.network) — the
 * project's own registry (the official map exposes no alliance layer). Each entry carries the nations it
 * groups and a colour, which the map's Meganations / Alliances modes recolour those nations' towns with.
 *
 * <p>The API has no bulk-details endpoint: {@code GET /alliances} returns a name→uuid map, then one
 * {@code POST /alliances} per name returns that alliance's full record. Blocking; called off the render
 * thread and cached, so the request fan-out only happens on a (throttled) refresh.
 */
public final class AllianceClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("TownyMapAddon");
    private static final String UA = "JR1258/EarthMC-Map-Addon alliances";
    private static final String BASE = "https://breakthebot.sparked.network";

    /**
     * One alliance or meganation: {@code mega} distinguishes the two, {@code nationsLower} are its member
     * nation names lower-cased, and the two colours are the town stroke/fill RGB (BreakTheBot gives a single
     * colour, used for both).
     */
    public record Alliance(String identifier, String label, boolean mega,
                           Set<String> nationsLower, List<String> nations,
                           int outlineRgb, int fillRgb) {}

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Fetches and parses every alliance. Blocking — call off the render thread. Empty list on any failure. */
    public List<Alliance> fetch() {
        try {
            HttpResponse<byte[]> listResp = http.send(
                    HttpRequest.newBuilder(URI.create(BASE + "/alliances"))
                            .timeout(Duration.ofSeconds(20))
                            .header("User-Agent", UA)
                            .header("Accept-Encoding", "gzip")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (listResp.statusCode() / 100 != 2) {
                LOGGER.warn("[TownyMap] Alliance list GET → HTTP {}", listResp.statusCode());
                return List.of();
            }
            JsonElement listEl = JsonParser.parseString(decode(listResp));
            if (!listEl.isJsonObject()) return List.of();

            // One POST per alliance, and there are dozens. If the source starts refusing us partway
            // through, every remaining request is refused too -- so stop, and report nothing rather
            // than a half list, which the caller would otherwise apply as if it were complete and
            // leave most nations unlabelled until the next refresh.
            List<Alliance> out = new ArrayList<>();
            try {
                for (String name : listEl.getAsJsonObject().keySet()) {   // { "African Union": "uuid", … }
                    Alliance a = fetchOne(name);
                    if (a != null) out.add(a);
                }
            } catch (RateLimited e) {
                LOGGER.warn("[TownyMap] Alliance fetch rate-limited after {} of {}; keeping the old data",
                        out.size(), listEl.getAsJsonObject().size());
                return List.of();
            }
            LOGGER.info("[TownyMap] Loaded {} alliances/meganations from BreakTheBot", out.size());
            return out;
        } catch (Exception e) {
            LOGGER.warn("[TownyMap] Alliance fetch failed: {}", e.toString());
            return List.of();
        }
    }

    /** POST /alliances {name} → the full Alliance_Model for one alliance. Null on any failure. */
    private Alliance fetchOne(String name) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("name", name);
            HttpResponse<byte[]> resp = http.send(
                    HttpRequest.newBuilder(URI.create(BASE + "/alliances"))
                            .timeout(Duration.ofSeconds(15))
                            .header("User-Agent", UA)
                            .header("Content-Type", "application/json")
                            .header("Accept-Encoding", "gzip")
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 429) throw new RateLimited();
            if (resp.statusCode() / 100 != 2) return null;
            JsonElement el = JsonParser.parseString(decode(resp));
            return el.isJsonObject() ? parseModel(el.getAsJsonObject()) : null;
        } catch (RateLimited e) {
            throw e;                       // the loop above needs to see this one, not swallow it
        } catch (Exception e) {
            return null;
        }
    }

    /** Raised out of the per-alliance loop when the source starts refusing us. No stack trace: it is
     *  flow control between two methods in this file, not a fault anyone needs to read. */
    private static final class RateLimited extends RuntimeException {
        RateLimited() { super(null, null, false, false); }
    }

    /** Maps a BreakTheBot Alliance_Model to our {@link Alliance}. */
    private static Alliance parseModel(JsonObject a) {
        String label = str(a, "name");
        String identifier = str(a, "short_name");
        boolean mega = str(a, "type").toUpperCase(Locale.ROOT).contains("MEGA");   // "MEGANATION" vs "ALLIANCE"

        Set<String> nations = new HashSet<>();
        List<String> display = new ArrayList<>();   // original casing, for the alliance panel's roster
        JsonElement nEl = a.get("nations");
        if (nEl != null && nEl.isJsonArray()) {
            for (JsonElement el : nEl.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                String nn = str(el.getAsJsonObject(), "name");
                if (nn.isBlank()) continue;
                if (nations.add(nn.toLowerCase(Locale.ROOT))) display.add(nn);
            }
        }
        if (nations.isEmpty()) return null;
        display.sort(String.CASE_INSENSITIVE_ORDER);

        // Fall back to a per-name colour when unset: near-black placeholders (e.g. #000001) would blend into
        // the blacked-out non-member towns, and a shared default would make different alliances look alike.
        int rgb = colorFromName(!label.isBlank() ? label : identifier);
        JsonElement colEl = a.get("color");
        if (colEl != null && colEl.isJsonPrimitive()) {
            try {
                int c = (int) (colEl.getAsLong() & 0xFFFFFFL);
                int brightness = ((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF);
                if (brightness >= 24) rgb = c;
            } catch (Exception ignored) { /* keep the name-derived colour */ }
        }
        return new Alliance(identifier, label, mega, nations, List.copyOf(display), rgb, rgb);
    }

    /** A stable, distinct, bright colour derived from an alliance's name — used when it has no colour set. */
    private static int colorFromName(String s) {
        float hue = Math.floorMod(s == null ? 0 : s.toLowerCase(Locale.ROOT).hashCode(), 360) / 60f;
        int i = (int) hue;
        float f = hue - i, v = 0.95f, sat = 0.6f;
        float p = v * (1 - sat), q = v * (1 - sat * f), t = v * (1 - sat * (1 - f));
        float r, g, b;
        switch (i % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    private static String decode(HttpResponse<byte[]> resp) throws Exception {
        byte[] bytes = resp.body();
        boolean gzip = (bytes.length > 1 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B)
                || "gzip".equalsIgnoreCase(resp.headers().firstValue("content-encoding").orElse(""));
        if (gzip) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : "";
    }
}
