// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

/**
 * NWP error code string constants. Mirror of {@code spec/error-codes.md} NWP section.
 * Values are the canonical wire strings; consumers MUST compare by string equality.
 */
public final class NwpErrorCodes {

    private NwpErrorCodes() {}

    // ── Auth ─────────────────────────────────────────────────────────────────
    public static final String AUTH_NID_SCOPE_VIOLATION    = "NWP-AUTH-NID-SCOPE-VIOLATION";
    public static final String AUTH_NID_EXPIRED            = "NWP-AUTH-NID-EXPIRED";
    public static final String AUTH_NID_REVOKED            = "NWP-AUTH-NID-REVOKED";
    public static final String AUTH_NID_UNTRUSTED_ISSUER   = "NWP-AUTH-NID-UNTRUSTED-ISSUER";
    public static final String AUTH_NID_CAPABILITY_MISSING = "NWP-AUTH-NID-CAPABILITY-MISSING";
    public static final String AUTH_ASSURANCE_TOO_LOW      = "NWP-AUTH-ASSURANCE-TOO-LOW";
    public static final String AUTH_REPUTATION_BLOCKED     = "NWP-AUTH-REPUTATION-BLOCKED";

    // ── Query ─────────────────────────────────────────────────────────────────
    public static final String QUERY_FILTER_INVALID        = "NWP-QUERY-FILTER-INVALID";
    public static final String QUERY_FIELD_UNKNOWN         = "NWP-QUERY-FIELD-UNKNOWN";
    public static final String QUERY_CURSOR_INVALID        = "NWP-QUERY-CURSOR-INVALID";
    public static final String QUERY_REGEX_UNSAFE          = "NWP-QUERY-REGEX-UNSAFE";
    public static final String QUERY_VECTOR_UNSUPPORTED    = "NWP-QUERY-VECTOR-UNSUPPORTED";
    public static final String QUERY_AGGREGATE_UNSUPPORTED = "NWP-QUERY-AGGREGATE-UNSUPPORTED";
    public static final String QUERY_AGGREGATE_INVALID     = "NWP-QUERY-AGGREGATE-INVALID";
    public static final String QUERY_STREAM_UNSUPPORTED    = "NWP-QUERY-STREAM-UNSUPPORTED";

    // ── Action ────────────────────────────────────────────────────────────────
    public static final String ACTION_NOT_FOUND            = "NWP-ACTION-NOT-FOUND";
    public static final String ACTION_PARAMS_INVALID       = "NWP-ACTION-PARAMS-INVALID";
    public static final String ACTION_IDEMPOTENCY_CONFLICT = "NWP-ACTION-IDEMPOTENCY-CONFLICT";

    // ── Task ──────────────────────────────────────────────────────────────────
    public static final String TASK_NOT_FOUND         = "NWP-TASK-NOT-FOUND";
    public static final String TASK_ALREADY_CANCELLED = "NWP-TASK-ALREADY-CANCELLED";
    public static final String TASK_ALREADY_COMPLETED = "NWP-TASK-ALREADY-COMPLETED";
    public static final String TASK_ALREADY_FAILED    = "NWP-TASK-ALREADY-FAILED";

    // ── Subscribe ─────────────────────────────────────────────────────────────
    public static final String SUBSCRIBE_STREAM_NOT_FOUND   = "NWP-SUBSCRIBE-STREAM-NOT-FOUND";
    public static final String SUBSCRIBE_LIMIT_EXCEEDED     = "NWP-SUBSCRIBE-LIMIT-EXCEEDED";
    public static final String SUBSCRIBE_FILTER_UNSUPPORTED = "NWP-SUBSCRIBE-FILTER-UNSUPPORTED";
    public static final String SUBSCRIBE_INTERRUPTED        = "NWP-SUBSCRIBE-INTERRUPTED";
    public static final String SUBSCRIBE_SEQ_TOO_OLD        = "NWP-SUBSCRIBE-SEQ-TOO-OLD";

    // ── Infrastructure ────────────────────────────────────────────────────────
    public static final String BUDGET_EXCEEDED     = "NWP-BUDGET-EXCEEDED";
    public static final String DEPTH_EXCEEDED      = "NWP-DEPTH-EXCEEDED";
    public static final String GRAPH_CYCLE         = "NWP-GRAPH-CYCLE";
    public static final String NODE_UNAVAILABLE    = "NWP-NODE-UNAVAILABLE";
    public static final String RATE_LIMIT_EXCEEDED = "NWP-RATE-LIMIT-EXCEEDED";

    // ── Manifest ──────────────────────────────────────────────────────────────
    public static final String MANIFEST_VERSION_UNSUPPORTED = "NWP-MANIFEST-VERSION-UNSUPPORTED";
    public static final String MANIFEST_NODE_TYPE_REMOVED   = "NWP-MANIFEST-NODE-TYPE-REMOVED";
    public static final String MANIFEST_NODE_TYPE_UNKNOWN   = "NWP-MANIFEST-NODE-TYPE-UNKNOWN";

    // ── Topology (alpha.4+) ───────────────────────────────────────────────────
    public static final String TOPOLOGY_UNAUTHORIZED       = "NWP-TOPOLOGY-UNAUTHORIZED";
    public static final String TOPOLOGY_UNSUPPORTED_SCOPE  = "NWP-TOPOLOGY-UNSUPPORTED-SCOPE";
    public static final String TOPOLOGY_DEPTH_UNSUPPORTED  = "NWP-TOPOLOGY-DEPTH-UNSUPPORTED";
    public static final String TOPOLOGY_FILTER_UNSUPPORTED = "NWP-TOPOLOGY-FILTER-UNSUPPORTED";

    // ── Reserved type (alpha.5+) ──────────────────────────────────────────────
    public static final String RESERVED_TYPE_UNSUPPORTED = "NWP-RESERVED-TYPE-UNSUPPORTED";
}
