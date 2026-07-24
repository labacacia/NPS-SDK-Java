// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.nop.AggregateStrategy;
import com.labacacia.nps.nop.NopConstants;
import com.labacacia.nps.nop.NopErrorCodes;
import com.labacacia.nps.nop.models.DagEdge;
import com.labacacia.nps.nop.models.TaskDag;
import com.labacacia.nps.nop.models.TaskDagNode;
import com.labacacia.nps.nop.validation.DagValidationResult;
import com.labacacia.nps.nop.validation.DagValidator;
import com.labacacia.nps.nop.validation.NopCallbackValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** DAG validation, condition truth table, input mapper, aggregation, callback signature. */
class NopUnitTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode j(String s) {
        try { return JSON.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static Map<String, JsonNode> ctx(String node, String json) {
        Map<String, JsonNode> m = new HashMap<>();
        m.put(node, j(json));
        return m;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DAG validation
    // ══════════════════════════════════════════════════════════════════════════

    private static TaskDagNode n(String id) { return TaskDagNode.of(id, "nwp://a", id); }

    @Test void validate_empty_fails() {
        var r = DagValidator.validate(new TaskDag(List.of(), List.of()));
        assertFalse(r.valid());
        assertEquals(NopErrorCodes.NOP_TASK_DAG_INVALID, r.errorCode());
    }

    @Test void validate_singleNode_ok() {
        var r = DagValidator.validate(new TaskDag(List.of(n("a")), List.of()));
        assertTrue(r.valid());
        assertEquals(List.of("a"), r.topologicalOrder());
    }

    @Test void validate_tooLarge_fails() {
        List<TaskDagNode> nodes = new ArrayList<>();
        for (int i = 0; i <= NopConstants.MAX_DAG_NODES; i++) nodes.add(n("n" + i));
        var r = DagValidator.validate(new TaskDag(nodes, List.of()));
        assertFalse(r.valid());
        assertEquals(NopErrorCodes.NOP_TASK_DAG_TOO_LARGE, r.errorCode());
    }

    @Test void validate_duplicateId_fails() {
        var r = DagValidator.validate(new TaskDag(List.of(n("a"), n("a")), List.of()));
        assertFalse(r.valid());
        assertEquals(NopErrorCodes.NOP_TASK_DAG_INVALID, r.errorCode());
    }

    @Test void validate_edgeUnknownNode_fails() {
        var r = DagValidator.validate(new TaskDag(List.of(n("a")), List.of(new DagEdge("a", "z"))));
        assertFalse(r.valid());
        assertEquals(NopErrorCodes.NOP_TASK_DAG_INVALID, r.errorCode());
    }

    @Test void validate_cycle_fails() {
        // a is a start node; b<->c form a cycle reached by Kahn's algorithm.
        var nodes = List.of(n("a"), n("b"), n("c"), n("d"));
        var edges = List.of(new DagEdge("a", "b"), new DagEdge("b", "c"),
            new DagEdge("c", "b"), new DagEdge("c", "d"));
        var r = DagValidator.validate(new TaskDag(nodes, edges));
        assertFalse(r.valid());
        assertEquals(NopErrorCodes.NOP_TASK_DAG_CYCLE, r.errorCode());
    }

    @Test void validate_linear_topoOrder() {
        var nodes = List.of(n("a"), n("b"), n("c"));
        var edges = List.of(new DagEdge("a", "b"), new DagEdge("b", "c"));
        var r = DagValidator.validate(new TaskDag(nodes, edges));
        assertTrue(r.valid());
        assertEquals(List.of("a", "b", "c"), r.topologicalOrder());
    }

    @Test void validate_conditionTooLong_fails() {
        String longCond = "true" + " ".repeat(NopConstants.MAX_CONDITION_LENGTH + 1);
        var node = new TaskDagNode.Builder("a", "nwp://a", "a").condition(longCond).build();
        var r = DagValidator.validate(new TaskDag(List.of(node), List.of()));
        assertFalse(r.valid());
        assertEquals(NopErrorCodes.NOP_CONDITION_EVAL_ERROR, r.errorCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Condition truth table
    // ══════════════════════════════════════════════════════════════════════════

    @Test void cond_gt_true()  { assertTrue(NopConditionEvaluator.evaluate("$.a.score > 0.7", ctx("a", "{\"score\":0.92}"))); }
    @Test void cond_gt_false() { assertFalse(NopConditionEvaluator.evaluate("$.a.score > 0.7", ctx("a", "{\"score\":0.5}"))); }
    @Test void cond_gte_exact(){ assertTrue(NopConditionEvaluator.evaluate("$.n.val >= 5", ctx("n", "{\"val\":5}"))); }
    @Test void cond_lt_true()  { assertTrue(NopConditionEvaluator.evaluate("$.n.count < 10", ctx("n", "{\"count\":3}"))); }
    @Test void cond_lte_true() { assertTrue(NopConditionEvaluator.evaluate("$.n.count <= 10", ctx("n", "{\"count\":10}"))); }
    @Test void cond_neq_true() { assertTrue(NopConditionEvaluator.evaluate("$.n.status != \"ok\"", ctx("n", "{\"status\":\"error\"}"))); }
    @Test void cond_strEq_true()  { assertTrue(NopConditionEvaluator.evaluate("$.c.label == \"positive\"", ctx("c", "{\"label\":\"positive\"}"))); }
    @Test void cond_strEq_false() { assertFalse(NopConditionEvaluator.evaluate("$.c.label == \"positive\"", ctx("c", "{\"label\":\"negative\"}"))); }
    @Test void cond_nullEq_missing() { assertTrue(NopConditionEvaluator.evaluate("$.n.missing == null", ctx("n", "{}"))); }
    @Test void cond_nullNeq_exists() { assertTrue(NopConditionEvaluator.evaluate("$.n.x != null", ctx("n", "{\"x\":1}"))); }

    @Test void cond_and_bothTrue() { assertTrue(NopConditionEvaluator.evaluate("$.n.score > 0.7 && $.n.count > 0", ctx("n", "{\"score\":0.9,\"count\":5}"))); }
    @Test void cond_and_oneFalse() { assertFalse(NopConditionEvaluator.evaluate("$.n.score > 0.7 && $.n.count > 0", ctx("n", "{\"score\":0.9,\"count\":0}"))); }
    @Test void cond_or_oneTrue()   { assertTrue(NopConditionEvaluator.evaluate("$.n.a > 5 || $.n.b == 0", ctx("n", "{\"a\":1,\"b\":0}"))); }
    @Test void cond_not_negates()  { assertTrue(NopConditionEvaluator.evaluate("!$.n.ok", ctx("n", "{\"ok\":false}"))); }
    @Test void cond_grouping()     { assertTrue(NopConditionEvaluator.evaluate("($.n.a > 0 || $.n.b > 0) && $.n.c > 0", ctx("n", "{\"a\":0,\"b\":1,\"c\":1}"))); }

    @Test void cond_trueLiteral()  { assertTrue(NopConditionEvaluator.evaluate("true", Map.of())); }
    @Test void cond_falseLiteral() { assertFalse(NopConditionEvaluator.evaluate("false", Map.of())); }
    @Test void cond_empty_true()   { assertTrue(NopConditionEvaluator.evaluate("", Map.of())); }

    @Test void cond_unknownToken_throws() {
        assertThrows(NopConditionException.class, () -> NopConditionEvaluator.evaluate("$.n.x @@ 1", Map.of()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Input mapper
    // ══════════════════════════════════════════════════════════════════════════

    @Test void map_topLevel_full() {
        JsonNode r = NopInputMapper.resolve("$.fetch", ctx("fetch", "{\"count\":3}"));
        assertNotNull(r);
        assertEquals(3, r.get("count").asInt());
    }

    @Test void map_nested_value() {
        JsonNode r = NopInputMapper.resolve("$.analyze.result.score", ctx("analyze", "{\"result\":{\"score\":0.92}}"));
        assertNotNull(r);
        assertEquals(0.92, r.asDouble(), 1e-6);
    }

    @Test void map_missingNode_null() {
        assertNull(NopInputMapper.resolve("$.missing.field", Map.of()));
    }

    @Test void map_missingField_null() {
        assertNull(NopInputMapper.resolve("$.fetch.no_such", ctx("fetch", "{\"count\":1}")));
    }

    @Test void map_dollarOnly_fullContext() {
        assertNotNull(NopInputMapper.resolve("$.", ctx("a", "{\"x\":1}")));
    }

    @Test void map_noPrefix_throws() {
        assertThrows(NopMappingException.class, () -> NopInputMapper.resolve("fetch.field", Map.of()));
    }

    @Test void map_empty_throws() {
        assertThrows(NopMappingException.class, () -> NopInputMapper.resolve("", Map.of()));
    }

    @Test void map_depthExceeded_throws() {
        String deep = "$.n." + String.join(".", java.util.Collections.nCopies(10, "a"));
        assertThrows(NopMappingException.class, () -> NopInputMapper.resolve(deep, ctx("n", "{}")));
    }

    @Test void buildParams_null_emptyObject() {
        JsonNode r = NopInputMapper.buildParams(null, Map.of());
        assertTrue(r.isObject());
        assertEquals(0, r.size());
    }

    @Test void buildParams_stringPath_resolves() {
        Map<String, JsonNode> mapping = Map.of("products", j("\"$.fetch.items\""));
        JsonNode r = NopInputMapper.buildParams(mapping, ctx("fetch", "{\"items\":[1,2,3]}"));
        assertTrue(r.get("products").isArray());
    }

    @Test void buildParams_arrayPaths_list() {
        Map<String, JsonNode> mapping = Map.of("combined", j("[\"$.a.v\",\"$.b.v\"]"));
        Map<String, JsonNode> context = new HashMap<>();
        context.put("a", j("{\"v\":1}"));
        context.put("b", j("{\"v\":2}"));
        JsonNode r = NopInputMapper.buildParams(mapping, context);
        assertTrue(r.get("combined").isArray());
        assertEquals(2, r.get("combined").size());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Aggregation strategies
    // ══════════════════════════════════════════════════════════════════════════

    @Test void agg_merge_combines() {
        JsonNode m = NopResultAggregator.merge(List.of(j("{\"a\":1}"), j("{\"b\":2}")));
        assertEquals(1, m.get("a").asInt());
        assertEquals(2, m.get("b").asInt());
    }

    @Test void agg_merge_lastWriteWins() {
        JsonNode m = NopResultAggregator.merge(List.of(j("{\"x\":1}"), j("{\"x\":99}")));
        assertEquals(99, m.get("x").asInt());
    }

    @Test void agg_merge_nonObjectWrapped() {
        JsonNode m = NopResultAggregator.merge(List.of(j("{\"a\":1}"), j("42")));
        assertTrue(m.has("a"));
        assertEquals(42, m.get("_result_1").asInt());
    }

    @Test void agg_merge_empty_object() {
        assertTrue(NopResultAggregator.merge(List.of()).isObject());
    }

    @Test void agg_first() {
        JsonNode a = NopResultAggregator.aggregate(AggregateStrategy.FIRST, List.of(j("{\"v\":1}"), j("{\"v\":2}")));
        assertEquals(1, a.get("v").asInt());
    }

    @Test void agg_all_array() {
        JsonNode a = NopResultAggregator.aggregate(AggregateStrategy.ALL, List.of(j("{\"v\":1}"), j("{\"v\":2}")));
        assertTrue(a.isArray());
        assertEquals(2, a.size());
    }

    @Test void agg_fastestK_takesMin() {
        JsonNode a = NopResultAggregator.aggregate(AggregateStrategy.FASTEST_K,
            List.of(j("{\"v\":1}"), j("{\"v\":2}"), j("{\"v\":3}")), 2);
        assertTrue(a.isArray());
        assertEquals(2, a.size());
    }

    @Test void agg_fastestK_zero_takesAll() {
        JsonNode a = NopResultAggregator.aggregate(AggregateStrategy.FASTEST_K,
            List.of(j("{\"v\":1}"), j("{\"v\":2}")), 0);
        assertEquals(2, a.size());
    }

    @Test void agg_endNodes_onlyEnds() {
        Map<String, JsonNode> all = new HashMap<>();
        all.put("fetch", j("{\"items\":[1]}"));
        all.put("analyze", j("{\"score\":0.9}"));
        all.put("report", j("{\"summary\":\"ok\"}"));
        JsonNode r = NopResultAggregator.aggregateEndNodes(List.of("report"), all, AggregateStrategy.MERGE);
        assertTrue(r.has("summary"));
        assertFalse(r.has("score"));
    }

    @Test void agg_endNodes_empty_object() {
        JsonNode r = NopResultAggregator.aggregateEndNodes(List.of("report"), Map.of(), AggregateStrategy.MERGE);
        assertTrue(r.isObject());
    }

    @Test void agg_unknownStrategy_fallsBackToMerge() {
        JsonNode a = NopResultAggregator.aggregate("unknown_strategy", List.of(j("{\"a\":1}"), j("{\"b\":2}")));
        assertTrue(a.has("a"));
        assertTrue(a.has("b"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Callback URL validation (https + SSRF guard)
    // ══════════════════════════════════════════════════════════════════════════

    @Test void cb_https_ok() { assertNull(NopCallbackValidator.validateCallbackUrl("https://cb.example.com/hook")); }
    @Test void cb_http_rejected() { assertNotNull(NopCallbackValidator.validateCallbackUrl("http://cb.example.com/hook")); }
    @Test void cb_empty_rejected() { assertNotNull(NopCallbackValidator.validateCallbackUrl("")); }
    @Test void cb_localhost_rejected() { assertNotNull(NopCallbackValidator.validateCallbackUrl("https://localhost/hook")); }
    @Test void cb_loopbackIp_rejected() { assertNotNull(NopCallbackValidator.validateCallbackUrl("https://127.0.0.1/hook")); }
    @Test void cb_privateIp10_rejected() { assertNotNull(NopCallbackValidator.validateCallbackUrl("https://10.1.2.3/hook")); }
    @Test void cb_privateIp192_rejected() { assertNotNull(NopCallbackValidator.validateCallbackUrl("https://192.168.0.5/hook")); }
    @Test void cb_privateIp172_rejected() { assertNotNull(NopCallbackValidator.validateCallbackUrl("https://172.16.5.5/hook")); }
    @Test void cb_publicIp_ok() { assertNull(NopCallbackValidator.validateCallbackUrl("https://8.8.8.8/hook")); }
    @Test void cb_ipv6Loopback_rejected() { assertNotNull(NopCallbackValidator.validateCallbackUrl("https://[::1]/hook")); }
}
