// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.orchestration;

import java.util.List;

/**
 * Result returned by a Worker Agent in response to a preflight probe (NPS-5 §4.3).
 *
 * @param agentNid          NID of the responding Worker Agent.
 * @param available         True when the agent can accept the delegated workload.
 * @param availableCgn      CGN budget the agent can commit. Null when unavailable.
 * @param estimatedQueueMs  Estimated queue depth in ms. Null when unavailable.
 * @param capabilities      Capability identifiers the agent supports.
 * @param unavailableReason Human-readable reason when {@code available} is false.
 */
public record PreflightResult(
    String agentNid,
    boolean available,
    Long availableCgn,
    Integer estimatedQueueMs,
    List<String> capabilities,
    String unavailableReason) {

    public static PreflightResult available(String agentNid) {
        return new PreflightResult(agentNid, true, null, null, null, null);
    }

    public static PreflightResult unavailable(String agentNid, String reason) {
        return new PreflightResult(agentNid, false, null, null, null, reason);
    }
}
