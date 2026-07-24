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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Complex Node server using the JDK built-in {@link HttpHandler}
 * (com.sun.net.httpserver, zero third-party deps). Port of the .NET
 * {@code ComplexNodeMiddleware} (NPS-2 §2.1, §11). Sub-paths: {@code /.nwm},
 * {@code /.schema}, {@code /query}, {@code /invoke}, {@code /actions}.
 *
 * <p>On {@code /query} the server asks the {@link com.labacacia.nps.nwp.MemoryNodeServer.Provider}
 * for local rows, then — when {@code X-NWP-Depth &gt; 0} and children are declared — fetches
 * each child's {@code /query} concurrently and attaches the embedded capsules under a
 * {@code graph} array. Cycle detection uses {@code X-NWP-Trace}; depth is clamped to
 * {@link #ABSOLUTE_MAX_DEPTH}.</p>
 */
public final class ComplexNodeServer implements HttpHandler {

    /** Header carrying the visited-nodes trace for cycle detection. */
    public static final String TRACE_HEADER = "X-NWP-Trace";
    /** Absolute traversal ceiling per NPS-2 §11. */
    public static final int ABSOLUTE_MAX_DEPTH = 5;

    private static final ObjectMapper MAPPER =
        new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    // ── Nested types ─────────────────────────────────────────────────────────────

    /** Child node reference declared in the graph (NPS-2 §11). */
    public record GraphRef(String rel, String nodeUrl) {}

    /** Configuration for a single Complex Node (NPS-2 §2.1, §11). */
    public static final class Options {
        public String nodeId;
        public String displayName;
        public String pathPrefix;
        public MemoryNodeServer.Schema schema;   // nullable
        public Map<String, ActionNodeServer.ActionSpec> actions = new LinkedHashMap<>();
        public List<GraphRef> graph = new ArrayList<>();
        public int graphMaxDepth = 2;
        public List<String> allowedChildUrlPrefixes = new ArrayList<>();
        public boolean rejectPrivateChildUrls = true;
        public boolean allowHttpChildUrls = false;
        public boolean requireAuth = false;
        public int defaultLimit = 20;
        public int maxLimit = 1000;
        public int defaultTimeoutMs = 5_000;
        public int maxTimeoutMs = 300_000;
        public Duration childFetchTimeout = Duration.ofSeconds(10);
    }

    // ── State ────────────────────────────────────────────────────────────────────

    private final Options opt;
    private final String prefix;
    private final MemoryNodeServer.Provider queryProvider;
    private final ActionNodeServer.Provider actionProvider;
    private final HttpClient http;
    private final java.util.concurrent.ExecutorService pool;
    private final byte[] nwmJson;
    private final byte[] schemaJson;
    private final byte[] actionsJson;
    private final String anchorId;

    public ComplexNodeServer(Options opt, MemoryNodeServer.Provider queryProvider,
                             ActionNodeServer.Provider actionProvider) {
        this(opt, queryProvider, actionProvider,
             HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
             java.util.concurrent.Executors.newCachedThreadPool());
    }

    public ComplexNodeServer(Options opt, MemoryNodeServer.Provider queryProvider,
                             ActionNodeServer.Provider actionProvider,
                             HttpClient http, java.util.concurrent.ExecutorService pool) {
        if (opt.actions.containsKey(ActionNodeServer.SYSTEM_TASK_STATUS)
            || opt.actions.containsKey(ActionNodeServer.SYSTEM_TASK_CANCEL)) {
            throw new IllegalStateException(
                "Reserved action ids '" + ActionNodeServer.SYSTEM_TASK_STATUS + "' / '"
                + ActionNodeServer.SYSTEM_TASK_CANCEL + "' MUST NOT be registered on a Complex Node.");
        }
        if (opt.graphMaxDepth > ABSOLUTE_MAX_DEPTH) {
            throw new IllegalStateException("graphMaxDepth " + opt.graphMaxDepth
                + " exceeds NPS-2 §11 absolute cap " + ABSOLUTE_MAX_DEPTH + ".");
        }
        this.opt = opt;
        this.prefix = opt.pathPrefix.replaceAll("/+$", "");
        this.queryProvider = queryProvider;
        this.actionProvider = actionProvider;
        this.http = http;
        this.pool = pool;
        try {
            if (opt.schema != null) {
                this.schemaJson = MAPPER.writeValueAsBytes(buildSchema());
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(schemaJson);
                this.anchorId = "sha256:" + HexFormat.of().formatHex(digest);
            } else {
                this.schemaJson = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                this.anchorId = "";
            }
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
            if (!path.startsWith(prefix)) { ex.sendResponseHeaders(404, -1); return; }
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
                    ex.getResponseHeaders().set(NwpHttpHeaders.NODE_TYPE, "complex");
                    writeRaw(ex, 200, nwmJson);
                }
                case "/.schema", "/.schema/" -> {
                    ex.getResponseHeaders().set("Content-Type", "application/json");
                    if (!anchorId.isEmpty()) ex.getResponseHeaders().set(NwpHttpHeaders.SCHEMA, anchorId);
                    writeRaw(ex, 200, schemaJson);
                }
                case "/actions", "/actions/" -> {
                    ex.getResponseHeaders().set("Content-Type", "application/json");
                    writeRaw(ex, 200, actionsJson);
                }
                case "/query", "/query/" -> {
                    if (!method.equals("POST")) { ex.sendResponseHeaders(405, -1); return; }
                    handleQuery(ex);
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

    // ── /query ─────────────────────────────────────────────────────────────────

    private void handleQuery(HttpExchange ex) throws IOException {
        QueryFrame frame;
        try {
            frame = QueryFrame.fromDict(readJson(ex));
        } catch (Exception e) {
            writeError(ex, 400, "NPS-CLIENT-BAD-REQUEST", NwpErrorCodes.NWP_QUERY_FILTER_INVALID, e.getMessage());
            return;
        }

        int requestedDepth;
        try {
            requestedDepth = parseDepth(ex);
        } catch (DepthException de) {
            writeError(ex, 400, "NPS-CLIENT-BAD-REQUEST", NwpErrorCodes.NWP_DEPTH_EXCEEDED, de.getMessage());
            return;
        }

        List<String> trace = parseTrace(ex);
        if (trace.contains(opt.nodeId)) {
            writeError(ex, 422, "NPS-CLIENT-UNPROCESSABLE", NwpErrorCodes.NWP_GRAPH_CYCLE,
                "graph cycle detected at '" + opt.nodeId + "'.");
            return;
        }

        MemoryNodeServer.QueryResult local;
        try {
            local = queryProvider.query(frame, opt.schema, memoryOptions());
        } catch (MemoryNodeServer.FilterException fe) {
            writeError(ex, 400, "NPS-CLIENT-BAD-REQUEST", fe.nwpErrorCode, fe.getMessage());
            return;
        } catch (Exception e) {
            writeError(ex, 500, "NPS-SERVER-INTERNAL", NwpErrorCodes.NWP_NODE_UNAVAILABLE, "local query failed.");
            return;
        }

        List<Map<String, Object>> localData = local.rows != null ? local.rows : List.of();

        List<Object> graph = null;
        if (requestedDepth > 0 && !opt.graph.isEmpty()) {
            List<String> nextTrace = new ArrayList<>(trace);
            nextTrace.add(opt.nodeId);
            int childDepth = requestedDepth - 1;
            List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
            for (GraphRef gref : opt.graph) {
                futures.add(CompletableFuture.supplyAsync(() -> fetchChild(gref, frame, childDepth, nextTrace, ex), pool));
            }
            graph = new ArrayList<>();
            for (CompletableFuture<Map<String, Object>> f : futures) {
                try {
                    graph.add(f.get());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ee) {
                    // supplyAsync body never throws checked; ignore defensively
                }
            }
        }

        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("anchor_ref", anchorId.isEmpty() ? null : anchorId);
        caps.put("count", localData.size());
        caps.put("data", localData);
        if (local.nextCursor != null) caps.put("next_cursor", local.nextCursor);
        if (graph != null) caps.put("graph", graph);

        ex.getResponseHeaders().set("Content-Type", NwpHttpHeaders.MIME_CAPSULE);
        ex.getResponseHeaders().set(NwpHttpHeaders.NODE_TYPE, "complex");
        if (!anchorId.isEmpty()) ex.getResponseHeaders().set(NwpHttpHeaders.SCHEMA, anchorId);
        writeRaw(ex, 200, MAPPER.writeValueAsBytes(caps));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchChild(GraphRef gref, QueryFrame frame, int childDepth,
                                           List<String> nextTrace, HttpExchange parentEx) {
        String ssrfError = ComplexChildUrlValidator.validate(gref.nodeUrl(),
            opt.allowedChildUrlPrefixes, opt.rejectPrivateChildUrls, opt.allowHttpChildUrls);
        if (ssrfError != null) {
            return childError(gref, NwpErrorCodes.NWP_AUTH_NID_SCOPE_VIOLATION, ssrfError);
        }

        try {
            byte[] payload = MAPPER.writeValueAsBytes(frame.toDict());
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(gref.nodeUrl().replaceAll("/+$", "") + "/query"))
                .timeout(opt.childFetchTimeout)
                .header("Content-Type", NwpHttpHeaders.MIME_FRAME)
                .header(NwpHttpHeaders.DEPTH, String.valueOf(childDepth))
                .header(TRACE_HEADER, String.join(",", nextTrace))
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));

            String agent = parentEx.getRequestHeaders().getFirst(NwpHttpHeaders.AGENT);
            if (agent != null) b.header(NwpHttpHeaders.AGENT, agent);
            String budget = parentEx.getRequestHeaders().getFirst(NwpHttpHeaders.BUDGET);
            if (budget != null) b.header(NwpHttpHeaders.BUDGET, budget);

            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            String body = resp.body();
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return childError(gref, NwpErrorCodes.NWP_NODE_UNAVAILABLE,
                    "child '" + gref.rel() + "' returned " + resp.statusCode() + ": " + truncate(body));
            }
            Object capsule;
            try {
                capsule = MAPPER.readValue(body, Object.class);
            } catch (Exception jx) {
                return childError(gref, NwpErrorCodes.NWP_NODE_UNAVAILABLE,
                    "child '" + gref.rel() + "' returned non-JSON body: " + jx.getMessage());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rel", gref.rel());
            out.put("node", gref.nodeUrl());
            out.put("data", capsule);
            return out;
        } catch (java.net.http.HttpTimeoutException te) {
            return childError(gref, NwpErrorCodes.NWP_NODE_UNAVAILABLE, "child '" + gref.rel() + "' fetch timed out.");
        } catch (Exception e) {
            return childError(gref, NwpErrorCodes.NWP_NODE_UNAVAILABLE, e.getMessage());
        }
    }

    private Map<String, Object> childError(GraphRef gref, String code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rel", gref.rel());
        out.put("node", gref.nodeUrl());
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        out.put("error", err);
        return out;
    }

    // ── /invoke ──────────────────────────────────────────────────────────────────

    private void handleInvoke(HttpExchange ex) throws IOException {
        ActionFrame frame;
        try {
            frame = ActionFrame.fromDict(readJson(ex));
        } catch (Exception e) {
            writeError(ex, 400, "NPS-CLIENT-BAD-REQUEST", NwpErrorCodes.NWP_ACTION_PARAMS_INVALID, e.getMessage());
            return;
        }

        String actionId = frame.actionId();
        ActionNodeServer.ActionSpec spec = actionId == null ? null : opt.actions.get(actionId);
        if (spec == null) {
            writeError(ex, 404, "NPS-CLIENT-NOT-FOUND", NwpErrorCodes.NWP_ACTION_NOT_FOUND,
                "Unknown action_id '" + actionId + "'.");
            return;
        }

        if (Boolean.TRUE.equals(frame.async_())) {
            writeError(ex, 400, "NPS-CLIENT-BAD-REQUEST", NwpErrorCodes.NWP_ACTION_PARAMS_INVALID,
                "Complex Node does not support async actions; invoke a downstream Action Node instead.");
            return;
        }

        String priority = frame.priority();
        if (priority != null && !priority.equals("low") && !priority.equals("normal") && !priority.equals("high")) {
            writeError(ex, 400, "NPS-CLIENT-BAD-REQUEST", NwpErrorCodes.NWP_ACTION_PARAMS_INVALID,
                "priority '" + priority + "' is invalid (allowed: low/normal/high).");
            return;
        }

        int timeoutMs = clampTimeout(frame.timeoutMs() != null ? frame.timeoutMs() : 0, spec);
        String agentNid = ex.getRequestHeaders().getFirst(NwpHttpHeaders.AGENT);
        ActionNodeServer.ActionContext ctx = new ActionNodeServer.ActionContext(
            agentNid, frame.requestId(), null, spec, timeoutMs, priority != null ? priority : "normal");

        CompletableFuture<ActionNodeServer.ActionExecutionResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                return actionProvider.execute(frame, ctx);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, pool);

        ActionNodeServer.ActionExecutionResult result;
        try {
            result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
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
        if (result == null) result = new ActionNodeServer.ActionExecutionResult();

        String anchorRef = result.anchorRef != null ? result.anchorRef
            : (spec.resultAnchor != null ? spec.resultAnchor : "");
        List<Object> data = result.result == null ? List.of() : List.of(result.result);
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("anchor_ref", anchorRef);
        caps.put("count", data.size());
        caps.put("data", data);
        if (result.tokenEst > 0) caps.put("token_est", result.tokenEst);

        ex.getResponseHeaders().set("Content-Type", NwpHttpHeaders.MIME_CAPSULE);
        ex.getResponseHeaders().set(NwpHttpHeaders.NODE_TYPE, "complex");
        ex.getResponseHeaders().set(NwpHttpHeaders.SCHEMA, anchorRef);
        if (result.tokenEst > 0) ex.getResponseHeaders().set(NwpHttpHeaders.TOKENS, String.valueOf(result.tokenEst));
        if (frame.requestId() != null) ex.getResponseHeaders().set(NwpHttpHeaders.REQUEST_ID, frame.requestId());
        writeRaw(ex, 200, MAPPER.writeValueAsBytes(caps));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static final class DepthException extends RuntimeException {
        DepthException(String m) { super(m); }
    }

    private int parseDepth(HttpExchange ex) {
        String raw = ex.getRequestHeaders().getFirst(NwpHttpHeaders.DEPTH);
        if (raw == null) return 0;
        int depth;
        try {
            depth = Integer.parseInt(raw.trim());
            if (depth < 0) throw new NumberFormatException();
        } catch (NumberFormatException nfe) {
            throw new DepthException("X-NWP-Depth '" + raw + "' is not a non-negative integer.");
        }
        if (depth > opt.graphMaxDepth) {
            throw new DepthException("X-NWP-Depth " + depth + " exceeds node max_depth " + opt.graphMaxDepth + ".");
        }
        if (depth > ABSOLUTE_MAX_DEPTH) {
            throw new DepthException("X-NWP-Depth " + depth + " exceeds NPS-2 §11 absolute cap " + ABSOLUTE_MAX_DEPTH + ".");
        }
        return depth;
    }

    private List<String> parseTrace(HttpExchange ex) {
        String raw = ex.getRequestHeaders().getFirst(TRACE_HEADER);
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private int clampTimeout(int requested, ActionNodeServer.ActionSpec spec) {
        int specMax = spec.timeoutMsMax != null ? spec.timeoutMsMax : opt.maxTimeoutMs;
        int hardMax = Math.min(specMax, opt.maxTimeoutMs);
        if (requested == 0) return spec.timeoutMsDefault != null ? spec.timeoutMsDefault : opt.defaultTimeoutMs;
        return Math.min(requested, hardMax);
    }

    private MemoryNodeServer.Options memoryOptions() {
        MemoryNodeServer.Options mo = new MemoryNodeServer.Options();
        mo.nodeId = opt.nodeId;
        mo.schema = opt.schema;
        mo.defaultLimit = opt.defaultLimit;
        mo.maxLimit = opt.maxLimit;
        mo.requireAuth = opt.requireAuth;
        return mo;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 256 ? s : s.substring(0, 256) + "…";
    }

    private void writeRaw(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private void writeError(HttpExchange ex, int status, String npsStatus, String errorCode, String message) throws IOException {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("status", npsStatus);
        env.put("error", errorCode);
        if (message != null) env.put("message", message);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        writeRaw(ex, status, MAPPER.writeValueAsBytes(env));
    }

    private Map<String, Object> buildSchema() {
        Map<String, Object> s = new LinkedHashMap<>();
        List<Map<String, Object>> fields = new ArrayList<>();
        for (MemoryNodeServer.Field f : opt.schema.fields) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("name", f.name);
            fm.put("type", f.type);
            fm.put("nullable", f.nullable);
            fields.add(fm);
        }
        s.put("fields", fields);
        return s;
    }

    private Map<String, Object> actionsMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, ActionNodeServer.ActionSpec> e : opt.actions.entrySet()) {
            ActionNodeServer.ActionSpec spec = e.getValue();
            Map<String, Object> entry = new LinkedHashMap<>();
            if (spec.description != null) entry.put("description", spec.description);
            if (spec.paramsAnchor != null) entry.put("params_anchor", spec.paramsAnchor);
            if (spec.resultAnchor != null) entry.put("result_anchor", spec.resultAnchor);
            entry.put("async", spec.async);
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
        m.put("node_type", "complex");
        if (opt.displayName != null) m.put("display_name", opt.displayName);
        m.put("wire_formats", List.of("ncp-capsule", "json"));
        m.put("preferred_format", "json");
        if (!anchorId.isEmpty()) m.put("schema_anchors", Map.of("default", anchorId));
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("query", opt.schema != null || !opt.graph.isEmpty());
        caps.put("stream", false);
        caps.put("token_budget_hint", true);
        m.put("capabilities", caps);
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("required", opt.requireAuth);
        auth.put("identity_type", opt.requireAuth ? "nip-cert" : "none");
        m.put("auth", auth);
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("query", baseUrl + "/query");
        if (!opt.actions.isEmpty()) endpoints.put("invoke", baseUrl + "/invoke");
        endpoints.put("schema", baseUrl + "/.schema");
        m.put("endpoints", endpoints);
        if (!opt.graph.isEmpty()) {
            List<Map<String, Object>> refs = new ArrayList<>();
            for (GraphRef r : opt.graph) {
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("rel", r.rel());
                rm.put("node", r.nodeUrl());
                refs.add(rm);
            }
            m.put("graph", Map.of("refs", refs, "max_depth", opt.graphMaxDepth));
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readAllBytes();
        if (raw.length == 0) return new LinkedHashMap<>();
        return MAPPER.readValue(raw, Map.class);
    }

    // ── Child URL SSRF / allowlist validator ────────────────────────────────────

    /** Validates child-node URLs before dereference (NPS-2 §13.2). */
    public static final class ComplexChildUrlValidator {
        private ComplexChildUrlValidator() {}

        /** Returns {@code null} when acceptable, otherwise a human-readable reason. */
        public static String validate(String childUrl, List<String> allowedPrefixes,
                                      boolean rejectPrivate, boolean allowHttp) {
            if (childUrl == null || childUrl.isBlank())
                return "child node URL must not be empty.";
            URI uri;
            try {
                uri = URI.create(childUrl);
            } catch (Exception e) {
                return "child node URL '" + childUrl + "' is not a valid absolute URI.";
            }
            if (uri.getScheme() == null || !uri.isAbsolute() || uri.getHost() == null)
                return "child node URL '" + childUrl + "' is not a valid absolute URI.";
            boolean isHttps = uri.getScheme().equalsIgnoreCase("https");
            boolean isHttp = uri.getScheme().equalsIgnoreCase("http");
            if (!isHttps && !(allowHttp && isHttp))
                return "child node URL MUST use the https:// scheme (got '" + uri.getScheme() + "://').";
            if (allowedPrefixes != null && !allowedPrefixes.isEmpty()) {
                boolean matched = false;
                for (String p : allowedPrefixes) {
                    if (childUrl.regionMatches(true, 0, p, 0, p.length())) { matched = true; break; }
                }
                if (!matched)
                    return "child node URL '" + childUrl + "' is not in the allowed prefix list.";
            }
            if (rejectPrivate && ActionNodeServer.ActionCallbackValidator.isPrivateHost(uri.getHost()))
                return "child node host '" + uri.getHost() + "' resolves to a private or loopback address (SSRF guard).";
            return null;
        }
    }
}
