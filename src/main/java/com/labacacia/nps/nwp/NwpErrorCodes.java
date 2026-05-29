// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.labacacia.nps.core.NpsStatusCodes;

import java.util.Map;

/**
 * NWP error code string constants. Mirror of {@code spec/error-codes.md} NWP section.
 * Values are the canonical wire strings; consumers MUST compare by string equality.
 */
public interface NwpErrorCodes {

    // ── Auth / NID ───────────────────────────────────────────────────────────
    String NWP_AUTH_NID_SCOPE_VIOLATION    = "NWP-AUTH-NID-SCOPE-VIOLATION";
    String NWP_AUTH_NID_EXPIRED            = "NWP-AUTH-NID-EXPIRED";
    String NWP_AUTH_NID_REVOKED            = "NWP-AUTH-NID-REVOKED";
    String NWP_AUTH_NID_UNTRUSTED_ISSUER   = "NWP-AUTH-NID-UNTRUSTED-ISSUER";
    String NWP_AUTH_NID_CAPABILITY_MISSING = "NWP-AUTH-NID-CAPABILITY-MISSING";
    String NWP_AUTH_ASSURANCE_TOO_LOW      = "NWP-AUTH-ASSURANCE-TOO-LOW";
    /** @deprecated since NPS-RFC-0005; use {@link #NWP_REPUTATION_REJECTED} or {@link #NWP_REPUTATION_BANNED} */
    @Deprecated
    String NWP_AUTH_REPUTATION_BLOCKED     = "NWP-AUTH-REPUTATION-BLOCKED";

    // ── Reputation ───────────────────────────────────────────────────────────
    String NWP_REPUTATION_THROTTLED = "NWP-REPUTATION-THROTTLED";
    String NWP_REPUTATION_REJECTED  = "NWP-REPUTATION-REJECTED";
    String NWP_REPUTATION_BANNED    = "NWP-REPUTATION-BANNED";

    // ── Query ────────────────────────────────────────────────────────────────
    String NWP_QUERY_FILTER_INVALID        = "NWP-QUERY-FILTER-INVALID";
    String NWP_QUERY_FIELD_UNKNOWN         = "NWP-QUERY-FIELD-UNKNOWN";
    String NWP_QUERY_CURSOR_INVALID        = "NWP-QUERY-CURSOR-INVALID";
    String NWP_QUERY_REGEX_UNSAFE          = "NWP-QUERY-REGEX-UNSAFE";
    String NWP_QUERY_VECTOR_UNSUPPORTED    = "NWP-QUERY-VECTOR-UNSUPPORTED";
    String NWP_QUERY_AGGREGATE_UNSUPPORTED = "NWP-QUERY-AGGREGATE-UNSUPPORTED";
    String NWP_QUERY_AGGREGATE_INVALID     = "NWP-QUERY-AGGREGATE-INVALID";
    String NWP_QUERY_STREAM_UNSUPPORTED    = "NWP-QUERY-STREAM-UNSUPPORTED";

    // ── Action ───────────────────────────────────────────────────────────────
    String NWP_ACTION_NOT_FOUND             = "NWP-ACTION-NOT-FOUND";
    String NWP_ACTION_PARAMS_INVALID        = "NWP-ACTION-PARAMS-INVALID";
    String NWP_ACTION_IDEMPOTENCY_CONFLICT  = "NWP-ACTION-IDEMPOTENCY-CONFLICT";

    // ── Task ─────────────────────────────────────────────────────────────────
    String NWP_TASK_NOT_FOUND         = "NWP-TASK-NOT-FOUND";
    String NWP_TASK_ALREADY_CANCELLED = "NWP-TASK-ALREADY-CANCELLED";
    String NWP_TASK_ALREADY_COMPLETED = "NWP-TASK-ALREADY-COMPLETED";
    String NWP_TASK_ALREADY_FAILED    = "NWP-TASK-ALREADY-FAILED";

    // ── Subscribe ────────────────────────────────────────────────────────────
    String NWP_SUBSCRIBE_STREAM_NOT_FOUND    = "NWP-SUBSCRIBE-STREAM-NOT-FOUND";
    String NWP_SUBSCRIBE_LIMIT_EXCEEDED      = "NWP-SUBSCRIBE-LIMIT-EXCEEDED";
    String NWP_SUBSCRIBE_FILTER_UNSUPPORTED  = "NWP-SUBSCRIBE-FILTER-UNSUPPORTED";
    String NWP_SUBSCRIBE_INTERRUPTED         = "NWP-SUBSCRIBE-INTERRUPTED";
    String NWP_SUBSCRIBE_SEQ_TOO_OLD         = "NWP-SUBSCRIBE-SEQ-TOO-OLD";

    // ── Budget / CGN ─────────────────────────────────────────────────────────
    String NWP_BUDGET_EXCEEDED     = "NWP-BUDGET-EXCEEDED";
    String NWP_CGN_LIMIT_EXCEEDED  = "NWP-CGN-LIMIT-EXCEEDED";

    // ── Depth / Graph ────────────────────────────────────────────────────────
    String NWP_DEPTH_EXCEEDED = "NWP-DEPTH-EXCEEDED";
    String NWP_GRAPH_CYCLE    = "NWP-GRAPH-CYCLE";

    // ── Node ─────────────────────────────────────────────────────────────────
    String NWP_NODE_UNAVAILABLE = "NWP-NODE-UNAVAILABLE";

    // ── Manifest ─────────────────────────────────────────────────────────────
    String NWP_MANIFEST_VERSION_UNSUPPORTED  = "NWP-MANIFEST-VERSION-UNSUPPORTED";
    String NWP_MANIFEST_NODE_TYPE_REMOVED    = "NWP-MANIFEST-NODE-TYPE-REMOVED";
    String NWP_MANIFEST_NODE_TYPE_UNKNOWN    = "NWP-MANIFEST-NODE-TYPE-UNKNOWN";

    // ── Rate ─────────────────────────────────────────────────────────────────
    String NWP_RATE_LIMIT_EXCEEDED = "NWP-RATE-LIMIT-EXCEEDED";

    // ── Reserved / Topology ──────────────────────────────────────────────────
    String NWP_RESERVED_TYPE_UNSUPPORTED      = "NWP-RESERVED-TYPE-UNSUPPORTED";
    String NWP_TOPOLOGY_UNAUTHORIZED          = "NWP-TOPOLOGY-UNAUTHORIZED";
    String NWP_TOPOLOGY_UNSUPPORTED_SCOPE     = "NWP-TOPOLOGY-UNSUPPORTED-SCOPE";
    String NWP_TOPOLOGY_DEPTH_UNSUPPORTED     = "NWP-TOPOLOGY-DEPTH-UNSUPPORTED";
    String NWP_TOPOLOGY_FILTER_UNSUPPORTED    = "NWP-TOPOLOGY-FILTER-UNSUPPORTED";

    // ── NWP error → NPS status mapping ───────────────────────────────────────

    /**
     * Maps each NWP error code to its corresponding NPS status code.
     */
    Map<String, String> NWP_TO_NPS_STATUS = Map.ofEntries(
        Map.entry(NWP_AUTH_NID_SCOPE_VIOLATION,    NpsStatusCodes.NPS_AUTH_FORBIDDEN),
        Map.entry(NWP_AUTH_NID_EXPIRED,            NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED),
        Map.entry(NWP_AUTH_NID_REVOKED,            NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED),
        Map.entry(NWP_AUTH_NID_UNTRUSTED_ISSUER,   NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED),
        Map.entry(NWP_AUTH_NID_CAPABILITY_MISSING, NpsStatusCodes.NPS_AUTH_FORBIDDEN),
        Map.entry(NWP_AUTH_ASSURANCE_TOO_LOW,      NpsStatusCodes.NPS_AUTH_FORBIDDEN),
        Map.entry(NWP_AUTH_REPUTATION_BLOCKED,     NpsStatusCodes.NPS_AUTH_FORBIDDEN),
        Map.entry(NWP_REPUTATION_THROTTLED,        NpsStatusCodes.NPS_CLIENT_RATE_LIMITED),
        Map.entry(NWP_REPUTATION_REJECTED,         NpsStatusCodes.NPS_AUTH_FORBIDDEN),
        Map.entry(NWP_REPUTATION_BANNED,           NpsStatusCodes.NPS_AUTH_FORBIDDEN),
        Map.entry(NWP_QUERY_FILTER_INVALID,        NpsStatusCodes.NPS_CLIENT_BAD_PARAM),
        Map.entry(NWP_QUERY_FIELD_UNKNOWN,         NpsStatusCodes.NPS_CLIENT_BAD_PARAM),
        Map.entry(NWP_QUERY_CURSOR_INVALID,        NpsStatusCodes.NPS_CLIENT_BAD_PARAM),
        Map.entry(NWP_QUERY_REGEX_UNSAFE,          NpsStatusCodes.NPS_CLIENT_BAD_PARAM),
        Map.entry(NWP_QUERY_VECTOR_UNSUPPORTED,    NpsStatusCodes.NPS_SERVER_UNSUPPORTED),
        Map.entry(NWP_QUERY_AGGREGATE_UNSUPPORTED, NpsStatusCodes.NPS_SERVER_UNSUPPORTED),
        Map.entry(NWP_QUERY_AGGREGATE_INVALID,     NpsStatusCodes.NPS_CLIENT_BAD_PARAM),
        Map.entry(NWP_QUERY_STREAM_UNSUPPORTED,    NpsStatusCodes.NPS_SERVER_UNSUPPORTED),
        Map.entry(NWP_ACTION_NOT_FOUND,            NpsStatusCodes.NPS_CLIENT_NOT_FOUND),
        Map.entry(NWP_ACTION_PARAMS_INVALID,       NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE),
        Map.entry(NWP_ACTION_IDEMPOTENCY_CONFLICT, NpsStatusCodes.NPS_CLIENT_CONFLICT),
        Map.entry(NWP_TASK_NOT_FOUND,              NpsStatusCodes.NPS_CLIENT_NOT_FOUND),
        Map.entry(NWP_TASK_ALREADY_CANCELLED,      NpsStatusCodes.NPS_CLIENT_CONFLICT),
        Map.entry(NWP_TASK_ALREADY_COMPLETED,      NpsStatusCodes.NPS_CLIENT_CONFLICT),
        Map.entry(NWP_TASK_ALREADY_FAILED,         NpsStatusCodes.NPS_CLIENT_CONFLICT),
        Map.entry(NWP_SUBSCRIBE_STREAM_NOT_FOUND,  NpsStatusCodes.NPS_CLIENT_NOT_FOUND),
        Map.entry(NWP_SUBSCRIBE_LIMIT_EXCEEDED,    NpsStatusCodes.NPS_LIMIT_EXCEEDED),
        Map.entry(NWP_SUBSCRIBE_FILTER_UNSUPPORTED,NpsStatusCodes.NPS_SERVER_UNSUPPORTED),
        Map.entry(NWP_SUBSCRIBE_INTERRUPTED,       NpsStatusCodes.NPS_SERVER_UNAVAILABLE),
        Map.entry(NWP_SUBSCRIBE_SEQ_TOO_OLD,       NpsStatusCodes.NPS_CLIENT_CONFLICT),
        Map.entry(NWP_BUDGET_EXCEEDED,             NpsStatusCodes.NPS_LIMIT_BUDGET),
        Map.entry(NWP_CGN_LIMIT_EXCEEDED,          NpsStatusCodes.NPS_CLIENT_REQUEST_TOO_LARGE),
        Map.entry(NWP_DEPTH_EXCEEDED,              NpsStatusCodes.NPS_CLIENT_BAD_PARAM),
        Map.entry(NWP_GRAPH_CYCLE,                 NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE),
        Map.entry(NWP_NODE_UNAVAILABLE,            NpsStatusCodes.NPS_SERVER_UNAVAILABLE),
        Map.entry(NWP_MANIFEST_VERSION_UNSUPPORTED,NpsStatusCodes.NPS_CLIENT_BAD_PARAM),
        Map.entry(NWP_MANIFEST_NODE_TYPE_REMOVED,  NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(NWP_MANIFEST_NODE_TYPE_UNKNOWN,  NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(NWP_RATE_LIMIT_EXCEEDED,         NpsStatusCodes.NPS_LIMIT_RATE),
        Map.entry(NWP_RESERVED_TYPE_UNSUPPORTED,   NpsStatusCodes.NPS_SERVER_UNSUPPORTED),
        Map.entry(NWP_TOPOLOGY_UNAUTHORIZED,       NpsStatusCodes.NPS_AUTH_FORBIDDEN),
        Map.entry(NWP_TOPOLOGY_UNSUPPORTED_SCOPE,  NpsStatusCodes.NPS_CLIENT_BAD_PARAM),
        Map.entry(NWP_TOPOLOGY_DEPTH_UNSUPPORTED,  NpsStatusCodes.NPS_CLIENT_BAD_PARAM),
        Map.entry(NWP_TOPOLOGY_FILTER_UNSUPPORTED, NpsStatusCodes.NPS_CLIENT_BAD_PARAM)
    );
}
