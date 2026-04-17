// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.core;

import com.labacacia.nps.core.exception.NpsFrameError;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * NPS frame header — 4-byte default or 8-byte extended (EXT flag set).
 *
 * <pre>
 * Default (4 bytes):
 *   [0] frame_type  [1] flags  [2-3] payload_length (uint16 big-endian)
 *
 * Extended (8 bytes):
 *   [0] frame_type  [1] flags  [2-3] reserved(0)  [4-7] payload_length (uint32 big-endian)
 * </pre>
 */
public final class FrameHeader {

    public static final int DEFAULT_HEADER_SIZE  = 4;
    public static final int EXTENDED_HEADER_SIZE = 8;
    private static final int MAX_DEFAULT_PAYLOAD = 0xFFFF;

    public final FrameType   frameType;
    public final int         flags;
    public final long        payloadLength;
    public final boolean     isExtended;

    public FrameHeader(FrameType frameType, int flags, long payloadLength) {
        this.frameType     = frameType;
        this.flags         = flags;
        this.payloadLength = payloadLength;
        this.isExtended    = (flags & FrameFlags.EXT) != 0;
    }

    public boolean isFinal()  { return (flags & FrameFlags.FINAL) != 0; }
    public int headerSize()   { return isExtended ? EXTENDED_HEADER_SIZE : DEFAULT_HEADER_SIZE; }

    public EncodingTier encodingTier() {
        if ((flags & FrameFlags.TIER2_MSGPACK) != 0) return EncodingTier.MSGPACK;
        if ((flags & FrameFlags.TIER1_JSON)    != 0) return EncodingTier.JSON;
        return EncodingTier.MSGPACK; // default
    }

    /** Parse a header from the start of {@code data}. */
    public static FrameHeader parse(byte[] data) {
        if (data.length < DEFAULT_HEADER_SIZE) {
            throw new NpsFrameError("Buffer too small for NPS frame header (need ≥ 4 bytes, got " + data.length + ")");
        }
        int frameTypeCode = data[0] & 0xFF;
        int flags         = data[1] & 0xFF;
        boolean ext       = (flags & FrameFlags.EXT) != 0;

        if (ext) {
            if (data.length < EXTENDED_HEADER_SIZE) {
                throw new NpsFrameError("EXT flag set but buffer too small for 8-byte header");
            }
            long payloadLength = Integer.toUnsignedLong(
                ByteBuffer.wrap(data, 4, 4).order(ByteOrder.BIG_ENDIAN).getInt());
            return new FrameHeader(FrameType.fromCode(frameTypeCode), flags, payloadLength);
        } else {
            int payloadLength = Short.toUnsignedInt(
                ByteBuffer.wrap(data, 2, 2).order(ByteOrder.BIG_ENDIAN).getShort());
            return new FrameHeader(FrameType.fromCode(frameTypeCode), flags, payloadLength);
        }
    }

    /** Serialise this header to bytes. */
    public byte[] toBytes() {
        if (isExtended) {
            ByteBuffer buf = ByteBuffer.allocate(EXTENDED_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
            buf.put((byte) frameType.code);
            buf.put((byte) flags);
            buf.putShort((short) 0); // reserved
            buf.putInt((int) payloadLength);
            return buf.array();
        } else {
            ByteBuffer buf = ByteBuffer.allocate(DEFAULT_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
            buf.put((byte) frameType.code);
            buf.put((byte) flags);
            buf.putShort((short) payloadLength);
            return buf.array();
        }
    }

    /** Build flags byte for a given tier, optionally with EXT and FINAL. */
    public static int buildFlags(EncodingTier tier, boolean isFinal, boolean isExtended) {
        int f = 0;
        if (isFinal)   f |= FrameFlags.FINAL;
        if (isExtended) f |= FrameFlags.EXT;
        if (tier == EncodingTier.JSON)    f |= FrameFlags.TIER1_JSON;
        if (tier == EncodingTier.MSGPACK) f |= FrameFlags.TIER2_MSGPACK;
        return f;
    }

    public static boolean needsExtended(long payloadLength) {
        return payloadLength > MAX_DEFAULT_PAYLOAD;
    }
}
