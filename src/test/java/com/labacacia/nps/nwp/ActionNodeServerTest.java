// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.labacacia.nps.core.NpsStatusCodes;

import static org.junit.jupiter.api.Assertions.*;

class ActionNodeServerTest {

    private static final String PREFIX = "/orders";
    private static final String NID = "urn:nps:node:api.example.com:orders";
    private static final String AGENT = "urn:nps:agent:tester";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String base;
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    private String start(ActionNodeServer app) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", app);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        return base;
    }

    private static ActionNodeServer.Options baseOpts() {
        var o = new ActionNodeServer.Options();
        o.nodeId = NID;
        o.pathPrefix = PREFIX;
        var sync = new ActionNodeServer.ActionSpec().withResultAnchor("nps:orders:result");
        o.actions.put("orders.create", sync);
        var async = new ActionNodeServer.ActionSpec().withAsync(true);
        async.resultAnchor = "nps:orders:async";
        o.actions.put("orders.batch", async);
        return o;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(HttpResponse<String> r) throws Exception {
        return MAPPER.readValue(r.body(), Map.class);
    }

    private HttpResponse<String> post(String path, Object b, String agent, Map<String, String> headers) throws Exception {
        var rb = HttpRequest.newBuilder(URI.create(base + path))
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(b)));
        if (agent != null) rb.header("X-NWP-Agent", agent);
        if (headers != null) headers.forEach(rb::header);
        return client.send(rb.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ── sync ──────────────────────────────────────────────────────────────────

    @Test
    void syncInvokeReturnsCaps() throws Exception {
        start(new ActionNodeServer(baseOpts(), (frame, ctx) ->
            new ActionNodeServer.ActionExecutionResult(Map.of("id", "o-1"), null, 3)));
        var resp = post("/orders/invoke",
            Map.of("action_id", "orders.create", "params", Map.of("qty", 2)), AGENT, null);
        assertEquals(200, resp.statusCode());
        assertEquals("action", resp.headers().firstValue("X-NWP-Node-Type").orElse(null));
        assertEquals("nps:orders:result", resp.headers().firstValue("X-NWP-Schema").orElse(null));
        var b = body(resp);
        assertEquals("nps:orders:result", b.get("anchor_ref"));
        assertEquals(1, ((Number) b.get("count")).intValue());
    }

    @Test
    void unknownActionIs404() throws Exception {
        start(new ActionNodeServer(baseOpts(), (frame, ctx) -> new ActionNodeServer.ActionExecutionResult(null)));
        var resp = post("/orders/invoke", Map.of("action_id", "does.notexist"), AGENT, null);
        assertEquals(404, resp.statusCode());
        assertEquals("NWP-ACTION-NOT-FOUND", body(resp).get("error"));
    }

    @Test
    void missingAgentIs401WhenAuthRequired() throws Exception {
        var o = baseOpts();
        o.requireAuth = true;
        start(new ActionNodeServer(o, (frame, ctx) -> new ActionNodeServer.ActionExecutionResult(null)));
        var resp = post("/orders/invoke", Map.of("action_id", "orders.create"), null, null);
        assertEquals(401, resp.statusCode());
        assertEquals("NWP-AUTH-NID-SCOPE-VIOLATION", body(resp).get("error"));
    }

    // ── async + reserved actions ────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void asyncInvokeThenTaskStatus() throws Exception {
        start(new ActionNodeServer(baseOpts(), (frame, ctx) -> {
            return new ActionNodeServer.ActionExecutionResult(Map.of("done", true));
        }));
        var resp = post("/orders/invoke",
            Map.of("action_id", "orders.batch", "async", true), AGENT, null);
        assertEquals(202, resp.statusCode());
        var b = body(resp);
        String taskId = (String) b.get("task_id");
        assertNotNull(taskId);
        assertEquals("pending", b.get("status"));
        assertTrue(((String) b.get("poll_url")).endsWith("/orders/invoke"));

        // Poll until terminal.
        Map<String, Object> status = null;
        for (int i = 0; i < 50; i++) {
            var sr = post("/orders/invoke",
                Map.of("action_id", "system.task.status", "params", Map.of("task_id", taskId)), AGENT, null);
            assertEquals(200, sr.statusCode());
            var data = (java.util.List<?>) body(sr).get("data");
            status = (Map<String, Object>) data.get(0);
            if ("completed".equals(status.get("status"))) break;
            Thread.sleep(20);
        }
        assertNotNull(status);
        assertEquals("completed", status.get("status"));
    }

    @Test
    void asyncCannotRunOnSyncOnlyAction() throws Exception {
        start(new ActionNodeServer(baseOpts(), (frame, ctx) -> new ActionNodeServer.ActionExecutionResult(null)));
        var resp = post("/orders/invoke",
            Map.of("action_id", "orders.create", "async", true), AGENT, null);
        assertEquals(400, resp.statusCode());
        assertEquals("NWP-ACTION-PARAMS-INVALID", body(resp).get("error"));
    }

    @Test
    void taskStatusUnknownIs404() throws Exception {
        start(new ActionNodeServer(baseOpts(), (frame, ctx) -> new ActionNodeServer.ActionExecutionResult(null)));
        var resp = post("/orders/invoke",
            Map.of("action_id", "system.task.status", "params", Map.of("task_id", "nope")), AGENT, null);
        assertEquals(404, resp.statusCode());
        assertEquals("NWP-TASK-NOT-FOUND", body(resp).get("error"));
    }

    @Test
    void taskCancelPendingThenConflictOnReCancel() throws Exception {
        // Async action that blocks so it stays pending/running for the cancel window.
        start(new ActionNodeServer(baseOpts(), (frame, ctx) -> {
            Thread.sleep(2_000);
            return new ActionNodeServer.ActionExecutionResult(null);
        }));
        var resp = post("/orders/invoke",
            Map.of("action_id", "orders.batch", "async", true), AGENT, null);
        String taskId = (String) body(resp).get("task_id");

        var c1 = post("/orders/invoke",
            Map.of("action_id", "system.task.cancel", "params", Map.of("task_id", taskId)), AGENT, null);
        assertEquals(200, c1.statusCode());
        var data = (java.util.List<?>) body(c1).get("data");
        assertEquals("cancelled", ((Map<?, ?>) data.get(0)).get("status"));

        var c2 = post("/orders/invoke",
            Map.of("action_id", "system.task.cancel", "params", Map.of("task_id", taskId)), AGENT, null);
        assertEquals(409, c2.statusCode());
        assertEquals("NWP-TASK-ALREADY-CANCELLED", body(c2).get("error"));
    }

    @Test
    void reservedActionRegistrationRejected() {
        var o = baseOpts();
        o.actions.put("system.task.status", new ActionNodeServer.ActionSpec());
        assertThrows(IllegalStateException.class,
            () -> new ActionNodeServer(o, (frame, ctx) -> new ActionNodeServer.ActionExecutionResult(null)));
    }

    // ── idempotency ──────────────────────────────────────────────────────────────

    @Test
    void idempotencyReHitReturnsCachedResult() throws Exception {
        int[] calls = {0};
        start(new ActionNodeServer(baseOpts(), (frame, ctx) -> {
            calls[0]++;
            return new ActionNodeServer.ActionExecutionResult(Map.of("n", calls[0]));
        }));
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("action_id", "orders.create");
        req.put("params", Map.of("x", 1));
        req.put("idempotency_key", "k1");

        var r1 = post("/orders/invoke", req, AGENT, null);
        var r2 = post("/orders/invoke", req, AGENT, null);
        assertEquals(200, r1.statusCode());
        assertEquals(200, r2.statusCode());
        assertEquals(1, calls[0], "provider must run once for identical idempotent requests");
        assertEquals(r1.body(), r2.body());
    }

    @Test
    void idempotencyConflictOnDifferentParams() throws Exception {
        start(new ActionNodeServer(baseOpts(), (frame, ctx) -> new ActionNodeServer.ActionExecutionResult(Map.of("ok", true))));
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("action_id", "orders.create");
        a.put("params", Map.of("x", 1));
        a.put("idempotency_key", "k2");
        post("/orders/invoke", a, AGENT, null);

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("action_id", "orders.create");
        b.put("params", Map.of("x", 999));
        b.put("idempotency_key", "k2");
        var conflict = post("/orders/invoke", b, AGENT, null);
        assertEquals(409, conflict.statusCode());
        assertEquals("NWP-ACTION-IDEMPOTENCY-CONFLICT", body(conflict).get("error"));
    }

    @Test
    void asyncIdempotencyReHitReturnsSameTask() throws Exception {
        start(new ActionNodeServer(baseOpts(), (frame, ctx) -> {
            Thread.sleep(500);
            return new ActionNodeServer.ActionExecutionResult(null);
        }));
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("action_id", "orders.batch");
        req.put("async", true);
        req.put("idempotency_key", "ak1");
        var r1 = post("/orders/invoke", req, AGENT, null);
        var r2 = post("/orders/invoke", req, AGENT, null);
        assertEquals(202, r1.statusCode());
        assertEquals(202, r2.statusCode());
        assertEquals(body(r1).get("task_id"), body(r2).get("task_id"));
    }

    @Test
    void idempotencyIsCallerScopedAndReplayIsReauthorized() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger admissions = new AtomicInteger();
        class Provider implements ActionNodeServer.Provider {
            volatile boolean revoke;

            @Override public void authorize(ActionFrame frame, ActionNodeServer.ActionContext context)
                throws ActionNodeServer.ActionExecutionException {
                admissions.incrementAndGet();
                if (revoke) throw new ActionNodeServer.ActionExecutionException(
                    401, NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED,
                    NwpErrorCodes.NWP_AUTH_NID_REVOKED, "revoked");
            }

            @Override public ActionNodeServer.ActionExecutionResult execute(
                ActionFrame frame, ActionNodeServer.ActionContext context) {
                return new ActionNodeServer.ActionExecutionResult(
                    Map.of("call", calls.incrementAndGet(), "owner", context.agentNid()));
            }
        }
        var provider = new Provider();
        start(new ActionNodeServer(baseOpts(), provider));
        var request = new LinkedHashMap<String, Object>();
        request.put("action_id", "orders.create");
        request.put("params", Map.of("x", 1));
        request.put("idempotency_key", "shared");
        assertEquals(200, post("/orders/invoke", request, AGENT, null).statusCode());
        var bob = post("/orders/invoke", request, "urn:nps:agent:bob", null);
        assertEquals(200, bob.statusCode());
        var bobData = (Map<?, ?>) ((java.util.List<?>) body(bob).get("data")).get(0);
        assertEquals("urn:nps:agent:bob", bobData.get("owner"));
        assertEquals(2, calls.get());

        provider.revoke = true;
        var replay = post("/orders/invoke", request, AGENT, null);
        assertEquals(401, replay.statusCode());
        assertEquals(NwpErrorCodes.NWP_AUTH_NID_REVOKED, body(replay).get("error"));
        assertEquals(3, admissions.get());
    }

    @Test
    void taskAccessIsOwnerScopedAndCancelSignalsProvider() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        start(new ActionNodeServer(baseOpts(), (frame, context) -> {
            started.countDown();
            context.cancellation().onCancel(cancelled::countDown);
            while (!context.cancellation().isCancelled()) Thread.sleep(10);
            throw new InterruptedException("cancelled");
        }));
        var accepted = post("/orders/invoke",
            Map.of("action_id", "orders.batch", "async", true), AGENT, null);
        String taskId = (String) body(accepted).get("task_id");
        assertTrue(started.await(1, TimeUnit.SECONDS));

        for (String action : List.of(
            ActionNodeServer.SYSTEM_TASK_STATUS, ActionNodeServer.SYSTEM_TASK_CANCEL)) {
            var denied = post("/orders/invoke",
                Map.of("action_id", action, "params", Map.of("task_id", taskId)),
                "urn:nps:agent:bob", null);
            assertEquals(403, denied.statusCode());
            assertEquals(NwpErrorCodes.NWP_AUTH_NID_SCOPE_VIOLATION, body(denied).get("error"));
        }
        var cancel = post("/orders/invoke",
            Map.of("action_id", ActionNodeServer.SYSTEM_TASK_CANCEL,
                "params", Map.of("task_id", taskId)), AGENT, null);
        assertEquals(200, cancel.statusCode());
        assertTrue(cancelled.await(1, TimeUnit.SECONDS));
    }

    // ── timeout ──────────────────────────────────────────────────────────────────

    @Test
    void syncTimeoutReturns504() throws Exception {
        start(new ActionNodeServer(baseOpts(), (frame, ctx) -> {
            Thread.sleep(3_000);
            return new ActionNodeServer.ActionExecutionResult(null);
        }));
        var resp = post("/orders/invoke",
            Map.of("action_id", "orders.create", "timeout_ms", 100), AGENT, null);
        assertEquals(504, resp.statusCode());
        assertEquals("NWP-NODE-UNAVAILABLE", body(resp).get("error"));
    }

    // ── callback SSRF ─────────────────────────────────────────────────────────────

    @Test
    void callbackSsrfRejected() throws Exception {
        start(new ActionNodeServer(baseOpts(), (frame, ctx) -> new ActionNodeServer.ActionExecutionResult(null)));
        var resp = post("/orders/invoke",
            Map.of("action_id", "orders.create", "callback_url", "https://127.0.0.1/cb"), AGENT, null);
        assertEquals(400, resp.statusCode());
        assertEquals("NWP-ACTION-PARAMS-INVALID", body(resp).get("error"));
    }

    @Test
    void callbackHttpSchemeRejected() throws Exception {
        start(new ActionNodeServer(baseOpts(), (frame, ctx) -> new ActionNodeServer.ActionExecutionResult(null)));
        var resp = post("/orders/invoke",
            Map.of("action_id", "orders.create", "callback_url", "http://example.com/cb"), AGENT, null);
        assertEquals(400, resp.statusCode());
        assertEquals("NWP-ACTION-PARAMS-INVALID", body(resp).get("error"));
    }

    @Test
    void nwmManifestServed() throws Exception {
        start(new ActionNodeServer(baseOpts(), (frame, ctx) -> new ActionNodeServer.ActionExecutionResult(null)));
        var req = HttpRequest.newBuilder(URI.create(base + "/orders/.nwm")).GET().build();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertEquals("action", resp.headers().firstValue("X-NWP-Node-Type").orElse(null));
        assertEquals("action", body(resp).get("node_type"));
    }
}
