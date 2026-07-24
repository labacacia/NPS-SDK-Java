// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.nop.models.StreamError;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * In-memory fake {@link INopWorkerClient} for orchestrator tests. Node behaviour is keyed
 * by DAG node ID (which the fixtures also use as the agent NID).
 */
final class FakeWorkerClient implements INopWorkerClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, String> successJson = new ConcurrentHashMap<>();
    private final Map<String, String> failureCode = new ConcurrentHashMap<>();
    private final Map<String, Function<NopDelegate, WorkerStreamFrame>> handlers = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> failFirstN = new ConcurrentHashMap<>();
    private final Map<String, Integer> attempts = new ConcurrentHashMap<>();

    boolean preflightAvailable = true;
    String preflightUnavailableReason;

    void setupSuccess(String nodeId, String json) { successJson.put(nodeId, json); }
    void setupFailure(String nodeId, String code)  { failureCode.put(nodeId, code); }
    void setupHandler(String nodeId, Function<NopDelegate, WorkerStreamFrame> h) { handlers.put(nodeId, h); }

    /** Fail the first {@code n} attempts of {@code nodeId} with {@code code}, then succeed with {@code json}. */
    void setupFailFirstN(String nodeId, int n, String code, String json) {
        failFirstN.put(nodeId, new AtomicInteger(n));
        failureCode.put(nodeId, code);
        successJson.put(nodeId, json);
    }

    int attemptCount(String nodeId) { return attempts.getOrDefault(nodeId, 0); }

    static JsonNode parse(String json) {
        try { return JSON.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Override
    public Stream<WorkerStreamFrame> delegate(NopDelegate delegate) {
        String nodeId = delegate.nodeId();
        attempts.merge(nodeId, 1, Integer::sum);

        Function<NopDelegate, WorkerStreamFrame> handler = handlers.get(nodeId);
        if (handler != null) {
            return Stream.of(handler.apply(delegate));
        }

        AtomicInteger remaining = failFirstN.get(nodeId);
        if (remaining != null && remaining.getAndDecrement() > 0) {
            return Stream.of(WorkerStreamFrame.finalError(0, nodeId,
                new StreamError(failureCode.getOrDefault(nodeId, "ERR"), "transient", true)));
        }

        if (failureCode.containsKey(nodeId) && (remaining == null)) {
            return Stream.of(WorkerStreamFrame.finalError(0, nodeId,
                new StreamError(failureCode.get(nodeId), "fail", false)));
        }

        String json = successJson.getOrDefault(nodeId, "{}");
        return Stream.of(WorkerStreamFrame.finalData(0, nodeId, parse(json)));
    }

    @Override
    public PreflightResult preflight(String agentNid, String action, long estimatedNpt, List<String> caps) {
        return preflightAvailable
            ? PreflightResult.available(agentNid)
            : PreflightResult.unavailable(agentNid, preflightUnavailableReason);
    }
}
