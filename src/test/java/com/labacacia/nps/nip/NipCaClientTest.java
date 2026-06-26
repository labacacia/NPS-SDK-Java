// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class NipCaClientTest {
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void registerAgentSendsTypedRequestWithBearer() throws Exception {
        server.createContext("/nip/v1/agents/register", exchange -> {
            assertEquals("Bearer secret", exchange.getRequestHeaders().getFirst("Authorization"));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("\"identifier\":\"a\""));
            byte[] response = """
                {"frame":"0x20","nid":"urn:nps:agent:example.test:a","pub_key":"ed25519:a","capabilities":["nwp:query"],"scope":{},"issued_by":"urn:nps:org:example.test","issued_at":"2026-01-01T00:00:00Z","expires_at":"2026-01-02T00:00:00Z","serial":"0x1","signature":"ed25519:sig"}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        NipCaClient client = new NipCaClient(baseUrl, "/nip", null);
        NipCaIdentFrame frame = client.registerAgent(
            new NipCaRegisterRequest("a", "ed25519:a", List.of("nwp:query"), "{}", null),
            "secret");

        assertEquals("urn:nps:agent:example.test:a", frame.nid);
    }

    @Test
    void errorResponseThrowsTypedException() {
        server.createContext("/v1/agents/urn:nps:agent:example.test:a/renew", exchange -> {
            byte[] response = "{\"error_code\":\"NIP-CA-UNAUTHORIZED\",\"message\":\"nope\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        NipCaClient client = new NipCaClient(baseUrl);
        NipCaClientException ex = assertThrows(NipCaClientException.class,
            () -> client.renewAgent("urn:nps:agent:example.test:a", null));

        assertEquals("NIP-CA-UNAUTHORIZED", ex.errorCode());
        assertEquals(401, ex.statusCode());
    }
}
