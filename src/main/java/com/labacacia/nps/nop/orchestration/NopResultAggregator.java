// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.labacacia.nps.nop.AggregateStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Aggregates results from multiple completed subtasks using the strategy defined in
 * {@code SyncFrame.aggregate} or the orchestrator default (NPS-5 §3.3.2).
 */
public final class NopResultAggregator {
    private NopResultAggregator() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Aggregates {@code results} using {@code strategy}.
     *
     * @param strategy    one of {@link AggregateStrategy} constants.
     * @param results     ordered list of successful subtask results.
     * @param minRequired for {@code fastest_k}: how many results to include; ignored otherwise.
     */
    public static JsonNode aggregate(String strategy, List<JsonNode> results, int minRequired) {
        if (results.isEmpty()) {
            return JSON.createObjectNode();
        }

        return switch (strategy == null ? "" : strategy) {
            case AggregateStrategy.FIRST -> results.get(0);
            case AggregateStrategy.ALL   -> buildArray(results);
            case AggregateStrategy.FASTEST_K ->
                buildArray(results.subList(0, Math.min(minRequired > 0 ? minRequired : results.size(), results.size())));
            default -> merge(results); // "merge" and default
        };
    }

    public static JsonNode aggregate(String strategy, List<JsonNode> results) {
        return aggregate(strategy, results, 0);
    }

    // ── Strategies ────────────────────────────────────────────────────────────

    /**
     * Merges all JSON object results into one (last-write-wins on key conflicts).
     * Non-object results are added under {@code "_result_{i}"} keys.
     */
    public static JsonNode merge(List<JsonNode> results) {
        ObjectNode merged = JSON.createObjectNode();
        for (int i = 0; i < results.size(); i++) {
            JsonNode result = results.get(i);
            if (result != null && result.isObject()) {
                var fields = result.fields();
                while (fields.hasNext()) {
                    var e = fields.next();
                    merged.set(e.getKey(), e.getValue());
                }
            } else {
                merged.set("_result_" + i, result != null ? result : JSON.nullNode());
            }
        }
        return merged;
    }

    /** Returns all results as a JSON array. */
    public static JsonNode buildArray(List<JsonNode> results) {
        ArrayNode arr = JSON.createArrayNode();
        for (JsonNode r : results) {
            arr.add(r != null ? r : JSON.nullNode());
        }
        return arr;
    }

    /**
     * Filters {@code allResults} to only end nodes (those with no outgoing edges),
     * then aggregates.
     */
    public static JsonNode aggregateEndNodes(
        List<String> endNodeIds,
        Map<String, JsonNode> allResults,
        String strategy) {

        List<JsonNode> endResults = new ArrayList<>();
        for (String id : endNodeIds) {
            if (allResults.containsKey(id)) endResults.add(allResults.get(id));
        }
        return aggregate(strategy, endResults, 0);
    }
}
