// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ncp;

import com.labacacia.nps.core.NpsStatusCodes;

import java.util.Map;

/**
 * NCP error code string constants. Mirror of {@code spec/error-codes.md} NCP section.
 * Values are the canonical wire strings; consumers MUST compare by string equality.
 */
public interface NcpErrorCodes {

    // ── Anchor ───────────────────────────────────────────────────────────────
    String NCP_ANCHOR_NOT_FOUND  = "NCP-ANCHOR-NOT-FOUND";
    String NCP_ANCHOR_SCHEMA_INVALID = "NCP-ANCHOR-SCHEMA-INVALID";
    String NCP_ANCHOR_ID_MISMATCH    = "NCP-ANCHOR-ID-MISMATCH";
    String NCP_ANCHOR_STALE          = "NCP-ANCHOR-STALE";

    // ── Frame ────────────────────────────────────────────────────────────────
    String NCP_FRAME_UNKNOWN_TYPE    = "NCP-FRAME-UNKNOWN-TYPE";
    String NCP_FRAME_PAYLOAD_TOO_LARGE = "NCP-FRAME-PAYLOAD-TOO-LARGE";
    String NCP_FRAME_FLAGS_INVALID   = "NCP-FRAME-FLAGS-INVALID";

    // ── Stream ───────────────────────────────────────────────────────────────
    String NCP_STREAM_SEQ_GAP        = "NCP-STREAM-SEQ-GAP";
    String NCP_STREAM_NOT_FOUND      = "NCP-STREAM-NOT-FOUND";
    String NCP_STREAM_LIMIT_EXCEEDED = "NCP-STREAM-LIMIT-EXCEEDED";
    String NCP_STREAM_WINDOW_OVERFLOW = "NCP-STREAM-WINDOW-OVERFLOW";

    // ── Encoding & Diff ──────────────────────────────────────────────────────
    String NCP_ENCODING_UNSUPPORTED    = "NCP-ENCODING-UNSUPPORTED";
    String NCP_DIFF_FORMAT_UNSUPPORTED = "NCP-DIFF-FORMAT-UNSUPPORTED";

    // ── Encryption ───────────────────────────────────────────────────────────
    String NCP_ENC_NOT_NEGOTIATED = "NCP-ENC-NOT-NEGOTIATED";
    String NCP_ENC_AUTH_FAILED    = "NCP-ENC-AUTH-FAILED";

    // ── Protocol / Preamble ──────────────────────────────────────────────────
    String NCP_VERSION_INCOMPATIBLE = "NCP-VERSION-INCOMPATIBLE";
    String NCP_PREAMBLE_INVALID     = "NCP-PREAMBLE-INVALID";

    // ── Keepalive (v0.8) ─────────────────────────────────────────────────────
    String NCP_KEEPALIVE_TIMEOUT    = "NCP-KEEPALIVE-TIMEOUT";
    String NCP_REKEY_REQUIRED       = "NCP-REKEY-REQUIRED";
    String NCP_EARLY_DATA_REJECTED  = "NCP-EARLY-DATA-REJECTED";

    // ── Native-mode TLS session-NID binding (NPS-RFC-0006 §6.3–§6.4) ─────────
    /**
     * The mTLS client-certificate NID does not match the session IdentFrame NID, or a
     * resumed TLS session's certificate NID differs from the ticket-bound NID.
     *
     * <p>NPS-CR-0009 reuses this as the native-path failover trigger: a session that
     * lands on the wrong Anchor after an ownership transfer fails this way, which is
     * one of the shapes {@link NcpFailoverConnector} retries on.</p>
     */
    String NCP_NID_MISMATCH         = "NCP-NID-MISMATCH";

    // ── NCP error → NPS status mapping ───────────────────────────────────────

    /**
     * Maps each NCP error code to its corresponding NPS status code.
     */
    Map<String, String> NCP_TO_NPS_STATUS = Map.ofEntries(
        Map.entry(NCP_ANCHOR_NOT_FOUND,          NpsStatusCodes.NPS_CLIENT_NOT_FOUND),
        Map.entry(NCP_ANCHOR_SCHEMA_INVALID,     NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(NCP_ANCHOR_ID_MISMATCH,        NpsStatusCodes.NPS_CLIENT_CONFLICT),
        Map.entry(NCP_ANCHOR_STALE,              NpsStatusCodes.NPS_CLIENT_CONFLICT),
        Map.entry(NCP_FRAME_UNKNOWN_TYPE,        NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(NCP_FRAME_PAYLOAD_TOO_LARGE,   NpsStatusCodes.NPS_LIMIT_PAYLOAD),
        Map.entry(NCP_FRAME_FLAGS_INVALID,       NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(NCP_STREAM_SEQ_GAP,            NpsStatusCodes.NPS_STREAM_SEQ_GAP),
        Map.entry(NCP_STREAM_NOT_FOUND,          NpsStatusCodes.NPS_STREAM_NOT_FOUND),
        Map.entry(NCP_STREAM_LIMIT_EXCEEDED,     NpsStatusCodes.NPS_STREAM_LIMIT),
        Map.entry(NCP_STREAM_WINDOW_OVERFLOW,    NpsStatusCodes.NPS_STREAM_LIMIT),
        Map.entry(NCP_ENCODING_UNSUPPORTED,      NpsStatusCodes.NPS_SERVER_ENCODING_UNSUPPORTED),
        Map.entry(NCP_DIFF_FORMAT_UNSUPPORTED,   NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(NCP_ENC_NOT_NEGOTIATED,        NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(NCP_ENC_AUTH_FAILED,           NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(NCP_VERSION_INCOMPATIBLE,      NpsStatusCodes.NPS_PROTO_VERSION_INCOMPATIBLE),
        Map.entry(NCP_PREAMBLE_INVALID,          NpsStatusCodes.NPS_PROTO_PREAMBLE_INVALID),
        Map.entry(NCP_KEEPALIVE_TIMEOUT,         NpsStatusCodes.NPS_SERVER_TIMEOUT),
        Map.entry(NCP_REKEY_REQUIRED,            NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(NCP_NID_MISMATCH,              NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED),
        Map.entry(NCP_EARLY_DATA_REJECTED,       NpsStatusCodes.NPS_PROTO_VERSION_INCOMPATIBLE)
    );
}
