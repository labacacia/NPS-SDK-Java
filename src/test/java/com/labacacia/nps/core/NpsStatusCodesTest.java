// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.core;

import com.labacacia.nps.ncp.NcpErrorCodes;
import com.labacacia.nps.ndp.NdpErrorCodes;
import com.labacacia.nps.nip.NipErrorCodes;
import com.labacacia.nps.nop.NopErrorCodes;
import com.labacacia.nps.nwp.NwpErrorCodes;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests for {@link NpsStatusCodes} constants, HTTP mapping,
 * and all protocol error-code → NPS-status maps.
 */
class NpsStatusCodesTest {

    // ── NpsStatusCodes constants ──────────────────────────────────────────────

    @Test
    void keyConstantsHaveExpectedWireValues() {
        assertEquals("NPS-OK",                    NpsStatusCodes.NPS_OK);
        assertEquals("NPS-OK-ACCEPTED",           NpsStatusCodes.NPS_OK_ACCEPTED);
        assertEquals("NPS-OK-NO-CONTENT",         NpsStatusCodes.NPS_OK_NO_CONTENT);
        assertEquals("NPS-CLIENT-BAD-FRAME",      NpsStatusCodes.NPS_CLIENT_BAD_FRAME);
        assertEquals("NPS-CLIENT-NOT-FOUND",      NpsStatusCodes.NPS_CLIENT_NOT_FOUND);
        assertEquals("NPS-CLIENT-CONFLICT",       NpsStatusCodes.NPS_CLIENT_CONFLICT);
        assertEquals("NPS-AUTH-UNAUTHENTICATED",  NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED);
        assertEquals("NPS-AUTH-FORBIDDEN",        NpsStatusCodes.NPS_AUTH_FORBIDDEN);
        assertEquals("NPS-LIMIT-PAYLOAD",         NpsStatusCodes.NPS_LIMIT_PAYLOAD);
        assertEquals("NPS-SERVER-INTERNAL",       NpsStatusCodes.NPS_SERVER_INTERNAL);
        assertEquals("NPS-STREAM-SEQ-GAP",        NpsStatusCodes.NPS_STREAM_SEQ_GAP);
        assertEquals("NPS-PROTO-VERSION-INCOMPATIBLE", NpsStatusCodes.NPS_PROTO_VERSION_INCOMPATIBLE);
        assertEquals("NPS-DOWNSTREAM-UNAVAILABLE",NpsStatusCodes.NPS_DOWNSTREAM_UNAVAILABLE);
    }

    // ── HTTP_STATUS_MAP coverage ──────────────────────────────────────────────

    @Test
    void httpStatusMapContainsAll25SpecCodes() {
        Set<String> specCodes = Set.of(
            NpsStatusCodes.NPS_OK,
            NpsStatusCodes.NPS_OK_ACCEPTED,
            NpsStatusCodes.NPS_OK_NO_CONTENT,
            NpsStatusCodes.NPS_CLIENT_BAD_FRAME,
            NpsStatusCodes.NPS_CLIENT_BAD_PARAM,
            NpsStatusCodes.NPS_CLIENT_NOT_FOUND,
            NpsStatusCodes.NPS_CLIENT_CONFLICT,
            NpsStatusCodes.NPS_CLIENT_GONE,
            NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE,
            NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED,
            NpsStatusCodes.NPS_AUTH_FORBIDDEN,
            NpsStatusCodes.NPS_LIMIT_RATE,
            NpsStatusCodes.NPS_LIMIT_BUDGET,
            NpsStatusCodes.NPS_LIMIT_PAYLOAD,
            NpsStatusCodes.NPS_SERVER_INTERNAL,
            NpsStatusCodes.NPS_SERVER_UNSUPPORTED,
            NpsStatusCodes.NPS_SERVER_UNAVAILABLE,
            NpsStatusCodes.NPS_SERVER_TIMEOUT,
            NpsStatusCodes.NPS_SERVER_ENCODING_UNSUPPORTED,
            NpsStatusCodes.NPS_DOWNSTREAM_UNAVAILABLE,
            NpsStatusCodes.NPS_STREAM_SEQ_GAP,
            NpsStatusCodes.NPS_STREAM_NOT_FOUND,
            NpsStatusCodes.NPS_STREAM_LIMIT,
            NpsStatusCodes.NPS_PROTO_VERSION_INCOMPATIBLE,
            NpsStatusCodes.NPS_PROTO_PREAMBLE_INVALID
        );
        Map<String, Integer> map = NpsStatusCodes.HTTP_STATUS_MAP;
        for (String code : specCodes) {
            assertTrue(map.containsKey(code), "HTTP_STATUS_MAP missing: " + code);
        }
    }

    @Test
    void toHttpStatusReturnsExpectedValues() {
        assertEquals(200, NpsStatusCodes.toHttpStatus(NpsStatusCodes.NPS_OK));
        assertEquals(202, NpsStatusCodes.toHttpStatus(NpsStatusCodes.NPS_OK_ACCEPTED));
        assertEquals(204, NpsStatusCodes.toHttpStatus(NpsStatusCodes.NPS_OK_NO_CONTENT));
        assertEquals(400, NpsStatusCodes.toHttpStatus(NpsStatusCodes.NPS_CLIENT_BAD_FRAME));
        assertEquals(401, NpsStatusCodes.toHttpStatus(NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED));
        assertEquals(403, NpsStatusCodes.toHttpStatus(NpsStatusCodes.NPS_AUTH_FORBIDDEN));
        assertEquals(404, NpsStatusCodes.toHttpStatus(NpsStatusCodes.NPS_CLIENT_NOT_FOUND));
        assertEquals(409, NpsStatusCodes.toHttpStatus(NpsStatusCodes.NPS_CLIENT_CONFLICT));
        assertEquals(410, NpsStatusCodes.toHttpStatus(NpsStatusCodes.NPS_CLIENT_GONE));
        assertEquals(413, NpsStatusCodes.toHttpStatus(NpsStatusCodes.NPS_LIMIT_PAYLOAD));
        assertEquals(415, NpsStatusCodes.toHttpStatus(NpsStatusCodes.NPS_SERVER_ENCODING_UNSUPPORTED));
        assertEquals(422, NpsStatusCodes.toHttpStatus(NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE));
        assertEquals(422, NpsStatusCodes.toHttpStatus(NpsStatusCodes.NPS_STREAM_SEQ_GAP));
        assertEquals(426, NpsStatusCodes.toHttpStatus(NpsStatusCodes.NPS_PROTO_VERSION_INCOMPATIBLE));
        assertEquals(429, NpsStatusCodes.toHttpStatus(NpsStatusCodes.NPS_LIMIT_RATE));
        assertEquals(429, NpsStatusCodes.toHttpStatus(NpsStatusCodes.NPS_LIMIT_BUDGET));
        assertEquals(429, NpsStatusCodes.toHttpStatus(NpsStatusCodes.NPS_STREAM_LIMIT));
        assertEquals(500, NpsStatusCodes.toHttpStatus(NpsStatusCodes.NPS_SERVER_INTERNAL));
        assertEquals(501, NpsStatusCodes.toHttpStatus(NpsStatusCodes.NPS_SERVER_UNSUPPORTED));
        assertEquals(502, NpsStatusCodes.toHttpStatus(NpsStatusCodes.NPS_DOWNSTREAM_UNAVAILABLE));
        assertEquals(503, NpsStatusCodes.toHttpStatus(NpsStatusCodes.NPS_SERVER_UNAVAILABLE));
        assertEquals(408, NpsStatusCodes.toHttpStatus(NpsStatusCodes.NPS_SERVER_TIMEOUT));
    }

    @Test
    void toHttpStatusReturnsFallback500ForUnknownCode() {
        assertEquals(500, NpsStatusCodes.toHttpStatus("NPS-UNKNOWN-XYZ"));
    }

    // ── NCP error codes ───────────────────────────────────────────────────────

    @Test
    void ncpErrorCodesMapCoversAll17Codes() {
        Set<String> expected = Set.of(
            NcpErrorCodes.NCP_ANCHOR_NOT_FOUND,
            NcpErrorCodes.NCP_ANCHOR_SCHEMA_INVALID,
            NcpErrorCodes.NCP_ANCHOR_ID_MISMATCH,
            NcpErrorCodes.NCP_ANCHOR_STALE,
            NcpErrorCodes.NCP_FRAME_UNKNOWN_TYPE,
            NcpErrorCodes.NCP_FRAME_PAYLOAD_TOO_LARGE,
            NcpErrorCodes.NCP_FRAME_FLAGS_INVALID,
            NcpErrorCodes.NCP_STREAM_SEQ_GAP,
            NcpErrorCodes.NCP_STREAM_NOT_FOUND,
            NcpErrorCodes.NCP_STREAM_LIMIT_EXCEEDED,
            NcpErrorCodes.NCP_STREAM_WINDOW_OVERFLOW,
            NcpErrorCodes.NCP_ENCODING_UNSUPPORTED,
            NcpErrorCodes.NCP_DIFF_FORMAT_UNSUPPORTED,
            NcpErrorCodes.NCP_ENC_NOT_NEGOTIATED,
            NcpErrorCodes.NCP_ENC_AUTH_FAILED,
            NcpErrorCodes.NCP_VERSION_INCOMPATIBLE,
            NcpErrorCodes.NCP_PREAMBLE_INVALID
        );
        for (String code : expected) {
            assertTrue(NcpErrorCodes.NCP_TO_NPS_STATUS.containsKey(code),
                "NCP_TO_NPS_STATUS missing: " + code);
        }
    }

    @Test
    void ncpKeyMappingsCorrect() {
        assertEquals(NpsStatusCodes.NPS_CLIENT_NOT_FOUND,
            NcpErrorCodes.NCP_TO_NPS_STATUS.get(NcpErrorCodes.NCP_ANCHOR_NOT_FOUND));
        assertEquals(NpsStatusCodes.NPS_LIMIT_PAYLOAD,
            NcpErrorCodes.NCP_TO_NPS_STATUS.get(NcpErrorCodes.NCP_FRAME_PAYLOAD_TOO_LARGE));
        assertEquals(NpsStatusCodes.NPS_PROTO_VERSION_INCOMPATIBLE,
            NcpErrorCodes.NCP_TO_NPS_STATUS.get(NcpErrorCodes.NCP_VERSION_INCOMPATIBLE));
        assertEquals(NpsStatusCodes.NPS_SERVER_ENCODING_UNSUPPORTED,
            NcpErrorCodes.NCP_TO_NPS_STATUS.get(NcpErrorCodes.NCP_ENCODING_UNSUPPORTED));
    }

    // ── NWP error codes ───────────────────────────────────────────────────────

    @Test
    void nwpErrorCodesMapCoversAll47Codes() {
        Set<String> expected = Set.of(
            NwpErrorCodes.NWP_AUTH_NID_SCOPE_VIOLATION,
            NwpErrorCodes.NWP_AUTH_NID_EXPIRED,
            NwpErrorCodes.NWP_AUTH_NID_REVOKED,
            NwpErrorCodes.NWP_AUTH_NID_UNTRUSTED_ISSUER,
            NwpErrorCodes.NWP_AUTH_NID_CAPABILITY_MISSING,
            NwpErrorCodes.NWP_AUTH_ASSURANCE_TOO_LOW,
            NwpErrorCodes.NWP_AUTH_REPUTATION_BLOCKED,
            NwpErrorCodes.NWP_REPUTATION_THROTTLED,
            NwpErrorCodes.NWP_REPUTATION_REJECTED,
            NwpErrorCodes.NWP_REPUTATION_BANNED,
            NwpErrorCodes.NWP_QUERY_FILTER_INVALID,
            NwpErrorCodes.NWP_QUERY_FIELD_UNKNOWN,
            NwpErrorCodes.NWP_QUERY_CURSOR_INVALID,
            NwpErrorCodes.NWP_QUERY_REGEX_UNSAFE,
            NwpErrorCodes.NWP_QUERY_VECTOR_UNSUPPORTED,
            NwpErrorCodes.NWP_QUERY_AGGREGATE_UNSUPPORTED,
            NwpErrorCodes.NWP_QUERY_AGGREGATE_INVALID,
            NwpErrorCodes.NWP_QUERY_STREAM_UNSUPPORTED,
            NwpErrorCodes.NWP_ACTION_NOT_FOUND,
            NwpErrorCodes.NWP_ACTION_PARAMS_INVALID,
            NwpErrorCodes.NWP_ACTION_IDEMPOTENCY_CONFLICT,
            NwpErrorCodes.NWP_TASK_NOT_FOUND,
            NwpErrorCodes.NWP_TASK_ALREADY_CANCELLED,
            NwpErrorCodes.NWP_TASK_ALREADY_COMPLETED,
            NwpErrorCodes.NWP_TASK_ALREADY_FAILED,
            NwpErrorCodes.NWP_SUBSCRIBE_STREAM_NOT_FOUND,
            NwpErrorCodes.NWP_SUBSCRIBE_LIMIT_EXCEEDED,
            NwpErrorCodes.NWP_SUBSCRIBE_FILTER_UNSUPPORTED,
            NwpErrorCodes.NWP_SUBSCRIBE_INTERRUPTED,
            NwpErrorCodes.NWP_SUBSCRIBE_SEQ_TOO_OLD,
            NwpErrorCodes.NWP_BUDGET_EXCEEDED,
            NwpErrorCodes.NWP_CGN_LIMIT_EXCEEDED,
            NwpErrorCodes.NWP_DEPTH_EXCEEDED,
            NwpErrorCodes.NWP_GRAPH_CYCLE,
            NwpErrorCodes.NWP_NODE_UNAVAILABLE,
            NwpErrorCodes.NWP_MANIFEST_VERSION_UNSUPPORTED,
            NwpErrorCodes.NWP_MANIFEST_NODE_TYPE_REMOVED,
            NwpErrorCodes.NWP_MANIFEST_NODE_TYPE_UNKNOWN,
            NwpErrorCodes.NWP_RATE_LIMIT_EXCEEDED,
            NwpErrorCodes.NWP_RESERVED_TYPE_UNSUPPORTED,
            NwpErrorCodes.NWP_HTTP_ORIGIN_FORBIDDEN,
            NwpErrorCodes.NWP_HTTP_CONTENT_TYPE_UNSUPPORTED,
            NwpErrorCodes.NWP_HTTP_ACCEPT_UNSATISFIABLE,
            NwpErrorCodes.NWP_HTTP_REQUEST_ID_MISMATCH,
            NwpErrorCodes.NWP_HTTP_FRAME_BODY_MALFORMED,
            NwpErrorCodes.NWP_CAPABILITY_ADVERTISED_UNIMPLEMENTED,
            NwpErrorCodes.NWP_TOPOLOGY_UNAUTHORIZED,
            NwpErrorCodes.NWP_TOPOLOGY_UNSUPPORTED_SCOPE,
            NwpErrorCodes.NWP_TOPOLOGY_DEPTH_UNSUPPORTED,
            NwpErrorCodes.NWP_TOPOLOGY_FILTER_UNSUPPORTED
        );
        for (String code : expected) {
            assertTrue(NwpErrorCodes.NWP_TO_NPS_STATUS.containsKey(code),
                "NWP_TO_NPS_STATUS missing: " + code);
        }
    }

    @Test
    void nwpKeyMappingsCorrect() {
        assertEquals(NpsStatusCodes.NPS_AUTH_FORBIDDEN,
            NwpErrorCodes.NWP_TO_NPS_STATUS.get(NwpErrorCodes.NWP_AUTH_ASSURANCE_TOO_LOW));
        assertEquals(NpsStatusCodes.NPS_LIMIT_BUDGET,
            NwpErrorCodes.NWP_TO_NPS_STATUS.get(NwpErrorCodes.NWP_BUDGET_EXCEEDED));
        assertEquals(NpsStatusCodes.NPS_SERVER_UNSUPPORTED,
            NwpErrorCodes.NWP_TO_NPS_STATUS.get(NwpErrorCodes.NWP_RESERVED_TYPE_UNSUPPORTED));
        assertEquals(NpsStatusCodes.NPS_CLIENT_RATE_LIMITED,
            NwpErrorCodes.NWP_TO_NPS_STATUS.get(NwpErrorCodes.NWP_REPUTATION_THROTTLED));
        assertEquals(NpsStatusCodes.NPS_AUTH_FORBIDDEN,
            NwpErrorCodes.NWP_TO_NPS_STATUS.get(NwpErrorCodes.NWP_HTTP_ORIGIN_FORBIDDEN));
        assertEquals(NpsStatusCodes.NPS_CLIENT_BAD_FRAME,
            NwpErrorCodes.NWP_TO_NPS_STATUS.get(NwpErrorCodes.NWP_HTTP_CONTENT_TYPE_UNSUPPORTED));
        assertEquals(NpsStatusCodes.NPS_CLIENT_BAD_PARAM,
            NwpErrorCodes.NWP_TO_NPS_STATUS.get(NwpErrorCodes.NWP_HTTP_ACCEPT_UNSATISFIABLE));
        assertEquals(NpsStatusCodes.NPS_CLIENT_BAD_PARAM,
            NwpErrorCodes.NWP_TO_NPS_STATUS.get(NwpErrorCodes.NWP_HTTP_REQUEST_ID_MISMATCH));
        assertEquals(NpsStatusCodes.NPS_CLIENT_BAD_FRAME,
            NwpErrorCodes.NWP_TO_NPS_STATUS.get(NwpErrorCodes.NWP_HTTP_FRAME_BODY_MALFORMED));
        assertEquals(NpsStatusCodes.NPS_SERVER_UNSUPPORTED,
            NwpErrorCodes.NWP_TO_NPS_STATUS.get(NwpErrorCodes.NWP_CAPABILITY_ADVERTISED_UNIMPLEMENTED));
    }

    // ── NIP error codes ───────────────────────────────────────────────────────

    @Test
    void nipErrorCodesMapContainsNewCodes() {
        // New codes added in this update
        assertTrue(NipErrorCodes.NIP_TO_NPS_STATUS.containsKey(NipErrorCodes.TRUST_FRAME_EXPIRED));
        assertTrue(NipErrorCodes.NIP_TO_NPS_STATUS.containsKey(NipErrorCodes.TRUST_FRAME_GRANTOR_REVOKED));
        assertTrue(NipErrorCodes.NIP_TO_NPS_STATUS.containsKey(NipErrorCodes.TRUST_FRAME_SCOPE_EXCEEDS_GRANTOR));
        assertTrue(NipErrorCodes.NIP_TO_NPS_STATUS.containsKey(NipErrorCodes.TRUST_FRAME_NODES_PATTERN_INVALID));
        assertTrue(NipErrorCodes.NIP_TO_NPS_STATUS.containsKey(NipErrorCodes.REVOKE_FRAME_INVALID));
        assertTrue(NipErrorCodes.NIP_TO_NPS_STATUS.containsKey(NipErrorCodes.REVOKE_FRAME_UNAUTHORIZED_ISSUER));
        assertTrue(NipErrorCodes.NIP_TO_NPS_STATUS.containsKey(NipErrorCodes.REVOKE_FRAME_SERIAL_MISMATCH));
        assertTrue(NipErrorCodes.NIP_TO_NPS_STATUS.containsKey(NipErrorCodes.REVOKE_FRAME_REASON_UNKNOWN));
        assertTrue(NipErrorCodes.NIP_TO_NPS_STATUS.containsKey(NipErrorCodes.CA_GROUP_REVOKED));
        assertTrue(NipErrorCodes.NIP_TO_NPS_STATUS.containsKey(NipErrorCodes.CA_PARENT_NOT_FOUND));
        assertTrue(NipErrorCodes.NIP_TO_NPS_STATUS.containsKey(NipErrorCodes.CA_SESSION_VALIDITY_INVALID));
        assertTrue(NipErrorCodes.NIP_TO_NPS_STATUS.containsKey(NipErrorCodes.CA_JWS_INVALID));
        assertTrue(NipErrorCodes.NIP_TO_NPS_STATUS.containsKey(NipErrorCodes.CERT_PARENT_REVOKED));
        assertTrue(NipErrorCodes.NIP_TO_NPS_STATUS.containsKey(NipErrorCodes.OCSP_STAPLE_EXPIRED));
    }

    @Test
    void nipKeyMappingsCorrect() {
        assertEquals(NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED,
            NipErrorCodes.NIP_TO_NPS_STATUS.get(NipErrorCodes.CERT_EXPIRED));
        assertEquals(NpsStatusCodes.NPS_AUTH_FORBIDDEN,
            NipErrorCodes.NIP_TO_NPS_STATUS.get(NipErrorCodes.CERT_SCOPE_VIOLATION));
        assertEquals(NpsStatusCodes.NPS_DOWNSTREAM_UNAVAILABLE,
            NipErrorCodes.NIP_TO_NPS_STATUS.get(NipErrorCodes.REPUTATION_LOG_UNREACHABLE));
        assertEquals(NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED,
            NipErrorCodes.NIP_TO_NPS_STATUS.get(NipErrorCodes.OCSP_STAPLE_EXPIRED));
        assertEquals(NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED,
            NipErrorCodes.NIP_TO_NPS_STATUS.get(NipErrorCodes.CERT_PARENT_REVOKED));
        assertEquals(NpsStatusCodes.NPS_AUTH_FORBIDDEN,
            NipErrorCodes.NIP_TO_NPS_STATUS.get(NipErrorCodes.CA_GROUP_REVOKED));
    }

    // ── NDP error codes ───────────────────────────────────────────────────────

    @Test
    void ndpErrorCodesMapCoversAllCodes() {
        Set<String> expected = Set.of(
            NdpErrorCodes.NDP_RESOLVE_NOT_FOUND,
            NdpErrorCodes.NDP_RESOLVE_AMBIGUOUS,
            NdpErrorCodes.NDP_RESOLVE_TIMEOUT,
            NdpErrorCodes.NDP_ANNOUNCE_SIGNATURE_INVALID,
            NdpErrorCodes.NDP_ANNOUNCE_NID_MISMATCH,
            NdpErrorCodes.NDP_ANNOUNCE_ROLE_REMOVED,
            NdpErrorCodes.NDP_ANNOUNCE_ROLE_UNKNOWN,
            NdpErrorCodes.NDP_ANNOUNCE_CONFLICT,
            NdpErrorCodes.NDP_GRAPH_SEQ_ROLLBACK,
            NdpErrorCodes.NDP_GRAPH_SEQ_GAP,
            NdpErrorCodes.NDP_GRAPH_INVALID,
            NdpErrorCodes.NDP_GRAPH_TOO_LARGE,
            NdpErrorCodes.NDP_FEDERATION_LOOP,
            NdpErrorCodes.NDP_ISSUER_NOT_ALLOWED,
            NdpErrorCodes.NDP_CA_ATTEST_REQUIRED,
            NdpErrorCodes.NDP_REGISTRY_UNAVAILABLE
        );
        for (String code : expected) {
            assertTrue(NdpErrorCodes.NDP_TO_NPS_STATUS.containsKey(code),
                "NDP_TO_NPS_STATUS missing: " + code);
        }
    }

    @Test
    void ndpKeyMappingsCorrect() {
        assertEquals(NpsStatusCodes.NPS_CLIENT_NOT_FOUND,
            NdpErrorCodes.NDP_TO_NPS_STATUS.get(NdpErrorCodes.NDP_RESOLVE_NOT_FOUND));
        assertEquals(NpsStatusCodes.NPS_SERVER_TIMEOUT,
            NdpErrorCodes.NDP_TO_NPS_STATUS.get(NdpErrorCodes.NDP_RESOLVE_TIMEOUT));
        assertEquals(NpsStatusCodes.NPS_CLIENT_BAD_FRAME,
            NdpErrorCodes.NDP_TO_NPS_STATUS.get(NdpErrorCodes.NDP_GRAPH_INVALID));
        assertEquals(NpsStatusCodes.NPS_LIMIT_PAYLOAD,
            NdpErrorCodes.NDP_TO_NPS_STATUS.get(NdpErrorCodes.NDP_GRAPH_TOO_LARGE));
        assertEquals(NpsStatusCodes.NPS_CLIENT_CONFLICT,
            NdpErrorCodes.NDP_TO_NPS_STATUS.get(NdpErrorCodes.NDP_FEDERATION_LOOP));
        assertEquals(NpsStatusCodes.NPS_STREAM_SEQ_GAP,
            NdpErrorCodes.NDP_TO_NPS_STATUS.get(NdpErrorCodes.NDP_GRAPH_SEQ_GAP));
    }

    // ── NOP error codes ───────────────────────────────────────────────────────

    @Test
    void nopErrorCodesMapCoversAllCodes() {
        Set<String> expected = Set.of(
            NopErrorCodes.NOP_TASK_NOT_FOUND,
            NopErrorCodes.NOP_TASK_TIMEOUT,
            NopErrorCodes.NOP_TASK_DAG_INVALID,
            NopErrorCodes.NOP_TASK_DAG_CYCLE,
            NopErrorCodes.NOP_TASK_DAG_TOO_LARGE,
            NopErrorCodes.NOP_TASK_ALREADY_COMPLETED,
            NopErrorCodes.NOP_TASK_CANCELLED,
            NopErrorCodes.NOP_DELEGATE_SCOPE_VIOLATION,
            NopErrorCodes.NOP_DELEGATE_REJECTED,
            NopErrorCodes.NOP_DELEGATE_CHAIN_TOO_DEEP,
            NopErrorCodes.NOP_DELEGATE_TIMEOUT,
            NopErrorCodes.NOP_SYNC_TIMEOUT,
            NopErrorCodes.NOP_SYNC_DEPENDENCY_FAILED,
            NopErrorCodes.NOP_STREAM_SEQ_GAP,
            NopErrorCodes.NOP_STREAM_NID_MISMATCH,
            NopErrorCodes.NOP_STREAM_NAK,
            NopErrorCodes.NOP_RESOURCE_INSUFFICIENT,
            NopErrorCodes.NOP_CONDITION_EVAL_ERROR,
            NopErrorCodes.NOP_INPUT_MAPPING_ERROR,
            NopErrorCodes.NOP_COMPENSATION_FAILED,
            NopErrorCodes.NOP_COMPENSATION_NOT_SUPPORTED,
            NopErrorCodes.NOP_CALLBACK_HMAC_MISSING
        );
        for (String code : expected) {
            assertTrue(NopErrorCodes.NOP_TO_NPS_STATUS.containsKey(code),
                "NOP_TO_NPS_STATUS missing: " + code);
        }
    }

    @Test
    void nopKeyMappingsCorrect() {
        assertEquals(NpsStatusCodes.NPS_SERVER_TIMEOUT,
            NopErrorCodes.NOP_TO_NPS_STATUS.get(NopErrorCodes.NOP_TASK_TIMEOUT));
        assertEquals(NpsStatusCodes.NPS_AUTH_FORBIDDEN,
            NopErrorCodes.NOP_TO_NPS_STATUS.get(NopErrorCodes.NOP_DELEGATE_SCOPE_VIOLATION));
        assertEquals(NpsStatusCodes.NPS_STREAM_SEQ_GAP,
            NopErrorCodes.NOP_TO_NPS_STATUS.get(NopErrorCodes.NOP_STREAM_NAK));
        assertEquals(NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED,
            NopErrorCodes.NOP_TO_NPS_STATUS.get(NopErrorCodes.NOP_CALLBACK_HMAC_MISSING));
        assertEquals(NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED,
            NopErrorCodes.NOP_TO_NPS_STATUS.get(NopErrorCodes.NOP_STREAM_NID_MISMATCH));
    }
}
