// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Inputs for {@link TrustFrameValidator}. Java parallel of the .NET
 * {@code TrustFrameValidationContext} record.
 */
public final class TrustFrameValidationContext {

    private final Set<String>  trustedGrantors;
    private final String       expectedGranteeCa;
    private final List<String> requiredCapabilities;
    private final String       targetNodePath;
    private final Instant      asOf;

    private TrustFrameValidationContext(Builder b) {
        this.trustedGrantors      = b.trustedGrantors;
        this.expectedGranteeCa    = b.expectedGranteeCa;
        this.requiredCapabilities = b.requiredCapabilities;
        this.targetNodePath       = b.targetNodePath;
        this.asOf                 = b.asOf;
    }

    /** Grantor CA NIDs that this node trusts as anchors. */
    public Set<String>  trustedGrantors()      { return trustedGrantors; }

    /** The CA NID expected to be authorized by the TrustFrame. */
    public String       expectedGranteeCa()    { return expectedGranteeCa; }

    /** Capabilities required for the current request. */
    public List<String> requiredCapabilities() { return requiredCapabilities; }

    /** Target NWP path required for the current request. */
    public String       targetNodePath()       { return targetNodePath; }

    /** Clock override for tests. */
    public Instant      asOf()                 { return asOf; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Set<String>  trustedGrantors;
        private String       expectedGranteeCa;
        private List<String> requiredCapabilities;
        private String       targetNodePath;
        private Instant      asOf;

        public Builder trustedGrantors(Set<String> v)      { this.trustedGrantors = v;      return this; }
        public Builder expectedGranteeCa(String v)         { this.expectedGranteeCa = v;    return this; }
        public Builder requiredCapabilities(List<String> v){ this.requiredCapabilities = v; return this; }
        public Builder targetNodePath(String v)            { this.targetNodePath = v;       return this; }
        public Builder asOf(Instant v)                     { this.asOf = v;                 return this; }

        public TrustFrameValidationContext build() { return new TrustFrameValidationContext(this); }
    }
}
