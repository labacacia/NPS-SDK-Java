// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ncp;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameHeader;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.exception.NpsError;

import java.util.List;

/**
 * Encoding policy negotiated for an established NCP native-mode session (NPS-1 §4.6).
 *
 * <p>The default tier is stable for ordinary frames; Tier-3 BinaryVector is an
 * optional extension for frame classes that explicitly bind to it (currently
 * only {@link FrameType#QUERY}).
 */
public final class NcpEncodingPolicy {

    private final EncodingTier defaultTier;
    private final boolean      binaryVectorEnabled;

    public NcpEncodingPolicy(EncodingTier defaultTier) {
        this(defaultTier, false);
    }

    public NcpEncodingPolicy(EncodingTier defaultTier, boolean binaryVectorEnabled) {
        this.defaultTier         = defaultTier;
        this.binaryVectorEnabled = binaryVectorEnabled;
    }

    public EncodingTier defaultTier()         { return defaultTier; }
    public boolean      binaryVectorEnabled() { return binaryVectorEnabled; }

    /** All encodings enabled by this policy, default tier first. */
    public List<String> enabledEncodings() {
        return binaryVectorEnabled
            ? List.of(encodingToken(defaultTier), "binary_vector.v1")
            : List.of(encodingToken(defaultTier));
    }

    /** Returns {@code true} if {@code tier} is permitted for {@code frameType}. */
    public boolean allows(EncodingTier tier, FrameType frameType) {
        return tier == defaultTier
            || (tier == EncodingTier.BINARY_VECTOR && binaryVectorEnabled && isBinaryVectorFrame(frameType));
    }

    /**
     * Throws {@link NcpEncodingUnsupportedException} if the header's tier is not
     * permitted for its frame type under this policy.
     */
    public void ensureAllows(FrameHeader header) {
        if (allows(header.encodingTier(), header.frameType)) return;

        throw new NcpEncodingUnsupportedException(
            "Frame type 0x" + Integer.toHexString(header.frameType.code) + " used "
                + encodingToken(header.encodingTier())
                + ", but the negotiated session policy allows "
                + String.join(", ", enabledEncodings()) + ".");
    }

    /**
     * Builds a policy from the {@code enabled_encodings} list echoed by the server.
     * BinaryVector is enabled iff {@code binary_vector.v1} appears in the list.
     */
    public static NcpEncodingPolicy fromEnabledEncodings(EncodingTier defaultTier,
                                                         List<String> enabledEncodings) {
        boolean bv = enabledEncodings != null && enabledEncodings.contains("binary_vector.v1");
        return new NcpEncodingPolicy(defaultTier, bv);
    }

    /** Maps an {@link EncodingTier} to its canonical wire token. */
    public static String encodingToken(EncodingTier tier) {
        return switch (tier) {
            case JSON          -> "json";
            case MSGPACK       -> "msgpack";
            case BINARY_VECTOR -> "binary_vector.v1";
            default            -> "unknown:" + tier.wireCode;
        };
    }

    private static boolean isBinaryVectorFrame(FrameType frameType) {
        return frameType == FrameType.QUERY;
    }

    /** Thrown when a frame uses an encoding tier the negotiated policy forbids. */
    public static final class NcpEncodingUnsupportedException extends NpsError {
        public final String errorCode = NcpErrorCodes.NCP_ENCODING_UNSUPPORTED;

        public NcpEncodingUnsupportedException(String message) {
            super(message);
        }
    }
}
