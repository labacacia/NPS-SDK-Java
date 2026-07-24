// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ncp;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameFlags;
import com.labacacia.nps.core.FrameHeader;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;
import com.labacacia.nps.core.codec.NpsFrameCodec;
import com.labacacia.nps.core.registry.FrameRegistry;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * NCP native-mode TCP client. Performs the 3-step handshake
 * (preamble → {@link HelloFrame} → {@link NcpHandshakeCapsFrame}) per NPS-1 §4.6
 * and returns a live {@link NcpSession}.
 */
public final class NcpNativeClient {

    /**
     * Handshake-only registry: frame type 0x04 (CAPS) resolves to
     * {@link NcpHandshakeCapsFrame} (not the anchor-query {@link CapsFrame}),
     * and 0xFE (ERROR) to {@link ErrorFrame}.
     */
    private static final FrameRegistry HANDSHAKE_REGISTRY = buildHandshakeRegistry();

    private final NpsFrameCodec codec;
    private final NpsFrameCodec handshakeCodec;

    /**
     * @param codec codec used to encode the outbound {@link HelloFrame} and to
     *              drive the returned session's frame exchange.
     */
    public NcpNativeClient(NpsFrameCodec codec) {
        this.codec          = codec;
        this.handshakeCodec = new NpsFrameCodec(HANDSHAKE_REGISTRY);
    }

    private static FrameRegistry buildHandshakeRegistry() {
        FrameRegistry r = new FrameRegistry();
        r.register(FrameType.CAPS,  NcpHandshakeCapsFrame::fromDict);
        r.register(FrameType.ERROR, ErrorFrame::fromDict);
        return r;
    }

    /**
     * Opens a TCP connection to {@code host}:{@code port}, performs the NCP
     * native-mode handshake, and returns a live session.
     *
     * @throws NcpHandshakeException if the server rejected the handshake or sent
     *                               an unexpected frame.
     */
    public NcpSession connect(String host, int port, HelloFrame hello) throws IOException {
        return connect(host, port, hello, 0);
    }

    /**
     * @param connectTimeoutMs socket connect timeout in ms (0 = OS default / no timeout).
     */
    public NcpSession connect(String host, int port, HelloFrame hello, int connectTimeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);

            OutputStream    rawOut = socket.getOutputStream();
            DataInputStream in     = new DataInputStream(socket.getInputStream());

            // 1 — preamble (encoding not yet negotiated)
            NcpPreamble.write(rawOut);

            // 2 — HelloFrame — always Tier-1 JSON per spec (encoding not yet agreed)
            byte[] helloWire = codec.encode(hello, EncodingTier.JSON);
            rawOut.write(helloWire);
            rawOut.flush();

            // 3 — read the server response header (handles EXT flag)
            FrameHeader header = readFrameHeader(in);

            // 4 — read payload
            byte[] payload = in.readNBytes((int) header.payloadLength);
            if (payload.length != header.payloadLength) {
                throw new EOFException("Truncated handshake response payload: expected "
                    + header.payloadLength + " bytes, got " + payload.length);
            }

            // 5 — ErrorFrame → throw
            if (header.frameType == FrameType.ERROR) {
                ErrorFrame err = (ErrorFrame) decodeHandshakeFrame(header, payload);
                throw new NcpHandshakeException(err.error(), err.message());
            }

            if (header.frameType != FrameType.CAPS) {
                throw new NcpHandshakeException(
                    NcpHandshakeException.UNEXPECTED_FRAME,
                    "Expected CapsFrame (0x" + Integer.toHexString(FrameType.CAPS.code)
                        + "), got 0x" + Integer.toHexString(header.frameType.code) + ".");
            }

            // 6 — decode caps using the negotiated tier the server signalled in the
            // response header flags, and build the session encoding policy.
            EncodingTier negotiatedTier = header.encodingTier();
            NcpHandshakeCapsFrame caps  = (NcpHandshakeCapsFrame) decodeHandshakeFrame(header, payload);
            NcpEncodingPolicy policy    = NcpEncodingPolicy.fromEnabledEncodings(
                negotiatedTier, caps.enabledEncodings());

            return new NcpSession(socket, codec, caps, policy);
        } catch (IOException | RuntimeException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    /** Decodes a handshake CAPS/ERROR frame at the header's tier via the handshake registry. */
    private NpsFrame decodeHandshakeFrame(FrameHeader header, byte[] payload) {
        byte[] hdr  = header.toBytes();
        byte[] wire = new byte[hdr.length + payload.length];
        System.arraycopy(hdr, 0, wire, 0, hdr.length);
        System.arraycopy(payload, 0, wire, hdr.length, payload.length);
        return handshakeCodec.decode(wire);
    }

    /**
     * Reads a frame header from {@code in}, first reading 2 bytes to detect the
     * EXT flag, then the remaining 2 (default) or 6 (extended) bytes.
     */
    public static FrameHeader readFrameHeader(InputStream in) throws IOException {
        byte[] peek = readExactly(in, 2);

        boolean ext       = (peek[1] & FrameFlags.EXT) != 0;
        int     remaining = (ext ? FrameHeader.EXTENDED_HEADER_SIZE : FrameHeader.DEFAULT_HEADER_SIZE) - 2;

        byte[] rest = readExactly(in, remaining);

        byte[] raw = new byte[peek.length + rest.length];
        System.arraycopy(peek, 0, raw, 0, peek.length);
        System.arraycopy(rest, 0, raw, peek.length, rest.length);

        return FrameHeader.parse(raw);
    }

    private static byte[] readExactly(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) {
                throw new EOFException("Stream closed after " + off + "/" + n + " header bytes.");
            }
            off += r;
        }
        return buf;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
