// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.orchestration;

/** Thrown when a condition expression cannot be parsed or evaluated. */
public final class NopConditionException extends RuntimeException {

    public NopConditionException(String message, String expression) {
        super(message + "  Expression: «" + expression + "»");
    }
}
