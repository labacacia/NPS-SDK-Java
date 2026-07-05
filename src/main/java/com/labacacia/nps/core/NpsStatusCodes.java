// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.core;

import java.util.Map;

/**
 * NPS native status-code constants and HTTP mapping.
 * Mirror of {@code spec/status-codes.md} (version 0.5).
 *
 * <p>Values are the canonical wire strings; consumers MUST compare by string equality.
 * In native mode, status codes are carried directly inside NCP ErrorFrames.
 * In HTTP / Overlay mode, use {@link #toHttpStatus(String)} to obtain the HTTP status code.
 */
public interface NpsStatusCodes {

    // ── Success (OK) ─────────────────────────────────────────────────────────
    String NPS_OK            = "NPS-OK";
    String NPS_OK_ACCEPTED   = "NPS-OK-ACCEPTED";
    String NPS_OK_NO_CONTENT = "NPS-OK-NO-CONTENT";

    // ── Client Errors (CLIENT) ───────────────────────────────────────────────
    String NPS_CLIENT_BAD_FRAME     = "NPS-CLIENT-BAD-FRAME";
    String NPS_CLIENT_BAD_PARAM     = "NPS-CLIENT-BAD-PARAM";
    String NPS_CLIENT_NOT_FOUND     = "NPS-CLIENT-NOT-FOUND";
    String NPS_CLIENT_CONFLICT      = "NPS-CLIENT-CONFLICT";
    String NPS_CLIENT_GONE          = "NPS-CLIENT-GONE";
    String NPS_CLIENT_UNPROCESSABLE = "NPS-CLIENT-UNPROCESSABLE";

    // ── Authentication & Authorization (AUTH) ───────────────────────────────
    String NPS_AUTH_UNAUTHENTICATED = "NPS-AUTH-UNAUTHENTICATED";
    String NPS_AUTH_FORBIDDEN       = "NPS-AUTH-FORBIDDEN";

    // ── Resource Limits (LIMIT) ──────────────────────────────────────────────
    String NPS_LIMIT_RATE    = "NPS-LIMIT-RATE";
    String NPS_LIMIT_BUDGET  = "NPS-LIMIT-BUDGET";
    String NPS_LIMIT_PAYLOAD = "NPS-LIMIT-PAYLOAD";

    // ── Server Errors (SERVER) ───────────────────────────────────────────────
    String NPS_SERVER_INTERNAL              = "NPS-SERVER-INTERNAL";
    String NPS_SERVER_UNSUPPORTED           = "NPS-SERVER-UNSUPPORTED";
    String NPS_SERVER_UNAVAILABLE           = "NPS-SERVER-UNAVAILABLE";
    String NPS_SERVER_TIMEOUT               = "NPS-SERVER-TIMEOUT";
    String NPS_SERVER_ENCODING_UNSUPPORTED  = "NPS-SERVER-ENCODING-UNSUPPORTED";
    String NPS_DOWNSTREAM_UNAVAILABLE       = "NPS-DOWNSTREAM-UNAVAILABLE";

    // ── Stream (STREAM) ──────────────────────────────────────────────────────
    String NPS_STREAM_SEQ_GAP   = "NPS-STREAM-SEQ-GAP";
    String NPS_STREAM_NOT_FOUND = "NPS-STREAM-NOT-FOUND";
    String NPS_STREAM_LIMIT     = "NPS-STREAM-LIMIT";

    // ── Protocol-Level (PROTO) ───────────────────────────────────────────────
    String NPS_PROTO_VERSION_INCOMPATIBLE = "NPS-PROTO-VERSION-INCOMPATIBLE";
    String NPS_PROTO_PREAMBLE_INVALID     = "NPS-PROTO-PREAMBLE-INVALID";

    // ── Extra codes referenced by protocol error mappings ────────────────────
    /** Rate-limited by reputation policy (NWP-REPUTATION-THROTTLED). */
    String NPS_CLIENT_RATE_LIMITED       = "NPS-CLIENT-RATE-LIMITED";
    /** Exceeded node's max concurrent subscriptions (NWP-SUBSCRIBE-LIMIT-EXCEEDED). */
    String NPS_LIMIT_EXCEEDED            = "NPS-LIMIT-EXCEEDED";
    /** CGN budget exceeded; trimming not possible (NWP-CGN-LIMIT-EXCEEDED). */
    String NPS_CLIENT_REQUEST_TOO_LARGE  = "NPS-CLIENT-REQUEST-TOO-LARGE";

    // ── HTTP status map ──────────────────────────────────────────────────────

    /**
     * Canonical NPS status → HTTP status code mapping for HTTP / Overlay mode.
     * Keys are NPS status strings; values are HTTP status codes.
     *
     * <p>For codes with dual HTTP mappings ({@code NPS-SERVER-TIMEOUT} → 408/504,
     * {@code NPS-DOWNSTREAM-UNAVAILABLE} → 502/503) the primary / most-specific
     * value is stored here (408 and 502 respectively).
     */
    Map<String, Integer> HTTP_STATUS_MAP = Map.ofEntries(
        // OK
        Map.entry(NPS_OK,                          200),
        Map.entry(NPS_OK_ACCEPTED,                 202),
        Map.entry(NPS_OK_NO_CONTENT,               204),
        // CLIENT
        Map.entry(NPS_CLIENT_BAD_FRAME,            400),
        Map.entry(NPS_CLIENT_BAD_PARAM,            400),
        Map.entry(NPS_CLIENT_NOT_FOUND,            404),
        Map.entry(NPS_CLIENT_CONFLICT,             409),
        Map.entry(NPS_CLIENT_GONE,                 410),
        Map.entry(NPS_CLIENT_UNPROCESSABLE,        422),
        // AUTH
        Map.entry(NPS_AUTH_UNAUTHENTICATED,        401),
        Map.entry(NPS_AUTH_FORBIDDEN,              403),
        // LIMIT
        Map.entry(NPS_LIMIT_RATE,                  429),
        Map.entry(NPS_LIMIT_BUDGET,                429),
        Map.entry(NPS_LIMIT_PAYLOAD,               413),
        // SERVER
        Map.entry(NPS_SERVER_INTERNAL,             500),
        Map.entry(NPS_SERVER_UNSUPPORTED,          501),
        Map.entry(NPS_SERVER_UNAVAILABLE,          503),
        Map.entry(NPS_SERVER_TIMEOUT,              408),
        Map.entry(NPS_SERVER_ENCODING_UNSUPPORTED, 415),
        Map.entry(NPS_DOWNSTREAM_UNAVAILABLE,      502),
        // STREAM
        Map.entry(NPS_STREAM_SEQ_GAP,              422),
        Map.entry(NPS_STREAM_NOT_FOUND,            404),
        Map.entry(NPS_STREAM_LIMIT,                429),
        // PROTO
        Map.entry(NPS_PROTO_VERSION_INCOMPATIBLE,  426),
        Map.entry(NPS_PROTO_PREAMBLE_INVALID,      400),
        // Extra
        Map.entry(NPS_CLIENT_RATE_LIMITED,         429),
        Map.entry(NPS_LIMIT_EXCEEDED,              429),
        Map.entry(NPS_CLIENT_REQUEST_TOO_LARGE,    413)
    );

    /**
     * Returns the HTTP status code for a given NPS status string.
     *
     * @param npsStatus the NPS status code string (e.g. {@code "NPS-OK"})
     * @return the corresponding HTTP status code, or {@code 500} if the code is unrecognised
     */
    static int toHttpStatus(String npsStatus) {
        return HTTP_STATUS_MAP.getOrDefault(npsStatus, 500);
    }
}
