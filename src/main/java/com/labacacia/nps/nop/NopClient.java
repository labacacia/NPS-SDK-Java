// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.codec.NpsFrameCodec;
import com.labacacia.nps.core.registry.FrameRegistry;
import com.labacacia.nps.ncp.NcpFrameRegistrar;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/** HTTP-mode NOP client. Uses {@link HttpClient} (Java 11+). */
public final class NopClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String        baseUrl;
    private final NpsFrameCodec codec;
    private final EncodingTier  tier;
    private final HttpClient    http;

    public NopClient(String baseUrl) {
        this(baseUrl, EncodingTier.MSGPACK, null, null);
    }

    public NopClient(String baseUrl, EncodingTier tier,
                     FrameRegistry registry, HttpClient httpClient) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.tier    = tier != null ? tier : EncodingTier.MSGPACK;
        this.http    = httpClient != null ? httpClient : HttpClient.newHttpClient();

        FrameRegistry reg = registry;
        if (reg == null) {
            reg = new FrameRegistry();
            NcpFrameRegistrar.register(reg);
            NopFrameRegistrar.register(reg);
        }
        this.codec = new NpsFrameCodec(reg);
    }

    // ── submit ────────────────────────────────────────────────────────────────

    public String submit(TaskFrame frame) throws IOException, InterruptedException {
        byte[] wire = codec.encode(frame, tier);
        HttpResponse<byte[]> res = http.send(
            post(baseUrl + "/task", wire, "application/x-nps-frame"),
            HttpResponse.BodyHandlers.ofByteArray());
        checkOk(res.statusCode(), "/task");
        @SuppressWarnings("unchecked")
        Map<String, Object> json = MAPPER.readValue(res.body(), Map.class);
        return (String) json.get("task_id");
    }

    // ── getStatus ─────────────────────────────────────────────────────────────

    public NopTaskStatus getStatus(String taskId) throws IOException, InterruptedException {
        HttpResponse<byte[]> res = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/task/" + taskId))
                .header("Accept", "application/json")
                .GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
        checkOk(res.statusCode(), "/task/" + taskId);
        @SuppressWarnings("unchecked")
        Map<String, Object> json = MAPPER.readValue(res.body(), Map.class);
        return new NopTaskStatus(json);
    }

    // ── cancel ────────────────────────────────────────────────────────────────

    public void cancel(String taskId) throws IOException, InterruptedException {
        HttpResponse<Void> res = http.send(
            post(baseUrl + "/task/" + taskId + "/cancel", new byte[0], "application/json"),
            HttpResponse.BodyHandlers.discarding());
        checkOk(res.statusCode(), "/task/" + taskId + "/cancel");
    }

    // ── wait ──────────────────────────────────────────────────────────────────

    public NopTaskStatus wait(String taskId, long pollIntervalMs, long timeoutMs)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            NopTaskStatus status = getStatus(taskId);
            if (status.isTerminal()) return status;
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                throw new RuntimeException(
                    "Task '" + taskId + "' did not complete within " + timeoutMs +
                    "ms (state: " + status.raw().get("state") + ").");
            }
            Thread.sleep(Math.min(pollIntervalMs, remaining));
        }
    }

    public NopTaskStatus wait(String taskId) throws IOException, InterruptedException {
        return wait(taskId, 1_000, 30_000);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private HttpRequest post(String url, byte[] body, String contentType) {
        return HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", contentType)
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    }

    private static void checkOk(int status, String path) {
        if (status < 200 || status >= 300) {
            throw new RuntimeException("NOP " + path + " failed: HTTP " + status);
        }
    }
}
