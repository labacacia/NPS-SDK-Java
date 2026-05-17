// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Typed client for an Anchor Node's reserved query types
 * (NPS-2 §12 — {@code topology.snapshot} and {@code topology.stream}).
 *
 * <p>Uses plain HTTP/JSON over {@link HttpClient} (Java 11+). The client never
 * assumes auth; callers should configure the underlying {@link HttpClient}
 * (e.g. add an {@code Authorization} or {@code X-NWP-Agent} header interceptor)
 * before constructing.</p>
 */
public final class AnchorNodeClient {

    // Wire constants (NPS-2 §12)
    private static final String TYPE_SNAPSHOT = "topology.snapshot";
    private static final String TYPE_STREAM   = "topology.stream";

    private static final String SCOPE_CLUSTER = "cluster";
    private static final String SCOPE_MEMBER  = "member";

    private static final String EVENT_MEMBER_JOINED   = "member_joined";
    private static final String EVENT_MEMBER_LEFT     = "member_left";
    private static final String EVENT_MEMBER_UPDATED  = "member_updated";
    private static final String EVENT_ANCHOR_STATE    = "anchor_state";
    private static final String EVENT_RESYNC_REQUIRED = "resync_required";

    private final String     baseUrl;
    private final String     pathPrefix;
    private final HttpClient http;
    private final ObjectMapper mapper;

    // ── Constructors ──────────────────────────────────────────────────────────

    /**
     * Build a client targeting the given base URL with no path prefix and a
     * default {@link HttpClient}.
     *
     * @param baseUrl Anchor host, e.g. {@code "http://localhost:8080"}
     */
    public AnchorNodeClient(String baseUrl) {
        this(baseUrl, "", null);
    }

    /**
     * Full constructor.
     *
     * @param baseUrl     Anchor host URL (trailing slash stripped).
     * @param pathPrefix  Anchor middleware's path prefix, e.g. {@code "/anchor"}.
     *                    Pass {@code ""} or {@code null} for root mounting.
     * @param httpClient  Shared {@link HttpClient} to reuse, or {@code null} to
     *                    create a default one.
     */
    public AnchorNodeClient(String baseUrl, String pathPrefix, HttpClient httpClient) {
        this.baseUrl    = baseUrl.replaceAll("/$", "");
        this.pathPrefix = pathPrefix == null ? "" : pathPrefix.replaceAll("/$", "");
        this.http       = httpClient != null ? httpClient : HttpClient.newHttpClient();

        this.mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    // ── topology.snapshot ─────────────────────────────────────────────────────

    /**
     * Fetch the current cluster topology (NPS-2 §12.1) using default parameters:
     * scope=cluster, include=[members], depth=1.
     */
    public TopologySnapshot getSnapshot()
            throws IOException, InterruptedException, AnchorTopologyException {
        return getSnapshot(null, null, 1, null);
    }

    /**
     * Fetch the current cluster topology (NPS-2 §12.1).
     *
     * @param scope     {@code "cluster"} or {@code "member"}; {@code null} defaults to {@code "cluster"}.
     * @param include   List of include fields (e.g. {@code ["members","capabilities"]}); {@code null} defaults to {@code ["members"]}.
     * @param depth     Recursion depth (1 = direct members only).
     * @param targetNid Required when scope is {@code "member"}; otherwise ignored.
     */
    public TopologySnapshot getSnapshot(String scope, List<String> include, int depth, String targetNid)
            throws IOException, InterruptedException, AnchorTopologyException {

        Map<String, Object> topology = new LinkedHashMap<>();
        topology.put("scope",   scope != null ? scope : SCOPE_CLUSTER);

        List<String> inc = include != null ? include : List.of("members");
        topology.put("include", inc);

        if (depth != 1) {
            topology.put("depth", depth);
        }
        if (targetNid != null) {
            topology.put("target_nid", targetNid);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type",     TYPE_SNAPSHOT);
        body.put("topology", topology);

        String json     = mapper.writeValueAsString(body);
        String url      = baseUrl + pathPrefix + "/query";

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Accept",       "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw buildHttpError(resp.statusCode(), resp.body(), "topology.snapshot");
        }

        // Response shape: {"data": [<snapshot>]}
        JsonNode root = mapper.readTree(resp.body());
        JsonNode dataNode = root.get("data");
        if (dataNode == null || !dataNode.isArray() || dataNode.size() == 0) {
            throw new IOException("topology.snapshot response missing 'data' array: " + resp.body());
        }
        JsonNode snapshotNode = dataNode.get(0);
        return mapper.treeToValue(snapshotNode, TopologySnapshot.class);
    }

    // ── topology.stream ───────────────────────────────────────────────────────

    /**
     * Subscribe to live topology changes (NPS-2 §12.2) using default parameters:
     * scope=cluster, no filter, no since_version.
     *
     * <p>Returns an {@link Iterable} suitable for use in an enhanced for-loop.
     * The underlying HTTP stream is opened lazily on the first call to
     * {@link Iterator#hasNext()}.</p>
     */
    public Iterable<TopologyEvent> subscribe()
            throws IOException, InterruptedException, AnchorTopologyException {
        return subscribe(null, null, null);
    }

    /**
     * Subscribe to live topology changes (NPS-2 §12.2).
     *
     * <p>The returned {@link Iterable} opens the HTTP stream when its iterator
     * is first used. The first line (subscription ack) is consumed silently;
     * subsequent NDJSON lines are decoded into {@link TopologyEvent} subclasses.
     * Iteration stops naturally after a {@link ResyncRequiredEvent} or when the
     * server closes the connection.</p>
     *
     * <p>If the server sends a protocol-error envelope mid-stream, the iterator's
     * {@link Iterator#next()} throws an unchecked
     * {@link TopologyStreamException}.</p>
     *
     * @param scope        {@code "cluster"} or {@code "member"}; {@code null} → {@code "cluster"}.
     * @param filter       Optional subscriber-side filter; {@code null} means no filter.
     * @param sinceVersion Resume token; {@code null} means "from now".
     */
    public Iterable<TopologyEvent> subscribe(String scope, TopologyFilter filter, Long sinceVersion)
            throws IOException, InterruptedException, AnchorTopologyException {

        Map<String, Object> topology = new LinkedHashMap<>();
        topology.put("scope", scope != null ? scope : SCOPE_CLUSTER);

        if (filter != null) {
            topology.put("filter", filter);
        }
        if (sinceVersion != null) {
            topology.put("since_version", sinceVersion);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type",      TYPE_STREAM);
        body.put("action",    "subscribe");
        body.put("stream_id", UUID.randomUUID().toString().replace("-", ""));
        body.put("topology",  topology);

        String json = mapper.writeValueAsString(body);
        String url  = baseUrl + pathPrefix + "/subscribe";

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Accept",       "application/x-ndjson")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();

        // Open with streaming — do not buffer whole body
        HttpResponse<InputStream> resp =
            http.send(req, HttpResponse.BodyHandlers.ofInputStream());

        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            byte[] errorBytes = resp.body().readAllBytes();
            String errorBody  = new String(errorBytes, StandardCharsets.UTF_8);
            throw buildHttpError(resp.statusCode(), errorBody, "topology.stream");
        }

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(resp.body(), StandardCharsets.UTF_8));

        // Consume the ack line
        reader.readLine();

        ObjectMapper om = this.mapper; // captured for lambda / inner class

        return () -> new Iterator<>() {

            private TopologyEvent pending = null;
            private boolean done          = false;

            @Override
            public boolean hasNext() {
                if (pending != null) return true; // buffered event takes priority (covers ResyncRequired with done=true)
                if (done)    return false;
                advance();
                return pending != null;
            }

            @Override
            public TopologyEvent next() {
                if (!hasNext()) throw new NoSuchElementException();
                TopologyEvent e = pending;
                pending = null;
                return e;
            }

            private void advance() {
                while (true) {
                    String line;
                    try {
                        line = reader.readLine();
                    } catch (IOException ex) {
                        done = true;
                        return;
                    }
                    if (line == null) {
                        done = true;
                        return;
                    }
                    if (line.isBlank()) continue;

                    // Check for protocol error envelope first
                    AnchorTopologyException protoErr = tryParseError(line, om);
                    if (protoErr != null) {
                        done = true;
                        throw new TopologyStreamException(protoErr);
                    }

                    TopologyEvent ev = parseEvent(line, om);
                    if (ev == null) continue;

                    pending = ev;
                    if (ev instanceof ResyncRequiredEvent) {
                        done = true; // stop after yielding this event
                    }
                    return;
                }
            }
        };
    }

    // ── Event parsing ─────────────────────────────────────────────────────────

    private static TopologyEvent parseEvent(String line, ObjectMapper om) {
        try {
            JsonNode root = om.readTree(line);
            if (!root.isObject()) return null;

            JsonNode etNode = root.get("event_type");
            if (etNode == null || !etNode.isTextual()) return null;

            String et  = etNode.asText();
            long   seq = root.has("seq") ? root.get("seq").asLong(0L) : 0L;

            JsonNode payload = root.get("payload");
            if (payload == null) payload = om.createObjectNode();

            return switch (et) {
                case EVENT_MEMBER_JOINED -> {
                    MemberInfo member = om.treeToValue(payload, MemberInfo.class);
                    yield new MemberJoinedEvent(seq, member);
                }
                case EVENT_MEMBER_LEFT -> {
                    String nid = payload.has("nid") ? payload.get("nid").asText() : "";
                    yield new MemberLeftEvent(seq, nid);
                }
                case EVENT_MEMBER_UPDATED -> {
                    String nid = payload.has("nid") ? payload.get("nid").asText() : "";
                    JsonNode changesNode = payload.has("changes") ? payload.get("changes") : om.createObjectNode();
                    MemberChanges changes = om.treeToValue(changesNode, MemberChanges.class);
                    yield new MemberUpdatedEvent(seq, nid, changes);
                }
                case EVENT_ANCHOR_STATE -> {
                    String field   = payload.has("field") ? payload.get("field").asText() : "";
                    JsonNode details = payload.has("details") ? payload.get("details") : null;
                    yield new AnchorStateEvent(seq, field, details);
                }
                case EVENT_RESYNC_REQUIRED -> {
                    String reason = payload.has("reason") ? payload.get("reason").asText() : "unknown";
                    yield new ResyncRequiredEvent(reason);
                }
                default -> null;
            };
        } catch (Exception ex) {
            return null;
        }
    }

    /** Returns non-null if the line is a protocol error envelope (not an event line). */
    private static AnchorTopologyException tryParseError(String line, ObjectMapper om) {
        try {
            JsonNode root = om.readTree(line);
            if (!root.isObject())                      return null;
            if (root.has("event_type"))                return null; // it's an event, not an error
            JsonNode errNode  = root.get("error");
            JsonNode stNode   = root.get("status");
            if (errNode == null || !errNode.isTextual()) return null;
            if (stNode  == null || !stNode.isTextual())  return null;
            JsonNode msgNode = root.get("message");
            String msg = (msgNode != null && msgNode.isTextual()) ? msgNode.asText() : null;
            return new AnchorTopologyException(errNode.asText(), stNode.asText(), msg);
        } catch (Exception ex) {
            return null;
        }
    }

    /** Build an exception from a non-2xx HTTP response. */
    private static AnchorTopologyException buildHttpError(int status, String body, String operation) {
        try {
            ObjectMapper tmp = new ObjectMapper();
            JsonNode root = tmp.readTree(body);
            if (root.isObject()) {
                JsonNode err = root.get("error");
                JsonNode st  = root.get("status");
                if (err != null && err.isTextual() && st != null && st.isTextual()) {
                    JsonNode msg = root.get("message");
                    String msgStr = (msg != null && msg.isTextual()) ? msg.asText() : body;
                    return new AnchorTopologyException(err.asText(), st.asText(), msgStr);
                }
            }
        } catch (Exception ignored) { /* fall through */ }
        return new AnchorTopologyException(
            "UNKNOWN",
            "HTTP-" + status,
            operation + " returned HTTP " + status + ": " + body);
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    /**
     * Unchecked wrapper for {@link AnchorTopologyException} thrown from inside
     * the stream iterator (where checked exceptions are not permitted by the
     * {@link Iterator} contract).
     */
    public static final class TopologyStreamException extends RuntimeException {

        public final AnchorTopologyException cause;

        public TopologyStreamException(AnchorTopologyException cause) {
            super(cause.getMessage(), cause);
            this.cause = cause;
        }
    }
}
