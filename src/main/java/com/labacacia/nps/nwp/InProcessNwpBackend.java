// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.labacacia.nps.core.NpsFrame;
import com.labacacia.nps.core.NpsStatusCodes;
import com.labacacia.nps.ncp.ErrorFrame;

import java.util.List;
import java.util.Map;

/**
 * {@link NwpBackend} that dispatches to in-process delegates — the SDK's deployment
 * shape for an inbound Bridge (NPS-CR-0010).
 */
public final class InProcessNwpBackend implements NwpBackend {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Handles an {@link ActionFrame} and returns the response frame. */
    @FunctionalInterface
    public interface ActionDispatcher { NpsFrame dispatch(ActionFrame frame); }

    /** Handles a {@link QueryFrame} and returns the response frame. */
    @FunctionalInterface
    public interface QueryDispatcher { NpsFrame dispatch(QueryFrame frame); }

    private final NwpNodeDescriptor          descriptor;
    private final List<NwpActionDescriptor>  actions;
    private final ActionDispatcher           invokeDelegate;   // nullable
    private final QueryDispatcher            queryDelegate;    // nullable

    public InProcessNwpBackend(NwpNodeDescriptor descriptor,
                               List<NwpActionDescriptor> actions,
                               ActionDispatcher invokeDelegate,
                               QueryDispatcher queryDelegate) {
        if (descriptor == null) throw new IllegalArgumentException("descriptor is required");
        this.descriptor     = descriptor;
        this.actions        = actions == null ? List.of() : List.copyOf(actions);
        this.invokeDelegate = invokeDelegate;
        this.queryDelegate  = queryDelegate;
    }

    @Override public NwpNodeDescriptor getDescriptor() { return descriptor; }

    @Override
    public NwpResult getManifest() {
        ObjectNode m = JsonNodeFactory.instance.objectNode();
        m.put("node_type", descriptor.role().wire());
        if (descriptor.displayName() != null) m.put("display_name", descriptor.displayName());
        if (descriptor.description() != null) m.put("description",  descriptor.description());
        return NwpResult.success(m);
    }

    @Override
    public List<NwpActionDescriptor> getActions() {
        return descriptor.isInvokable() ? actions : List.of();
    }

    @Override
    public NwpResult query(JsonNode query) {
        if (!descriptor.isQueryable()) {
            return NwpResult.failure(NpsStatusCodes.NPS_SERVER_UNSUPPORTED,
                BridgeErrorCodes.NWP_BRIDGE_SERVER_TOOL_NOT_FOUND,
                "Node '" + descriptor.name() + "' is not queryable (role: "
                    + descriptor.role().wire() + ").");
        }
        if (queryDelegate == null) {
            return NwpResult.failure(NpsStatusCodes.NPS_SERVER_INTERNAL,
                BridgeErrorCodes.NWP_BRIDGE_SERVER_DISPATCHER_MISSING,
                "Node '" + descriptor.name() + "' has no query dispatcher configured.");
        }
        try {
            return toResult(queryDelegate.dispatch(new QueryFrame(null, toMap(query),
                null, null, null, null, null, null)));
        } catch (Exception e) {
            return NwpResult.dispatchFailed(e.getMessage());
        }
    }

    @Override
    public NwpResult invoke(String actionId, JsonNode arguments, boolean async) {
        if (invokeDelegate == null) {
            return NwpResult.failure(NpsStatusCodes.NPS_SERVER_INTERNAL,
                BridgeErrorCodes.NWP_BRIDGE_SERVER_DISPATCHER_MISSING,
                "Node '" + descriptor.name() + "' has no action dispatcher configured.");
        }
        try {
            return toResult(invokeDelegate.dispatch(
                new ActionFrame(actionId, toMap(arguments), async, null, null)));
        } catch (Exception e) {
            return NwpResult.dispatchFailed(e.getMessage());
        }
    }

    /**
     * An ErrorFrame carries the NPS status forward; anything else is the serialised
     * success payload.
     */
    static NwpResult toResult(NpsFrame frame) {
        if (frame instanceof ErrorFrame err) {
            return NwpResult.failure(
                err.status() != null ? err.status() : NpsStatusCodes.NPS_SERVER_INTERNAL,
                err.error(), err.message());
        }
        if (frame == null) return NwpResult.success(JsonNodeFactory.instance.objectNode());
        return NwpResult.success(MAPPER.valueToTree(frame.toDict()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isObject()) return Map.of("value", MAPPER.convertValue(node, Object.class));
        return MAPPER.convertValue(node, Map.class);
    }
}
