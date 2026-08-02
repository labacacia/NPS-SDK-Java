// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Inbound gRPC service logic for a Bridge Node — NPS-CR-0010, the {@code NwpIngress}
 * service of {@code Protos/nwp_ingress.proto} (package
 * {@code labacacia.grpc_ingress.v1}), carried over unchanged from the published
 * {@code LabAcacia.GrpcIngress} because clients hold generated stubs.
 *
 * <p><strong>Transport note</strong>: this Java SDK deliberately carries no grpc-java or
 * protobuf dependency (the Gradle build must resolve offline), so what ships here is the
 * <em>service logic</em> — the four RPC handlers over {@link NwpBackend}, backend
 * resolution, and the §16.3 status mapping — expressed over plain records. A host that
 * does link grpc-java binds the generated stubs straight onto these four methods and
 * rethrows {@link GrpcInboundException} as {@code StatusRuntimeException}.</p>
 *
 * <p>All payloads are JSON-encoded NWP frame bodies carried as {@code bytes}: schemas are
 * runtime-declared via AnchorFrame, so a typed proto is impossible.</p>
 */
public final class GrpcInboundService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NF  = JsonNodeFactory.instance;

    /** {@code UpstreamContext { upstream = 1; agent_nid = 2; idempotency_key = 3; traceparent = 4; }} */
    public record UpstreamContext(String upstream, String agentNid,
                                  String idempotencyKey, String traceparent) {
        public static final UpstreamContext EMPTY = new UpstreamContext(null, null, null, null);
    }

    public record ManifestResponse(byte[] nwmJson, String nodeType) {}
    public record InvokeResponse(int httpStatus, byte[] bodyJson, String taskId) {}
    public record QueryResponse(int httpStatus, byte[] bodyJson) {}
    public record ActionsResponse(byte[] actionsJson) {}

    private final BridgeInboundOptions options;

    public GrpcInboundService(BridgeInboundOptions options) {
        if (options == null) throw new IllegalArgumentException("options is required");
        this.options = options;
    }

    // ── RPCs ─────────────────────────────────────────────────────────────────

    /** {@code GetManifest(ManifestRequest) → ManifestResponse}. */
    public ManifestResponse getManifest(UpstreamContext ctx) {
        checkDirection();
        NwpBackend backend = resolveBackend(ctx);
        NwpResult result = backend.getManifest();
        if (!result.ok()) throw failure(result);
        NwpNodeRole role = backend.getDescriptor().role();
        return new ManifestResponse(toBytes(result.payload()), role.wire());
    }

    /** {@code Invoke(InvokeRequest) → InvokeResponse}; always {@code async: false}. */
    public InvokeResponse invoke(UpstreamContext ctx, String actionId, byte[] paramsJson) {
        checkDirection();
        if (actionId == null || actionId.isBlank()) {
            throw new GrpcInboundException(GrpcStatusCode.INVALID_ARGUMENT, "action_id is required");
        }
        NwpBackend backend = resolveBackend(ctx);
        NwpResult result = backend.invoke(actionId, parse(paramsJson), false);
        if (!result.ok()) throw failure(result);

        JsonNode payload = result.payload();
        String taskId = payload != null && payload.hasNonNull("task_id")
            ? payload.get("task_id").asText() : "";
        return new InvokeResponse(200, toBytes(payload), taskId);
    }

    /** {@code Query(QueryRequest) → QueryResponse}; an empty {@code query_json} means {@code {}}. */
    public QueryResponse query(UpstreamContext ctx, byte[] queryJson) {
        checkDirection();
        NwpBackend backend = resolveBackend(ctx);
        JsonNode query = parse(queryJson);
        NwpResult result = backend.query(query == null ? NF.objectNode() : query);
        if (!result.ok()) throw failure(result);
        return new QueryResponse(200, toBytes(result.payload()));
    }

    /** {@code ListActions(ActionsRequest) → ActionsResponse}. */
    public ActionsResponse listActions(UpstreamContext ctx) {
        checkDirection();
        NwpBackend backend = resolveBackend(ctx);
        ObjectNode root = NF.objectNode();
        ObjectNode actions = root.putObject("actions");
        for (NwpActionDescriptor a : backend.getActions()) {
            ObjectNode entry = actions.putObject(a.actionId());
            entry.put("description", a.description());
        }
        return new ActionsResponse(toBytes(root));
    }

    // ── Gates and resolution ─────────────────────────────────────────────────

    private void checkDirection() {
        if (!options.servesInbound(BridgeProtocols.GRPC)) {
            throw new GrpcInboundException(GrpcStatusCode.UNIMPLEMENTED,
                "NPS-SERVER-UNSUPPORTED " + BridgeErrorCodes.NWP_BRIDGE_DIRECTION_UNSUPPORTED
                    + ": this Bridge Node does not declare \"grpc\" in bridge_inbound_protocols.");
        }
    }

    /**
     * If {@code ctx.upstream} is empty and exactly one backend is configured, use it;
     * otherwise match the descriptor name case-insensitively.
     */
    private NwpBackend resolveBackend(UpstreamContext ctx) {
        List<NwpBackend> backends = options.backends == null ? List.of() : options.backends;
        String name = ctx == null ? null : ctx.upstream();
        if ((name == null || name.isBlank()) && backends.size() == 1) return backends.get(0);
        if (name != null && !name.isBlank()) {
            for (NwpBackend b : backends) {
                if (b.getDescriptor().name().equalsIgnoreCase(name)) return b;
            }
        }
        throw new GrpcInboundException(GrpcStatusCode.NOT_FOUND,
            "NPS-CLIENT-NOT-FOUND " + BridgeErrorCodes.NWP_BRIDGE_SERVER_TOOL_NOT_FOUND
                + ": no NWP node named '" + (name == null ? "" : name)
                + "' is fronted by this Bridge Node.");
    }

    private static GrpcInboundException failure(NwpResult result) {
        return GrpcInboundException.of(result.npsStatus(), result.nwpError(), result.message());
    }

    // ── JSON helpers ─────────────────────────────────────────────────────────

    private static JsonNode parse(byte[] json) {
        if (json == null || json.length == 0) return null;
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new GrpcInboundException(GrpcStatusCode.INVALID_ARGUMENT,
                "NPS-CLIENT-BAD-FRAME " + BridgeErrorCodes.NWP_BRIDGE_SERVER_DISPATCH_FAILED
                    + ": " + e.getMessage());
        }
    }

    private static byte[] toBytes(JsonNode node) {
        try {
            return MAPPER.writeValueAsBytes(node == null ? NF.objectNode() : node);
        } catch (Exception e) {
            return "{}".getBytes(StandardCharsets.UTF_8);
        }
    }
}
