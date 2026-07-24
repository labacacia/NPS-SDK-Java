// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.nop.CompensationPolicy;
import com.labacacia.nps.nop.NopConstants;
import com.labacacia.nps.nop.NopErrorCodes;
import com.labacacia.nps.nop.TaskState;
import com.labacacia.nps.nop.models.RetryPolicy;
import com.labacacia.nps.nop.models.StreamError;
import com.labacacia.nps.nop.models.TaskContext;
import com.labacacia.nps.nop.models.DagEdge;
import com.labacacia.nps.nop.models.TaskDagNode;
import com.labacacia.nps.nop.validation.DagValidationResult;
import com.labacacia.nps.nop.validation.DagValidator;
import com.labacacia.nps.nop.validation.NopCallbackValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Core NOP Orchestrator: accepts a {@link NopTask}, runs its DAG by dispatching
 * delegations to Worker Agents, handles retries, condition-based skipping, K-of-N
 * synchronisation, saga compensation, and result aggregation (NPS-5 §3, §5).
 */
public final class NopOrchestrator implements INopOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(NopOrchestrator.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final INopWorkerClient worker;
    private final INopTaskStore store;
    private final NopOrchestratorOptions opts;
    private final ExecutorService executor;
    private final HttpClient httpClient;

    /** Cancellation flags keyed by task_id — allows external cancel(). */
    private final Map<String, TaskCancellation> cancellations = new ConcurrentHashMap<>();

    public NopOrchestrator(INopWorkerClient worker, INopTaskStore store) {
        this(worker, store, new NopOrchestratorOptions(), null);
    }

    public NopOrchestrator(INopWorkerClient worker, INopTaskStore store,
                           NopOrchestratorOptions opts, ExecutorService executor) {
        this.worker = worker;
        this.store = store;
        this.opts = opts != null ? opts : new NopOrchestratorOptions();
        this.executor = executor != null ? executor : Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "nop-orchestrator");
            t.setDaemon(true);
            return t;
        });
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(this.opts.callbackTimeoutMs()))
            .build();
    }

    // ── INopOrchestrator ──────────────────────────────────────────────────────

    @Override
    public CompletableFuture<NopTaskResult> execute(NopTask task) {
        return CompletableFuture.supplyAsync(() -> executeSync(task), executor);
    }

    private NopTaskResult executeSync(NopTask task) {
        // 1a. Validate delegation chain depth
        if (task.delegateDepth() >= NopConstants.MAX_DELEGATE_CHAIN_DEPTH) {
            LOG.warn("Task {} rejected: delegation chain depth {} >= max {}",
                task.taskId(), task.delegateDepth(), NopConstants.MAX_DELEGATE_CHAIN_DEPTH);
            NopTelemetry.taskFailed();
            return NopTaskResult.failure(task.taskId(), NopErrorCodes.NOP_DELEGATE_CHAIN_TOO_DEEP,
                "Delegation chain depth " + task.delegateDepth()
                    + " exceeds the maximum of " + NopConstants.MAX_DELEGATE_CHAIN_DEPTH + ".");
        }

        // 1b. Validate callback_url (MUST https://, SHOULD not be private IP)
        if (task.callbackUrl() != null && !task.callbackUrl().isEmpty()) {
            String urlError = NopCallbackValidator.validateCallbackUrl(task.callbackUrl());
            if (urlError != null) {
                LOG.warn("Task {} rejected: invalid callback_url — {}", task.taskId(), urlError);
                return NopTaskResult.failure(task.taskId(), NopErrorCodes.NOP_TASK_DAG_INVALID, urlError);
            }
        }

        // 1c. Validate DAG
        DagValidationResult validation = DagValidator.validate(task.dag());
        if (!validation.valid()) {
            LOG.warn("DAG validation failed for task {}: {}", task.taskId(), validation.errorMessage());
            return NopTaskResult.failure(task.taskId(), validation.errorCode(), validation.errorMessage());
        }

        // 2. Reject already-known tasks
        if (store.get(task.taskId()) != null) {
            return NopTaskResult.failure(task.taskId(), NopErrorCodes.NOP_TASK_ALREADY_COMPLETED,
                "Task '" + task.taskId() + "' already exists.");
        }

        // 3. Persist initial record
        NopTaskRecord record = new NopTaskRecord(task.taskId(), task, TaskState.PENDING, Instant.now());
        store.save(record);

        // 4. Register cancellation with deadline
        long timeoutMs = Math.min(task.timeoutMs(), NopConstants.MAX_TIMEOUT_MS);
        TaskCancellation cancel = new TaskCancellation(System.currentTimeMillis() + timeoutMs);
        cancellations.put(task.taskId(), cancel);

        try {
            // 5. Optional preflight
            if (task.preflight()) {
                store.updateState(task.taskId(), TaskState.PREFLIGHT);
                String preflightFail = runPreflight(task);
                if (preflightFail != null) {
                    LOG.warn("Preflight failed for task {}: {}", task.taskId(), preflightFail);
                    store.updateState(task.taskId(), TaskState.FAILED);
                    return NopTaskResult.failure(task.taskId(), NopErrorCodes.NOP_RESOURCE_INSUFFICIENT, preflightFail);
                }
            }

            store.updateState(task.taskId(), TaskState.RUNNING);

            // 6. Execute DAG
            NopTaskResult result;
            try {
                result = runDag(task, validation.topologicalOrder(), cancel);
            } catch (TaskTimeoutException e) {
                LOG.warn("Task {} exceeded timeout of {}ms", task.taskId(), timeoutMs);
                store.updateState(task.taskId(), TaskState.FAILED);
                NopTelemetry.taskFailed();
                return NopTaskResult.failure(task.taskId(), NopErrorCodes.NOP_TASK_TIMEOUT,
                    "Task exceeded timeout of " + timeoutMs + "ms.");
            } catch (TaskCancelledException e) {
                LOG.warn("Task {} cancelled", task.taskId());
                store.updateState(task.taskId(), TaskState.CANCELLED);
                return NopTaskResult.cancelled(task.taskId(), "Task was cancelled.");
            }

            // 7. Finalise state in store
            record.completedAt(Instant.now());
            store.updateState(task.taskId(), result.finalState());

            // 8. Fire callback (fire-and-forget)
            if (opts.enableCallback() && task.callbackUrl() != null && !task.callbackUrl().isEmpty()) {
                CompletableFuture.runAsync(
                    () -> fireCallback(task.callbackUrl(), task.callbackSecret(), result), executor);
            }

            LOG.info("Task {} finished as {}", task.taskId(), result.finalState());
            if (result.finalState() == TaskState.COMPLETED) NopTelemetry.taskCompleted();
            else NopTelemetry.taskFailed();
            return result;
        } finally {
            cancellations.remove(task.taskId());
        }
    }

    @Override
    public void cancel(String taskId) {
        TaskCancellation c = cancellations.get(taskId);
        if (c != null) c.cancelled = true;
        store.updateState(taskId, TaskState.CANCELLED);
    }

    @Override
    public NopTaskRecord getStatus(String taskId) {
        return store.get(taskId);
    }

    // ── DAG execution ─────────────────────────────────────────────────────────

    private NopTaskResult runDag(NopTask task, List<String> topoOrder, TaskCancellation cancel) {
        Map<String, TaskDagNode> allNodes = new LinkedHashMap<>();
        for (TaskDagNode n : task.dag().nodes()) allNodes.put(n.id(), n);

        Map<String, JsonNode> nodeResults = new HashMap<>();   // completed only
        Map<String, TaskState> nodeStates = new HashMap<>();   // terminal state per node
        Map<String, CompletableFuture<NodeOutcome>> inFlight = new HashMap<>();

        List<DagEdge> edges = task.dag().edges() != null ? task.dag().edges() : List.of();
        Set<String> hasOutgoing = new HashSet<>();
        for (DagEdge e : edges) hasOutgoing.add(e.from());
        List<String> endNodeIds = new ArrayList<>();
        for (String id : allNodes.keySet()) if (!hasOutgoing.contains(id)) endNodeIds.add(id);

        while (nodeStates.size() < allNodes.size()) {
            cancel.check();

            // Find ready nodes (deps done, not started)
            List<TaskDagNode> readyNodes = new ArrayList<>();
            for (TaskDagNode n : allNodes.values()) {
                if (nodeStates.containsKey(n.id()) || inFlight.containsKey(n.id())) continue;
                if (areDepsDone(n, nodeStates)) readyNodes.add(n);
            }

            // K-of-N: fail nodes that can never satisfy K
            List<TaskDagNode> stillReady = new ArrayList<>();
            for (TaskDagNode n : readyNodes) {
                if (n.inputFrom() == null || n.inputFrom().isEmpty()) { stillReady.add(n); continue; }
                int total = n.inputFrom().size();
                int k = n.minRequired() > 0 ? n.minRequired() : total;
                int success = countDeps(n, nodeStates, true);
                if (success < k) {
                    LOG.debug("Node {} cannot satisfy K-of-N ({}/{})", n.id(), k, total);
                    nodeStates.put(n.id(), TaskState.FAILED);
                    store.updateSubtask(task.taskId(), n.id(), UUID.randomUUID().toString(),
                        TaskState.FAILED, null, NopErrorCodes.NOP_SYNC_DEPENDENCY_FAILED,
                        "Only " + success + "/" + k + " required dependencies succeeded.", 1);
                } else {
                    stillReady.add(n);
                }
            }

            // Launch ready nodes up to MaxConcurrentNodes
            for (TaskDagNode node : stillReady) {
                if (inFlight.size() >= opts.maxConcurrentNodes()) break;
                LOG.debug("Launching node {}", node.id());
                Map<String, JsonNode> ctxSnapshot = new HashMap<>(nodeResults);
                inFlight.put(node.id(),
                    CompletableFuture.supplyAsync(
                        () -> executeNodeWithRetry(task, node, ctxSnapshot, cancel), executor));
            }

            if (inFlight.isEmpty()) break; // stuck or finished

            // Wait for next completion
            CompletableFuture.anyOf(inFlight.values().toArray(new CompletableFuture[0])).join();

            String finishedNodeId = null;
            NodeOutcome outcome = null;
            for (Map.Entry<String, CompletableFuture<NodeOutcome>> e : inFlight.entrySet()) {
                if (e.getValue().isDone()) {
                    finishedNodeId = e.getKey();
                    try {
                        outcome = e.getValue().join();
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        if (cause instanceof TaskTimeoutException tt) throw tt;
                        if (cause instanceof TaskCancelledException tc) throw tc;
                        outcome = new NodeOutcome(TaskState.FAILED, null, NopErrorCodes.NOP_DELEGATE_REJECTED, cause.getMessage());
                    }
                    break;
                }
            }
            inFlight.remove(finishedNodeId);

            nodeStates.put(finishedNodeId, outcome.state());
            if (outcome.result() != null && outcome.state() == TaskState.COMPLETED) {
                nodeResults.put(finishedNodeId, outcome.result());
            }

            // If failure: abort only when an end node can no longer satisfy its K.
            if (outcome.state() == TaskState.FAILED) {
                final String fnid = finishedNodeId;
                boolean mustAbort = false;
                for (String e : endNodeIds) {
                    if (canReachEndNode(e, fnid, edges)
                        && !canEndNodeStillSucceed(e, allNodes, nodeStates)) {
                        mustAbort = true;
                        break;
                    }
                }
                if (mustAbort) {
                    LOG.warn("Node {} failed; end node(s) cannot recover — aborting task {}", fnid, task.taskId());
                    waitAndAbortInFlight(inFlight);
                    SagaCompensationResult comp = CompensationPolicy.runsOnFailure(task.compensationPolicy())
                        ? runSagaCompensation(task, allNodes, topoOrder, nodeResults, nodeStates)
                        : null;
                    String errorCode = compensationFailureErrorCode(task, comp);
                    if (errorCode == null) errorCode = NopErrorCodes.NOP_SYNC_DEPENDENCY_FAILED;
                    return NopTaskResult.failure(task.taskId(), errorCode,
                        "Node '" + fnid + "' failed: " + outcome.errorCode(), comp);
                }
            }
        }

        // All nodes done — check for end-node failures
        List<String> failedNodes = new ArrayList<>();
        for (Map.Entry<String, TaskState> e : nodeStates.entrySet()) {
            if (e.getValue() == TaskState.FAILED) failedNodes.add(e.getKey());
        }
        boolean endFailed = endNodeIds.stream().anyMatch(e -> nodeStates.get(e) == TaskState.FAILED);
        if (!failedNodes.isEmpty() && endFailed) {
            SagaCompensationResult comp = CompensationPolicy.runsOnFailure(task.compensationPolicy())
                ? runSagaCompensation(task, allNodes, topoOrder, nodeResults, nodeStates)
                : null;
            String errorCode = compensationFailureErrorCode(task, comp);
            if (errorCode == null) errorCode = NopErrorCodes.NOP_SYNC_DEPENDENCY_FAILED;
            return NopTaskResult.failure(task.taskId(), errorCode,
                "End node(s) failed: " + String.join(", ", failedNodes), comp);
        }

        // Aggregate end-node results
        JsonNode aggregated = NopResultAggregator.aggregateEndNodes(
            endNodeIds, nodeResults, opts.defaultAggregateStrategy());

        SagaCompensationResult successComp = CompensationPolicy.runsOnSuccess(task.compensationPolicy())
            ? runSagaCompensation(task, allNodes, topoOrder, nodeResults, nodeStates)
            : null;

        return NopTaskResult.success(task.taskId(), aggregated, nodeResults, successComp);
    }

    // ── Node execution + retry ────────────────────────────────────────────────

    private NodeOutcome executeNodeWithRetry(
        NopTask task, TaskDagNode node, Map<String, JsonNode> context, TaskCancellation cancel) {

        String subtaskId = UUID.randomUUID().toString();
        String idempotencyKey = UUID.randomUUID().toString(); // stable across retries
        int maxRetries = node.retryPolicy() != null ? node.retryPolicy().maxRetries() : task.maxRetries();

        for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
            cancel.check();

            // Evaluate condition once, before the first attempt
            if (attempt == 1 && node.condition() != null && !node.condition().isEmpty()) {
                try {
                    if (!NopConditionEvaluator.evaluate(node.condition(), context)) {
                        LOG.debug("Node {} skipped (condition=false)", node.id());
                        store.updateSubtask(task.taskId(), node.id(), subtaskId, TaskState.SKIPPED, null, null, null, 1);
                        return new NodeOutcome(TaskState.SKIPPED, null, null, null);
                    }
                } catch (NopConditionException ex) {
                    LOG.error("Condition evaluation error for node {}", node.id(), ex);
                    store.updateSubtask(task.taskId(), node.id(), subtaskId, TaskState.FAILED, null,
                        NopErrorCodes.NOP_CONDITION_EVAL_ERROR, ex.getMessage(), attempt);
                    return new NodeOutcome(TaskState.FAILED, null, NopErrorCodes.NOP_CONDITION_EVAL_ERROR, ex.getMessage());
                }
            }

            store.updateSubtask(task.taskId(), node.id(), subtaskId, TaskState.RUNNING, null, null, null, attempt);

            NodeOutcome outcome = executeNodeOnce(task, node, subtaskId, idempotencyKey, context, cancel);

            if (outcome.state() == TaskState.COMPLETED) {
                store.updateSubtask(task.taskId(), node.id(), subtaskId, TaskState.COMPLETED,
                    outcome.result(), null, null, attempt);
                NopTelemetry.recordNodeDuration(0, "success");
                return outcome;
            }

            boolean retriable = shouldRetry(node.retryPolicy(), outcome.errorCode(), attempt, maxRetries);
            if (!retriable) {
                LOG.warn("Node {} failed after {} attempt(s): {}", node.id(), attempt, outcome.errorCode());
                store.updateSubtask(task.taskId(), node.id(), subtaskId, TaskState.FAILED, null,
                    outcome.errorCode(), outcome.errorMessage(), attempt);
                NopTelemetry.recordNodeDuration(0, "failure");
                return outcome;
            }

            NopTelemetry.nodeRetry();
            long delayMs = node.retryPolicy() != null ? node.retryPolicy().computeDelayMs(attempt - 1) : 1000;
            LOG.debug("Node {} retrying in {}ms (attempt {}/{})", node.id(), delayMs, attempt, maxRetries + 1);
            cancel.sleep(delayMs);
        }

        store.updateSubtask(task.taskId(), node.id(), subtaskId, TaskState.FAILED, null,
            NopErrorCodes.NOP_DELEGATE_TIMEOUT, "Node '" + node.id() + "' exhausted " + maxRetries + " retries.", maxRetries + 1);
        NopTelemetry.recordNodeDuration(0, "exhausted");
        return new NodeOutcome(TaskState.FAILED, null, NopErrorCodes.NOP_DELEGATE_TIMEOUT, null);
    }

    private NodeOutcome executeNodeOnce(
        NopTask task, TaskDagNode node, String subtaskId, String idempotencyKey,
        Map<String, JsonNode> context, TaskCancellation cancel) {

        JsonNode resolvedParams;
        try {
            resolvedParams = NopInputMapper.buildParams(node.inputMapping(), context);
        } catch (NopMappingException ex) {
            return new NodeOutcome(TaskState.FAILED, null, ex.errorCode(), ex.getMessage());
        }

        long nodeTimeoutMs = node.timeoutMs() != null ? node.timeoutMs() : task.timeoutMs();
        long nodeDeadline = System.currentTimeMillis() + Math.min(nodeTimeoutMs, NopConstants.MAX_TIMEOUT_MS);
        String deadlineAt = DateTimeFormatter.ISO_INSTANT.format(
            Instant.ofEpochMilli(System.currentTimeMillis() + nodeTimeoutMs));

        TaskContext delegateCtx = task.context() != null ? task.context() : TaskContext.empty();

        NopDelegate delegate = new NopDelegate(
            task.taskId(), subtaskId, node.id(), node.agent(), node.action(),
            resolvedParams, deadlineAt, idempotencyKey, task.priority(),
            delegateCtx, task.delegateDepth() + 1);

        JsonNode finalResult = null;
        String errorCode = null;
        String errorMsg = null;
        long lastSeq = 0;
        boolean gotFinal = false;

        try (Stream<WorkerStreamFrame> stream = worker.delegate(delegate)) {
            var it = stream.iterator();
            while (it.hasNext()) {
                cancel.check();
                if (System.currentTimeMillis() > nodeDeadline) {
                    return new NodeOutcome(TaskState.FAILED, null, NopErrorCodes.NOP_DELEGATE_TIMEOUT,
                        "Node '" + node.id() + "' timed out after " + nodeTimeoutMs + "ms.");
                }

                WorkerStreamFrame frame = it.next();

                // Sequence gap check
                if (frame.seq() != lastSeq && frame.seq() != 0) {
                    if (frame.seq() != lastSeq + 1) {
                        LOG.warn("Node {}: seq gap {} → {}", node.id(), lastSeq, frame.seq());
                        return new NodeOutcome(TaskState.FAILED, null, NopErrorCodes.NOP_STREAM_SEQ_GAP, null);
                    }
                }
                lastSeq = frame.seq();

                // Sender NID validation
                if (opts.validateSenderNid() && !node.agent().equals(frame.senderNid())) {
                    LOG.warn("Node {}: sender_nid mismatch (expected {}, got {})",
                        node.id(), node.agent(), frame.senderNid());
                    return new NodeOutcome(TaskState.FAILED, null, NopErrorCodes.NOP_STREAM_NID_MISMATCH, null);
                }

                if (frame.isFinal()) {
                    gotFinal = true;
                    if (frame.error() != null) {
                        errorCode = frame.error().code();
                        errorMsg = frame.error().message();
                    } else {
                        finalResult = frame.data();
                    }
                    break;
                }
                LOG.trace("Node {} intermediate result seq={}", node.id(), frame.seq());
            }
        }

        if (!gotFinal) {
            return new NodeOutcome(TaskState.FAILED, null, NopErrorCodes.NOP_DELEGATE_TIMEOUT,
                "Stream ended without final frame.");
        }
        if (errorCode != null) {
            return new NodeOutcome(TaskState.FAILED, null, errorCode, errorMsg);
        }
        return new NodeOutcome(TaskState.COMPLETED, finalResult, null, null);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns true when a node's dependencies are in a terminal state that allows it to
     * either proceed (K-of-N satisfied) or be marked failed (impossible to satisfy K).
     */
    private static boolean areDepsDone(TaskDagNode node, Map<String, TaskState> states) {
        if (node.inputFrom() == null || node.inputFrom().isEmpty()) return true;

        int total = node.inputFrom().size();
        int k = node.minRequired() > 0 ? node.minRequired() : total;
        int success = countDeps(node, states, true);
        int failed = countDeps(node, states, false);

        if (success >= k) return true;         // K already satisfied
        if (total - failed < k) return true;   // Impossible to satisfy K
        return false;                          // Still waiting
    }

    /** Counts dependencies that succeeded (success=true) or failed (success=false). */
    private static int countDeps(TaskDagNode node, Map<String, TaskState> states, boolean success) {
        int count = 0;
        for (String d : node.inputFrom()) {
            TaskState s = states.get(d);
            if (s == null) continue;
            if (success) {
                if (s == TaskState.COMPLETED || s == TaskState.SKIPPED) count++;
            } else {
                if (s == TaskState.FAILED) count++;
            }
        }
        return count;
    }

    private String runPreflight(NopTask task) {
        // Deduplicate by agent NID (one probe per unique agent)
        Map<String, List<String>> byAgent = new LinkedHashMap<>();
        for (TaskDagNode n : task.dag().nodes()) {
            byAgent.computeIfAbsent(n.agent(), k -> new ArrayList<>());
            if (!byAgent.get(n.agent()).contains(n.action())) byAgent.get(n.agent()).add(n.action());
        }

        LOG.debug("Running preflight for task {} against {} agent(s)", task.taskId(), byAgent.size());

        List<CompletableFuture<PreflightResult>> probes = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : byAgent.entrySet()) {
            probes.add(CompletableFuture.supplyAsync(
                () -> worker.preflight(e.getKey(), e.getValue().get(0)), executor));
        }

        try {
            CompletableFuture.allOf(probes.toArray(new CompletableFuture[0])).join();
        } catch (Exception ex) {
            return "Preflight probe failed: " + ex.getMessage();
        }

        for (CompletableFuture<PreflightResult> p : probes) {
            PreflightResult r = p.join();
            if (!r.available()) {
                return "Agent '" + r.agentNid() + "' is unavailable: "
                    + (r.unavailableReason() != null ? r.unavailableReason() : "no reason given");
            }
        }
        LOG.debug("Preflight passed for task {}", task.taskId());
        return null;
    }

    private static boolean shouldRetry(RetryPolicy policy, String errorCode, int attempt, int maxRetries) {
        if (attempt > maxRetries) return false;
        if (policy != null && policy.retryOn() != null && !policy.retryOn().isEmpty() && errorCode != null) {
            return policy.retryOn().contains(errorCode);
        }
        return true;
    }

    /**
     * Returns true when end node can still complete successfully after a dependency
     * failure, considering K-of-N. Optimistic: non-failed deps are assumed to succeed.
     */
    private static boolean canEndNodeStillSucceed(
        String endNodeId, Map<String, TaskDagNode> allNodes, Map<String, TaskState> nodeStates) {

        TaskDagNode node = allNodes.get(endNodeId);
        if (node.inputFrom() == null || node.inputFrom().isEmpty()) return false;

        int total = node.inputFrom().size();
        int k = node.minRequired() > 0 ? node.minRequired() : total;
        int failed = countDeps(node, nodeStates, false);
        int optimistic = total - failed;
        return optimistic >= k;
    }

    private static boolean canReachEndNode(String endNodeId, String failedNodeId, List<DagEdge> edges) {
        Map<String, List<String>> adj = new HashMap<>();
        for (DagEdge e : edges) adj.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());

        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(failedNodeId);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (cur.equals(endNodeId)) return true;
            if (!visited.add(cur)) continue;
            List<String> ns = adj.get(cur);
            if (ns != null) queue.addAll(ns);
        }
        return false;
    }

    private static void waitAndAbortInFlight(Map<String, CompletableFuture<NodeOutcome>> inFlight) {
        try {
            CompletableFuture.allOf(inFlight.values().toArray(new CompletableFuture[0])).join();
        } catch (Exception ignore) {
            // already failed — ignore
        }
    }

    // ── Callback (HMAC-SHA256, exponential backoff) ─────────────────────────────

    private void fireCallback(String callbackUrl, String callbackSecret, NopTaskResult result) {
        String payload;
        try {
            payload = JSON.writeValueAsString(callbackPayload(result));
        } catch (Exception e) {
            LOG.warn("Failed to serialize callback payload — skipping callback.", e);
            return;
        }

        String signature = buildCallbackSignature(callbackSecret, payload);
        if (callbackSecret != null && !callbackSecret.isBlank() && signature == null) {
            LOG.warn("callback_secret is not a valid base64url-encoded 32-byte HMAC key; "
                + "callback will be sent without X-NPS-Signature.");
        }

        for (int attempt = 1; attempt <= NopConstants.CALLBACK_MAX_RETRIES; attempt++) {
            try {
                HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(callbackUrl))
                    .timeout(Duration.ofMillis(opts.callbackTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
                if (signature != null) req.header("X-NPS-Signature", signature);

                HttpResponse<Void> resp = httpClient.send(req.build(), HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    LOG.info("Callback to {} succeeded on attempt {} ({})", callbackUrl, attempt, resp.statusCode());
                    return;
                }
                LOG.warn("Callback to {} returned non-success {} (attempt {}/{})",
                    callbackUrl, resp.statusCode(), attempt, NopConstants.CALLBACK_MAX_RETRIES);
            } catch (Exception ex) {
                LOG.warn("Callback to {} failed with exception (attempt {}/{}): {}",
                    callbackUrl, attempt, NopConstants.CALLBACK_MAX_RETRIES, ex.getMessage());
            }

            if (attempt < NopConstants.CALLBACK_MAX_RETRIES && opts.callbackRetryBaseDelayMs() > 0) {
                long delayMs = (long) (opts.callbackRetryBaseDelayMs() * Math.pow(2, attempt - 1));
                try { Thread.sleep(delayMs); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        LOG.warn("Callback to {} gave up after {} attempt(s) — non-fatal.",
            callbackUrl, NopConstants.CALLBACK_MAX_RETRIES);
    }

    /** Builds the snake_case JSON payload for the callback (interop with .NET). */
    private static Map<String, Object> callbackPayload(NopTaskResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("task_id", r.taskId());
        m.put("final_state", r.finalState().value);
        m.put("aggregated_result", r.aggregatedResult());
        m.put("error_code", r.errorCode());
        m.put("error_message", r.errorMessage());
        m.put("node_results", r.nodeResults());
        return m;
    }

    static String buildCallbackSignature(String callbackSecret, String payload) {
        if (callbackSecret == null || callbackSecret.isBlank()) return null;

        byte[] key = tryDecodeBase64Url(callbackSecret);
        if (key == null || key.length != 32) return null;

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("sha256=");
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] tryDecodeBase64Url(String value) {
        try {
            return Base64.getUrlDecoder().decode(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ── Saga compensation ─────────────────────────────────────────────────────

    private SagaCompensationResult runSagaCompensation(
        NopTask task, Map<String, TaskDagNode> allNodes, List<String> topoOrder,
        Map<String, JsonNode> nodeResults, Map<String, TaskState> nodeStates) {

        // Completed nodes in reverse topo order
        List<String> completed = new ArrayList<>();
        for (String id : topoOrder) {
            if (nodeStates.get(id) == TaskState.COMPLETED && allNodes.containsKey(id)) completed.add(id);
        }
        java.util.Collections.reverse(completed);

        if (CompensationPolicy.isStrict(task.compensationPolicy())) {
            List<String> missing = new ArrayList<>();
            for (String id : completed) {
                String ca = allNodes.get(id).compensateAction();
                if (ca == null || ca.isBlank()) missing.add(id);
            }
            if (!missing.isEmpty()) {
                LOG.warn("Strict saga compensation for task {} cannot proceed; node(s) lack "
                    + "compensate_action: {}", task.taskId(), String.join(", ", missing));
                return new SagaCompensationResult(0, 0, missing.size(), missing);
            }
        }

        List<String> toCompensate = new ArrayList<>();
        for (String id : completed) {
            String ca = allNodes.get(id).compensateAction();
            if (ca != null && !ca.isBlank()) toCompensate.add(id);
        }

        if (toCompensate.isEmpty()) {
            return new SagaCompensationResult(0, 0, 0, List.of());
        }

        LOG.info("Saga compensation: {} node(s) to compensate for task {}", toCompensate.size(), task.taskId());
        store.updateState(task.taskId(), TaskState.COMPENSATING);

        int succeeded = 0;
        List<String> failedIds = new ArrayList<>();
        TaskCancellation noCancel = new TaskCancellation(Long.MAX_VALUE);

        for (String nodeId : toCompensate) {
            TaskDagNode node = allNodes.get(nodeId);
            TaskDagNode compensationNode = node.toBuilder()
                .action(node.compensateAction())
                .inputMapping(node.compensateParamsMapping())
                .build();

            NodeOutcome outcome = executeNodeOnce(
                task, compensationNode, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), nodeResults, noCancel);

            if (outcome.state() == TaskState.COMPLETED) {
                succeeded++;
                LOG.info("Compensation for node {} succeeded", nodeId);
            } else {
                failedIds.add(nodeId);
                LOG.warn("Compensation for node {} failed: {} — {}", nodeId, outcome.errorCode(), outcome.errorMessage());
            }
        }

        store.updateState(task.taskId(), TaskState.COMPENSATED);
        int failed = failedIds.size();
        if (failed == 0) LOG.info("Saga compensation for task {} complete ({} succeeded)", task.taskId(), succeeded);
        else LOG.warn("Saga compensation for task {}: {}/{} failed", task.taskId(), failed, toCompensate.size());

        return new SagaCompensationResult(toCompensate.size(), succeeded, failed, failedIds);
    }

    private static String compensationFailureErrorCode(NopTask task, SagaCompensationResult comp) {
        if (!CompensationPolicy.isStrict(task.compensationPolicy()) || comp == null || comp.failed() <= 0) {
            return null;
        }
        return comp.attempted() == 0
            ? NopErrorCodes.NOP_COMPENSATION_NOT_SUPPORTED
            : NopErrorCodes.NOP_COMPENSATION_FAILED;
    }

    // A synthetic node with an action override is created via TaskDagNode#toBuilder above.

    // ── Inner types ───────────────────────────────────────────────────────────

    private record NodeOutcome(TaskState state, JsonNode result, String errorCode, String errorMessage) {}

    /** Cooperative cancellation + deadline handle for a single task run. */
    private static final class TaskCancellation {
        volatile boolean cancelled;
        final long deadlineEpochMs;

        TaskCancellation(long deadlineEpochMs) {
            this.deadlineEpochMs = deadlineEpochMs;
        }

        void check() {
            if (cancelled) throw new TaskCancelledException();
            if (System.currentTimeMillis() > deadlineEpochMs) throw new TaskTimeoutException();
        }

        /** Sleeps up to {@code ms}, waking early to honour cancellation / deadline. */
        void sleep(long ms) {
            long end = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < end) {
                check();
                try { Thread.sleep(Math.min(50, end - System.currentTimeMillis())); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private static final class TaskTimeoutException extends RuntimeException {}
    private static final class TaskCancelledException extends RuntimeException {}
}
