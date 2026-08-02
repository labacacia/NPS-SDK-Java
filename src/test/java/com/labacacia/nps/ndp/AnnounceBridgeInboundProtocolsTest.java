// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NPS-CR-0010 — {@code bridge_inbound_protocols} on the AnnounceFrame: it round-trips on
 * the wire, is inside the signed canonical form (unlike the advisory
 * {@code health}/{@code last_seen}), and is omitted entirely when unset.
 */
class AnnounceBridgeInboundProtocolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AnnounceFrame bridge(List<String> outbound, List<String> inbound) {
        return new AnnounceFrame(
            "urn:nps:node:ex.com:bridge-1",
            List.of(Map.of("host", "10.0.0.9", "port", 17433, "protocol", "nwp")),
            List.of("bridge.invoke"),
            3600,
            "2026-07-05T00:00:00Z",
            "ed25519:placeholder",
            "bridge",
            List.of("bridge"),
            null, null, null,
            outbound, inbound,
            null, null, 60_000, null, null);
    }

    @Test
    void roundTripsOnTheWire() {
        AnnounceFrame f = bridge(List.of("http"), List.of("mcp", "a2a"));

        assertEquals(List.of("mcp", "a2a"), f.toDict().get("bridge_inbound_protocols"));
        assertEquals(List.of("http"),       f.toDict().get("bridge_protocols"));

        AnnounceFrame back = AnnounceFrame.fromDict(f.toDict());
        assertEquals(List.of("mcp", "a2a"), back.bridgeInboundProtocols());
        assertEquals(List.of("http"),       back.bridgeProtocols());
    }

    @Test
    void isInsideTheSignedCanonicalForm() throws Exception {
        String json = canonical(bridge(List.of("http"), List.of("mcp", "a2a")));
        assertTrue(json.contains("\"bridge_inbound_protocols\":[\"mcp\",\"a2a\"]"), json);
        // Arrays keep source order — only object keys are sorted.
        assertTrue(json.indexOf("bridge_inbound_protocols") < json.indexOf("bridge_protocols"));
    }

    @Test
    void isOmittedEntirelyWhenUnsetAndCanonicalisesIdenticallyToPreCr0010() throws Exception {
        AnnounceFrame outboundOnly = bridge(List.of("http"), null);
        // The pre-CR-0010 constructor arity, unchanged in meaning.
        AnnounceFrame preCr0010 = new AnnounceFrame(
            "urn:nps:node:ex.com:bridge-1",
            List.of(Map.of("host", "10.0.0.9", "port", 17433, "protocol", "nwp")),
            List.of("bridge.invoke"), 3600, "2026-07-05T00:00:00Z", "ed25519:placeholder",
            "bridge", List.of("bridge"), null, null, List.of("http"), null, null, 60_000,
            null, null);

        String json = canonical(outboundOnly);
        assertFalse(json.contains("bridge_inbound_protocols"));
        assertFalse(outboundOnly.toDict().containsKey("bridge_inbound_protocols"));
        assertArrayEquals(canonical(preCr0010).getBytes(StandardCharsets.UTF_8),
                          json.getBytes(StandardCharsets.UTF_8));

        // Absent ⇒ [] per §16.2, but the field itself stays null so nothing is emitted.
        assertNull(AnnounceFrame.fromDict(outboundOnly.toDict()).bridgeInboundProtocols());
    }

    @Test
    void anEmptyDeclaredSetIsDistinctFromAbsent() throws Exception {
        AnnounceFrame explicitlyEmpty = bridge(List.of("http"), List.of());
        assertTrue(canonical(explicitlyEmpty).contains("\"bridge_inbound_protocols\":[]"));
        assertEquals(List.of(), AnnounceFrame.fromDict(explicitlyEmpty.toDict()).bridgeInboundProtocols());
    }

    @Test
    void bothCr0009AndCr0010FieldsCoexistInTheSignedBody() throws Exception {
        AnnounceFrame f = new AnnounceFrame(
            "urn:nps:node:ex.com:bridge-1",
            List.of(Map.of("host", "10.0.0.9", "port", 17433)),
            List.of("bridge.invoke"), 3600, "2026-07-05T00:00:00Z", "ed25519:sig",
            "bridge", List.of("bridge"), "urn:nps:cluster:ex.com:main", 4L, null,
            List.of("http"), List.of("grpc"), null, null, 60_000, "healthy", "2026-07-05T00:01:00Z");

        String json = canonical(f);
        assertTrue(json.contains("\"cluster_epoch\":4"));
        assertTrue(json.contains("\"bridge_inbound_protocols\":[\"grpc\"]"));
        assertFalse(json.contains("\"health\""));
        assertFalse(json.contains("\"last_seen\""));
        assertFalse(json.contains("\"signature\""));
    }

    /** Reproduces {@code NdpAnnounceValidator}'s canonicalization exactly. */
    private static String canonical(AnnounceFrame f) throws Exception {
        return MAPPER.writeValueAsString(new TreeMap<>(f.unsignedDict()));
    }
}
