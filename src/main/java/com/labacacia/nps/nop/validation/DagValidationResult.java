// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.validation;

import java.util.List;

/**
 * Result of DAG validation.
 *
 * @param valid            Whether the DAG passed validation.
 * @param errorCode        NOP error code when invalid; null otherwise.
 * @param errorMessage     Human-readable message when invalid; null otherwise.
 * @param topologicalOrder Topologically sorted node IDs (populated only when valid).
 */
public record DagValidationResult(
    boolean valid,
    String errorCode,
    String errorMessage,
    List<String> topologicalOrder) {

    public static DagValidationResult success(List<String> order) {
        return new DagValidationResult(true, null, null, order);
    }

    public static DagValidationResult failure(String errorCode, String message) {
        return new DagValidationResult(false, errorCode, message, null);
    }
}
