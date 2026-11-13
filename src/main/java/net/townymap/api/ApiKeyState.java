package net.townymap.api;

/**
 * Where the EarthMC API key stands.
 *
 * <p>EarthMC requires a key in the {@code Authorization} header on every endpoint from 2026-11-19,
 * and stops reading the {@code key} field out of POST bodies at the same time. The mod sends the
 * header only, and treats a missing or refused key the way the server is about to: the request is
 * not made at all, rather than quietly working until the deadline passes and then failing for
 * everyone at once.
 */
public enum ApiKeyState {
    /** No key configured. Nothing is sent to the API. */
    MISSING,
    /** A key is set but has not been checked yet. Requests go out normally. */
    UNVERIFIED,
    /** EarthMC accepted the key. */
    OK,
    /** EarthMC refused the key (401/403). Requests are held, with an occasional re-check. */
    REJECTED;

    /** Whether API-backed features can run. */
    public boolean usable() {
        return this == UNVERIFIED || this == OK;
    }
}
