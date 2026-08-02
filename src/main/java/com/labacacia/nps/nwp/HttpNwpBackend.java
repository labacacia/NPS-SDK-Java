// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.labacacia.nps.core.NpsStatusCodes;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * {@link NwpBackend} that speaks HTTP to a remote NWP node — the
 * {@code compat/*-ingress} deployment shape, folded into the Bridge by NPS-CR-0010.
 *
 * <p>Paths: {@code GET /.nwm}, {@code GET /actions}, {@code POST /query},
 * {@code POST /invoke}.</p>
 */
public final class HttpNwpBackend implements NwpBackend {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NwpUpstream upstream;
    private final HttpClient  http;

    /** Cached after the first fetch; an unreachable {@code /.nwm} caches role UNKNOWN. */
    private volatile NwpNodeDescriptor descriptor;
    private volatile JsonNode          manifest;

    public HttpNwpBackend(NwpUpstream upstream, HttpClient http) {
        if (upstream == null) throw new IllegalArgumentException("upstream is required");
        if (http == null)     throw new IllegalArgumentException("httpClient is required");
        this.upstream = upstream;
        this.http     = http;
    }

    // ── Descriptor / manifest ────────────────────────────────────────────────

    @Override
    public NwpNodeDescriptor getDescriptor() {
        NwpNodeDescriptor d = descriptor;
        if (d != null) return d;
        synchronized (this) {
            if (descriptor != null) return descriptor;
            NwpNodeRole role = NwpNodeRole.UNKNOWN;
            String displayName = null;
            String description = null;
            NwpResult r = fetchManifest();
            if (r.ok() && r.payload() != null && r.payload().isObject()) {
                role = NwpNodeRole.parseRole(r.payload().path("node_type").asText(null));
                displayName = textOrNull(r.payload(), "display_name");
                description = textOrNull(r.payload(), "description");
            }
            // A dead upstream must not take the Bridge down — it is projected onto nothing.
            descriptor = new NwpNodeDescriptor(upstream.name(), role, displayName, description);
            return descriptor;
        }
    }

    @Override
    public NwpResult getManifest() {
        JsonNode m = manifest;
        if (m != null) return NwpResult.success(m);
        NwpResult r = fetchManifest();
        if (r.ok()) manifest = r.payload();
        return r;
    }

    private NwpResult fetchManifest() {
        return send(HttpRequest.newBuilder(URI.create(upstream.normalisedBaseUrl() + "/.nwm")).GET());
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    @Override
    public List<NwpActionDescriptor> getActions() {
        if (!getDescriptor().isInvokable()) return List.of();
        NwpResult r = send(HttpRequest.newBuilder(
            URI.create(upstream.normalisedBaseUrl() + "/actions")).GET());
        if (!r.ok() || r.payload() == null) return List.of();

        JsonNode actions = r.payload().path("actions");
        if (!actions.isObject()) return List.of();
        List<NwpActionDescriptor> out = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = actions.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode spec = e.getValue();
            JsonNode schema = spec.has("params_schema") ? spec.get("params_schema") : null;
            out.add(new NwpActionDescriptor(e.getKey(), textOrNull(spec, "description"),
                schema, spec.path("async").asBoolean(false), null));
        }
        return List.copyOf(out);
    }

    // ── Query / invoke ───────────────────────────────────────────────────────

    @Override
    public NwpResult query(JsonNode query) {
        if (!getDescriptor().isQueryable()) {
            return NwpResult.failure(NpsStatusCodes.NPS_SERVER_UNSUPPORTED,
                BridgeErrorCodes.NWP_BRIDGE_SERVER_TOOL_NOT_FOUND,
                "Node '" + upstream.name() + "' is not queryable (role: "
                    + getDescriptor().role().wire() + ").");
        }
        JsonNode body = query == null || query.isNull()
            ? JsonNodeFactory.instance.objectNode() : query;
        return post("/query", body);
    }

    @Override
    public NwpResult invoke(String actionId, JsonNode arguments, boolean async) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("action_id", actionId);
        body.set("params", arguments == null || arguments.isNull()
            ? JsonNodeFactory.instance.objectNode() : arguments);
        body.put("async", async);
        return post("/invoke", body);
    }

    private NwpResult post(String path, JsonNode body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(
                    URI.create(upstream.normalisedBaseUrl() + path))
                .header("Content-Type", NwpHttpHeaders.MIME_FRAME)
                .POST(HttpRequest.BodyPublishers.ofString(
                    MAPPER.writeValueAsString(body), StandardCharsets.UTF_8));
            return send(b);
        } catch (Exception e) {
            return NwpResult.failure(NpsStatusCodes.NPS_DOWNSTREAM_UNAVAILABLE,
                BridgeErrorCodes.NWP_BRIDGE_UPSTREAM_FAILED, e.getMessage());
        }
    }

    // ── Transport + §16.3 inverse-direction translation ──────────────────────

    private NwpResult send(HttpRequest.Builder builder) {
        if (upstream.agentNid() != null)   builder.header(NwpHttpHeaders.AGENT, upstream.agentNid());
        if (upstream.authHeader() != null) builder.header("Authorization", upstream.authHeader());
        builder.header("Accept", "application/json");

        HttpResponse<String> resp;
        try {
            resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            return NwpResult.failure(NpsStatusCodes.NPS_SERVER_TIMEOUT,
                BridgeErrorCodes.NWP_BRIDGE_UPSTREAM_FAILED, e.getMessage());
        } catch (ConnectException e) {
            return NwpResult.failure(NpsStatusCodes.NPS_DOWNSTREAM_UNAVAILABLE,
                BridgeErrorCodes.NWP_BRIDGE_UPSTREAM_FAILED, e.getMessage());
        } catch (IOException e) {
            return NwpResult.failure(NpsStatusCodes.NPS_DOWNSTREAM_UNAVAILABLE,
                BridgeErrorCodes.NWP_BRIDGE_UPSTREAM_FAILED, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return NwpResult.failure(NpsStatusCodes.NPS_DOWNSTREAM_UNAVAILABLE,
                BridgeErrorCodes.NWP_BRIDGE_UPSTREAM_FAILED, "upstream call interrupted");
        }

        String raw = resp.body() == null ? "" : resp.body();
        JsonNode parsed;
        try {
            parsed = raw.isEmpty() ? JsonNodeFactory.instance.objectNode() : MAPPER.readTree(raw);
        } catch (Exception e) {
            parsed = null;
        }

        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            String nwpError = parsed != null ? textOrNull(parsed, "error") : null;
            return NwpResult.failure(BridgeErrorMap.fromHttpStatus(resp.statusCode()),
                nwpError != null ? nwpError : BridgeErrorCodes.NWP_BRIDGE_UPSTREAM_FAILED, raw);
        }
        if (parsed == null) {
            return NwpResult.failure(NpsStatusCodes.NPS_DOWNSTREAM_UNAVAILABLE,
                BridgeErrorCodes.NWP_BRIDGE_UPSTREAM_FAILED,
                "upstream returned a non-JSON 2xx body");
        }
        return NwpResult.success(parsed);
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }
}
