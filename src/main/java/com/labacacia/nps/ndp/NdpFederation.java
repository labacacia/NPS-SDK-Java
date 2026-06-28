// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Helpers for NDP cross-registry federation forwarding (NPS-4 §7.6). */
public final class NdpFederation {
    public static final String FORWARDED_BY_HEADER = "ndp-forwarded-by";
    public static final int MAX_FEDERATION_HOPS = 3;

    private NdpFederation() {}

    public static List<String> parseForwardedBy(String header) {
        var out = new ArrayList<String>();
        if (header == null || header.isBlank()) return out;
        for (String part : header.split(",")) {
            var hop = part.trim();
            if (!hop.isEmpty()) out.add(hop);
        }
        return out;
    }

    public static Optional<String> appendForwardedBy(String ownNid, String header) {
        var hops = parseForwardedBy(header);
        if (hops.contains(ownNid)) {
            throw new IllegalArgumentException(
                NdpErrorCodes.NDP_FEDERATION_LOOP + ": own NID already appears in ndp-forwarded-by");
        }
        if (hops.size() >= MAX_FEDERATION_HOPS) return Optional.empty();

        hops.add(ownNid);
        return Optional.of(String.join(", ", hops));
    }
}
