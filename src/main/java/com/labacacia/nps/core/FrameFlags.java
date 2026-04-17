// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.core;

/** Bit-flag constants for NPS frame flags byte. */
public final class FrameFlags {
    private FrameFlags() {}

    public static final int FINAL        = 0x01;
    public static final int EXT          = 0x02;
    public static final int TIER1_JSON   = 0x04;
    public static final int TIER2_MSGPACK = 0x08;
}
