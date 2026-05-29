// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0

package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BridgeTest {

    @Test
    void bridgeProtocolConstants() {
        assertEquals("http", BridgeProtocols.HTTP);
        assertEquals("grpc", BridgeProtocols.GRPC);
        assertEquals("mcp",  BridgeProtocols.MCP);
        assertEquals("a2a",  BridgeProtocols.A2A);
        assertEquals(4, BridgeProtocols.STANDARD.size());
        assertTrue(BridgeProtocols.STANDARD.contains("http"));
        assertTrue(BridgeProtocols.STANDARD.contains("grpc"));
        assertTrue(BridgeProtocols.STANDARD.contains("mcp"));
        assertTrue(BridgeProtocols.STANDARD.contains("a2a"));
    }

    @Test
    void bridgeNodeMetadata() {
        assertEquals("bridge", BridgeNodeMetadata.NODE_TYPE);
    }

    @Test
    void bridgeNodeDescriptorFields() {
        BridgeNodeDescriptor d = new BridgeNodeDescriptor(
            "urn:nps:node:ex.com:b",
            Set.of("http", "grpc")
        );
        assertEquals("urn:nps:node:ex.com:b", d.nid);
        assertEquals(2, d.supportedProtocols.size());
        assertTrue(d.supportedProtocols.contains("http"));
        assertTrue(d.supportedProtocols.contains("grpc"));
    }

    @Test
    void bridgeTargetWithoutExtras() {
        BridgeTarget bt = new BridgeTarget("http", "https://example.com/api");
        assertEquals("http", bt.protocol);
        assertEquals("https://example.com/api", bt.endpoint);
        assertNull(bt.extras);
    }

    @Test
    void bridgeTargetWithExtras() {
        Map<String, Object> extras = Map.of("timeout", 5000);
        BridgeTarget bt = new BridgeTarget("grpc", "grpc://example.com:50051", extras);
        assertEquals("grpc", bt.protocol);
        assertEquals("grpc://example.com:50051", bt.endpoint);
        assertEquals(extras, bt.extras);
    }

    @Test
    void bridgeTargetSerializationRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        BridgeTarget bt = new BridgeTarget("mcp", "https://mcp.example.com/v1");
        String json = mapper.writeValueAsString(bt);
        // extras should be absent (NON_NULL)
        assertFalse(json.contains("extras"));
        assertTrue(json.contains("\"protocol\":\"mcp\""));
        assertTrue(json.contains("\"endpoint\":\"https://mcp.example.com/v1\""));

        // round-trip via Map (BridgeTarget has no default ctor, so deserialize to Map)
        @SuppressWarnings("unchecked")
        Map<String, Object> back = mapper.readValue(json, Map.class);
        assertEquals("mcp", back.get("protocol"));
        assertEquals("https://mcp.example.com/v1", back.get("endpoint"));
    }

    @Test
    void bridgeTargetWithExtrasSerializationRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> extras = Map.of("version", "2");
        BridgeTarget bt = new BridgeTarget("a2a", "https://a2a.example.com", extras);
        String json = mapper.writeValueAsString(bt);
        assertTrue(json.contains("extras"));
        assertTrue(json.contains("\"protocol\":\"a2a\""));

        @SuppressWarnings("unchecked")
        Map<String, Object> back = mapper.readValue(json, Map.class);
        assertEquals("a2a", back.get("protocol"));
        assertNotNull(back.get("extras"));
    }
}
