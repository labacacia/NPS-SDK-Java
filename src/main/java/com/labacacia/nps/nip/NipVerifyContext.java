// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import java.time.Instant;
import java.util.List;

/**
 * Per-request context passed to {@link NipIdentVerifier} for the NPS-3 §7
 * IdentFrame verification flow (steps 5–6 plus the clock override for step 1).
 *
 * <p>Java parallel of the .NET {@code NipVerifyContext} record. All fields are
 * optional — omit to skip the corresponding check.
 */
public final class NipVerifyContext {

    private final List<String>   requiredCapabilities;
    private final String         targetNodePath;
    private final Instant        asOf;
    private final AssuranceLevel minAssuranceLevel;

    private NipVerifyContext(Builder b) {
        this.requiredCapabilities = b.requiredCapabilities;
        this.targetNodePath       = b.targetNodePath;
        this.asOf                 = b.asOf;
        this.minAssuranceLevel    = b.minAssuranceLevel;
    }

    /** Capabilities the Node requires the Agent to hold (Step 5). Null/empty skips the check. */
    public List<String>   requiredCapabilities() { return requiredCapabilities; }

    /** Full NWP node path the Agent is trying to access (Step 6). Null skips the check. */
    public String         targetNodePath()       { return targetNodePath; }

    /** Clock override (replaces {@code Instant.now()} in the expiry check). Null in production. */
    public Instant        asOf()                 { return asOf; }

    /** Minimum required Agent assurance level (NPS-RFC-0003). Null skips the check. */
    public AssuranceLevel minAssuranceLevel()    { return minAssuranceLevel; }

    public static Builder builder() { return new Builder(); }

    /** An empty context — all optional checks skipped. */
    public static NipVerifyContext empty() { return builder().build(); }

    public static final class Builder {
        private List<String>   requiredCapabilities;
        private String         targetNodePath;
        private Instant        asOf;
        private AssuranceLevel minAssuranceLevel;

        public Builder requiredCapabilities(List<String> v) { this.requiredCapabilities = v; return this; }
        public Builder targetNodePath(String v)             { this.targetNodePath = v;       return this; }
        public Builder asOf(Instant v)                      { this.asOf = v;                 return this; }
        public Builder minAssuranceLevel(AssuranceLevel v)  { this.minAssuranceLevel = v;    return this; }

        public NipVerifyContext build() { return new NipVerifyContext(this); }
    }
}
