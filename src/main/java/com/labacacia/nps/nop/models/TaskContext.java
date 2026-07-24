// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.models;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Transparent context carried across all sub-tasks (NPS-5 §3.1.2).
 * Supports OpenTelemetry W3C TraceContext for distributed tracing.
 *
 * @param sessionId  Agent session identifier (reused across requests).
 * @param traceId    OpenTelemetry Trace ID (16 bytes hex, 32 characters).
 * @param spanId     Current Span ID (8 bytes hex, 16 characters).
 * @param traceFlags OpenTelemetry Trace Flags (e.g. 0x01 = sampled).
 * @param baggage    OpenTelemetry Baggage key-value pairs, propagated to all sub-tasks.
 * @param custom     Application-defined context. NOP does not inspect this field.
 */
public record TaskContext(
    String sessionId,
    String traceId,
    String spanId,
    Integer traceFlags,
    Map<String, String> baggage,
    JsonNode custom) {

    /** Creates an empty context (all fields null). */
    public static TaskContext empty() {
        return new TaskContext(null, null, null, null, null, null);
    }
}
