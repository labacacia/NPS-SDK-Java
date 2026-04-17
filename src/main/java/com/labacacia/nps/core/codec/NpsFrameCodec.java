// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.core.codec;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameHeader;
import com.labacacia.nps.core.FrameFlags;
import com.labacacia.nps.core.NpsFrame;
import com.labacacia.nps.core.exception.NpsCodecError;
import com.labacacia.nps.core.registry.FrameRegistry;

import java.nio.ByteBuffer;

/**
 * NPS frame codec — dispatches encode/decode to the correct tier codec,
 * assembles wire bytes with a 4-byte or 8-byte header.
 */
public final class NpsFrameCodec {

    public static final long DEFAULT_MAX_PAYLOAD = 10L * 1024 * 1024; // 10 MiB

    private final FrameRegistry      registry;
    private final long               maxPayload;
    private final Tier1JsonCodec     jsonCodec    = new Tier1JsonCodec();
    private final Tier2MsgPackCodec  msgpackCodec = new Tier2MsgPackCodec();

    public NpsFrameCodec(FrameRegistry registry) {
        this(registry, DEFAULT_MAX_PAYLOAD);
    }

    public NpsFrameCodec(FrameRegistry registry, long maxPayload) {
        this.registry   = registry;
        this.maxPayload = maxPayload;
    }

    // ── Encode ────────────────────────────────────────────────────────────────

    public byte[] encode(NpsFrame frame) {
        return encode(frame, null);
    }

    public byte[] encode(NpsFrame frame, EncodingTier overrideTier) {
        EncodingTier tier    = overrideTier != null ? overrideTier : frame.preferredTier();
        byte[]       payload = encodePayload(frame, tier);

        if (payload.length > maxPayload) {
            throw new NpsCodecError(
                "Payload size " + payload.length + " exceeds maxPayload " + maxPayload);
        }

        boolean ext   = FrameHeader.needsExtended(payload.length);
        boolean isFin = true; // all frames are final by default except StreamFrame (handled in frame)
        // For StreamFrame, check isLast flag — delegated via overridden isFinal() if needed.
        // Caller can use encode(frame, tier, isFinal) if needed; here we follow preferredFinal.
        if (frame instanceof com.labacacia.nps.ncp.StreamFrame sf) {
            isFin = sf.isLast();
        }

        int flags = FrameHeader.buildFlags(tier, isFin, ext);
        FrameHeader header = new FrameHeader(frame.frameType(), flags, payload.length);

        byte[] hdr = header.toBytes();
        return ByteBuffer.allocate(hdr.length + payload.length)
            .put(hdr).put(payload).array();
    }

    // ── Decode ────────────────────────────────────────────────────────────────

    public NpsFrame decode(byte[] wire) {
        FrameHeader header  = FrameHeader.parse(wire);
        int         hdrSize = header.headerSize();
        byte[]      payload = new byte[(int) header.payloadLength];
        System.arraycopy(wire, hdrSize, payload, 0, payload.length);
        return decodePayload(header.frameType, header.encodingTier(), payload);
    }

    public static FrameHeader peekHeader(byte[] wire) {
        return FrameHeader.parse(wire);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private byte[] encodePayload(NpsFrame frame, EncodingTier tier) {
        return switch (tier) {
            case JSON    -> jsonCodec.encode(frame);
            case MSGPACK -> msgpackCodec.encode(frame);
        };
    }

    private NpsFrame decodePayload(com.labacacia.nps.core.FrameType type,
                                   EncodingTier tier, byte[] payload) {
        return switch (tier) {
            case JSON    -> jsonCodec.decode(type, payload, registry);
            case MSGPACK -> msgpackCodec.decode(type, payload, registry);
        };
    }
}
