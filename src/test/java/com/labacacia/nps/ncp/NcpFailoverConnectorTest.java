// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ncp;

import com.labacacia.nps.core.NpsStatusCodes;
import com.labacacia.nps.core.exception.NpsProtocolError;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** NPS-CR-0009 §5.3 — failover reconnect / session continuity on the native path. */
class NcpFailoverConnectorTest {

    /** Stand-in for whatever a real connect would produce. */
    private record Session(String host, int port) {}

    private static NcpFailoverConnector.ActiveResolver queue(
            AtomicInteger calls, List<NcpFailoverConnector.Endpoint> endpoints) {
        Deque<NcpFailoverConnector.Endpoint> q = new ArrayDeque<>(endpoints);
        return () -> {
            calls.incrementAndGet();
            return q.isEmpty() ? endpoints.get(endpoints.size() - 1) : q.removeFirst();
        };
    }

    @Test
    void reresolvesAndReconnectsAfterNidMismatch() throws Exception {
        var calls = new AtomicInteger();
        var resolver = queue(calls, List.of(
            new NcpFailoverConnector.Endpoint("old-anchor", 17433),
            new NcpFailoverConnector.Endpoint("new-anchor", 17433)));

        var connector = new NcpFailoverConnector<Session>(resolver, (host, port) -> {
            if ("old-anchor".equals(host)) {
                throw new NpsProtocolError(NcpErrorCodes.NCP_NID_MISMATCH,
                    NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED,
                    "session NID does not match the certificate NID");
            }
            return new Session(host, port);
        });

        Session session = connector.connect();

        assertEquals("new-anchor", session.host());
        assertEquals(2, calls.get(), "both resolutions must be consumed");
    }

    @Test
    void reresolvesAfterSocketLoss() throws Exception {
        var calls = new AtomicInteger();
        var resolver = queue(calls, List.of(
            new NcpFailoverConnector.Endpoint("anchor-1", 17433),
            new NcpFailoverConnector.Endpoint("anchor-2", 17433)));

        var connector = new NcpFailoverConnector<Session>(resolver, (host, port) -> {
            if ("anchor-1".equals(host)) throw new ConnectException("Connection refused");
            return new Session(host, port);
        });

        assertEquals("anchor-2", connector.connect().host());
        assertEquals(2, calls.get());
    }

    @Test
    void nonFailoverErrorsPropagateImmediately() {
        var calls = new AtomicInteger();
        var resolver = queue(calls, List.of(new NcpFailoverConnector.Endpoint("anchor-1", 17433)));

        var connector = new NcpFailoverConnector<Session>(resolver, (host, port) -> {
            throw new NpsProtocolError(NcpErrorCodes.NCP_FRAME_FLAGS_INVALID,
                NpsStatusCodes.NPS_CLIENT_BAD_FRAME, "bad flags");
        });

        var ex = assertThrows(NpsProtocolError.class, connector::connect);
        assertEquals(NcpErrorCodes.NCP_FRAME_FLAGS_INVALID, ex.protocolErrorCode());
        assertEquals(1, calls.get(), "a non-failover error must not trigger re-resolution");
    }

    @Test
    void exhaustedAttemptsRethrowTheLastFailure() {
        var calls = new AtomicInteger();
        var resolver = queue(calls, List.of(new NcpFailoverConnector.Endpoint("anchor-1", 17433)));
        var attempt = new AtomicInteger();

        var connector = new NcpFailoverConnector<Session>(resolver,
            (host, port) -> { throw new SocketTimeoutException("timeout #" + attempt.incrementAndGet()); },
            3);

        // The ORIGINAL type is preserved, and it is the LAST failure that surfaces.
        var ex = assertThrows(SocketTimeoutException.class, connector::connect);
        assertEquals("timeout #3", ex.getMessage());
        assertEquals(3, calls.get());
    }

    @Test
    void theFirstAttemptAlsoResolves() throws Exception {
        var calls = new AtomicInteger();
        var resolver = queue(calls, List.of(new NcpFailoverConnector.Endpoint("anchor-1", 17433)));
        var connector = new NcpFailoverConnector<Session>(resolver, Session::new);

        assertEquals("anchor-1", connector.connect().host());
        assertEquals(1, calls.get());
    }

    @Test
    void constructorValidation() {
        NcpFailoverConnector.ActiveResolver r = () -> new NcpFailoverConnector.Endpoint("h", 1);
        NcpFailoverConnector.SessionConnector<Session> c = Session::new;

        assertThrows(IllegalArgumentException.class, () -> new NcpFailoverConnector<Session>(null, c));
        assertThrows(IllegalArgumentException.class, () -> new NcpFailoverConnector<Session>(r, null));
        assertThrows(IllegalArgumentException.class, () -> new NcpFailoverConnector<>(r, c, 0));
        assertEquals(2, new NcpFailoverConnector<>(r, c).maxAttempts(), "the default is 2");
    }

    @Test
    void aResolverFailurePropagatesUnwrapped() {
        var connector = new NcpFailoverConnector<Session>(
            () -> { throw new IllegalStateException("no active anchor"); }, Session::new);
        assertThrows(IllegalStateException.class, connector::connect);
    }

    @Test
    void nidMismatchCodeIsRegistered() {
        assertEquals("NCP-NID-MISMATCH", NcpErrorCodes.NCP_NID_MISMATCH);
        assertEquals(NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED,
            NcpErrorCodes.NCP_TO_NPS_STATUS.get(NcpErrorCodes.NCP_NID_MISMATCH));
    }
}
