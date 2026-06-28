// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

import com.labacacia.nps.core.NpsStatusCodes;

import java.util.Map;

/**
 * NDP error code string constants. Mirror of {@code spec/error-codes.md} NDP section.
 * Values are the canonical wire strings; consumers MUST compare by string equality.
 */
public interface NdpErrorCodes {

    // ── Resolve ───────────────────────────────────────────────────────────────
    String NDP_RESOLVE_NOT_FOUND = "NDP-RESOLVE-NOT-FOUND";
    String NDP_RESOLVE_AMBIGUOUS = "NDP-RESOLVE-AMBIGUOUS";
    String NDP_RESOLVE_TIMEOUT   = "NDP-RESOLVE-TIMEOUT";
    String NDP_RESOLVE_STALE     = "NDP-RESOLVE-STALE";

    // ── Announce ──────────────────────────────────────────────────────────────
    String NDP_ANNOUNCE_SIGNATURE_INVALID = "NDP-ANNOUNCE-SIGNATURE-INVALID";
    String NDP_ANNOUNCE_NID_MISMATCH      = "NDP-ANNOUNCE-NID-MISMATCH";
    String NDP_ANNOUNCE_ROLE_REMOVED      = "NDP-ANNOUNCE-ROLE-REMOVED";
    String NDP_ANNOUNCE_ROLE_UNKNOWN      = "NDP-ANNOUNCE-ROLE-UNKNOWN";
    String NDP_ANNOUNCE_CONFLICT          = "NDP-ANNOUNCE-CONFLICT";
    String NDP_ANNOUNCE_PROFILE_VIOLATION = "NDP-ANNOUNCE-PROFILE-VIOLATION";

    // ── Graph ─────────────────────────────────────────────────────────────────
    String NDP_GRAPH_SEQ_ROLLBACK = "NDP-GRAPH-SEQ-ROLLBACK";
    String NDP_GRAPH_SEQ_GAP      = "NDP-GRAPH-SEQ-GAP";
    /** GraphFrame §3.3 topology-snapshot: structure is invalid (NPS-4 roadmap). */
    String NDP_GRAPH_INVALID      = "NDP-GRAPH-INVALID";
    /** GraphFrame §3.3 topology-snapshot: node/edge count exceeds limit (NPS-4 roadmap). */
    String NDP_GRAPH_TOO_LARGE    = "NDP-GRAPH-TOO-LARGE";

    // ── Federation ────────────────────────────────────────────────────────────
    /** §9 federation forwarding: maximum hop count exceeded (NPS-4 roadmap). */
    String NDP_FEDERATION_LOOP    = "NDP-FEDERATION-LOOP";

    // ── Issuer / CA ───────────────────────────────────────────────────────────
    String NDP_ISSUER_NOT_ALLOWED  = "NDP-ISSUER-NOT-ALLOWED";
    String NDP_CA_ATTEST_REQUIRED  = "NDP-CA-ATTEST-REQUIRED";

    // ── Registry ──────────────────────────────────────────────────────────────
    String NDP_REGISTRY_UNAVAILABLE = "NDP-REGISTRY-UNAVAILABLE";

    // ── Heartbeat (v0.9) ──────────────────────────────────────────────────────
    /** Announce not received within heartbeat_interval_ms × 3 dead-peer threshold (NDP v0.9). */
    String NDP_ANNOUNCE_STALE       = "NDP-ANNOUNCE-STALE";

    // ── NDP error → NPS status mapping ───────────────────────────────────────

    /**
     * Maps each NDP error code to its corresponding NPS status code.
     */
    Map<String, String> NDP_TO_NPS_STATUS = Map.ofEntries(
        Map.entry(NDP_RESOLVE_NOT_FOUND,          NpsStatusCodes.NPS_CLIENT_NOT_FOUND),
        Map.entry(NDP_RESOLVE_AMBIGUOUS,          NpsStatusCodes.NPS_CLIENT_CONFLICT),
        Map.entry(NDP_RESOLVE_TIMEOUT,            NpsStatusCodes.NPS_SERVER_TIMEOUT),
        Map.entry(NDP_RESOLVE_STALE,              NpsStatusCodes.NPS_CLIENT_NOT_FOUND),
        Map.entry(NDP_ANNOUNCE_SIGNATURE_INVALID, NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED),
        Map.entry(NDP_ANNOUNCE_NID_MISMATCH,      NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(NDP_ANNOUNCE_ROLE_REMOVED,      NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(NDP_ANNOUNCE_ROLE_UNKNOWN,      NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(NDP_ANNOUNCE_CONFLICT,          NpsStatusCodes.NPS_CLIENT_CONFLICT),
        Map.entry(NDP_ANNOUNCE_PROFILE_VIOLATION, NpsStatusCodes.NPS_AUTH_FORBIDDEN),
        Map.entry(NDP_GRAPH_SEQ_ROLLBACK,         NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(NDP_GRAPH_SEQ_GAP,              NpsStatusCodes.NPS_STREAM_SEQ_GAP),
        Map.entry(NDP_GRAPH_INVALID,              NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(NDP_GRAPH_TOO_LARGE,            NpsStatusCodes.NPS_LIMIT_PAYLOAD),
        Map.entry(NDP_FEDERATION_LOOP,            NpsStatusCodes.NPS_CLIENT_CONFLICT),
        Map.entry(NDP_ISSUER_NOT_ALLOWED,         NpsStatusCodes.NPS_AUTH_FORBIDDEN),
        Map.entry(NDP_CA_ATTEST_REQUIRED,         NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED),
        Map.entry(NDP_REGISTRY_UNAVAILABLE,       NpsStatusCodes.NPS_SERVER_UNAVAILABLE),
        Map.entry(NDP_ANNOUNCE_STALE,             NpsStatusCodes.NPS_CLIENT_NOT_FOUND)
    );
}
