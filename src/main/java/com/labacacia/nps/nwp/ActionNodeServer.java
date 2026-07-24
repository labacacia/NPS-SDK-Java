// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Action Node server using the JDK built-in {@link HttpHandler}
 * (com.sun.net.httpserver, zero third-party deps). Port of the .NET
 * {@code ActionNodeMiddleware} wire contract (NPS-2 §3.2, §7).
 *
 * <p>Sub-paths: {@code /.nwm}, {@code /.schema}, {@code /actions}, {@code /invoke}.
 * Built-in reserved actions {@code system.task.status} and {@code system.task.cancel}
 * are handled by the server and MUST NOT appear in {@link Options#actions}.</p>
 */
public final class ActionNodeServer implements HttpHandler {

    /** Reserved action id for polling async task state (NPS-2 §7.3). */
    public static final String SYSTEM_TASK_STATUS = "system.task.status";
    /** Reserved action id for cancelling a running async task (NPS-2 §7.3). */
    public static final String SYSTEM_TASK_CANCEL = "system.task.cancel";

    private static final ObjectMapper MAPPER =
        new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private static final SecureRandom RNG = new SecureRandom();

    // ── Nested types ─────────────────────────────────────────────────────────────

    /** NWM action registry entry (NPS-2 §4.6). */
    public static final class ActionSpec {
        public String description;
        public String paramsAnchor;
        public String resultAnchor;
        public boolean async;
        public Boolean idempotent;
        public Integer timeoutMsDefault;
        public Integer timeoutMsMax;
        public String requiredCapability;

        public ActionSpec() {}

        public ActionSpec withResultAnchor(String ra) { this.resultAnchor = ra; return this; }
        public ActionSpec withAsync(boolean a) { this.async = a; return this; }
    }

    /** Configuration for a single Action Node (NPS-2 §2.1, §7). */
    public static final class Options {
        public String nodeId;
        public String displayName;
        public Map<String, ActionSpec> actions = new LinkedHashMap<>();
        public String pathPrefix;
        public boolean requireAuth = false;
        public int defaultTimeoutMs = 5_000;
        public int maxTimeoutMs = 300_000;
        /** Idempotency cache TTL. Default 24 h (NPS-2 §7.1). */
        public Duration idempotencyTtl = Duration.ofHours(24);
        /** Completed/failed/cancelled task retention. Default 1 h. */
        public Duration taskRetention = Duration.ofHours(1);
        public boolean rejectPrivateCallbackUrls = true;
        public int defaultTokenBudget = 0;
    }

    /** Result of a single action execution. */
    public static final class ActionExecutionResult {
        public Object result;      // nullable
        public String anchorRef;   // nullable
        public int tokenEst;       // 0 = unknown

        public ActionExecutionResult() {}
        public ActionExecutionResult(Object result) { this.result = result; }
        public ActionExecutionResult(Object result, String anchorRef, int tokenEst) {
            this.result = result; this.anchorRef = anchorRef; this.tokenEst = tokenEst;
        }
    }

    /** Execution context passed to the provider. */
    public record ActionContext(String agentNid, String requestId, String taskId,
                                ActionSpec spec, int timeoutMs, String priority) {}

    /** Provider seam — implement to expose the declared actions. */
    @FunctionalInterface
    public interface Provider {
        ActionExecutionResult execute(ActionFrame frame, ActionContext context) throws Exception;
    }

    // ── Task store ───────────────────────────────────────────────────────────────

    /** Record of a single asynchronous action task (state machine NPS-2 §7.2). */
    public static final class ActionTaskRecord {
        public String taskId;
        public String actionId;
        public volatile String status;   // pending | running | completed | failed | cancelled
        public volatile Double progress;
        public Instant createdAt;
        public volatile Instant updatedAt;
        public String requestId;
        public String agentNid;
        public volatile Object result;
        public volatile Object error;
    }

    /** Storage for asynchronous action task state. */
    public interface TaskStore {
        ActionTaskRecord create(String taskId, String actionId, String requestId, String agentNid);
        ActionTaskRecord get(String taskId);
        boolean tryTransition(String taskId, String expectedStatus, String newStatus);
        boolean complete(String taskId, Object result);
        boolean fail(String taskId, Object error);
        boolean cancel(String taskId);
        boolean updateProgress(String taskId, double progress);
        int purgeExpired(Duration retention, Instant now);
    }

    /** Thread-safe, in-process task store. */
    public static final class InMemoryTaskStore implements TaskStore {
        private final ConcurrentHashMap<String, ActionTaskRecord> tasks = new ConcurrentHashMap<>();
        private final java.util.function.Supplier<Instant> clock;

        public InMemoryTaskStore() { this(Instant::now); }
        public InMemoryTaskStore(java.util.function.Supplier<Instant> clock) { this.clock = clock; }

        @Override public ActionTaskRecord create(String taskId, String actionId, String requestId, String agentNid) {
            Instant now = clock.get();
            ActionTaskRecord rec = new ActionTaskRecord();
            rec.taskId = taskId;
            rec.actionId = actionId;
            rec.status = "pending";
            rec.createdAt = now;
            rec.updatedAt = now;
            rec.requestId = requestId;
            rec.agentNid = agentNid;
            if (tasks.putIfAbsent(taskId, rec) != null)
                throw new IllegalStateException("Task id collision: " + taskId);
            return rec;
        }

        @Override public ActionTaskRecord get(String taskId) { return tasks.get(taskId); }

        @Override public boolean tryTransition(String taskId, String expectedStatus, String newStatus) {
            ActionTaskRecord rec = tasks.get(taskId);
            if (rec == null) return false;
            synchronized (rec) {
                if (!rec.status.equals(expectedStatus)) return false;
                rec.status = newStatus;
                rec.updatedAt = clock.get();
                return true;
            }
        }

        @Override public boolean complete(String taskId, Object result) {
            ActionTaskRecord rec = tasks.get(taskId);
            if (rec == null) return false;
            synchronized (rec) {
                if (isTerminal(rec.status)) return false;
                rec.status = "completed";
                rec.result = result;
                rec.progress = 1.0;
                rec.updatedAt = clock.get();
                return true;
            }
        }

        @Override public boolean fail(String taskId, Object error) {
            ActionTaskRecord rec = tasks.get(taskId);
            if (rec == null) return false;
            synchronized (rec) {
                if (isTerminal(rec.status)) return false;
                rec.status = "failed";
                rec.error = error;
                rec.updatedAt = clock.get();
                return true;
            }
        }

        @Override public boolean cancel(String taskId) {
            ActionTaskRecord rec = tasks.get(taskId);
            if (rec == null) return false;
            synchronized (rec) {
                if (isTerminal(rec.status)) return false;
                rec.status = "cancelled";
                rec.updatedAt = clock.get();
                return true;
            }
        }

        @Override public boolean updateProgress(String taskId, double progress) {
            ActionTaskRecord rec = tasks.get(taskId);
            if (rec == null) return false;
            synchronized (rec) {
                rec.progress = Math.max(0.0, Math.min(1.0, progress));
                rec.updatedAt = clock.get();
                return true;
            }
        }

        @Override public int purgeExpired(Duration retention, Instant now) {
            Instant cutoff = now.minus(retention);
            int purged = 0;
            for (Map.Entry<String, ActionTaskRecord> e : tasks.entrySet()) {
                ActionTaskRecord rec = e.getValue();
                if (isTerminal(rec.status) && rec.updatedAt.isBefore(cutoff)) {
                    if (tasks.remove(e.getKey()) != null) purged++;
                }
            }
            return purged;
        }

        private static boolean isTerminal(String s) {
            return "completed".equals(s) || "failed".equals(s) || "cancelled".equals(s);
        }
    }

    // ── Idempotency cache ──────────────────────────────────────────────────────────

    /** A cached idempotent response (NPS-2 §7.1). */
    public static final class IdempotentEntry {
        public String actionId;
        public String paramsHash;
        public Object result;     // nullable
        public String anchorRef;  // nullable
        public String taskId;     // nullable (async)
        public Instant expiresAt;
    }

    /** Cache of idempotent action results keyed by (action_id, idempotency_key). */
    public interface IdempotencyCache {
        IdempotentEntry get(String actionId, String idempotencyKey);
        boolean tryStore(String actionId, String idempotencyKey, IdempotentEntry entry);
        int purgeExpired(Instant now);
    }

    /** In-process idempotency cache with TTL. */
    public static final class InMemoryIdempotencyCache implements IdempotencyCache {
        private final ConcurrentHashMap<String, IdempotentEntry> entries = new ConcurrentHashMap<>();
        private final java.util.function.Supplier<Instant> clock;

        public InMemoryIdempotencyCache() { this(Instant::now); }
        public InMemoryIdempotencyCache(java.util.function.Supplier<Instant> clock) { this.clock = clock; }

        private static String key(String actionId, String idempotencyKey) {
            return actionId + "" + idempotencyKey;
        }

        @Override public IdempotentEntry get(String actionId, String idempotencyKey) {
            String k = key(actionId, idempotencyKey);
            IdempotentEntry entry = entries.get(k);
            if (entry == null) return null;
            if (!entry.expiresAt.isAfter(clock.get())) {
                entries.remove(k, entry);
                return null;
            }
            return entry;
        }

        @Override public boolean tryStore(String actionId, String idempotencyKey, IdempotentEntry entry) {
            String k = key(actionId, idempotencyKey);
            while (true) {
                Instant now = clock.get();
                IdempotentEntry existing = entries.get(k);
                if (existing != null) {
                    if (!existing.expiresAt.isAfter(now)) {
                        entries.remove(k, existing);
                        continue;
                    }
                    return existing.paramsHash.equals(entry.paramsHash);
                }
                if (entries.putIfAbsent(k, entry) == null) return true;
            }
        }

        @Override public int purgeExpired(Instant now) {
            int purged = 0;
            for (Map.Entry<String, IdempotentEntry> e : entries.entrySet()) {
                if (!e.getValue().expiresAt.isAfter(now) && entries.remove(e.getKey(), e.getValue())) purged++;
            }
            return purged;
        }
    }

    // ── State ────────────────────────────────────────────────────────────────────

    private final Options opt;
    private final String prefix;
    private final Provider provider;
    private final TaskStore taskStore;
    private final IdempotencyCache idempotency;
    private final ExecutorService taskExecutor;
    private final java.util.function.Supplier<Instant> clock;
    private final byte[] nwmJson;
    private final byte[] actionsJson;

    public ActionNodeServer(Options opt, Provider provider) {
        this(opt, provider, new InMemoryTaskStore(), new InMemoryIdempotencyCache(),
             Executors.newCachedThreadPool(), Instant::now);
    }

    public ActionNodeServer(Options opt, Provider provider, TaskStore taskStore,
                            IdempotencyCache idempotency, ExecutorService taskExecutor,
                            java.util.function.Supplier<Instant> clock) {
        if (opt.actions.containsKey(SYSTEM_TASK_STATUS) || opt.actions.containsKey(SYSTEM_TASK_CANCEL)) {
            throw new IllegalStateException(
                "Reserved action ids '" + SYSTEM_TASK_STATUS + "' / '" + SYSTEM_TASK_CANCEL
                + "' are provided by the server and MUST NOT be registered in Options.actions.");
        }
        this.opt = opt;
        this.prefix = opt.pathPrefix.replaceAll("/+$", "");
        this.provider = provider;
        this.taskStore = taskStore;
        this.idempotency = idempotency;
        this.taskExecutor = taskExecutor;
        this.clock = clock;
        try {
            this.nwmJson = MAPPER.writeValueAsBytes(buildManifest());
            this.actionsJson = MAPPER.writeValueAsBytes(Map.of("actions", actionsMap()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── HttpHandler ─────────────────────────────────────────────────────────────

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (!path.startsWith(prefix)) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            String sub = path.substring(prefix.length());
            String method = ex.getRequestMethod();

            if (opt.requireAuth && ex.getRequestHeaders().getFirst(NwpHttpHeaders.AGENT) == null) {
                writeError(ex, 401, "NPS-CLIENT-UNAUTHORIZED", NwpErrorCodes.NWP_AUTH_NID_SCOPE_VIOLATION,
                    "X-NWP-Agent header is required.");
                return;
            }

            switch (sub) {
                case "/.nwm", "/.nwm/" -> {
                    ex.getResponseHeaders().set("Content-Type", NwpHttpHeaders.MIME_MANIFEST);
                    ex.getResponseHeaders().set(NwpHttpHeaders.NODE_TYPE, "action");
                    writeRaw(ex, 200, nwmJson);
                }
                case "/.schema", "/.schema/", "/actions", "/actions/" -> {
                    ex.getResponseHeaders().set("Content-Type", "application/json");
                    writeRaw(ex, 200, actionsJson);
                }
                case "/invoke", "/invoke/" -> {
                    if (!method.equals("POST")) { ex.sendResponseHeaders(405, -1); return; }
                    handleInvoke(ex);
                }
                default -> ex.sendResponseHeaders(404, -1);
            }
        } finally {
            ex.close();
        }
    }

    // ── /invoke ──────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void handleInvoke(HttpExchange ex) throws IOException {
        ActionFrame frame;
        try {
            Map<String, Object> body = readJson(ex);
            frame = ActionFrame.fromDict(body);
        } catch (Exception e) {
            writeError(ex, 400, "NPS-CLIENT-BAD-REQUEST", NwpErrorCodes.NWP_ACTION_PARAMS_INVALID, e.getMessage());
            return;
        }

        String actionId = frame.actionId();

        if (SYSTEM_TASK_STATUS.equals(actionId)) { handleSystemTaskStatus(ex, frame); return; }
        if (SYSTEM_TASK_CANCEL.equals(actionId)) { handleSystemTaskCancel(ex, frame); return; }

        ActionSpec spec = actionId == null ? null : opt.actions.get(actionId);
        if (spec == null) {
            writeError(ex, 404, "NPS-CLIENT-NOT-FOUND", NwpErrorCodes.NWP_ACTION_NOT_FOUND,
                "Unknown action_id '" + actionId + "'.");
            return;
        }

        String priority = frame.priority();
        if (priority != null && !priority.equals("low") && !priority.equals("normal") && !priority.equals("high")) {
            writeError(ex, 400, "NPS-CLIENT-BAD-REQUEST", NwpErrorCodes.NWP_ACTION_PARAMS_INVALID,
                "priority '" + priority + "' is invalid (allowed: low/normal/high).");
            return;
        }

        boolean async = Boolean.TRUE.equals(frame.async_());
        if (async && !spec.async) {
            writeError(ex, 400, "NPS-CLIENT-BAD-REQUEST", NwpErrorCodes.NWP_ACTION_PARAMS_INVALID,
                "action '" + actionId + "' does not support async execution.");
            return;
        }

        int effectiveTimeout = clampTimeout(frame.timeoutMs() != null ? frame.timeoutMs() : 0, spec);

        if (frame.callbackUrl() != null) {
            String err = ActionCallbackValidator.validate(frame.callbackUrl(), opt.rejectPrivateCallbackUrls);
            if (err != null) {
                writeError(ex, 400, "NPS-CLIENT-BAD-REQUEST", NwpErrorCodes.NWP_ACTION_PARAMS_INVALID, err);
                return;
            }
        }

        String paramsHash = hashParams(frame.params());
        if (frame.idempotencyKey() != null) {
            IdempotentEntry cached = idempotency.get(actionId, frame.idempotencyKey());
            if (cached != null) {
                if (!cached.paramsHash.equals(paramsHash)) {
                    writeError(ex, 409, "NPS-CLIENT-CONFLICT", NwpErrorCodes.NWP_ACTION_IDEMPOTENCY_CONFLICT,
                        "idempotency_key reuse with different params.");
                    return;
                }
                if (cached.taskId != null) {
                    ActionTaskRecord rec = taskStore.get(cached.taskId);
                    writeAsyncResponse(ex, cached.taskId, rec != null ? rec.status : "pending",
                        frame.requestId(), null);
                    return;
                }
                writeCaps(ex, cached.result, cached.anchorRef, frame.requestId(), 0);
                return;
            }
        }

        String agentNid = ex.getRequestHeaders().getFirst(NwpHttpHeaders.AGENT);
        String effPriority = priority != null ? priority : "normal";

        if (async) {
            String taskId = randomHex(16);
            taskStore.create(taskId, actionId, frame.requestId(), agentNid);

            if (frame.idempotencyKey() != null) {
                IdempotentEntry entry = new IdempotentEntry();
                entry.actionId = actionId;
                entry.paramsHash = paramsHash;
                entry.taskId = taskId;
                entry.expiresAt = clock.get().plus(opt.idempotencyTtl);
                idempotency.tryStore(actionId, frame.idempotencyKey(), entry);
            }

            ActionContext runCtx = new ActionContext(agentNid, frame.requestId(), taskId, spec, effectiveTimeout, effPriority);
            taskExecutor.submit(() -> runAsyncTask(frame, runCtx, effectiveTimeout));

            writeAsyncResponse(ex, taskId, "pending", frame.requestId(), spec.timeoutMsDefault);
            return;
        }

        // Synchronous path — bound the wait by the effective timeout, 504 on overrun.
        ActionContext syncCtx = new ActionContext(agentNid, frame.requestId(), null, spec, effectiveTimeout, effPriority);
        CompletableFuture<ActionExecutionResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                return provider.execute(frame, syncCtx);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, taskExecutor);

        ActionExecutionResult result;
        try {
            result = future.get(effectiveTimeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            writeError(ex, 504, "NPS-SERVER-TIMEOUT", NwpErrorCodes.NWP_NODE_UNAVAILABLE, "action execution timed out.");
            return;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            writeError(ex, 500, "NPS-SERVER-INTERNAL", NwpErrorCodes.NWP_NODE_UNAVAILABLE, "action execution failed.");
            return;
        } catch (ExecutionException ee) {
            writeError(ex, 500, "NPS-SERVER-INTERNAL", NwpErrorCodes.NWP_NODE_UNAVAILABLE, "action execution failed.");
            return;
        }
        if (result == null) result = new ActionExecutionResult();

        String anchorRef = result.anchorRef != null ? result.anchorRef : spec.resultAnchor;
        if (frame.idempotencyKey() != null) {
            IdempotentEntry entry = new IdempotentEntry();
            entry.actionId = actionId;
            entry.paramsHash = paramsHash;
            entry.result = result.result;
            entry.anchorRef = anchorRef;
            entry.expiresAt = clock.get().plus(opt.idempotencyTtl);
            idempotency.tryStore(actionId, frame.idempotencyKey(), entry);
        }

        writeCaps(ex, result.result, anchorRef, frame.requestId(), result.tokenEst);
    }

    private void runAsyncTask(ActionFrame frame, ActionContext runCtx, int timeoutMs) {
        taskStore.tryTransition(runCtx.taskId(), "pending", "running");
        CompletableFuture<ActionExecutionResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                return provider.execute(frame, runCtx);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, taskExecutor);
        try {
            ActionExecutionResult res = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            taskStore.complete(runCtx.taskId(), res == null ? null : res.result);
        } catch (TimeoutException te) {
            future.cancel(true);
            taskStore.fail(runCtx.taskId(), Map.of("code", "NWP-NODE-UNAVAILABLE", "message", "task timed out"));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            taskStore.fail(runCtx.taskId(), Map.of("code", "NWP-NODE-UNAVAILABLE", "message", "task interrupted"));
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() instanceof CompletionException ce ? ce.getCause() : ee.getCause();
            String msg = cause != null && cause.getMessage() != null ? cause.getMessage() : "task failed";
            taskStore.fail(runCtx.taskId(), Map.of("code", "NWP-NODE-UNAVAILABLE", "message", msg));
        }
    }

    // ── system.task.status / system.task.cancel ──────────────────────────────

    private void handleSystemTaskStatus(HttpExchange ex, ActionFrame frame) throws IOException {
        String taskId = readStringParam(frame.params(), "task_id");
        if (taskId == null || taskId.isEmpty()) {
            writeError(ex, 400, "NPS-CLIENT-BAD-REQUEST", NwpErrorCodes.NWP_ACTION_PARAMS_INVALID, "params.task_id is required.");
            return;
        }
        ActionTaskRecord rec = taskStore.get(taskId);
        if (rec == null) {
            writeError(ex, 404, "NPS-CLIENT-NOT-FOUND", NwpErrorCodes.NWP_TASK_NOT_FOUND, "Unknown task_id '" + taskId + "'.");
            return;
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("task_id", rec.taskId);
        status.put("status", rec.status);
        if (rec.progress != null) status.put("progress", rec.progress);
        status.put("created_at", rec.createdAt.toString());
        status.put("updated_at", rec.updatedAt.toString());
        if (rec.requestId != null) status.put("request_id", rec.requestId);
        if (rec.result != null) status.put("result", rec.result);
        if (rec.error != null) status.put("error", rec.error);
        writeCaps(ex, status, null, frame.requestId(), 0);
    }

    private void handleSystemTaskCancel(HttpExchange ex, ActionFrame frame) throws IOException {
        String taskId = readStringParam(frame.params(), "task_id");
        if (taskId == null || taskId.isEmpty()) {
            writeError(ex, 400, "NPS-CLIENT-BAD-REQUEST", NwpErrorCodes.NWP_ACTION_PARAMS_INVALID, "params.task_id is required.");
            return;
        }
        ActionTaskRecord rec = taskStore.get(taskId);
        if (rec == null) {
            writeError(ex, 404, "NPS-CLIENT-NOT-FOUND", NwpErrorCodes.NWP_TASK_NOT_FOUND, "Unknown task_id '" + taskId + "'.");
            return;
        }
        String s = rec.status;
        if ("completed".equals(s) || "failed".equals(s) || "cancelled".equals(s)) {
            writeError(ex, 409, "NPS-CLIENT-CONFLICT", NwpErrorCodes.NWP_TASK_ALREADY_CANCELLED,
                "Task '" + taskId + "' is already in a terminal state ('" + s + "').");
            return;
        }
        taskStore.cancel(taskId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_id", taskId);
        payload.put("status", "cancelled");
        writeCaps(ex, payload, null, frame.requestId(), 0);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private int clampTimeout(int requested, ActionSpec spec) {
        int specMax = spec.timeoutMsMax != null ? spec.timeoutMsMax : opt.maxTimeoutMs;
        int hardMax = Math.min(specMax, opt.maxTimeoutMs);
        if (requested == 0) {
            return spec.timeoutMsDefault != null ? spec.timeoutMsDefault : opt.defaultTimeoutMs;
        }
        return Math.min(requested, hardMax);
    }

    private static String hashParams(Map<String, Object> params) {
        try {
            String json = params == null ? "null" : MAPPER.writeValueAsString(params);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String readStringParam(Map<String, Object> params, String name) {
        if (params == null) return null;
        Object v = params.get(name);
        return v instanceof String s ? s : null;
    }

    // ── Response writers ─────────────────────────────────────────────────────

    private void writeRaw(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private void writeAsyncResponse(HttpExchange ex, String taskId, String status, String requestId, Integer estimatedMs) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("task_id", taskId);
        body.put("status", status);
        body.put("poll_url", prefix + "/invoke");
        if (estimatedMs != null) body.put("estimated_ms", estimatedMs);
        if (requestId != null) body.put("request_id", requestId);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set(NwpHttpHeaders.NODE_TYPE, "action");
        writeRaw(ex, 202, MAPPER.writeValueAsBytes(body));
    }

    private void writeCaps(HttpExchange ex, Object payload, String anchorRef, String requestId, int tokenEst) throws IOException {
        List<Object> data = payload == null ? List.of() : List.of(payload);
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("anchor_ref", anchorRef != null ? anchorRef : "");
        caps.put("count", data.size());
        caps.put("data", data);
        if (tokenEst > 0) caps.put("token_est", tokenEst);
        ex.getResponseHeaders().set("Content-Type", NwpHttpHeaders.MIME_CAPSULE);
        ex.getResponseHeaders().set(NwpHttpHeaders.NODE_TYPE, "action");
        if (anchorRef != null) ex.getResponseHeaders().set(NwpHttpHeaders.SCHEMA, anchorRef);
        if (tokenEst > 0) ex.getResponseHeaders().set(NwpHttpHeaders.TOKENS, String.valueOf(tokenEst));
        if (requestId != null) ex.getResponseHeaders().set(NwpHttpHeaders.REQUEST_ID, requestId);
        writeRaw(ex, 200, MAPPER.writeValueAsBytes(caps));
    }

    private void writeError(HttpExchange ex, int status, String npsStatus, String errorCode, String message) throws IOException {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("status", npsStatus);
        env.put("error", errorCode);
        if (message != null) env.put("message", message);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        writeRaw(ex, status, MAPPER.writeValueAsBytes(env));
    }

    // ── Manifest ───────────────────────────────────────────────────────────────

    private Map<String, Object> actionsMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, ActionSpec> e : opt.actions.entrySet()) {
            ActionSpec spec = e.getValue();
            Map<String, Object> entry = new LinkedHashMap<>();
            if (spec.description != null) entry.put("description", spec.description);
            if (spec.paramsAnchor != null) entry.put("params_anchor", spec.paramsAnchor);
            if (spec.resultAnchor != null) entry.put("result_anchor", spec.resultAnchor);
            entry.put("async", spec.async);
            if (spec.idempotent != null) entry.put("idempotent", spec.idempotent);
            if (spec.timeoutMsDefault != null) entry.put("timeout_ms_default", spec.timeoutMsDefault);
            if (spec.timeoutMsMax != null) entry.put("timeout_ms_max", spec.timeoutMsMax);
            if (spec.requiredCapability != null) entry.put("required_capability", spec.requiredCapability);
            out.put(e.getKey(), entry);
        }
        return out;
    }

    private Map<String, Object> buildManifest() {
        String baseUrl = prefix;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nwp", "0.4");
        m.put("node_id", opt.nodeId);
        m.put("node_type", "action");
        if (opt.displayName != null) m.put("display_name", opt.displayName);
        m.put("wire_formats", List.of("ncp-capsule", "json"));
        m.put("preferred_format", "json");
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("query", false);
        caps.put("stream", false);
        caps.put("token_budget_hint", true);
        m.put("capabilities", caps);
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("required", opt.requireAuth);
        auth.put("identity_type", opt.requireAuth ? "nip-cert" : "none");
        m.put("auth", auth);
        m.put("endpoints", Map.of("invoke", baseUrl + "/invoke", "schema", baseUrl + "/.schema"));
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readAllBytes();
        if (raw.length == 0) return new LinkedHashMap<>();
        return MAPPER.readValue(raw, Map.class);
    }

    private static String randomHex(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    // ── Callback SSRF validator ────────────────────────────────────────────────

    /** Validates {@code callback_url} per NPS-2 §7.1 (https-only + SSRF guard). */
    public static final class ActionCallbackValidator {
        private ActionCallbackValidator() {}

        /** Returns {@code null} when valid, otherwise a human-readable error string. */
        public static String validate(String callbackUrl, boolean rejectPrivate) {
            if (callbackUrl == null || callbackUrl.isBlank())
                return "callback_url must not be empty.";
            URI uri;
            try {
                uri = URI.create(callbackUrl);
            } catch (Exception e) {
                return "callback_url '" + callbackUrl + "' is not a valid absolute URI.";
            }
            if (uri.getScheme() == null || !uri.isAbsolute() || uri.getHost() == null)
                return "callback_url '" + callbackUrl + "' is not a valid absolute URI.";
            if (!uri.getScheme().equalsIgnoreCase("https"))
                return "callback_url MUST use the https:// scheme (got '" + uri.getScheme() + "://').";
            if (rejectPrivate && isPrivateHost(uri.getHost()))
                return "callback_url host '" + uri.getHost() + "' resolves to a private or loopback address (SSRF guard).";
            return null;
        }

        /** Detects loopback / link-local / RFC1918 literals. DNS resolution avoided. */
        public static boolean isPrivateHost(String host) {
            if (host == null || host.isEmpty()) return true;
            if (host.equalsIgnoreCase("localhost")) return true;
            String stripped = host;
            if (stripped.startsWith("[")) stripped = stripped.substring(1);
            if (stripped.endsWith("]")) stripped = stripped.substring(0, stripped.length() - 1);
            java.net.InetAddress ip;
            try {
                if (!isIpLiteral(stripped)) return false;
                ip = java.net.InetAddress.getByName(stripped);
            } catch (Exception e) {
                return false;
            }
            if (ip instanceof java.net.Inet4Address) {
                byte[] b = ip.getAddress();
                int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF;
                return b0 == 127 || b0 == 10 || b0 == 0
                    || (b0 == 172 && b1 >= 16 && b1 <= 31)
                    || (b0 == 192 && b1 == 168)
                    || (b0 == 169 && b1 == 254);
            }
            return ip.isLoopbackAddress() || ip.isLinkLocalAddress() || ip.isSiteLocalAddress();
        }

        private static boolean isIpLiteral(String host) {
            // Accept only IPv4/IPv6 literals (avoid triggering DNS on hostnames).
            if (host.indexOf(':') >= 0) return true; // IPv6
            String[] parts = host.split("\\.");
            if (parts.length != 4) return false;
            for (String p : parts) {
                if (p.isEmpty()) return false;
                for (int i = 0; i < p.length(); i++) if (!Character.isDigit(p.charAt(i))) return false;
            }
            return true;
        }
    }
}
