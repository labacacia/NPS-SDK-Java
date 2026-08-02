// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.labacacia.nps.core.NpsStatusCodes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** NPS-2 §16.3 — the single shared error map, both directions, all three protocols. */
class BridgeErrorMapTest {

    // ── §5.1 NPS status → JSON-RPC ───────────────────────────────────────────

    @Test
    void npsStatusToJsonRpcRows() {
        assertEquals(-32600, BridgeErrorMap.toJsonRpc(NpsStatusCodes.NPS_CLIENT_BAD_FRAME));
        assertEquals(-32602, BridgeErrorMap.toJsonRpc(NpsStatusCodes.NPS_CLIENT_BAD_PARAM));
        assertEquals(-32602, BridgeErrorMap.toJsonRpc(NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE));
        assertEquals(-32602, BridgeErrorMap.toJsonRpc(NpsStatusCodes.NPS_CLIENT_GONE));
        assertEquals(-32004, BridgeErrorMap.toJsonRpc(NpsStatusCodes.NPS_CLIENT_CONFLICT));
        assertEquals(-32001, BridgeErrorMap.toJsonRpc(NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED));
        assertEquals(-32003, BridgeErrorMap.toJsonRpc(NpsStatusCodes.NPS_AUTH_FORBIDDEN));
        assertEquals(-32005, BridgeErrorMap.toJsonRpc(NpsStatusCodes.NPS_LIMIT_RATE));
        assertEquals(-32005, BridgeErrorMap.toJsonRpc(NpsStatusCodes.NPS_LIMIT_BUDGET));
        assertEquals(-32005, BridgeErrorMap.toJsonRpc(NpsStatusCodes.NPS_LIMIT_PAYLOAD));
        assertEquals(-32601, BridgeErrorMap.toJsonRpc(NpsStatusCodes.NPS_SERVER_UNSUPPORTED));
        assertEquals(-32603, BridgeErrorMap.toJsonRpc(NpsStatusCodes.NPS_SERVER_INTERNAL));
        assertEquals(-32603, BridgeErrorMap.toJsonRpc(NpsStatusCodes.NPS_SERVER_UNAVAILABLE));
        assertEquals(-32603, BridgeErrorMap.toJsonRpc(NpsStatusCodes.NPS_SERVER_TIMEOUT));
        assertEquals(-32603, BridgeErrorMap.toJsonRpc(NpsStatusCodes.NPS_DOWNSTREAM_UNAVAILABLE));
        assertEquals(-32603, BridgeErrorMap.toJsonRpc("NPS-SOMETHING-ELSE"));
        assertEquals(-32603, BridgeErrorMap.toJsonRpc(null));
    }

    @Test
    void notFoundIsTheOnlyParamSensitiveRow() {
        // Unknown TOOL in tools/call ⇒ method not found.
        assertEquals(-32601, BridgeErrorMap.toJsonRpc(NpsStatusCodes.NPS_CLIENT_NOT_FOUND, false));
        // Unknown URI in resources/read ⇒ invalid params.
        assertEquals(-32602, BridgeErrorMap.toJsonRpc(NpsStatusCodes.NPS_CLIENT_NOT_FOUND, true));
        // No other row changes with the flag.
        assertEquals(BridgeErrorMap.toJsonRpc(NpsStatusCodes.NPS_AUTH_FORBIDDEN, false),
                     BridgeErrorMap.toJsonRpc(NpsStatusCodes.NPS_AUTH_FORBIDDEN, true));
    }

    @Test
    void authClassesAreNotCollapsed() {
        assertNotEquals(BridgeErrorMap.toJsonRpc(NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED),
                        BridgeErrorMap.toJsonRpc(NpsStatusCodes.NPS_AUTH_FORBIDDEN));
        assertNotEquals(BridgeErrorMap.toGrpcStatus(NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED),
                        BridgeErrorMap.toGrpcStatus(NpsStatusCodes.NPS_AUTH_FORBIDDEN));
    }

    @Test
    void serverClassesAreNotAllCollapsedOntoUnavailable() {
        assertEquals(GrpcStatusCode.INTERNAL,          BridgeErrorMap.toGrpcStatus(NpsStatusCodes.NPS_SERVER_INTERNAL));
        assertEquals(GrpcStatusCode.UNAVAILABLE,       BridgeErrorMap.toGrpcStatus(NpsStatusCodes.NPS_SERVER_UNAVAILABLE));
        assertEquals(GrpcStatusCode.DEADLINE_EXCEEDED, BridgeErrorMap.toGrpcStatus(NpsStatusCodes.NPS_SERVER_TIMEOUT));
        assertEquals(GrpcStatusCode.UNIMPLEMENTED,     BridgeErrorMap.toGrpcStatus(NpsStatusCodes.NPS_SERVER_UNSUPPORTED));
    }

    @Test
    void reservedJsonRpcCodeIsNeverProduced() {
        for (String status : new String[]{
            NpsStatusCodes.NPS_CLIENT_BAD_FRAME, NpsStatusCodes.NPS_CLIENT_BAD_PARAM,
            NpsStatusCodes.NPS_CLIENT_NOT_FOUND, NpsStatusCodes.NPS_CLIENT_CONFLICT,
            NpsStatusCodes.NPS_CLIENT_GONE, NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE,
            NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED, NpsStatusCodes.NPS_AUTH_FORBIDDEN,
            NpsStatusCodes.NPS_LIMIT_RATE, NpsStatusCodes.NPS_LIMIT_BUDGET,
            NpsStatusCodes.NPS_LIMIT_PAYLOAD, NpsStatusCodes.NPS_SERVER_UNSUPPORTED,
            NpsStatusCodes.NPS_SERVER_INTERNAL, NpsStatusCodes.NPS_SERVER_UNAVAILABLE,
            NpsStatusCodes.NPS_SERVER_TIMEOUT, NpsStatusCodes.NPS_DOWNSTREAM_UNAVAILABLE}) {
            assertNotEquals(-32002, BridgeErrorMap.toJsonRpc(status, false));
            assertNotEquals(-32002, BridgeErrorMap.toJsonRpc(status, true));
        }
    }

    // ── §5.2 NPS status → gRPC ───────────────────────────────────────────────

    @Test
    void npsStatusToGrpcRows() {
        assertEquals(GrpcStatusCode.INVALID_ARGUMENT,   BridgeErrorMap.toGrpcStatus(NpsStatusCodes.NPS_CLIENT_BAD_FRAME));
        assertEquals(GrpcStatusCode.INVALID_ARGUMENT,   BridgeErrorMap.toGrpcStatus(NpsStatusCodes.NPS_CLIENT_BAD_PARAM));
        assertEquals(GrpcStatusCode.INVALID_ARGUMENT,   BridgeErrorMap.toGrpcStatus(NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE));
        assertEquals(GrpcStatusCode.NOT_FOUND,          BridgeErrorMap.toGrpcStatus(NpsStatusCodes.NPS_CLIENT_NOT_FOUND));
        assertEquals(GrpcStatusCode.NOT_FOUND,          BridgeErrorMap.toGrpcStatus(NpsStatusCodes.NPS_CLIENT_GONE));
        assertEquals(GrpcStatusCode.ABORTED,            BridgeErrorMap.toGrpcStatus(NpsStatusCodes.NPS_CLIENT_CONFLICT));
        assertEquals(GrpcStatusCode.UNAUTHENTICATED,    BridgeErrorMap.toGrpcStatus(NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED));
        assertEquals(GrpcStatusCode.PERMISSION_DENIED,  BridgeErrorMap.toGrpcStatus(NpsStatusCodes.NPS_AUTH_FORBIDDEN));
        assertEquals(GrpcStatusCode.RESOURCE_EXHAUSTED, BridgeErrorMap.toGrpcStatus(NpsStatusCodes.NPS_LIMIT_BUDGET));
        assertEquals(GrpcStatusCode.UNAVAILABLE,        BridgeErrorMap.toGrpcStatus(NpsStatusCodes.NPS_DOWNSTREAM_UNAVAILABLE));
        assertEquals(GrpcStatusCode.INTERNAL,           BridgeErrorMap.toGrpcStatus("NPS-WHATEVER"));
    }

    // ── §5.4 reverse direction ───────────────────────────────────────────────

    @Test
    void fromHttpStatusRows() {
        assertEquals(NpsStatusCodes.NPS_CLIENT_BAD_PARAM,          BridgeErrorMap.fromHttpStatus(400));
        assertEquals(NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED,      BridgeErrorMap.fromHttpStatus(401));
        assertEquals(NpsStatusCodes.NPS_AUTH_FORBIDDEN,            BridgeErrorMap.fromHttpStatus(403));
        assertEquals(NpsStatusCodes.NPS_CLIENT_NOT_FOUND,          BridgeErrorMap.fromHttpStatus(404));
        assertEquals(NpsStatusCodes.NPS_SERVER_TIMEOUT,            BridgeErrorMap.fromHttpStatus(408));
        assertEquals(NpsStatusCodes.NPS_CLIENT_CONFLICT,           BridgeErrorMap.fromHttpStatus(409));
        assertEquals(NpsStatusCodes.NPS_CLIENT_GONE,               BridgeErrorMap.fromHttpStatus(410));
        assertEquals(NpsStatusCodes.NPS_LIMIT_PAYLOAD,             BridgeErrorMap.fromHttpStatus(413));
        assertEquals(NpsStatusCodes.NPS_SERVER_ENCODING_UNSUPPORTED, BridgeErrorMap.fromHttpStatus(415));
        assertEquals(NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE,      BridgeErrorMap.fromHttpStatus(422));
        assertEquals(NpsStatusCodes.NPS_LIMIT_RATE,                BridgeErrorMap.fromHttpStatus(429));
        assertEquals(NpsStatusCodes.NPS_SERVER_UNSUPPORTED,        BridgeErrorMap.fromHttpStatus(501));
        assertEquals(NpsStatusCodes.NPS_DOWNSTREAM_UNAVAILABLE,    BridgeErrorMap.fromHttpStatus(502));
        assertEquals(NpsStatusCodes.NPS_SERVER_UNAVAILABLE,        BridgeErrorMap.fromHttpStatus(503));
        assertEquals(NpsStatusCodes.NPS_DOWNSTREAM_UNAVAILABLE,    BridgeErrorMap.fromHttpStatus(504));
        assertEquals(NpsStatusCodes.NPS_SERVER_INTERNAL,           BridgeErrorMap.fromHttpStatus(507));
        assertEquals(NpsStatusCodes.NPS_CLIENT_BAD_PARAM,          BridgeErrorMap.fromHttpStatus(418));
        assertEquals(NpsStatusCodes.NPS_OK,                        BridgeErrorMap.fromHttpStatus(200));
    }

    @Test
    void fromJsonRpcRows() {
        assertEquals(NpsStatusCodes.NPS_CLIENT_BAD_FRAME,       BridgeErrorMap.fromJsonRpc(-32700));
        assertEquals(NpsStatusCodes.NPS_CLIENT_BAD_FRAME,       BridgeErrorMap.fromJsonRpc(-32600));
        assertEquals(NpsStatusCodes.NPS_CLIENT_NOT_FOUND,       BridgeErrorMap.fromJsonRpc(-32601));
        assertEquals(NpsStatusCodes.NPS_CLIENT_BAD_PARAM,       BridgeErrorMap.fromJsonRpc(-32602));
        assertEquals(NpsStatusCodes.NPS_SERVER_INTERNAL,        BridgeErrorMap.fromJsonRpc(-32603));
        assertEquals(NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED,   BridgeErrorMap.fromJsonRpc(-32001));
        assertEquals(NpsStatusCodes.NPS_AUTH_FORBIDDEN,         BridgeErrorMap.fromJsonRpc(-32003));
        assertEquals(NpsStatusCodes.NPS_CLIENT_CONFLICT,        BridgeErrorMap.fromJsonRpc(-32004));
        assertEquals(NpsStatusCodes.NPS_LIMIT_RATE,             BridgeErrorMap.fromJsonRpc(-32005));
        assertEquals(NpsStatusCodes.NPS_DOWNSTREAM_UNAVAILABLE, BridgeErrorMap.fromJsonRpc(-32000));
        assertEquals(NpsStatusCodes.NPS_SERVER_INTERNAL,        BridgeErrorMap.fromJsonRpc(-1));
    }

    @Test
    void fromGrpcStatusRows() {
        assertEquals(NpsStatusCodes.NPS_OK,                     BridgeErrorMap.fromGrpcStatus(GrpcStatusCode.OK));
        assertEquals(NpsStatusCodes.NPS_CLIENT_BAD_PARAM,       BridgeErrorMap.fromGrpcStatus(GrpcStatusCode.INVALID_ARGUMENT));
        assertEquals(NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE,   BridgeErrorMap.fromGrpcStatus(GrpcStatusCode.FAILED_PRECONDITION));
        assertEquals(NpsStatusCodes.NPS_CLIENT_NOT_FOUND,       BridgeErrorMap.fromGrpcStatus(GrpcStatusCode.NOT_FOUND));
        assertEquals(NpsStatusCodes.NPS_CLIENT_CONFLICT,        BridgeErrorMap.fromGrpcStatus(GrpcStatusCode.ALREADY_EXISTS));
        assertEquals(NpsStatusCodes.NPS_CLIENT_CONFLICT,        BridgeErrorMap.fromGrpcStatus(GrpcStatusCode.ABORTED));
        assertEquals(NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED,   BridgeErrorMap.fromGrpcStatus(GrpcStatusCode.UNAUTHENTICATED));
        assertEquals(NpsStatusCodes.NPS_AUTH_FORBIDDEN,         BridgeErrorMap.fromGrpcStatus(GrpcStatusCode.PERMISSION_DENIED));
        assertEquals(NpsStatusCodes.NPS_LIMIT_RATE,             BridgeErrorMap.fromGrpcStatus(GrpcStatusCode.RESOURCE_EXHAUSTED));
        assertEquals(NpsStatusCodes.NPS_SERVER_UNSUPPORTED,     BridgeErrorMap.fromGrpcStatus(GrpcStatusCode.UNIMPLEMENTED));
        assertEquals(NpsStatusCodes.NPS_SERVER_UNAVAILABLE,     BridgeErrorMap.fromGrpcStatus(GrpcStatusCode.UNAVAILABLE));
        assertEquals(NpsStatusCodes.NPS_SERVER_TIMEOUT,         BridgeErrorMap.fromGrpcStatus(GrpcStatusCode.DEADLINE_EXCEEDED));
        assertEquals(NpsStatusCodes.NPS_SERVER_INTERNAL,        BridgeErrorMap.fromGrpcStatus(GrpcStatusCode.DATA_LOSS));
        assertEquals(NpsStatusCodes.NPS_SERVER_INTERNAL,        BridgeErrorMap.fromGrpcStatus(GrpcStatusCode.CANCELLED));
        assertEquals(NpsStatusCodes.NPS_SERVER_INTERNAL,        BridgeErrorMap.fromGrpcStatus(null));
    }

    // ── §5.5 the protocol-error vs isError split ─────────────────────────────

    @Test
    void mustBeProtocolErrorSet() {
        for (String s : new String[]{
            NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED, NpsStatusCodes.NPS_AUTH_FORBIDDEN,
            NpsStatusCodes.NPS_LIMIT_RATE, NpsStatusCodes.NPS_LIMIT_BUDGET,
            NpsStatusCodes.NPS_LIMIT_PAYLOAD, NpsStatusCodes.NPS_SERVER_UNSUPPORTED,
            NpsStatusCodes.NPS_SERVER_INTERNAL, NpsStatusCodes.NPS_SERVER_UNAVAILABLE,
            NpsStatusCodes.NPS_SERVER_TIMEOUT, NpsStatusCodes.NPS_DOWNSTREAM_UNAVAILABLE}) {
            assertTrue(BridgeErrorMap.mustBeProtocolError(s), s);
        }
        for (String s : new String[]{
            NpsStatusCodes.NPS_CLIENT_BAD_FRAME, NpsStatusCodes.NPS_CLIENT_BAD_PARAM,
            NpsStatusCodes.NPS_CLIENT_NOT_FOUND, NpsStatusCodes.NPS_CLIENT_CONFLICT,
            NpsStatusCodes.NPS_CLIENT_GONE, NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE,
            NpsStatusCodes.NPS_OK}) {
            assertFalse(BridgeErrorMap.mustBeProtocolError(s), s);
        }
        assertFalse(BridgeErrorMap.mustBeProtocolError(null));
    }

    // ── §5.6 Bridge error codes ──────────────────────────────────────────────

    @Test
    void bridgeErrorCodesMapOntoRealNpsStatuses() {
        assertEquals(NpsStatusCodes.NPS_SERVER_UNSUPPORTED,
            BridgeErrorCodes.BRIDGE_TO_NPS_STATUS.get(BridgeErrorCodes.NWP_BRIDGE_DIRECTION_UNSUPPORTED));
        assertEquals(NpsStatusCodes.NPS_CLIENT_NOT_FOUND,
            BridgeErrorCodes.BRIDGE_TO_NPS_STATUS.get(BridgeErrorCodes.NWP_BRIDGE_SERVER_TOOL_NOT_FOUND));
        assertEquals(NpsStatusCodes.NPS_SERVER_INTERNAL,
            BridgeErrorCodes.BRIDGE_TO_NPS_STATUS.get(BridgeErrorCodes.NWP_BRIDGE_SERVER_DISPATCHER_MISSING));
        assertEquals(NpsStatusCodes.NPS_SERVER_INTERNAL,
            BridgeErrorCodes.BRIDGE_TO_NPS_STATUS.get(BridgeErrorCodes.NWP_BRIDGE_SERVER_DISPATCH_FAILED));
        assertEquals(NpsStatusCodes.NPS_DOWNSTREAM_UNAVAILABLE,
            BridgeErrorCodes.BRIDGE_TO_NPS_STATUS.get(BridgeErrorCodes.NWP_BRIDGE_UPSTREAM_FAILED));
        assertEquals(8, BridgeErrorCodes.BRIDGE_TO_NPS_STATUS.size());

        // The invented statuses CR-0010 removed must not reappear.
        for (String invented : new String[]{
            "NPS-SERVER-NOT-IMPLEMENTED", "NPS-SERVER-ERROR", "NPS-CLIENT-UNAUTHORIZED",
            "NPS-CLIENT-BAD-REQUEST", "NPS-SERVER-UPSTREAM-FAILED"}) {
            assertFalse(BridgeErrorCodes.BRIDGE_TO_NPS_STATUS.containsValue(invented), invented);
        }
    }
}
