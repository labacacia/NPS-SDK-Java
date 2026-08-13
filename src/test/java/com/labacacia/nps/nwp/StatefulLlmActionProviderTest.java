// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.core.NpsStatusCodes;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

final class StatefulLlmActionProviderTest {
    private static final String PREFIX = "/llm";
    private static final String NODE_ID = "urn:nps:node:labacacia:llm";
    private static final String ALICE = "urn:nps:agent:labacacia:alice";
    private static final String BOB = "urn:nps:agent:labacacia:bob";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client = HttpClient.newHttpClient();
    private final List<HttpServer> servers = new ArrayList<>();
    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(server -> server.stop(0));
        executors.forEach(ExecutorService::shutdownNow);
    }

    @FunctionalInterface
    private interface Behavior {
        ActionNodeServer.ActionExecutionResult execute(
            ActionFrame frame, ActionNodeServer.ActionContext context) throws Exception;
    }

    private static final class TestProvider implements ActionNodeServer.Provider {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile Behavior behavior;

        @Override
        public ActionNodeServer.ActionExecutionResult execute(
            ActionFrame frame, ActionNodeServer.ActionContext context) throws Exception {
            calls.incrementAndGet();
            if (behavior != null) return behavior.execute(frame, context);
            return completion("First");
        }
    }

    private record TestApp(
        String baseUrl,
        TestProvider provider,
        StatefulLlmActionProvider coordinator,
        InMemoryLlmContextStore store) {}

    private TestApp start(
        TestProvider provider,
        Consumer<StatefulLlmActionProvider.Options> configure) throws Exception {
        if (provider == null) provider = new TestProvider();
        var ids = new ArrayDeque<>(List.of(
            "AQIDBAUGBwgJCgsMDQ4PEA", "ERITFBUWFxgZGhscHR4fIA",
            "ISIjJCUmJygpKissLS4vMA", "MTIzNDU2Nzg5Ojs8PT4_QA"));
        var storeOptions = new LlmContextStoreOptions();
        storeOptions.contextIdFactory = () -> ids.isEmpty()
            ? "QUJDREVGR0hJSktMTU5PUA" : ids.removeFirst();
        var store = new InMemoryLlmContextStore(storeOptions);
        var options = new StatefulLlmActionProvider.Options("workspace-a", "runtime-1");
        if (configure != null) configure.accept(options);
        var coordinator = new StatefulLlmActionProvider(provider, store, options);
        var node = new ActionNodeServer.Options();
        node.nodeId = NODE_ID;
        node.pathPrefix = PREFIX;
        node.requireAuth = true;
        coordinator.configureNode(node);

        var executor = Executors.newFixedThreadPool(6);
        var actionServer = new ActionNodeServer(
            node, coordinator, new ActionNodeServer.InMemoryTaskStore(),
            new ActionNodeServer.InMemoryIdempotencyCache(), executor, Instant::now);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", actionServer);
        server.setExecutor(executor);
        server.start();
        servers.add(server);
        executors.add(executor);
        return new TestApp(
            "http://127.0.0.1:" + server.getAddress().getPort(), provider, coordinator, store);
    }

    private HttpResponse<String> invoke(
        TestApp app, String action, Object params, String key, String agent, boolean async)
        throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("action_id", action);
        body.put("params", params);
        body.put("async", async);
        if (key != null && !key.isBlank()) {
            body.put("idempotency_key", key);
            body.put("request_id", "req-" + key);
        }
        var request = HttpRequest.newBuilder(URI.create(app.baseUrl() + PREFIX + "/invoke"))
            .header(NwpHttpHeaders.AGENT, agent == null ? ALICE : agent)
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(HttpResponse<String> response) throws Exception {
        return JSON.readValue(response.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(HttpResponse<String> response) throws Exception {
        var values = (List<Object>) body(response).get("data");
        return (Map<String, Object>) values.get(0);
    }

    private static Map<String, Object> createParams(String content) {
        return Map.of(
            "kind", LlmActionCodec.LLM_COMPLETE,
            "model", "willow-small",
            "messages", List.of(
                Map.of("role", "system", "content", "Be concise."),
                Map.of("role", "user", "content", content)),
            "context", Map.of("operation", "create"));
    }

    private static ActionNodeServer.ActionExecutionResult completion(String content) {
        return new ActionNodeServer.ActionExecutionResult(new LlmCompleteActionResponse(
            LlmStopReason.END_TURN, content, null, null,
            new LlmUsageDto(12, 2, false, 0, 12, 128L), null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void advertisesExactProfileAndRunsLifecycle() throws Exception {
        var app = start(null, null);
        var manifestRequest = HttpRequest.newBuilder(URI.create(app.baseUrl() + PREFIX + "/.nwm"))
            .header(NwpHttpHeaders.AGENT, ALICE).GET().build();
        var manifestResponse = client.send(manifestRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, manifestResponse.statusCode());
        var llm = (Map<String, Object>) ((Map<String, Object>) body(manifestResponse)
            .get("profiles")).get("llm");
        var context = (Map<String, Object>) llm.get("context");
        assertEquals("0.2", llm.get("profile_version"));
        assertEquals(false, llm.get("supports_stream"));
        assertEquals(List.of("llm.complete", "llm.context.status", "llm.context.release"),
            llm.get("actions"));
        assertEquals("process", context.get("persistence"));
        assertEquals(List.of("create", "append", "fork", "reset", "release"),
            context.get("operations"));
        assertEquals(32, ((Number) context.get("max_contexts_per_principal")).intValue());

        var createdResponse = invoke(
            app, LlmActionCodec.LLM_COMPLETE, createParams("One"), "create-1", ALICE, false);
        assertEquals(200, createdResponse.statusCode());
        assertEquals(StatefulLlmActionProvider.COMPLETE_RESPONSE_ANCHOR,
            createdResponse.headers().firstValue(NwpHttpHeaders.SCHEMA).orElse(null));
        var created = data(createdResponse);
        var receipt = (Map<String, Object>) created.get("context");
        String contextId = (String) receipt.get("context_id");
        assertEquals(1, ((Number) receipt.get("version")).intValue());
        assertEquals("active", receipt.get("state"));

        var append = Map.of(
            "kind", LlmActionCodec.LLM_COMPLETE,
            "model", "willow-small",
            "messages", List.of(Map.of("role", "user", "content", "Two")),
            "context", Map.of(
                "operation", "append", "context_id", contextId, "base_version", 1));
        var appended = data(invoke(
            app, LlmActionCodec.LLM_COMPLETE, append, "append-1", ALICE, false));
        assertEquals(2, ((Number) ((Map<String, Object>) appended.get("context"))
            .get("version")).intValue());
        assertEquals(5, app.store().snapshot(
            new LlmContextOwner(ALICE, "workspace-a"), contextId).transcript().size());

        var status = data(invoke(app, LlmActionCodec.LLM_CONTEXT_STATUS,
            Map.of("context_id", contextId), null, ALICE, false));
        assertEquals("active", status.get("state"));
        assertEquals(2, ((Number) status.get("version")).intValue());
        var released = data(invoke(app, LlmActionCodec.LLM_CONTEXT_RELEASE,
            Map.of("context_id", contextId, "base_version", 2), "release-1", ALICE, false));
        assertEquals("released", released.get("state"));
        assertEquals(3, ((Number) released.get("version")).intValue());
    }

    @Test
    void rejectsMalformedPayloadAndAbortsProviderAndModelErrors() throws Exception {
        var app = start(null, null);
        var malformed = invoke(app, LlmActionCodec.LLM_COMPLETE,
            Map.of("model", "", "messages", List.of(),
                "context", Map.of("operation", "create")), "bad", ALICE, false);
        assertEquals(422, malformed.statusCode());
        assertEquals(NwpErrorCodes.NWP_ACTION_PARAMS_INVALID, body(malformed).get("error"));
        var tools = new LinkedHashMap<>(createParams("tools"));
        tools.put("tools", List.of(Map.of("name", "search")));
        var unsupported = invoke(
            app, LlmActionCodec.LLM_COMPLETE, tools, "tools", ALICE, false);
        assertEquals(422, unsupported.statusCode());
        assertEquals(0, app.provider().calls.get());
        var reset = new LinkedHashMap<>(createParams("reset"));
        reset.put("context", Map.of("operation", "reset"));
        var resetWithoutVersion = invoke(app, LlmActionCodec.LLM_COMPLETE,
            reset, "reset-without-version", ALICE, false);
        assertEquals(422, resetWithoutVersion.statusCode());
        assertEquals(0, app.provider().calls.get());
        var missingVersion = invoke(app, LlmActionCodec.LLM_CONTEXT_RELEASE,
            Map.of("context_id", "AQIDBAUGBwgJCgsMDQ4PEA"), "release", ALICE, false);
        assertEquals(422, missingVersion.statusCode());
        assertEquals(NwpErrorCodes.NWP_ACTION_PARAMS_INVALID, body(missingVersion).get("error"));

        var provider = new TestProvider();
        provider.behavior = (frame, context) -> { throw new IllegalStateException("down"); };
        var providerApp = start(provider, null);
        assertEquals(500, invoke(providerApp, LlmActionCodec.LLM_COMPLETE,
            createParams("provider"), "provider", ALICE, false).statusCode());
        var providerStatus = data(invoke(providerApp, LlmActionCodec.LLM_CONTEXT_STATUS,
            Map.of("idempotency_key", "provider"), null, ALICE, false));
        assertEquals("failed", providerStatus.get("state"));
        assertNull(providerStatus.get("context_id"));

        var model = new TestProvider();
        model.behavior = (frame, context) -> new ActionNodeServer.ActionExecutionResult(
            new LlmCompleteActionResponse(
                LlmStopReason.ERROR, null, null, "refused", null, null));
        var modelApp = start(model, null);
        assertEquals(200, invoke(modelApp, LlmActionCodec.LLM_COMPLETE,
            createParams("model"), "model", ALICE, false).statusCode());
        var modelStatus = data(invoke(modelApp, LlmActionCodec.LLM_CONTEXT_STATUS,
            Map.of("idempotency_key", "model"), null, ALICE, false));
        assertEquals("failed", modelStatus.get("state"));
        assertNull(modelStatus.get("context_id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void enforcesCommitAuthorizationCallerIsolationAndReplayAuthorization() throws Exception {
        var revokeCommit = new AtomicBoolean();
        var denyAdmission = new AtomicBoolean();
        var app = start(null, options -> options.authorizer = (owner, action, stage, context) -> {
            if ((denyAdmission.get() && stage == StatefulLlmActionProvider.AuthorizationStage.ADMISSION) ||
                (revokeCommit.get() && stage == StatefulLlmActionProvider.AuthorizationStage.COMMIT)) {
                throw new ActionNodeServer.ActionExecutionException(
                    401, NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED,
                    NwpErrorCodes.NWP_AUTH_NID_REVOKED, "revoked before commit");
            }
        });
        var alice = data(invoke(
            app, LlmActionCodec.LLM_COMPLETE, createParams("One"), "shared", ALICE, false));
        var replay = data(invoke(
            app, LlmActionCodec.LLM_COMPLETE, createParams("One"), "shared", ALICE, false));
        var bob = data(invoke(
            app, LlmActionCodec.LLM_COMPLETE, createParams("One"), "shared", BOB, false));
        String aliceId = (String) ((Map<String, Object>) alice.get("context")).get("context_id");
        assertEquals(aliceId,
            ((Map<String, Object>) replay.get("context")).get("context_id"));
        assertNotEquals(aliceId,
            ((Map<String, Object>) bob.get("context")).get("context_id"));
        assertEquals(2, app.provider().calls.get());

        denyAdmission.set(true);
        var rejectedReplay = invoke(
            app, LlmActionCodec.LLM_COMPLETE, createParams("One"), "shared", ALICE, false);
        assertEquals(401, rejectedReplay.statusCode());
        assertEquals(NwpErrorCodes.NWP_AUTH_NID_REVOKED, body(rejectedReplay).get("error"));
        denyAdmission.set(false);

        revokeCommit.set(true);
        var revoked = invoke(
            app, LlmActionCodec.LLM_COMPLETE, createParams("revoked"), "revoked", ALICE, false);
        assertEquals(401, revoked.statusCode());
        assertEquals(NwpErrorCodes.NWP_AUTH_NID_REVOKED, body(revoked).get("error"));
        var status = data(invoke(app, LlmActionCodec.LLM_CONTEXT_STATUS,
            Map.of("idempotency_key", "revoked"), null, ALICE, false));
        assertEquals("failed", status.get("state"));
        assertEquals(NwpErrorCodes.NWP_AUTH_NID_REVOKED, status.get("error_code"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void asyncReceiptAppearsOnlyInTerminalTaskResult() throws Exception {
        var app = start(null, null);
        var accepted = invoke(
            app, LlmActionCodec.LLM_COMPLETE, createParams("async"), "async", ALICE, true);
        assertEquals(202, accepted.statusCode());
        var ack = body(accepted);
        assertFalse(ack.containsKey("context"));
        String taskId = (String) ack.get("task_id");

        Map<String, Object> task = null;
        for (int i = 0; i < 100; i++) {
            task = data(invoke(app, ActionNodeServer.SYSTEM_TASK_STATUS,
                Map.of("task_id", taskId), null, ALICE, false));
            if ("completed".equals(task.get("status"))) break;
            Thread.sleep(10);
        }
        assertNotNull(task);
        assertEquals("completed", task.get("status"));
        var result = (Map<String, Object>) task.get("result");
        var receipt = (Map<String, Object>) result.get("context");
        assertEquals(1, ((Number) receipt.get("version")).intValue());
        assertEquals("active", receipt.get("state"));
    }

    @Test
    void cancellationAbortsReservationEvenWhenProviderIgnoresInterruption() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var provider = new TestProvider();
        provider.behavior = (frame, context) -> {
            entered.countDown();
            while (release.getCount() > 0) {
                try { release.await(20, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) {
                    // Deliberately suppress interruption and ignore the cancellation signal.
                }
            }
            return completion("too late");
        };
        var app = start(provider, null);
        try {
            var accepted = invoke(
                app, LlmActionCodec.LLM_COMPLETE, createParams("cancel"), "cancel", ALICE, true);
            String taskId = (String) body(accepted).get("task_id");
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(403, invoke(app, ActionNodeServer.SYSTEM_TASK_STATUS,
                Map.of("task_id", taskId), null, BOB, false).statusCode());
            assertEquals(200, invoke(app, ActionNodeServer.SYSTEM_TASK_CANCEL,
                Map.of("task_id", taskId), null, ALICE, false).statusCode());

            Map<String, Object> status = null;
            for (int i = 0; i < 100; i++) {
                status = data(invoke(app, LlmActionCodec.LLM_CONTEXT_STATUS,
                    Map.of("idempotency_key", "cancel"), null, ALICE, false));
                if ("failed".equals(status.get("state"))) break;
                Thread.sleep(10);
            }
            assertNotNull(status);
            assertEquals("failed", status.get("state"));
            assertNull(status.get("context_id"));
        } finally {
            release.countDown();
        }
    }
}
