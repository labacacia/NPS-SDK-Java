// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameFlags;
import com.labacacia.nps.core.FrameHeader;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;
import com.labacacia.nps.core.codec.NpsFrameCodec;
import com.labacacia.nps.core.registry.FrameRegistry;
import com.labacacia.nps.ncp.CapsFrame;
import com.labacacia.nps.ncp.ErrorFrame;
import com.labacacia.nps.ncp.NcpFrameRegistrar;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * Native-mode NWP serving loop over an already established NCP stream.
 *
 * TLS, preamble validation, and Hello negotiation are intentionally owned by
 * the caller or a future NCP session layer. This class only reads complete NPS
 * frames, dispatches QueryFrame/ActionFrame, and writes response frames.
 */
public final class NwpNativeNodeServer {
    @FunctionalInterface
    public interface QueryHandler {
        CapsFrame handle(QueryFrame frame) throws Exception;
    }

    @FunctionalInterface
    public interface ActionHandler {
        Object handle(ActionFrame frame) throws Exception;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NpsFrameCodec codec;
    private final EncodingTier tier;
    private final List<String> enabledEncodings;
    private final String anchorRef;
    private final QueryHandler queryHandler;
    private final ActionHandler actionHandler;

    public NwpNativeNodeServer(QueryHandler queryHandler, ActionHandler actionHandler) {
        this(null, EncodingTier.MSGPACK, "native:nwp", queryHandler, actionHandler);
    }

    public NwpNativeNodeServer(
            NpsFrameCodec codec,
            EncodingTier tier,
            String anchorRef,
            QueryHandler queryHandler,
            ActionHandler actionHandler) {
        this(codec, tier, List.of(encodingToken(tier != null ? tier : EncodingTier.MSGPACK)),
             anchorRef, queryHandler, actionHandler);
    }

    public NwpNativeNodeServer(
            NpsFrameCodec codec,
            EncodingTier tier,
            List<String> enabledEncodings,
            String anchorRef,
            QueryHandler queryHandler,
            ActionHandler actionHandler) {
        this.codec = codec != null ? codec : new NpsFrameCodec(defaultRegistry());
        this.tier = tier != null ? tier : EncodingTier.MSGPACK;
        this.enabledEncodings = enabledEncodings == null || enabledEncodings.isEmpty()
            ? List.of(encodingToken(this.tier))
            : List.copyOf(enabledEncodings);
        this.anchorRef = anchorRef == null || anchorRef.isBlank() ? "native:nwp" : anchorRef;
        this.queryHandler = queryHandler;
        this.actionHandler = actionHandler;
    }

    public NpsFrame dispatch(NpsFrame frame) {
        com.labacacia.nps.telemetry.NwpInstrumentation.FRAMES_PROCESSED.add();
        com.labacacia.nps.telemetry.Span span =
            com.labacacia.nps.telemetry.NwpInstrumentation.TRACER.startSpan("nwp.dispatch")
                .setAttribute("nwp.frame_type", frame.frameType().toString());
        NpsFrame response;
        try {
            if (frame instanceof QueryFrame query) {
                if (queryHandler == null) throw new IllegalStateException("No native NWP query handler configured.");
                response = queryHandler.handle(query);
            } else if (frame instanceof ActionFrame action) {
                if (actionHandler == null) throw new IllegalStateException("No native NWP action handler configured.");
                Object result = actionHandler.handle(action);
                if (result instanceof NpsFrame npsFrame) response = npsFrame;
                else if (result == null) response = new CapsFrame(anchorRef, 0, List.of());
                else response = new CapsFrame(anchorRef, 1, List.of(toRow(result)));
            } else {
                response = new ErrorFrame(
                    "NPS-CLIENT-BAD-FRAME",
                    "NWP-NATIVE-FRAME-UNSUPPORTED",
                    "Native NWP server does not handle frame type " + frame.frameType() + ".",
                    null);
            }
        } catch (Exception ex) {
            response = new ErrorFrame(
                "NPS-SERVER-INTERNAL",
                "NWP-NATIVE-DISPATCH-FAILED",
                ex.getMessage(),
                null);
        }
        if (response instanceof ErrorFrame) {
            com.labacacia.nps.telemetry.NwpInstrumentation.FRAME_ERRORS.add();
            span.setError("dispatch returned error frame");
        } else {
            span.setOk();
        }
        span.close();
        com.labacacia.nps.telemetry.NwpInstrumentation.FRAME_DURATION_MS.record(span.durationMillis());
        return response;
    }

    public byte[] dispatchWire(byte[] wire) {
        FrameHeader header = FrameHeader.parse(wire);
        if (!encodingAllowed(header)) {
            return codec.encode(new ErrorFrame(
                "NPS-SERVER-ENCODING-UNSUPPORTED",
                "NCP-ENCODING-UNSUPPORTED",
                "Frame type 0x" + Integer.toHexString(header.frameType.code) +
                    " used " + encodingToken(header.encodingTier()) +
                    ", but the negotiated policy allows " + String.join(", ", enabledEncodings) + ".",
                null), tier);
        }
        return codec.encode(dispatch(codec.decode(wire)), tier);
    }

    public void serve(InputStream input, OutputStream output) throws IOException {
        while (true) {
            byte[] wire;
            try {
                wire = readWireFrame(input);
            } catch (EOFException eof) {
                return;
            }
            output.write(dispatchWire(wire));
            output.flush();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toRow(Object result) {
        if (result instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return MAPPER.convertValue(result, Map.class);
    }

    private static byte[] readWireFrame(InputStream input) throws IOException {
        byte[] head = input.readNBytes(FrameHeader.DEFAULT_HEADER_SIZE);
        if (head.length == 0) throw new EOFException();
        if (head.length < FrameHeader.DEFAULT_HEADER_SIZE) throw new EOFException("Partial NPS frame header.");

        byte[] rawHeader = head;
        if ((head[1] & FrameFlags.EXT) != 0) {
            byte[] rest = input.readNBytes(FrameHeader.EXTENDED_HEADER_SIZE - FrameHeader.DEFAULT_HEADER_SIZE);
            if (rest.length < FrameHeader.EXTENDED_HEADER_SIZE - FrameHeader.DEFAULT_HEADER_SIZE) {
                throw new EOFException("Partial extended NPS frame header.");
            }
            rawHeader = new byte[FrameHeader.EXTENDED_HEADER_SIZE];
            System.arraycopy(head, 0, rawHeader, 0, head.length);
            System.arraycopy(rest, 0, rawHeader, head.length, rest.length);
        }

        FrameHeader header = FrameHeader.parse(rawHeader);
        byte[] payload = input.readNBytes((int) header.payloadLength);
        if (payload.length < header.payloadLength) throw new EOFException("Partial NPS frame payload.");

        byte[] wire = new byte[rawHeader.length + payload.length];
        System.arraycopy(rawHeader, 0, wire, 0, rawHeader.length);
        System.arraycopy(payload, 0, wire, rawHeader.length, payload.length);
        return wire;
    }

    private static FrameRegistry defaultRegistry() {
        FrameRegistry registry = new FrameRegistry();
        NcpFrameRegistrar.register(registry);
        NwpFrameRegistrar.register(registry);
        return registry;
    }

    private boolean encodingAllowed(FrameHeader header) {
        if (header.encodingTier() == tier) return true;
        return header.encodingTier() == EncodingTier.BINARY_VECTOR
            && header.frameType == FrameType.QUERY
            && enabledEncodings.contains("binary_vector.v1");
    }

    private static String encodingToken(EncodingTier tier) {
        if (tier == null) return "msgpack";
        return switch (tier) {
            case JSON -> "json";
            case MSGPACK -> "msgpack";
            case BINARY_VECTOR -> "binary_vector.v1";
            case RESERVED -> "reserved";
        };
    }
}
