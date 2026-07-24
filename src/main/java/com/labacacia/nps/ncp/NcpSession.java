// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ncp;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameHeader;
import com.labacacia.nps.core.NpsFrame;
import com.labacacia.nps.core.codec.NpsFrameCodec;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * A live NCP native-mode session established after a successful handshake
 * (NPS-1 §4.6). Wraps the underlying TCP socket and exposes the negotiated
 * parameters. Upper-layer protocols (NWP, NIP, …) may either use the raw
 * streams via {@link #getInputStream()} / {@link #getOutputStream()} or the
 * policy-enforcing {@link #sendFrame} / {@link #receiveFrame} helpers.
 */
public final class NcpSession implements Closeable {

    private final Socket                socket;
    private final DataInputStream       in;
    private final OutputStream          out;
    private final NpsFrameCodec         codec;
    private final NcpHandshakeCapsFrame serverCaps;
    private final NcpEncodingPolicy     encodingPolicy;

    NcpSession(Socket socket,
               NpsFrameCodec codec,
               NcpHandshakeCapsFrame serverCaps,
               NcpEncodingPolicy encodingPolicy) throws IOException {
        this.socket         = socket;
        this.in             = new DataInputStream(socket.getInputStream());
        this.out            = socket.getOutputStream();
        this.codec          = codec;
        this.serverCaps     = serverCaps;
        this.encodingPolicy = encodingPolicy;
    }

    /** Capabilities the peer advertised during the handshake. */
    public NcpHandshakeCapsFrame serverCaps() { return serverCaps; }

    /** Encoding policy negotiated during the handshake. */
    public NcpEncodingPolicy encodingPolicy() { return encodingPolicy; }

    /** Stable default encoding tier negotiated during the handshake. */
    public EncodingTier negotiatedTier() { return encodingPolicy.defaultTier(); }

    /** {@code true} while the underlying TCP connection is still open. */
    public boolean isConnected() { return socket.isConnected() && !socket.isClosed(); }

    /** The authenticated transport socket. Owned by this session — do not close directly. */
    public Socket getSocket() { return socket; }

    /** Raw input stream for upper-layer protocol use. */
    public InputStream getInputStream() { return in; }

    /** Raw output stream for upper-layer protocol use. */
    public OutputStream getOutputStream() { return out; }

    /**
     * Encodes {@code frame} at the negotiated default tier, verifies the
     * resulting header against the session policy, and writes it to the wire.
     */
    public void sendFrame(NpsFrame frame) throws IOException {
        sendFrame(frame, encodingPolicy.defaultTier());
    }

    /**
     * Encodes {@code frame} at {@code tier}, verifies it against the session
     * policy, and writes it to the wire.
     */
    public synchronized void sendFrame(NpsFrame frame, EncodingTier tier) throws IOException {
        byte[] wire = codec.encode(frame, tier);
        encodingPolicy.ensureAllows(FrameHeader.parse(wire));
        out.write(wire);
        out.flush();
    }

    /**
     * Reads one complete frame from the wire (handling the EXT header flag),
     * verifies it against the session policy, and decodes it.
     */
    public synchronized NpsFrame receiveFrame() throws IOException {
        FrameHeader header = NcpNativeClient.readFrameHeader(in);
        encodingPolicy.ensureAllows(header);
        byte[] payload = in.readNBytes((int) header.payloadLength);
        if (payload.length != header.payloadLength) {
            throw new IOException("Truncated frame payload: expected " + header.payloadLength
                + " bytes, got " + payload.length);
        }
        byte[] wire = new byte[header.headerSize() + payload.length];
        byte[] hdr  = header.toBytes();
        System.arraycopy(hdr, 0, wire, 0, hdr.length);
        System.arraycopy(payload, 0, wire, hdr.length, payload.length);
        return codec.decode(wire);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
