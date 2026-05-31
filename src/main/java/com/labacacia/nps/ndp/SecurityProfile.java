// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

/** Well-known security profile identifiers for {@link InMemoryNdpRegistry}. */
public final class SecurityProfile {
    private SecurityProfile() {}

    /** Local development — no IP enforcement, relaxed TTL caps. */
    public static final String LOCAL_DEV        = "local-dev";

    /** Organisation-private — enforces RFC-1918 / loopback addresses. */
    public static final String ORG_PRIVATE      = "org-private";

    /** Public federated — no IP restriction; announcements are publicly routable. */
    public static final String PUBLIC_FEDERATED = "public-federated";
}
