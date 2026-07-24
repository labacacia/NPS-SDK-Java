// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.labacacia.nps.nop.NopErrorCodes;
import com.labacacia.nps.nop.CompensationPolicy;
import com.labacacia.nps.nop.TaskState;
import com.labacacia.nps.nop.models.DagEdge;
import com.labacacia.nps.nop.models.RetryPolicy;
import com.labacacia.nps.nop.models.StreamError;
import com.labacacia.nps.nop.models.TaskDag;
import com.labacacia.nps.nop.models.TaskDagNode;
import com.labacacia.nps.nop.storage.InMemoryNopTaskStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Linear/diamond/K-of-N execution, retry, saga compensation. */
class NopOrchestratorTests {

    // ── Linear ──────────────────────────────────────────────────────────────

    @Test void linear_allComplete() {
        var worker = new FakeWorkerClient();
        worker.setupSuccess("a", "{\"a\":1}");
        worker.setupSuccess("b", "{\"b\":2}");
        worker.setupSuccess("c", "{\"c\":3}");
        var orch = OrchFixture.orchestrator(worker);

        var result = orch.execute(OrchFixture.linear("a", "b", "c")).join();

        assertEquals(TaskState.COMPLETED, result.finalState());
        assertEquals(3, result.nodeResults().size());
        assertEquals(3, result.aggregatedResult().get("c").asInt());
    }

    // ── Diamond ─────────────────────────────────────────────────────────────

    @Test void diamond_allComplete() {
        var worker = new FakeWorkerClient();
        worker.setupSuccess("root", "{\"root\":1}");
        worker.setupSuccess("left", "{\"left\":2}");
        worker.setupSuccess("right", "{\"right\":3}");
        worker.setupSuccess("sink", "{\"sink\":4}");
        var orch = OrchFixture.orchestrator(worker);

        var nodes = List.of(
            OrchFixture.node("root"),
            new TaskDagNode.Builder("left", "nwp://node/left", "left").inputFrom(List.of("root")).build(),
            new TaskDagNode.Builder("right", "nwp://node/right", "right").inputFrom(List.of("root")).build(),
            new TaskDagNode.Builder("sink", "nwp://node/sink", "sink").inputFrom(List.of("left", "right")).build());
        var edges = List.of(new DagEdge("root", "left"), new DagEdge("root", "right"),
            new DagEdge("left", "sink"), new DagEdge("right", "sink"));
        var task = NopTask.of(UUID.randomUUID().toString(), new TaskDag(nodes, edges));

        var result = orch.execute(task).join();

        assertEquals(TaskState.COMPLETED, result.finalState());
        assertEquals(4, result.nodeResults().size());
        assertEquals(4, result.aggregatedResult().get("sink").asInt());
    }

    // ── Input mapping data flow ─────────────────────────────────────────────

    @Test void inputMapping_passesUpstreamData() {
        var worker = new FakeWorkerClient();
        worker.setupSuccess("src", "{\"value\":42}");
        AtomicReference<JsonNode> seenParams = new AtomicReference<>();
        worker.setupHandler("dst", d -> {
            seenParams.set(d.params());
            return WorkerStreamFrame.finalData(0, "dst", FakeWorkerClient.parse("{\"ok\":true}"));
        });
        var orch = OrchFixture.orchestrator(worker);

        var dst = new TaskDagNode.Builder("dst", "nwp://node/dst", "dst")
            .inputFrom(List.of("src"))
            .inputMapping(java.util.Map.of("v", FakeWorkerClient.parse("\"$.src.value\"")))
            .build();
        var task = NopTask.of(UUID.randomUUID().toString(), new TaskDag(
            List.of(OrchFixture.node("src"), dst), List.of(new DagEdge("src", "dst"))));

        var result = orch.execute(task).join();
        assertEquals(TaskState.COMPLETED, result.finalState());
        assertEquals(42, seenParams.get().get("v").asInt());
    }

    // ── Condition skip ──────────────────────────────────────────────────────

    @Test void condition_false_skipsNode() {
        var worker = new FakeWorkerClient();
        worker.setupSuccess("gate", "{\"score\":0.1}");
        worker.setupSuccess("act", "{\"acted\":true}");
        var orch = OrchFixture.orchestrator(worker);

        var act = new TaskDagNode.Builder("act", "nwp://node/act", "act")
            .inputFrom(List.of("gate"))
            .condition("$.gate.score > 0.5")
            .build();
        var task = NopTask.of(UUID.randomUUID().toString(), new TaskDag(
            List.of(OrchFixture.node("gate"), act), List.of(new DagEdge("gate", "act"))));

        var result = orch.execute(task).join();
        // gate completes; act skipped → task completes; act not in node results
        assertEquals(TaskState.COMPLETED, result.finalState());
        assertFalse(result.nodeResults().containsKey("act"));
    }

    // ── K-of-N ──────────────────────────────────────────────────────────────

    @Test void kOfN_enoughSucceed_completes() {
        var worker = new FakeWorkerClient();
        worker.setupSuccess("s1", "{\"v\":1}");
        worker.setupSuccess("s2", "{\"v\":2}");
        worker.setupFailure("s3", "S3-FAIL");
        worker.setupSuccess("sink", "{\"sink\":true}");
        var orch = OrchFixture.orchestrator(worker);

        // 2-of-3 required
        var result = orch.execute(OrchFixture.fanIn(new String[]{"s1", "s2", "s3"}, "sink", 2)).join();
        assertEquals(TaskState.COMPLETED, result.finalState());
    }

    @Test void kOfN_notEnough_fails() {
        var worker = new FakeWorkerClient();
        worker.setupSuccess("s1", "{\"v\":1}");
        worker.setupFailure("s2", "S2-FAIL");
        worker.setupFailure("s3", "S3-FAIL");
        worker.setupSuccess("sink", "{\"sink\":true}");
        var orch = OrchFixture.orchestrator(worker);

        // 2-of-3 required, only 1 succeeds → sink cannot satisfy K → abort
        var result = orch.execute(OrchFixture.fanIn(new String[]{"s1", "s2", "s3"}, "sink", 2)).join();
        assertEquals(TaskState.FAILED, result.finalState());
        assertEquals(NopErrorCodes.NOP_SYNC_DEPENDENCY_FAILED, result.errorCode());
    }

    // ── Retry ───────────────────────────────────────────────────────────────

    @Test void retry_transientFailure_thenSucceeds() {
        var worker = new FakeWorkerClient();
        worker.setupFailFirstN("a", 2, "TRANSIENT", "{\"ok\":true}");
        var opts = new NopOrchestratorOptions().validateSenderNid(false).enableCallback(false);
        var orch = new NopOrchestrator(worker, new InMemoryNopTaskStore(), opts, null);

        var node = new TaskDagNode.Builder("a", "nwp://node/a", "a")
            .retryPolicy(new RetryPolicy(3, "fixed", 1, 1, null))
            .build();
        var task = NopTask.of(UUID.randomUUID().toString(), new TaskDag(List.of(node), List.of()));

        var result = orch.execute(task).join();
        assertEquals(TaskState.COMPLETED, result.finalState());
        assertEquals(3, worker.attemptCount("a")); // 2 fails + 1 success
    }

    @Test void retry_retryOnFilter_nonMatching_noRetry() {
        var worker = new FakeWorkerClient();
        worker.setupFailure("a", "OTHER-ERROR");
        var opts = new NopOrchestratorOptions().validateSenderNid(false).enableCallback(false);
        var orch = new NopOrchestrator(worker, new InMemoryNopTaskStore(), opts, null);

        // retryOn only lists a different code → no retry
        var node = new TaskDagNode.Builder("a", "nwp://node/a", "a")
            .retryPolicy(new RetryPolicy(3, "fixed", 1, 1, List.of("TRANSIENT")))
            .build();
        var task = NopTask.of(UUID.randomUUID().toString(), new TaskDag(List.of(node), List.of()));

        var result = orch.execute(task).join();
        assertEquals(TaskState.FAILED, result.finalState());
        assertEquals(1, worker.attemptCount("a"));
    }

    // ── Saga compensation ───────────────────────────────────────────────────

    @Test void saga_bestEffort_compensatesCompletedPredecessor() {
        var worker = new FakeWorkerClient();
        AtomicInteger refundCalls = new AtomicInteger();
        AtomicReference<JsonNode> refundParams = new AtomicReference<>();
        worker.setupHandler("charge", d -> {
            if ("nwp://payments/refund".equals(d.action())) {
                refundCalls.incrementAndGet();
                refundParams.set(d.params());
                return WorkerStreamFrame.finalData(0, "charge", FakeWorkerClient.parse("{\"refunded\":true}"));
            }
            return WorkerStreamFrame.finalData(0, "charge",
                FakeWorkerClient.parse("{\"charge_id\":\"ch_1\",\"amount\":25}"));
        });
        worker.setupFailure("ship", "SHIP-FAILED");
        var orch = OrchFixture.orchestrator(worker);

        var charge = new TaskDagNode.Builder("charge", "nwp://payments/charge", "charge")
            .compensateAction("nwp://payments/refund")
            .compensateParamsMapping(java.util.Map.of("charge_id", FakeWorkerClient.parse("\"$.charge.charge_id\"")))
            .build();
        var ship = new TaskDagNode.Builder("ship", "nwp://shipping/ship", "ship")
            .inputFrom(List.of("charge")).build();
        var task = NopTask.of(UUID.randomUUID().toString(),
            new TaskDag(List.of(charge, ship), List.of(new DagEdge("charge", "ship"))));

        var result = orch.execute(task).join();

        assertEquals(TaskState.FAILED, result.finalState());
        assertEquals(1, refundCalls.get());
        assertNotNull(result.compensation());
        assertEquals(1, result.compensation().attempted());
        assertEquals(1, result.compensation().succeeded());
        assertEquals("ch_1", refundParams.get().get("charge_id").asText());
    }

    @Test void saga_strict_missingCompensateAction_notSupported() {
        var worker = new FakeWorkerClient();
        worker.setupSuccess("charge", "{\"charge_id\":\"ch_1\"}");
        worker.setupFailure("ship", "SHIP-FAILED");
        var orch = OrchFixture.orchestrator(worker);

        var charge = OrchFixture.node("charge");
        var ship = new TaskDagNode.Builder("ship", "nwp://shipping/ship", "ship")
            .inputFrom(List.of("charge")).build();
        var task = new NopTask.Builder(UUID.randomUUID().toString(),
            new TaskDag(List.of(charge, ship), List.of(new DagEdge("charge", "ship"))))
            .compensationPolicy(CompensationPolicy.STRICT)
            .build();

        var result = orch.execute(task).join();

        assertEquals(TaskState.FAILED, result.finalState());
        assertEquals(NopErrorCodes.NOP_COMPENSATION_NOT_SUPPORTED, result.errorCode());
        assertNotNull(result.compensation());
        assertEquals(0, result.compensation().attempted());
        assertEquals(1, result.compensation().failed());
        assertEquals(List.of("charge"), result.compensation().failedNodeIds());
    }

    // ── Validation rejections ───────────────────────────────────────────────

    @Test void reject_duplicateTask() {
        var worker = new FakeWorkerClient();
        worker.setupSuccess("a", "{}");
        var store = new InMemoryNopTaskStore();
        var opts = new NopOrchestratorOptions().validateSenderNid(false).enableCallback(false);
        var orch = new NopOrchestrator(worker, store, opts, null);

        String id = UUID.randomUUID().toString();
        var task = NopTask.of(id, new TaskDag(List.of(OrchFixture.node("a")), List.of()));
        assertEquals(TaskState.COMPLETED, orch.execute(task).join().finalState());

        var dup = NopTask.of(id, new TaskDag(List.of(OrchFixture.node("a")), List.of()));
        var result = orch.execute(dup).join();
        assertEquals(NopErrorCodes.NOP_TASK_ALREADY_COMPLETED, result.errorCode());
    }

    @Test void reject_delegateDepthTooDeep() {
        var worker = new FakeWorkerClient();
        var orch = OrchFixture.orchestrator(worker);
        var task = new NopTask.Builder(UUID.randomUUID().toString(),
            new TaskDag(List.of(OrchFixture.node("a")), List.of()))
            .delegateDepth(3).build();
        var result = orch.execute(task).join();
        assertEquals(NopErrorCodes.NOP_DELEGATE_CHAIN_TOO_DEEP, result.errorCode());
    }

    @Test void reject_invalidCallbackUrl() {
        var worker = new FakeWorkerClient();
        var orch = OrchFixture.orchestrator(worker);
        var task = new NopTask.Builder(UUID.randomUUID().toString(),
            new TaskDag(List.of(OrchFixture.node("a")), List.of()))
            .callbackUrl("http://insecure.example.com/hook").build();
        var result = orch.execute(task).join();
        assertEquals(NopErrorCodes.NOP_TASK_DAG_INVALID, result.errorCode());
    }

    // ── Sender NID validation ───────────────────────────────────────────────

    @Test void senderNidMismatch_fails() {
        var worker = new FakeWorkerClient();
        worker.setupHandler("a", d ->
            WorkerStreamFrame.finalData(0, "wrong-nid", FakeWorkerClient.parse("{}")));
        // enable validation
        var store = new InMemoryNopTaskStore();
        var opts = new NopOrchestratorOptions().validateSenderNid(true).enableCallback(false);
        var orch = new NopOrchestrator(worker, store, opts, null);
        String id = UUID.randomUUID().toString();
        var task = NopTask.of(id, new TaskDag(List.of(OrchFixture.node("a")), List.of()));

        var result = orch.execute(task).join();
        assertEquals(TaskState.FAILED, result.finalState());
        // Task-level code for an end-node failure is SYNC-DEPENDENCY-FAILED (mirrors .NET);
        // the node's own mismatch code is recorded on its subtask record.
        assertEquals(NopErrorCodes.NOP_SYNC_DEPENDENCY_FAILED, result.errorCode());
        assertEquals(NopErrorCodes.NOP_STREAM_NID_MISMATCH,
            store.get(id).subtasks().get("a").errorCode());
    }

    // ── Preflight ───────────────────────────────────────────────────────────

    @Test void preflight_unavailable_failsResourceInsufficient() {
        var worker = new FakeWorkerClient();
        worker.preflightAvailable = false;
        worker.preflightUnavailableReason = "busy";
        var orch = OrchFixture.orchestrator(worker);
        var task = new NopTask.Builder(UUID.randomUUID().toString(),
            new TaskDag(List.of(OrchFixture.node("a")), List.of()))
            .preflight(true).build();

        var result = orch.execute(task).join();
        assertEquals(NopErrorCodes.NOP_RESOURCE_INSUFFICIENT, result.errorCode());
    }

    @Test void finalErrorFrame_propagatesFailure() {
        var worker = new FakeWorkerClient();
        worker.setupHandler("a", d -> WorkerStreamFrame.finalError(0, "a",
            new StreamError("NOP-CUSTOM", "boom", false)));
        var orch = OrchFixture.orchestrator(worker);
        var task = NopTask.of(UUID.randomUUID().toString(),
            new TaskDag(List.of(OrchFixture.node("a")), List.of()));
        var result = orch.execute(task).join();
        assertEquals(TaskState.FAILED, result.finalState());
    }
}
