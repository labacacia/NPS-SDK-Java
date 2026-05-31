// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop;

/** Well-known compensation policy constants for {@link TaskFrame#compensationPolicy()}. */
public final class CompensationPolicy {
    private CompensationPolicy() {}

    /** No compensation — default behaviour. */
    public static final String NONE       = "none";

    /** Trigger compensation only when the task fails. */
    public static final String ON_FAILURE = "on_failure";

    /** Always trigger compensation regardless of outcome. */
    public static final String ALWAYS     = "always";
}
