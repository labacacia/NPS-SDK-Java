// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ncp;

import java.util.List;

/** Server capabilities used by the NCP v0.11 portable handshake profile. */
public record NcpHandshakeProfile(
    String minVersion,
    String npsVersion,
    List<String> supportedEncodings,
    List<String> supportedProtocols,
    int maxFramePayload,
    boolean extSupport,
    int maxConcurrentStreams
) {
    public NcpHandshakeProfile {
        supportedEncodings = List.copyOf(supportedEncodings);
        supportedProtocols = List.copyOf(supportedProtocols);
    }

    public static NcpHandshakeProfile defaults() {
        return new NcpHandshakeProfile(
            "0.1",
            "0.11",
            List.of("msgpack", "json", "binary_vector.v1"),
            List.of("ncp", "nwp", "nip", "ndp", "nop"),
            0xFFFF,
            false,
            32);
    }
}
