// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

/**
 * A remote NWP node fronted by an inbound Bridge over HTTP (NPS-CR-0010).
 *
 * @param name       node name, unique per Bridge — namespaces URIs and tool names
 * @param baseUrl    base URL; the Bridge appends {@code /.nwm}, {@code /actions},
 *                   {@code /query}, {@code /invoke}
 * @param agentNid   optional NID forwarded as {@code X-NWP-Agent}
 * @param authHeader optional value forwarded as {@code Authorization}
 * @param readLimit  default row limit for a {@code resources/read}-driven query
 */
public record NwpUpstream(String name, String baseUrl, String agentNid,
                          String authHeader, int readLimit) {

    public NwpUpstream {
        if (name == null || name.isBlank())    throw new IllegalArgumentException("upstream name is required");
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("upstream baseUrl is required");
        if (readLimit <= 0) readLimit = 100;
    }

    public NwpUpstream(String name, String baseUrl) {
        this(name, baseUrl, null, null, 100);
    }

    /** {@link #baseUrl()} with any trailing slashes removed. */
    public String normalisedBaseUrl() {
        return baseUrl.replaceAll("/+$", "");
    }
}
