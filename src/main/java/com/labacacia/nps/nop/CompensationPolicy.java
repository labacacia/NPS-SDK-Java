// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop;

/**
 * Valid values for {@code TaskFrame.compensation_policy} (NPS-5 §3.5).
 *
 * <p>Wire values and predicate semantics are interop-compatible with the .NET
 * reference SDK ({@code NPS.NOP.Models.CompensationPolicy}).
 */
public final class CompensationPolicy {
    private CompensationPolicy() {}

    /**
     * Run compensation for completed predecessors when the task fails; compensation
     * failures are reported but do not stop remaining compensation.
     */
    public static final String BEST_EFFORT = "best_effort";

    /**
     * Run compensation for completed predecessors when the task fails; missing or
     * failed compensation is terminal.
     */
    public static final String STRICT = "strict";

    /** Legacy alias: no saga rollback. Not a NPS-5 wire value. */
    public static final String NONE = "none";

    /** Legacy alias for {@link #BEST_EFFORT}. */
    public static final String ON_FAILURE = "on_failure";

    /** Non-standard extension: run compensation after both success and failure. */
    public static final String ALWAYS = "always";

    /** Returns true when the policy runs compensation after a task failure. */
    public static boolean runsOnFailure(String policy) {
        return BEST_EFFORT.equals(policy) || STRICT.equals(policy)
            || ON_FAILURE.equals(policy) || ALWAYS.equals(policy);
    }

    /** Returns true when the policy runs compensation after a successful task. */
    public static boolean runsOnSuccess(String policy) {
        return ALWAYS.equals(policy);
    }

    /** Returns true when any missing or failed compensation step is terminal. */
    public static boolean isStrict(String policy) {
        return STRICT.equals(policy);
    }
}
