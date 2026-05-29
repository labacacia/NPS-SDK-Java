// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0

package com.labacacia.nps.nwp;

/** Thrown when a response payload would exceed the declared CGN budget. */
public class BudgetExceededError extends RuntimeException {
    public final int requested;
    public final int limit;
    public BudgetExceededError(int requested, int limit) {
        super("CGN budget exceeded: " + requested + " > " + limit);
        this.requested = requested;
        this.limit = limit;
    }
}
