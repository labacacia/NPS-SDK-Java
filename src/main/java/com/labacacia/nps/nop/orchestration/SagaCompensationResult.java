// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.orchestration;

import java.util.List;

/**
 * Summary of the Saga compensation run (NPS-5 §3.5).
 * Attached to {@link NopTaskResult#compensation()} when rollback was attempted.
 *
 * @param attempted     Number of compensation actions dispatched.
 * @param succeeded     Number that completed successfully.
 * @param failed        Number that failed (or, for strict, were missing).
 * @param failedNodeIds IDs of the nodes whose compensation failed/was missing.
 */
public record SagaCompensationResult(
    int attempted,
    int succeeded,
    int failed,
    List<String> failedNodeIds) {
}
