// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.orchestration;

import com.labacacia.nps.nop.models.DagEdge;
import com.labacacia.nps.nop.models.TaskDag;
import com.labacacia.nps.nop.models.TaskDagNode;
import com.labacacia.nps.nop.storage.InMemoryNopTaskStore;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Shared helpers for building orchestrators and DAGs in tests. */
final class OrchFixture {
    private OrchFixture() {}

    static NopOrchestrator orchestrator(FakeWorkerClient worker) {
        NopOrchestratorOptions opts = new NopOrchestratorOptions()
            .validateSenderNid(false)
            .enableCallback(false)
            .callbackRetryBaseDelayMs(0);
        return new NopOrchestrator(worker, new InMemoryNopTaskStore(), opts, null);
    }

    static TaskDagNode node(String id) {
        return TaskDagNode.of(id, "nwp://node/" + id, id);
    }

    static NopTask linear(String... ids) {
        List<TaskDagNode> nodes = new ArrayList<>();
        List<DagEdge> edges = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i];
            TaskDagNode.Builder b = new TaskDagNode.Builder(id, "nwp://node/" + id, id);
            if (i > 0) b.inputFrom(List.of(ids[i - 1]));
            nodes.add(b.build());
            if (i > 0) edges.add(new DagEdge(ids[i - 1], id));
        }
        return NopTask.of(UUID.randomUUID().toString(), new TaskDag(nodes, edges));
    }

    static NopTask fanIn(String[] sources, String sink, int minRequired) {
        List<TaskDagNode> nodes = new ArrayList<>();
        for (String s : sources) nodes.add(node(s));
        nodes.add(new TaskDagNode.Builder(sink, "nwp://node/" + sink, sink)
            .inputFrom(List.of(sources))
            .minRequired(minRequired)
            .build());
        List<DagEdge> edges = new ArrayList<>();
        for (String s : sources) edges.add(new DagEdge(s, sink));
        return NopTask.of(UUID.randomUUID().toString(), new TaskDag(nodes, edges));
    }
}
