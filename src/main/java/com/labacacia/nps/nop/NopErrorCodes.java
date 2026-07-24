// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop;

import com.labacacia.nps.core.NpsStatusCodes;

import java.util.Map;

/**
 * NOP error code string constants. Mirror of {@code spec/error-codes.md} NOP section.
 * Values are the canonical wire strings; consumers MUST compare by string equality.
 */
public interface NopErrorCodes {

    // ── Task ─────────────────────────────────────────────────────────────────
    String NOP_TASK_NOT_FOUND         = "NOP-TASK-NOT-FOUND";
    String NOP_TASK_TIMEOUT           = "NOP-TASK-TIMEOUT";
    String NOP_TASK_DAG_INVALID       = "NOP-TASK-DAG-INVALID";
    String NOP_TASK_DAG_CYCLE         = "NOP-TASK-DAG-CYCLE";
    String NOP_TASK_DAG_TOO_LARGE     = "NOP-TASK-DAG-TOO-LARGE";
    String NOP_TASK_ALREADY_COMPLETED = "NOP-TASK-ALREADY-COMPLETED";
    String NOP_TASK_CANCELLED         = "NOP-TASK-CANCELLED";

    // ── Delegate ─────────────────────────────────────────────────────────────
    String NOP_DELEGATE_SCOPE_VIOLATION  = "NOP-DELEGATE-SCOPE-VIOLATION";
    String NOP_DELEGATE_REJECTED         = "NOP-DELEGATE-REJECTED";
    String NOP_DELEGATE_CHAIN_TOO_DEEP   = "NOP-DELEGATE-CHAIN-TOO-DEEP";
    String NOP_DELEGATE_TIMEOUT          = "NOP-DELEGATE-TIMEOUT";

    // ── Sync ─────────────────────────────────────────────────────────────────
    String NOP_SYNC_TIMEOUT             = "NOP-SYNC-TIMEOUT";
    String NOP_SYNC_DEPENDENCY_FAILED   = "NOP-SYNC-DEPENDENCY-FAILED";

    // ── Stream ───────────────────────────────────────────────────────────────
    String NOP_STREAM_SEQ_GAP      = "NOP-STREAM-SEQ-GAP";
    String NOP_STREAM_NID_MISMATCH = "NOP-STREAM-NID-MISMATCH";
    /** AlignStream ack/NAK protocol: NAK received (NOP v0.6 roadmap). */
    String NOP_STREAM_NAK          = "NOP-STREAM-NAK";

    // ── Resources / Conditions / Mappings / Compensation ────────────────────
    String NOP_RESOURCE_INSUFFICIENT     = "NOP-RESOURCE-INSUFFICIENT";
    String NOP_CONDITION_EVAL_ERROR      = "NOP-CONDITION-EVAL-ERROR";
    String NOP_INPUT_MAPPING_ERROR       = "NOP-INPUT-MAPPING-ERROR";
    String NOP_COMPENSATION_FAILED       = "NOP-COMPENSATION-FAILED";
    String NOP_COMPENSATION_PARTIAL_FAILED = "NOP-COMPENSATION-PARTIAL-FAILED";
    String NOP_COMPENSATION_NOT_SUPPORTED = "NOP-COMPENSATION-NOT-SUPPORTED";

    // ── Webhook HMAC (NOP v0.6 roadmap) ──────────────────────────────────────
    /** Webhook callback is missing the required HMAC signature header (NOP v0.6). */
    String NOP_CALLBACK_HMAC_MISSING = "NOP-CALLBACK-HMAC-MISSING";

    // ── Result TTL / NAK (NOP v0.7) ──────────────────────────────────────────
    /** Task result requested after result_ttl_seconds elapsed (NOP v0.7). */
    String NOP_TASK_RESULT_EXPIRED      = "NOP-TASK-RESULT-EXPIRED";
    /** NAK retransmission requested for a frame no longer in sender's buffer (NOP v0.7). */
    String NOP_STREAM_NAK_UNRESOLVABLE  = "NOP-STREAM-NAK-UNRESOLVABLE";

    // ── NOP error → NPS status mapping ───────────────────────────────────────

    /**
     * Maps each NOP error code to its corresponding NPS status code.
     */
    Map<String, String> NOP_TO_NPS_STATUS = Map.ofEntries(
        Map.entry(NOP_TASK_NOT_FOUND,           NpsStatusCodes.NPS_CLIENT_NOT_FOUND),
        Map.entry(NOP_TASK_TIMEOUT,             NpsStatusCodes.NPS_SERVER_TIMEOUT),
        Map.entry(NOP_TASK_DAG_INVALID,         NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(NOP_TASK_DAG_CYCLE,           NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(NOP_TASK_DAG_TOO_LARGE,       NpsStatusCodes.NPS_CLIENT_BAD_FRAME),
        Map.entry(NOP_TASK_ALREADY_COMPLETED,   NpsStatusCodes.NPS_CLIENT_CONFLICT),
        Map.entry(NOP_TASK_CANCELLED,           NpsStatusCodes.NPS_CLIENT_CONFLICT),
        Map.entry(NOP_DELEGATE_SCOPE_VIOLATION, NpsStatusCodes.NPS_AUTH_FORBIDDEN),
        Map.entry(NOP_DELEGATE_REJECTED,        NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE),
        Map.entry(NOP_DELEGATE_CHAIN_TOO_DEEP,  NpsStatusCodes.NPS_CLIENT_BAD_PARAM),
        Map.entry(NOP_DELEGATE_TIMEOUT,         NpsStatusCodes.NPS_SERVER_TIMEOUT),
        Map.entry(NOP_SYNC_TIMEOUT,             NpsStatusCodes.NPS_SERVER_TIMEOUT),
        Map.entry(NOP_SYNC_DEPENDENCY_FAILED,   NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE),
        Map.entry(NOP_STREAM_SEQ_GAP,           NpsStatusCodes.NPS_STREAM_SEQ_GAP),
        Map.entry(NOP_STREAM_NID_MISMATCH,      NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED),
        Map.entry(NOP_STREAM_NAK,               NpsStatusCodes.NPS_STREAM_SEQ_GAP),
        Map.entry(NOP_RESOURCE_INSUFFICIENT,    NpsStatusCodes.NPS_SERVER_UNAVAILABLE),
        Map.entry(NOP_CONDITION_EVAL_ERROR,     NpsStatusCodes.NPS_CLIENT_BAD_PARAM),
        Map.entry(NOP_INPUT_MAPPING_ERROR,      NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE),
        Map.entry(NOP_COMPENSATION_FAILED,      NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE),
        Map.entry(NOP_COMPENSATION_PARTIAL_FAILED, NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE),
        Map.entry(NOP_COMPENSATION_NOT_SUPPORTED, NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE),
        Map.entry(NOP_CALLBACK_HMAC_MISSING,    NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED),
        Map.entry(NOP_TASK_RESULT_EXPIRED,      NpsStatusCodes.NPS_CLIENT_NOT_FOUND),
        Map.entry(NOP_STREAM_NAK_UNRESOLVABLE,  NpsStatusCodes.NPS_STREAM_SEQ_GAP)
    );
}
