// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 tests for {@link AnchorNodeClient} using JDK built-in {@link HttpServer}.
 */
class AnchorNodeClientTest {

    // ── Infrastructure ────────────────────────────────────────────────────────

    private HttpServer server;
    private int        port;

    /** Captured request bodies (raw UTF-8 JSON) for inspection. */
    private final List<String> capturedBodies = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        port   = server.getAddress().getPort();
        capturedBodies.clear();
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Register a fixed JSON response on the given path. */
    private void handle(String path, int status, String responseJson) {
        server.createContext(path, exchange -> {
            // capture request body
            capturedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });
    }

    /** Register a streaming (NDJSON) response on the given path. */
    private void handleNdjson(String path, int status, String... lines) {
        server.createContext(path, exchange -> {
            capturedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (status < 200 || status >= 300) {
                // For error responses, join without trailing newlines
                byte[] errBody = String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, errBody.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(errBody); }
                return;
            }
            // Pre-compute the full NDJSON body so we can set an accurate Content-Length.
            // com.sun.net.httpserver's chunked (-1) mode does not interoperate reliably
            // with java.net.http.HttpClient's InputStream handler, so we send a fixed body.
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                sb.append(line).append("\n");
            }
            byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
    }

    private String baseUrl() { return "http://localhost:" + port; }

    // ── topology.snapshot — success tests ────────────────────────────────────

    /**
     * Test 1: getSnapshot() success — verify all returned snapshot fields.
     */
    @Test
    void getSnapshot_success_returnsSnapshotFields() throws Exception {
        String json = """
            {
              "data": [{
                "version": 42,
                "anchor_nid": "anchor-001",
                "cluster_size": 3,
                "members": [
                  {"nid": "m1", "node_roles": ["worker"], "activation_mode": "resident"},
                  {"nid": "m2", "node_roles": ["gateway"], "activation_mode": "ephemeral"}
                ],
                "truncated": false
              }]
            }
            """;
        handle("/query", 200, json);

        AnchorNodeClient client = new AnchorNodeClient(baseUrl());
        TopologySnapshot snap = client.getSnapshot();

        assertEquals(42L, snap.version);
        assertEquals("anchor-001", snap.anchorNid);
        assertEquals(3, snap.clusterSize);
        assertNotNull(snap.members);
        assertEquals(2, snap.members.size());
        assertEquals("m1", snap.members.get(0).nid);
        assertEquals("m2", snap.members.get(1).nid);
        assertEquals(Boolean.FALSE, snap.truncated);
    }

    /**
     * Test 2: getSnapshot with scope=member and targetNid — verify wire body
     * contains correct type, scope, and target_nid.
     */
    @Test
    void getSnapshot_memberScope_wireSendsTargetNid() throws Exception {
        String json = """
            {"data": [{"version": 5, "anchor_nid": "a1", "cluster_size": 1, "members": []}]}
            """;
        handle("/query", 200, json);

        AnchorNodeClient client = new AnchorNodeClient(baseUrl());
        client.getSnapshot("member", List.of("members"), 1, "target-nid-xyz");

        assertEquals(1, capturedBodies.size());
        String body = capturedBodies.get(0);
        assertTrue(body.contains("\"type\":\"topology.snapshot\""),
                   "Wire body must contain type=topology.snapshot");
        assertTrue(body.contains("\"scope\":\"member\""),
                   "Wire body must contain scope=member");
        assertTrue(body.contains("\"target_nid\":\"target-nid-xyz\""),
                   "Wire body must contain target_nid");
    }

    /**
     * Test 3: getSnapshot non-2xx NPS error JSON → AnchorTopologyException with
     * correct error code and status.
     */
    @Test
    void getSnapshot_npsErrorJson_throwsAnchorTopologyExceptionWithFields() throws Exception {
        String errJson = """
            {"error": "NWP-TOPOLOGY-001", "status": "TOPOLOGY_UNAVAILABLE", "message": "cluster offline"}
            """;
        handle("/query", 503, errJson);

        AnchorNodeClient client = new AnchorNodeClient(baseUrl());
        AnchorTopologyException ex = assertThrows(AnchorTopologyException.class,
                () -> client.getSnapshot());

        assertEquals("NWP-TOPOLOGY-001", ex.nwpErrorCode);
        assertEquals("TOPOLOGY_UNAVAILABLE", ex.npsStatus);
        assertTrue(ex.getMessage().contains("cluster offline"));
    }

    /**
     * Test 4: getSnapshot non-2xx with plain (non-JSON) body → exception.
     */
    @Test
    void getSnapshot_plainErrorBody_throwsAnchorTopologyException() throws Exception {
        handle("/query", 500, "Internal Server Error");

        AnchorNodeClient client = new AnchorNodeClient(baseUrl());
        AnchorTopologyException ex = assertThrows(AnchorTopologyException.class,
                () -> client.getSnapshot());

        // Falls through to UNKNOWN / HTTP-500 path
        assertEquals("UNKNOWN", ex.nwpErrorCode);
        assertTrue(ex.npsStatus.startsWith("HTTP-5"));
    }

    /**
     * Test 5: getSnapshot with empty data array → IOException.
     */
    @Test
    void getSnapshot_emptyDataArray_throwsIOException() throws Exception {
        handle("/query", 200, "{\"data\": []}");

        AnchorNodeClient client = new AnchorNodeClient(baseUrl());
        assertThrows(IOException.class, () -> client.getSnapshot());
    }

    // ── topology.stream — success tests ───────────────────────────────────────

    /**
     * Test 6: subscribe() — ack + all event types yielded correctly.
     * NDJSON: ack, member_joined, member_left, member_updated, anchor_state, resync_required.
     */
    @Test
    void subscribe_allEventTypes_allYielded() throws Exception {
        String ack = "{\"type\":\"topology.stream\",\"action\":\"ack\",\"stream_id\":\"s1\"}";
        String joined = """
            {"event_type":"member_joined","seq":1,"payload":{"nid":"n1","node_roles":["worker"],"activation_mode":"resident"}}
            """.strip();
        String left = "{\"event_type\":\"member_left\",\"seq\":2,\"payload\":{\"nid\":\"n2\"}}";
        String updated = """
            {"event_type":"member_updated","seq":3,"payload":{"nid":"n3","changes":{"node_roles":["leader"]}}}
            """.strip();
        String anchorState = """
            {"event_type":"anchor_state","seq":4,"payload":{"field":"version_rebased","details":{"old":1,"new":10}}}
            """.strip();
        String resync = "{\"event_type\":\"resync_required\",\"payload\":{\"reason\":\"gap_too_large\"}}";

        handleNdjson("/subscribe", 200, ack, joined, left, updated, anchorState, resync);

        AnchorNodeClient client = new AnchorNodeClient(baseUrl());
        Iterable<TopologyEvent> stream = client.subscribe();

        List<TopologyEvent> events = new ArrayList<>();
        for (TopologyEvent e : stream) events.add(e);

        assertEquals(5, events.size());
        assertInstanceOf(MemberJoinedEvent.class,  events.get(0));
        assertInstanceOf(MemberLeftEvent.class,    events.get(1));
        assertInstanceOf(MemberUpdatedEvent.class, events.get(2));
        assertInstanceOf(AnchorStateEvent.class,   events.get(3));
        assertInstanceOf(ResyncRequiredEvent.class, events.get(4));
    }

    /**
     * Test 7: subscribe() — resync_required terminates iteration (no more events after it).
     */
    @Test
    void subscribe_resyncRequired_terminatesIteration() throws Exception {
        String ack    = "{\"type\":\"topology.stream\",\"action\":\"ack\"}";
        String resync = "{\"event_type\":\"resync_required\",\"payload\":{\"reason\":\"too_old\"}}";
        String extra  = "{\"event_type\":\"member_left\",\"seq\":99,\"payload\":{\"nid\":\"orphan\"}}";

        handleNdjson("/subscribe", 200, ack, resync, extra);

        AnchorNodeClient client = new AnchorNodeClient(baseUrl());
        Iterator<TopologyEvent> it = client.subscribe().iterator();

        assertTrue(it.hasNext());
        TopologyEvent e = it.next();
        assertInstanceOf(ResyncRequiredEvent.class, e);

        // After ResyncRequiredEvent the iterator must be exhausted
        assertFalse(it.hasNext());
    }

    /**
     * Test 8: subscribe() — mid-stream error envelope → TopologyStreamException.
     */
    @Test
    void subscribe_midStreamError_throwsTopologyStreamException() throws Exception {
        String ack   = "{\"type\":\"topology.stream\",\"action\":\"ack\"}";
        String error = "{\"error\":\"NWP-TOPOLOGY-002\",\"status\":\"STREAM_BROKEN\",\"message\":\"link lost\"}";

        handleNdjson("/subscribe", 200, ack, error);

        AnchorNodeClient client = new AnchorNodeClient(baseUrl());
        Iterable<TopologyEvent> stream = client.subscribe();

        AnchorNodeClient.TopologyStreamException ex =
                assertThrows(AnchorNodeClient.TopologyStreamException.class, () -> {
                    for (TopologyEvent ignored : stream) { /* iterate */ }
                });

        assertEquals("NWP-TOPOLOGY-002", ex.cause.nwpErrorCode);
        assertEquals("STREAM_BROKEN",    ex.cause.npsStatus);
    }

    /**
     * Test 9: subscribe() non-2xx → AnchorTopologyException (before iteration).
     */
    @Test
    void subscribe_nonTwoXx_throwsAnchorTopologyException() throws Exception {
        handleNdjson("/subscribe", 404, "{\"error\":\"NWP-NOT-FOUND\",\"status\":\"NOT_FOUND\",\"message\":\"no anchor\"}");

        AnchorNodeClient client = new AnchorNodeClient(baseUrl());
        AnchorTopologyException ex = assertThrows(AnchorTopologyException.class,
                () -> client.subscribe());

        assertEquals("NWP-NOT-FOUND", ex.nwpErrorCode);
        assertEquals("NOT_FOUND",     ex.npsStatus);
    }

    /**
     * Test 10: subscribe() with filter → verify filter appears in wire body.
     */
    @Test
    void subscribe_withFilter_filterInWireBody() throws Exception {
        String ack = "{\"type\":\"topology.stream\",\"action\":\"ack\"}";
        handleNdjson("/subscribe", 200, ack);

        TopologyFilter filter = new TopologyFilter(
                List.of("tagA"),
                null,
                List.of("worker"));

        AnchorNodeClient client = new AnchorNodeClient(baseUrl());
        // drain the iterator so the request is actually sent
        for (TopologyEvent ignored : client.subscribe(null, filter, null)) { /* empty */ }

        assertEquals(1, capturedBodies.size());
        String body = capturedBodies.get(0);
        assertTrue(body.contains("\"filter\""),           "Wire body must contain filter key");
        assertTrue(body.contains("\"tags_any\""),         "Wire body must contain tags_any");
        assertTrue(body.contains("\"node_roles\""),       "Wire body must contain node_roles");
    }

    /**
     * Test 11: subscribe() with sinceVersion → verify since_version in wire body.
     */
    @Test
    void subscribe_withSinceVersion_sinceVersionInWireBody() throws Exception {
        String ack = "{\"type\":\"topology.stream\",\"action\":\"ack\"}";
        handleNdjson("/subscribe", 200, ack);

        AnchorNodeClient client = new AnchorNodeClient(baseUrl());
        for (TopologyEvent ignored : client.subscribe(null, null, 77L)) { /* empty */ }

        assertEquals(1, capturedBodies.size());
        assertTrue(capturedBodies.get(0).contains("\"since_version\":77"),
                   "Wire body must contain since_version=77");
    }

    // ── URL & path prefix normalisation ──────────────────────────────────────

    /**
     * Test 12: base URL with trailing slash — client strips it correctly.
     */
    @Test
    void urlNormalisation_trailingSlashStripped() throws Exception {
        String json = """
            {"data": [{"version": 1, "anchor_nid": "a", "cluster_size": 0, "members": []}]}
            """;
        handle("/query", 200, json);

        // Pass URL with trailing slash
        AnchorNodeClient client = new AnchorNodeClient(baseUrl() + "/");
        TopologySnapshot snap = client.getSnapshot();
        assertEquals(1L, snap.version); // just ensure the request reached /query correctly
    }

    /**
     * Test 13: pathPrefix is prepended to both /query and /subscribe paths.
     */
    @Test
    void pathPrefix_prependedToBothEndpoints() throws Exception {
        String snapJson = """
            {"data": [{"version": 2, "anchor_nid": "b", "cluster_size": 0, "members": []}]}
            """;
        handle("/anchor/query", 200, snapJson);

        String ack = "{\"type\":\"topology.stream\",\"action\":\"ack\"}";
        handleNdjson("/anchor/subscribe", 200, ack);

        AnchorNodeClient client = new AnchorNodeClient(baseUrl(), "/anchor", null);

        // Both calls should route through /anchor/...
        TopologySnapshot snap = client.getSnapshot();
        assertEquals(2L, snap.version);

        // subscribe — just open and drain
        for (TopologyEvent ignored : client.subscribe()) { /* empty */ }
    }

    // ── Individual event field tests ──────────────────────────────────────────

    /**
     * Test 14: MemberJoinedEvent fields: member nid, node_roles, activation_mode.
     */
    @Test
    void memberJoined_fieldsDeserialised() throws Exception {
        String ack = "{\"type\":\"topology.stream\",\"action\":\"ack\"}";
        String joined = """
            {"event_type":"member_joined","seq":10,"payload":{"nid":"joined-nid","node_roles":["sensor","reporter"],"activation_mode":"ephemeral"}}
            """.strip();
        String resync = "{\"event_type\":\"resync_required\",\"payload\":{\"reason\":\"end\"}}";

        handleNdjson("/subscribe", 200, ack, joined, resync);

        AnchorNodeClient client = new AnchorNodeClient(baseUrl());
        MemberJoinedEvent ev = null;
        for (TopologyEvent e : client.subscribe()) {
            if (e instanceof MemberJoinedEvent mj) { ev = mj; break; }
        }

        assertNotNull(ev);
        assertEquals("joined-nid",          ev.member.nid);
        assertEquals(List.of("sensor","reporter"), ev.member.nodeRoles);
        assertEquals("ephemeral",            ev.member.activationMode);
        assertEquals(10L,                    ev.version);
    }

    /**
     * Test 15: MemberLeftEvent field: nid.
     */
    @Test
    void memberLeft_nidDeserialised() throws Exception {
        String ack   = "{\"type\":\"topology.stream\",\"action\":\"ack\"}";
        String left  = "{\"event_type\":\"member_left\",\"seq\":7,\"payload\":{\"nid\":\"left-nid\"}}";
        String resync = "{\"event_type\":\"resync_required\",\"payload\":{\"reason\":\"end\"}}";

        handleNdjson("/subscribe", 200, ack, left, resync);

        AnchorNodeClient client = new AnchorNodeClient(baseUrl());
        MemberLeftEvent ev = null;
        for (TopologyEvent e : client.subscribe()) {
            if (e instanceof MemberLeftEvent ml) { ev = ml; break; }
        }

        assertNotNull(ev);
        assertEquals("left-nid", ev.nid);
        assertEquals(7L,          ev.version);
    }

    /**
     * Test 16: MemberUpdatedEvent fields: nid and changes.
     */
    @Test
    void memberUpdated_nidAndChangesDeserialised() throws Exception {
        String ack     = "{\"type\":\"topology.stream\",\"action\":\"ack\"}";
        String updated = """
            {"event_type":"member_updated","seq":20,"payload":{"nid":"upd-nid","changes":{"node_roles":["master"],"activation_mode":"resident"}}}
            """.strip();
        String resync  = "{\"event_type\":\"resync_required\",\"payload\":{\"reason\":\"end\"}}";

        handleNdjson("/subscribe", 200, ack, updated, resync);

        AnchorNodeClient client = new AnchorNodeClient(baseUrl());
        MemberUpdatedEvent ev = null;
        for (TopologyEvent e : client.subscribe()) {
            if (e instanceof MemberUpdatedEvent mu) { ev = mu; break; }
        }

        assertNotNull(ev);
        assertEquals("upd-nid",                ev.nid);
        assertEquals(List.of("master"),         ev.changes.nodeRoles);
        assertEquals("resident",                ev.changes.activationMode);
        assertEquals(20L,                       ev.version);
    }

    /**
     * Test 17: AnchorStateEvent fields: field and details.
     */
    @Test
    void anchorState_fieldAndDetailsDeserialised() throws Exception {
        String ack   = "{\"type\":\"topology.stream\",\"action\":\"ack\"}";
        String state = """
            {"event_type":"anchor_state","seq":30,"payload":{"field":"version_rebased","details":{"old_version":5,"new_version":10}}}
            """.strip();
        String resync = "{\"event_type\":\"resync_required\",\"payload\":{\"reason\":\"end\"}}";

        handleNdjson("/subscribe", 200, ack, state, resync);

        AnchorNodeClient client = new AnchorNodeClient(baseUrl());
        AnchorStateEvent ev = null;
        for (TopologyEvent e : client.subscribe()) {
            if (e instanceof AnchorStateEvent as) { ev = as; break; }
        }

        assertNotNull(ev);
        assertEquals("version_rebased", ev.field);
        assertNotNull(ev.details);
        assertEquals(5,  ev.details.get("old_version").asInt());
        assertEquals(10, ev.details.get("new_version").asInt());
        assertEquals(30L, ev.version);
    }

    /**
     * Test 18: ResyncRequiredEvent field: reason; version is always 0.
     */
    @Test
    void resyncRequired_reasonAndVersionZero() throws Exception {
        String ack    = "{\"type\":\"topology.stream\",\"action\":\"ack\"}";
        String resync = "{\"event_type\":\"resync_required\",\"payload\":{\"reason\":\"gap_too_large\"}}";

        handleNdjson("/subscribe", 200, ack, resync);

        AnchorNodeClient client = new AnchorNodeClient(baseUrl());
        ResyncRequiredEvent ev = null;
        for (TopologyEvent e : client.subscribe()) {
            if (e instanceof ResyncRequiredEvent rr) { ev = rr; }
        }

        assertNotNull(ev);
        assertEquals("gap_too_large", ev.reason);
        assertEquals(0L,               ev.version);
    }

    /**
     * Test 19: Unknown event type is silently skipped; iteration continues normally.
     */
    @Test
    void subscribe_unknownEventType_silentlySkipped() throws Exception {
        String ack     = "{\"type\":\"topology.stream\",\"action\":\"ack\"}";
        String unknown = "{\"event_type\":\"future_event\",\"seq\":1,\"payload\":{}}";
        String left    = "{\"event_type\":\"member_left\",\"seq\":2,\"payload\":{\"nid\":\"n9\"}}";
        String resync  = "{\"event_type\":\"resync_required\",\"payload\":{\"reason\":\"end\"}}";

        handleNdjson("/subscribe", 200, ack, unknown, left, resync);

        AnchorNodeClient client = new AnchorNodeClient(baseUrl());
        List<TopologyEvent> events = new ArrayList<>();
        for (TopologyEvent e : client.subscribe()) events.add(e);

        // unknown_event is skipped; we get member_left + resync_required
        assertEquals(2, events.size());
        assertInstanceOf(MemberLeftEvent.class,    events.get(0));
        assertInstanceOf(ResyncRequiredEvent.class, events.get(1));
    }

    /**
     * Test 20: AnchorTopologyException attributes — nwpErrorCode and npsStatus accessible.
     */
    @Test
    void anchorTopologyException_attributesAccessible() {
        AnchorTopologyException ex = new AnchorTopologyException(
                "NWP-TOPOLOGY-ERROR",
                "SOME_STATUS",
                "detail message");

        assertEquals("NWP-TOPOLOGY-ERROR", ex.nwpErrorCode);
        assertEquals("SOME_STATUS",         ex.npsStatus);
        assertEquals("detail message",      ex.getMessage());
    }

    // ── Bonus tests (beyond the 20 required) ─────────────────────────────────

    /**
     * Bonus 1: getSnapshot() wire body always contains type=topology.snapshot
     * and scope=cluster by default.
     */
    @Test
    void getSnapshot_defaultScope_wireBodyContainsCluster() throws Exception {
        String json = """
            {"data": [{"version": 1, "anchor_nid": "x", "cluster_size": 0, "members": []}]}
            """;
        handle("/query", 200, json);

        AnchorNodeClient client = new AnchorNodeClient(baseUrl());
        client.getSnapshot();

        assertTrue(capturedBodies.get(0).contains("\"scope\":\"cluster\""),
                   "Default scope must be cluster");
        assertTrue(capturedBodies.get(0).contains("\"type\":\"topology.snapshot\""));
    }

    /**
     * Bonus 2: subscribe() wire body contains type=topology.stream and action=subscribe.
     */
    @Test
    void subscribe_wireBodyContainsTypeAndAction() throws Exception {
        String ack = "{\"type\":\"topology.stream\",\"action\":\"ack\"}";
        handleNdjson("/subscribe", 200, ack);

        AnchorNodeClient client = new AnchorNodeClient(baseUrl());
        for (TopologyEvent ignored : client.subscribe()) { /* drain */ }

        String body = capturedBodies.get(0);
        assertTrue(body.contains("\"type\":\"topology.stream\""));
        assertTrue(body.contains("\"action\":\"subscribe\""));
    }

    /**
     * Bonus 3: pathPrefix with trailing slash is stripped correctly.
     */
    @Test
    void pathPrefix_trailingSlashStripped() throws Exception {
        String json = """
            {"data": [{"version": 3, "anchor_nid": "c", "cluster_size": 0, "members": []}]}
            """;
        handle("/pfx/query", 200, json);

        // trailing slash in prefix
        AnchorNodeClient client = new AnchorNodeClient(baseUrl(), "/pfx/", null);
        TopologySnapshot snap = client.getSnapshot();
        assertEquals(3L, snap.version);
    }

    /**
     * Bonus 4: getSnapshot() missing 'data' key (null node) → IOException.
     */
    @Test
    void getSnapshot_missingDataKey_throwsIOException() throws Exception {
        handle("/query", 200, "{\"result\": \"ok\"}");

        AnchorNodeClient client = new AnchorNodeClient(baseUrl());
        assertThrows(IOException.class, () -> client.getSnapshot());
    }

    /**
     * Bonus 5: TopologyStreamException wraps the underlying AnchorTopologyException.
     */
    @Test
    void topologyStreamException_wrapsAnchorTopologyException() {
        AnchorTopologyException cause = new AnchorTopologyException("CODE", "STATUS", "msg");
        AnchorNodeClient.TopologyStreamException wrapper =
                new AnchorNodeClient.TopologyStreamException(cause);

        assertSame(cause, wrapper.cause);
        assertSame(cause, wrapper.getCause());
    }
}
