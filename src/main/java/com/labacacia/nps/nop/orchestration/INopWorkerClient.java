// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.orchestration;

import java.util.List;
import java.util.stream.Stream;

/**
 * Abstraction for dispatching delegations to Worker Agents and receiving their
 * result streams (NPS-5 §3.2, §3.4).
 *
 * <p>Implement this interface to connect the Orchestrator to real agents (e.g. via
 * HTTP/NWP, in-process, or a mock in tests).
 */
public interface INopWorkerClient {

    /**
     * Dispatches a {@link NopDelegate} to the target Worker Agent and returns a lazy
     * stream of {@link WorkerStreamFrame} messages. The final frame has
     * {@link WorkerStreamFrame#isFinal()} set to {@code true}.
     *
     * <p>The stream is consumed on the orchestrator's node-execution thread; blocking
     * within the stream's iterator is permitted. The stream MUST be closed by the caller.
     */
    Stream<WorkerStreamFrame> delegate(NopDelegate delegate);

    /**
     * Sends a lightweight preflight probe to {@code agentNid} to confirm resource
     * availability before committing to full execution (NPS-5 §4).
     *
     * @param agentNid             Target Worker Agent NID.
     * @param action               The action URL the agent will be asked to perform.
     * @param estimatedNpt         Estimated CGN budget for the operation.
     * @param requiredCapabilities Capability identifiers the agent must support (nullable).
     */
    PreflightResult preflight(
        String agentNid,
        String action,
        long estimatedNpt,
        List<String> requiredCapabilities);

    /** Convenience overload with no budget/capability hints. */
    default PreflightResult preflight(String agentNid, String action) {
        return preflight(agentNid, action, 0, null);
    }
}
