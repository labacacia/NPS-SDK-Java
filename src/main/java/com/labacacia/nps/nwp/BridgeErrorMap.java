// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.labacacia.nps.core.NpsStatusCodes;

import java.util.Set;

/**
 * NPS-2 §16.3 error mapping — the <strong>single</strong> implementation serving both
 * Bridge directions and all three foreign protocols. No inbound or outbound path may
 * hand-roll its own mapping.
 */
public final class BridgeErrorMap {

    private BridgeErrorMap() {}

    // ── JSON-RPC 2.0 codes ───────────────────────────────────────────────────
    public static final int PARSE_ERROR      = -32700;
    public static final int INVALID_REQUEST  = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS   = -32602;
    public static final int INTERNAL_ERROR   = -32603;
    /** Hosting-layer dispatch timeout / upstream failure. */
    public static final int UPSTREAM_ERROR   = -32000;
    public static final int UNAUTHENTICATED  = -32001;
    public static final int FORBIDDEN        = -32003;
    public static final int CONFLICT         = -32004;
    public static final int RATE_LIMITED     = -32005;

    /**
     * {@code -32002} is <strong>reserved and MUST NOT be emitted</strong> — it was the
     * pre-CR-0010 "unknown tool" code, now retired in favour of {@code -32601}.
     */
    public static final int RESERVED_DO_NOT_EMIT = -32002;

    // ── NPS status → JSON-RPC (MCP, A2A) ─────────────────────────────────────

    /**
     * @param npsStatus    the NPS status carried by the {@link NwpResult}
     * @param resourceRead {@code true} when mapping a {@code resources/read} failure —
     *                     the only param-sensitive row: an unknown <em>URI</em> is a bad
     *                     param ({@code -32602}), whereas an unknown <em>tool</em> in
     *                     {@code tools/call} is a missing method ({@code -32601}).
     */
    public static int toJsonRpc(String npsStatus, boolean resourceRead) {
        if (npsStatus == null) return INTERNAL_ERROR;
        return switch (npsStatus) {
            case NpsStatusCodes.NPS_CLIENT_BAD_FRAME     -> INVALID_REQUEST;
            case NpsStatusCodes.NPS_CLIENT_BAD_PARAM,
                 NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE -> INVALID_PARAMS;
            case NpsStatusCodes.NPS_CLIENT_NOT_FOUND     -> resourceRead ? INVALID_PARAMS : METHOD_NOT_FOUND;
            case NpsStatusCodes.NPS_CLIENT_GONE          -> INVALID_PARAMS;
            case NpsStatusCodes.NPS_CLIENT_CONFLICT      -> CONFLICT;
            case NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED -> UNAUTHENTICATED;
            case NpsStatusCodes.NPS_AUTH_FORBIDDEN       -> FORBIDDEN;
            case NpsStatusCodes.NPS_LIMIT_RATE,
                 NpsStatusCodes.NPS_LIMIT_BUDGET,
                 NpsStatusCodes.NPS_LIMIT_PAYLOAD        -> RATE_LIMITED;
            case NpsStatusCodes.NPS_SERVER_UNSUPPORTED   -> METHOD_NOT_FOUND;
            case NpsStatusCodes.NPS_SERVER_INTERNAL,
                 NpsStatusCodes.NPS_SERVER_UNAVAILABLE,
                 NpsStatusCodes.NPS_SERVER_TIMEOUT,
                 NpsStatusCodes.NPS_DOWNSTREAM_UNAVAILABLE -> INTERNAL_ERROR;
            default -> INTERNAL_ERROR;
        };
    }

    public static int toJsonRpc(String npsStatus) { return toJsonRpc(npsStatus, false); }

    // ── NPS status → gRPC ────────────────────────────────────────────────────

    public static GrpcStatusCode toGrpcStatus(String npsStatus) {
        if (npsStatus == null) return GrpcStatusCode.INTERNAL;
        return switch (npsStatus) {
            case NpsStatusCodes.NPS_OK                   -> GrpcStatusCode.OK;
            case NpsStatusCodes.NPS_CLIENT_BAD_FRAME,
                 NpsStatusCodes.NPS_CLIENT_BAD_PARAM,
                 NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE -> GrpcStatusCode.INVALID_ARGUMENT;
            case NpsStatusCodes.NPS_CLIENT_NOT_FOUND,
                 NpsStatusCodes.NPS_CLIENT_GONE          -> GrpcStatusCode.NOT_FOUND;
            case NpsStatusCodes.NPS_CLIENT_CONFLICT      -> GrpcStatusCode.ABORTED;
            case NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED -> GrpcStatusCode.UNAUTHENTICATED;
            case NpsStatusCodes.NPS_AUTH_FORBIDDEN       -> GrpcStatusCode.PERMISSION_DENIED;
            case NpsStatusCodes.NPS_LIMIT_RATE,
                 NpsStatusCodes.NPS_LIMIT_BUDGET,
                 NpsStatusCodes.NPS_LIMIT_PAYLOAD        -> GrpcStatusCode.RESOURCE_EXHAUSTED;
            case NpsStatusCodes.NPS_SERVER_UNSUPPORTED   -> GrpcStatusCode.UNIMPLEMENTED;
            case NpsStatusCodes.NPS_SERVER_INTERNAL      -> GrpcStatusCode.INTERNAL;
            case NpsStatusCodes.NPS_SERVER_UNAVAILABLE,
                 NpsStatusCodes.NPS_DOWNSTREAM_UNAVAILABLE -> GrpcStatusCode.UNAVAILABLE;
            case NpsStatusCodes.NPS_SERVER_TIMEOUT       -> GrpcStatusCode.DEADLINE_EXCEEDED;
            default -> GrpcStatusCode.INTERNAL;
        };
    }

    // ── Reverse direction: foreign error → NPS status ────────────────────────

    /**
     * Choose the <em>most specific</em> NPS status where the inverse is not injective;
     * never a blanket {@code NPS-SERVER-INTERNAL}.
     */
    public static String fromHttpStatus(int status) {
        return switch (status) {
            case 400 -> NpsStatusCodes.NPS_CLIENT_BAD_PARAM;
            case 401 -> NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED;
            case 403 -> NpsStatusCodes.NPS_AUTH_FORBIDDEN;
            case 404 -> NpsStatusCodes.NPS_CLIENT_NOT_FOUND;
            case 408 -> NpsStatusCodes.NPS_SERVER_TIMEOUT;
            case 409 -> NpsStatusCodes.NPS_CLIENT_CONFLICT;
            case 410 -> NpsStatusCodes.NPS_CLIENT_GONE;
            case 413 -> NpsStatusCodes.NPS_LIMIT_PAYLOAD;
            case 415 -> NpsStatusCodes.NPS_SERVER_ENCODING_UNSUPPORTED;
            case 422 -> NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE;
            case 429 -> NpsStatusCodes.NPS_LIMIT_RATE;
            case 501 -> NpsStatusCodes.NPS_SERVER_UNSUPPORTED;
            case 502, 504 -> NpsStatusCodes.NPS_DOWNSTREAM_UNAVAILABLE;
            case 503 -> NpsStatusCodes.NPS_SERVER_UNAVAILABLE;
            default -> status >= 500 ? NpsStatusCodes.NPS_SERVER_INTERNAL
                     : status >= 400 ? NpsStatusCodes.NPS_CLIENT_BAD_PARAM
                     : NpsStatusCodes.NPS_OK;
        };
    }

    public static String fromJsonRpc(int code) {
        return switch (code) {
            case PARSE_ERROR, INVALID_REQUEST -> NpsStatusCodes.NPS_CLIENT_BAD_FRAME;
            case METHOD_NOT_FOUND -> NpsStatusCodes.NPS_CLIENT_NOT_FOUND;
            case INVALID_PARAMS   -> NpsStatusCodes.NPS_CLIENT_BAD_PARAM;
            case INTERNAL_ERROR   -> NpsStatusCodes.NPS_SERVER_INTERNAL;
            case UNAUTHENTICATED  -> NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED;
            case FORBIDDEN        -> NpsStatusCodes.NPS_AUTH_FORBIDDEN;
            case CONFLICT         -> NpsStatusCodes.NPS_CLIENT_CONFLICT;
            case RATE_LIMITED     -> NpsStatusCodes.NPS_LIMIT_RATE;
            case UPSTREAM_ERROR   -> NpsStatusCodes.NPS_DOWNSTREAM_UNAVAILABLE;
            default -> NpsStatusCodes.NPS_SERVER_INTERNAL;
        };
    }

    public static String fromGrpcStatus(GrpcStatusCode code) {
        if (code == null) return NpsStatusCodes.NPS_SERVER_INTERNAL;
        return switch (code) {
            case OK                  -> NpsStatusCodes.NPS_OK;
            case INVALID_ARGUMENT    -> NpsStatusCodes.NPS_CLIENT_BAD_PARAM;
            case FAILED_PRECONDITION -> NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE;
            case NOT_FOUND           -> NpsStatusCodes.NPS_CLIENT_NOT_FOUND;
            case ALREADY_EXISTS, ABORTED -> NpsStatusCodes.NPS_CLIENT_CONFLICT;
            case UNAUTHENTICATED     -> NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED;
            case PERMISSION_DENIED   -> NpsStatusCodes.NPS_AUTH_FORBIDDEN;
            case RESOURCE_EXHAUSTED  -> NpsStatusCodes.NPS_LIMIT_RATE;
            case UNIMPLEMENTED       -> NpsStatusCodes.NPS_SERVER_UNSUPPORTED;
            case UNAVAILABLE         -> NpsStatusCodes.NPS_SERVER_UNAVAILABLE;
            case DEADLINE_EXCEEDED   -> NpsStatusCodes.NPS_SERVER_TIMEOUT;
            case INTERNAL, UNKNOWN, DATA_LOSS -> NpsStatusCodes.NPS_SERVER_INTERNAL;
            default -> NpsStatusCodes.NPS_SERVER_INTERNAL;
        };
    }

    // ── §16.3 protocol-error vs isError split ────────────────────────────────

    private static final Set<String> PROTOCOL_ERROR_STATUSES = Set.of(
        NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED,
        NpsStatusCodes.NPS_AUTH_FORBIDDEN,
        NpsStatusCodes.NPS_LIMIT_RATE,
        NpsStatusCodes.NPS_LIMIT_BUDGET,
        NpsStatusCodes.NPS_LIMIT_PAYLOAD,
        NpsStatusCodes.NPS_SERVER_UNSUPPORTED,
        NpsStatusCodes.NPS_SERVER_INTERNAL,
        NpsStatusCodes.NPS_SERVER_UNAVAILABLE,
        NpsStatusCodes.NPS_SERVER_TIMEOUT,
        NpsStatusCodes.NPS_DOWNSTREAM_UNAVAILABLE);

    /**
     * {@code true} when the failure MUST surface as a foreign-protocol error rather than
     * a successful result carrying {@code isError: true}.
     *
     * <p>These are <strong>infrastructure failures — the tool did not run</strong>.
     * Returning them as a successful result lets an MCP client mistake a 403 for a tool
     * that merely returned unhappy text. Genuine tool-domain failures (the
     * {@code NPS-CLIENT-*} classes) stay as {@code isError: true} content, which is what
     * MCP's flag is for.</p>
     */
    public static boolean mustBeProtocolError(String npsStatus) {
        return npsStatus != null && PROTOCOL_ERROR_STATUSES.contains(npsStatus);
    }
}
