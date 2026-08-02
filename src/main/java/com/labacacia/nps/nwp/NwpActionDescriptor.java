// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * One action a fronted node exposes, projected onto an MCP tool / A2A skill
 * (NPS-CR-0010).
 *
 * @param actionId    required NWP action id
 * @param description optional; doubles as the A2A skill name when present
 * @param inputSchema optional JSON Schema; absent ⇒ the open object schema is advertised
 * @param async       whether the action supports async execution
 * @param tags        optional tags, surfaced on the A2A skill
 */
public record NwpActionDescriptor(String actionId, String description,
                                  JsonNode inputSchema, boolean async, List<String> tags) {

    public NwpActionDescriptor {
        if (actionId == null || actionId.isBlank()) {
            throw new IllegalArgumentException("NwpActionDescriptor.actionId is required");
        }
    }

    public NwpActionDescriptor(String actionId) {
        this(actionId, null, null, false, null);
    }

    public NwpActionDescriptor(String actionId, String description) {
        this(actionId, description, null, false, null);
    }

    /** {@code {"type":"object","additionalProperties":true}} — advertised when no schema is declared. */
    public static ObjectNode openObjectSchema() {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("type", "object");
        n.put("additionalProperties", true);
        return n;
    }

    /** The declared schema, or the open object schema when none was declared. */
    public JsonNode effectiveInputSchema() {
        return inputSchema != null ? inputSchema : openObjectSchema();
    }
}
