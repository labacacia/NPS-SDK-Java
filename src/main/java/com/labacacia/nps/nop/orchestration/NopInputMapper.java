// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.labacacia.nps.nop.NopConstants;
import com.labacacia.nps.nop.NopErrorCodes;

import java.util.Map;

/**
 * Resolves NOP JSONPath expressions of the form {@code $.node_id.field.subfield}
 * against a map of upstream node results (NPS-5 §3.1.3).
 *
 * <p>Path syntax:
 * <ul>
 *   <li>{@code $} — the entire upstream context (all node results combined).</li>
 *   <li>{@code $.node_id} — the full result object of a specific node.</li>
 *   <li>{@code $.node_id.field} — a specific field within a node's result.</li>
 *   <li>{@code $.node_id.field.sub} — nested navigation
 *       (max {@link NopConstants#MAX_INPUT_MAPPING_DEPTH} levels).</li>
 * </ul>
 */
public final class NopInputMapper {
    private NopInputMapper() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Resolves a single JSONPath expression against the upstream node result context.
     * Returns {@code null} when the path leads to a missing property.
     *
     * @throws NopMappingException for malformed paths or depth violations.
     */
    public static JsonNode resolve(String path, Map<String, JsonNode> context) {
        if (path == null || path.isBlank()) {
            throw new NopMappingException(
                "Input mapping path must not be empty.", NopErrorCodes.NOP_INPUT_MAPPING_ERROR);
        }

        if (!path.startsWith("$.") && !path.equals("$")) {
            throw new NopMappingException(
                "Input mapping path must start with '$.' — got: " + path,
                NopErrorCodes.NOP_INPUT_MAPPING_ERROR);
        }

        // Split: "$", "node_id", "field", "sub", ... (drop empty segments)
        String[] parts = java.util.Arrays.stream(path.split("\\."))
            .filter(s -> !s.isEmpty())
            .toArray(String[]::new);
        // parts[0] == "$"

        if (parts.length > NopConstants.MAX_INPUT_MAPPING_DEPTH + 1) {
            throw new NopMappingException(
                "Input mapping path depth " + (parts.length - 1) + " exceeds maximum "
                    + NopConstants.MAX_INPUT_MAPPING_DEPTH + ": " + path,
                NopErrorCodes.NOP_INPUT_MAPPING_ERROR);
        }

        if (parts.length == 1) {
            // Just "$" → serialize the entire context as a JSON object
            ObjectNode all = JSON.createObjectNode();
            for (Map.Entry<String, JsonNode> e : context.entrySet()) {
                all.set(e.getKey(), e.getValue());
            }
            return all;
        }

        String nodeId = parts[1];
        JsonNode nodeResult = context.get(nodeId);
        if (nodeResult == null) return null;

        if (parts.length == 2) {
            return nodeResult; // "$.node_id" → full result
        }

        JsonNode current = nodeResult;
        for (int i = 2; i < parts.length; i++) {
            if (current == null || !current.isObject()) return null;
            if (!current.has(parts[i])) return null;
            current = current.get(parts[i]);
        }
        return current;
    }

    /**
     * Builds a {@code params} object by resolving all {@code input_mapping} entries
     * against the upstream result context.
     *
     * @param inputMapping the node's input_mapping (parameter → JSONPath string or array).
     * @param context      upstream node results.
     * @return a JSON object suitable for the delegation params.
     */
    public static JsonNode buildParams(
        Map<String, JsonNode> inputMapping,
        Map<String, JsonNode> context) {

        ObjectNode obj = JSON.createObjectNode();
        if (inputMapping == null || inputMapping.isEmpty()) {
            return obj;
        }

        for (Map.Entry<String, JsonNode> entry : inputMapping.entrySet()) {
            String paramName = entry.getKey();
            JsonNode pathNode = entry.getValue();

            if (pathNode != null && pathNode.isTextual()) {
                JsonNode resolved = resolve(pathNode.asText(), context);
                obj.set(paramName, resolved != null ? resolved : JSON.nullNode());
            } else if (pathNode != null && pathNode.isArray()) {
                ArrayNode arr = JSON.createArrayNode();
                for (JsonNode p : pathNode) {
                    if (p.isTextual()) {
                        JsonNode resolved = resolve(p.asText(), context);
                        arr.add(resolved != null ? resolved : JSON.nullNode());
                    } else {
                        arr.add(p);
                    }
                }
                obj.set(paramName, arr);
            } else {
                obj.set(paramName, pathNode != null ? pathNode : JSON.nullNode());
            }
        }
        return obj;
    }
}
