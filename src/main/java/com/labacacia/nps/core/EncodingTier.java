// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.core;

/** NPS wire-encoding tiers. */
public enum EncodingTier {
    /** Tier-1: UTF-8 JSON (human-readable, debugging / interop). */
    JSON(0x00),
    /** Tier-2: MsgPack (production default, ~60% size reduction). */
    MSGPACK(0x01),
    /** Tier-3: BinaryVector v1 (MsgPack metadata plus float32 vector segments). */
    BINARY_VECTOR(0x02),
    /** Reserved wire tier 0x03. */
    RESERVED(0x03);

    public final int wireCode;

    EncodingTier(int wireCode) {
        this.wireCode = wireCode;
    }

    public static EncodingTier fromWireCode(int wireCode) {
        return switch (wireCode & 0x03) {
            case 0x00 -> JSON;
            case 0x01 -> MSGPACK;
            case 0x02 -> BINARY_VECTOR;
            default   -> RESERVED;
        };
    }
}
