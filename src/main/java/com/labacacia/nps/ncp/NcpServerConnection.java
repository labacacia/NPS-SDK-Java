// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ncp;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.codec.NpsFrameCodec;
import com.labacacia.nps.core.exception.NpsError;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Server-side representation of an inbound NCP connection that has passed the
 * preamble check and sent its {@link HelloFrame}. Call {@link #accept} to
 * complete the handshake, or {@link #reject} to send an error and close the
 * connection (NPS-1 §4.6).
 */
public final class NcpServerConnection implements Closeable {

    private final Socket        socket;
    private final NpsFrameCodec codec;
    private final HelloFrame    clientHello;

    NcpServerConnection(Socket socket, NpsFrameCodec codec, HelloFrame clientHello) {
        this.socket      = socket;
        this.codec       = codec;
        this.clientHello = clientHello;
    }

    /** The {@link HelloFrame} sent by the connecting client. */
    public HelloFrame clientHello() { return clientHello; }

    /** The underlying accepted socket. */
    public Socket getSocket() { return socket; }

    /**
     * Sends {@code serverCaps} to the client and returns a live {@link NcpSession}.
     * The encoding policy is negotiated from the client's supported-encodings list;
     * the CAPS frame is encoded at the negotiated default tier, and its
     * {@code negotiated_encoding}/{@code enabled_encodings} fields are filled in.
     */
    public NcpSession accept(NcpHandshakeCapsFrame serverCaps) throws IOException {
        NcpEncodingPolicy policy = negotiateEncodingPolicy(clientHello);

        NcpHandshakeCapsFrame caps = serverCaps.withNegotiation(
            NcpEncodingPolicy.encodingToken(policy.defaultTier()),
            policy.enabledEncodings());

        byte[] wire = codec.encode(caps, policy.defaultTier());
        OutputStream out = socket.getOutputStream();
        out.write(wire);
        out.flush();

        return new NcpSession(socket, codec, caps, policy);
    }

    /**
     * Sends an {@link ErrorFrame} to reject the client (Tier-1 JSON) and closes
     * the connection.
     */
    public void reject(ErrorFrame error) throws IOException {
        try {
            byte[] wire = codec.encode(error, EncodingTier.JSON);
            OutputStream out = socket.getOutputStream();
            out.write(wire);
            out.flush();
        } finally {
            close();
        }
    }

    /**
     * Selects a stable default encoding from the client's supported-encodings list.
     * Optional encodings such as BinaryVector are recorded as extensions, not defaults.
     */
    private static NcpEncodingPolicy negotiateEncodingPolicy(HelloFrame hello) {
        java.util.List<String> supported = hello.supportedEncodings() == null
            ? java.util.List.of()
            : hello.supportedEncodings();

        boolean binaryVectorEnabled = supported.contains("binary_vector.v1");

        for (String enc : supported) {
            if ("msgpack".equals(enc)) {
                return new NcpEncodingPolicy(EncodingTier.MSGPACK, binaryVectorEnabled);
            }
            if ("json".equals(enc)) {
                return new NcpEncodingPolicy(EncodingTier.JSON, binaryVectorEnabled);
            }
        }

        throw new NpsEncodingUnsupportedException(
            "Client did not offer a supported stable default encoding (expected msgpack or json).");
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    /** Thrown when the client offers no stable default encoding the server accepts. */
    public static final class NpsEncodingUnsupportedException extends NpsError {
        public final String errorCode = NcpErrorCodes.NCP_ENCODING_UNSUPPORTED;

        public NpsEncodingUnsupportedException(String message) {
            super(message);
        }
    }
}
