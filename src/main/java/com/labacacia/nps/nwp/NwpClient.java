// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.codec.NpsFrameCodec;
import com.labacacia.nps.core.NpsFrame;
import com.labacacia.nps.core.registry.FrameRegistry;
import com.labacacia.nps.ncp.AnchorFrame;
import com.labacacia.nps.ncp.CapsFrame;
import com.labacacia.nps.ncp.NcpFrameRegistrar;
import com.labacacia.nps.ncp.StreamFrame;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HTTP-mode NWP client. Uses {@link HttpClient} (Java 11+).
 */
public final class NwpClient {

    private static final String CONTENT_TYPE = "application/x-nps-frame";
    private static final ObjectMapper MAPPER  = new ObjectMapper();

    private final String       baseUrl;
    private final NpsFrameCodec codec;
    private final EncodingTier tier;
    private final HttpClient   http;

    public NwpClient(String baseUrl) {
        this(baseUrl, EncodingTier.MSGPACK, null, null);
    }

    public NwpClient(String baseUrl, EncodingTier tier,
                     FrameRegistry registry, HttpClient httpClient) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.tier    = tier != null ? tier : EncodingTier.MSGPACK;
        this.http    = httpClient != null ? httpClient : HttpClient.newHttpClient();

        FrameRegistry reg = registry;
        if (reg == null) {
            reg = new FrameRegistry();
            NcpFrameRegistrar.register(reg);
            NwpFrameRegistrar.register(reg);
        }
        this.codec = new NpsFrameCodec(reg);
    }

    // ── sendAnchor ────────────────────────────────────────────────────────────

    public void sendAnchor(AnchorFrame frame) throws IOException, InterruptedException {
        byte[] wire = codec.encode(frame, tier);
        HttpResponse<Void> res = http.send(
            post(baseUrl + "/anchor", wire),
            HttpResponse.BodyHandlers.discarding());
        checkOk(res.statusCode(), "/anchor");
    }

    // ── query ─────────────────────────────────────────────────────────────────

    public CapsFrame query(QueryFrame frame) throws IOException, InterruptedException {
        byte[] wire = codec.encode(frame, tier);
        HttpResponse<byte[]> res = http.send(
            post(baseUrl + "/query", wire),
            HttpResponse.BodyHandlers.ofByteArray());
        checkOk(res.statusCode(), "/query");

        NpsFrame result = codec.decode(res.body());
        if (!(result instanceof CapsFrame caps)) {
            throw new IllegalStateException(
                "Expected CapsFrame from /query, got " + result.getClass().getSimpleName());
        }
        return caps;
    }

    // ── stream (buffered — collects all StreamFrames) ─────────────────────────

    public List<StreamFrame> stream(QueryFrame frame) throws IOException, InterruptedException {
        byte[] wire = codec.encode(frame, tier);
        HttpResponse<byte[]> res = http.send(
            post(baseUrl + "/stream", wire),
            HttpResponse.BodyHandlers.ofByteArray());
        checkOk(res.statusCode(), "/stream");

        // Simple single-response streaming: each chunk must be a self-contained frame.
        // For true chunked streaming, use streamAsync().
        List<StreamFrame> frames = new ArrayList<>();
        byte[] body = res.body();
        int offset  = 0;
        while (offset < body.length) {
            var header  = com.labacacia.nps.core.FrameHeader.parse(
                java.util.Arrays.copyOfRange(body, offset, body.length));
            int total   = header.headerSize() + (int) header.payloadLength;
            byte[] chunk = java.util.Arrays.copyOfRange(body, offset, offset + total);
            NpsFrame f  = codec.decode(chunk);
            if (!(f instanceof StreamFrame sf)) {
                throw new IllegalStateException(
                    "Expected StreamFrame from /stream, got " + f.getClass().getSimpleName());
            }
            frames.add(sf);
            if (sf.isLast()) break;
            offset += total;
        }
        return frames;
    }

    // ── invoke ────────────────────────────────────────────────────────────────

    public Object invoke(ActionFrame frame) throws IOException, InterruptedException {
        byte[] wire = codec.encode(frame, tier);
        HttpResponse<byte[]> res = http.send(
            post(baseUrl + "/invoke", wire),
            HttpResponse.BodyHandlers.ofByteArray());
        checkOk(res.statusCode(), "/invoke");

        if (Boolean.TRUE.equals(frame.async_())) {
            @SuppressWarnings("unchecked")
            Map<String, Object> json = MAPPER.readValue(res.body(), Map.class);
            return AsyncActionResponse.fromDict(json);
        }

        String ct = res.headers().firstValue("content-type").orElse("");
        if (ct.contains(CONTENT_TYPE)) {
            return codec.decode(res.body());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> json = MAPPER.readValue(res.body(), Map.class);
        return json;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private HttpRequest post(String url, byte[] body) {
        return HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", CONTENT_TYPE)
            .header("Accept", CONTENT_TYPE)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    }

    private static void checkOk(int status, String path) {
        if (status < 200 || status >= 300) {
            throw new RuntimeException("NWP " + path + " failed: HTTP " + status);
        }
    }
}
