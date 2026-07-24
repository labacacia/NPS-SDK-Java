// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.core;

import java.util.Set;

/**
 * {@code DiffFrame.patchFormat} value constants (NPS-1 §4.2).
 *
 * <p>Mirror of the .NET {@code NPS.Core.NcpPatchFormat}. Consumers MUST compare
 * by string equality against these canonical wire tokens.
 */
public final class NcpPatchFormat {

    private NcpPatchFormat() {}

    /**
     * Default format. {@code patch} is an RFC 6902 JSON Patch array.
     * Compatible with all encoding tiers.
     */
    public static final String JSON_PATCH = "json_patch";

    /**
     * Compact binary format. {@code binary_patch} contains a changed-fields
     * bitset followed by MsgPack-encoded new values. MUST only be used in
     * Tier-2 (MsgPack) frames.
     */
    public static final String BINARY_BITSET = "binary_bitset";

    private static final Set<String> KNOWN = Set.of(JSON_PATCH, BINARY_BITSET);

    /** Returns {@code true} if {@code format} is a recognised patch-format token. */
    public static boolean isValid(String format) {
        return format != null && KNOWN.contains(format);
    }

    /**
     * Returns {@code true} if {@code format} may only be carried in Tier-2
     * (MsgPack) frames. Currently only {@link #BINARY_BITSET}.
     */
    public static boolean requiresMsgPack(String format) {
        return BINARY_BITSET.equals(format);
    }

    /**
     * Returns {@code true} if {@code format} may be carried under {@code tier}.
     * {@link #JSON_PATCH} is valid on any tier; {@link #BINARY_BITSET} only on MsgPack.
     */
    public static boolean isAllowedForTier(String format, EncodingTier tier) {
        if (!isValid(format)) return false;
        if (requiresMsgPack(format)) return tier == EncodingTier.MSGPACK;
        return true;
    }
}
