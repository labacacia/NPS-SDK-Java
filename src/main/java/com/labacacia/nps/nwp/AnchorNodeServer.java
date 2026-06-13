// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.nip.AssuranceLevel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Anchor Node server using the JDK built-in {@link HttpHandler}
 * (com.sun.net.httpserver, zero third-party deps). Server-side counterpart of
 * {@link AnchorNodeClient}, porting the .NET AnchorNodeMiddleware wire contract
 * (NPS-AaaS §2, NPS-2 §12) and mirroring the Python/TS/Go reference ports.
 *
 * <p>The business execution behind {@code /invoke} is delegated to an injected
 * {@link InvokeHandler} (NOP orchestration is host-supplied).</p>
 */
public final class AnchorNodeServer implements HttpHandler {

    private static final ObjectMapper MAPPER =
        new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private static final SecureRandom RNG = new SecureRandom();
    private static final String SNAPSHOT_ANCHOR_REF = "nps:system:topology:snapshot";
    private static final DateTimeFormatter ISO =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    // ── Nested types ─────────────────────────────────────────────────────────────

    public record ActionSpec(String description, String paramsAnchor, String resultAnchor,
                             Integer estimatedCgn, Integer timeoutMsDefault, Integer timeoutMsMax,
                             String requiredCapability, boolean async) {
        public ActionSpec() { this(null, null, null, null, null, null, null, false); }
        public ActionSpec withResultAnchor(String ra, int cgn) {
            return new ActionSpec(description, paramsAnchor, ra, cgn, timeoutMsDefault, timeoutMsMax, requiredCapability, async);
        }
        public ActionSpec withAsync(boolean a) {
            return new ActionSpec(description, paramsAnchor, resultAnchor, estimatedCgn, timeoutMsDefault, timeoutMsMax, requiredCapability, a);
        }
    }

    public static final class Options {
        public String nodeId;
        public String pathPrefix;
        public Map<String, ActionSpec> actions = new LinkedHashMap<>();
        public String displayName;
        public boolean requireAuth = true;
        public List<String> requiredCapabilities;
        public boolean requireTopologyCapability = false;
        public int defaultTimeoutMs = 30_000;
        public int maxTimeoutMs = 300_000;
        public int defaultTokenBudget = 0;
        public int cgnLimit = 0;
        public String assuranceHintUrl;
        public ReputationPolicy reputationPolicy;
        public List<String> trustAnchors;
        public Map<String, Object> rateLimits;
        public boolean autoInjectTraceContext = true;
    }

    public record InvokeContext(String agentNid, int effectiveTimeoutMs, int budgetCgn,
                               String traceId, String spanId) {}

    @FunctionalInterface
    public interface InvokeHandler {
        Object handle(String actionId, Object params, InvokeContext ctx) throws ActionException;
    }

    public record SnapshotRequest(String scope, Set<String> include, int depth, String targetNid) {}
    public record StreamRequest(String scope, TopologyFilter filter, Long sinceVersion) {}

    public interface TopologyService {
        String anchorNid();
        TopologySnapshot getSnapshot(SnapshotRequest req);
        List<TopologyEvent> subscribe(StreamRequest req);
    }

    public static final class InMemoryTopologyService implements TopologyService {
        private final String nid;
        private final List<MemberInfo> members;
        private final long version;
        private final List<TopologyEvent> events;

        public InMemoryTopologyService(String nid, List<MemberInfo> members, long version, List<TopologyEvent> events) {
            this.nid = nid;
            this.members = members == null ? List.of() : members;
            this.version = version;
            this.events = events == null ? List.of() : events;
        }

        @Override public String anchorNid() { return nid; }

        @Override public TopologySnapshot getSnapshot(SnapshotRequest req) {
            List<MemberInfo> m = req.include().contains("members") ? members : List.of();
            TopologySnapshot s = new TopologySnapshot();
            s.version = version;
            s.anchorNid = nid;
            s.clusterSize = members.size();
            s.members = m;
            return s;
        }

        @Override public List<TopologyEvent> subscribe(StreamRequest req) { return events; }
    }

    public record RateDecision(boolean allowed, Integer retryAfterSeconds, String reason) {
        public static RateDecision allow() { return new RateDecision(true, null, null); }
    }

    public interface RateLimiter {
        RateDecision tryAcquire(String consumerKey, int costHint);
        void release(String consumerKey);
    }

    public static final class AllowAllRateLimiter implements RateLimiter {
        @Override public RateDecision tryAcquire(String c, int h) { return RateDecision.allow(); }
        @Override public void release(String c) {}
    }

    /** Thrown by an InvokeHandler to produce a custom NWP error envelope. */
    public static final class ActionException extends Exception {
        public final int httpStatus;
        public final String npsStatus;
        public final String errorCode;
        public final Object details;
        public ActionException(int httpStatus, String npsStatus, String errorCode, String message, Object details) {
            super(message);
            this.httpStatus = httpStatus;
            this.npsStatus = npsStatus;
            this.errorCode = errorCode;
            this.details = details;
        }
    }

    static final class TopologyProtocolException extends RuntimeException {
        final String nwpErrorCode;
        final String npsStatus;
        TopologyProtocolException(String nwpErrorCode, String npsStatus, String message) {
            super(message);
            this.nwpErrorCode = nwpErrorCode;
            this.npsStatus = npsStatus;
        }
    }

    /**
     * Reputation policy evaluator seam. The reputation types ship as client-side parity
     * ({@link ReputationPolicy} / {@link ReputationPolicyRule} / {@link ReputationDecision} /
     * {@link RepOutcome}); this evaluator drives the server admission gate. Default impl:
     * {@link DefaultReputationPolicyEvaluator}.
     */
    @FunctionalInterface
    public interface ReputationEvaluator {
        ReputationDecision evaluate(String requesterNid, String assuranceWire, ReputationPolicy policy);
    }

    public record Deps(InvokeHandler invokeHandler, TopologyService topologyService,
                      ReputationEvaluator reputationEvaluator, RateLimiter rateLimiter) {
        public Deps() { this(null, null, null, null); }
    }

    // ── State ────────────────────────────────────────────────────────────────────

    private final Options opt;
    private final String prefix;
    private final Deps deps;
    private final RateLimiter limiter;
    private final byte[] nwmJson;
    private final byte[] actionsJson;

    public AnchorNodeServer(Options opt, Deps deps) {
        this.opt = opt;
        this.prefix = opt.pathPrefix.replaceAll("/+$", "");
        this.deps = deps;
        this.limiter = deps.rateLimiter() != null ? deps.rateLimiter() : new AllowAllRateLimiter();
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
                writeError(ex, 404, "NPS-CLIENT-NOT-FOUND", NwpErrorCodes.NWP_ACTION_NOT_FOUND, "no NWP node at this path.", null, null);
                return;
            }
            String sub = path.substring(prefix.length());
            String method = ex.getRequestMethod();

            // Resolve the route BEFORE the auth gate: an unknown sub-path is a 404 regardless of
            // auth, so a missing X-NWP-Agent on a non-existent route does not leak a 401 (auth
            // state) for a path that has no resource. Known routes fall through to the auth check.
            if (!isKnownRoute(sub)) {
                writeError(ex, 404, "NPS-CLIENT-NOT-FOUND", NwpErrorCodes.NWP_ACTION_NOT_FOUND, "unknown anchor sub-path.", null, null);
                return;
            }

            if (opt.requireAuth && ex.getRequestHeaders().getFirst(NwpHttpHeaders.AGENT) == null) {
                writeError(ex, 401, "NPS-AUTH-UNAUTHENTICATED", NwpErrorCodes.NWP_AUTH_NID_SCOPE_VIOLATION,
                    "X-NWP-Agent header is required.", null, null);
                return;
            }

            switch (sub) {
                case "/.nwm", "/.nwm/" -> {
                    ex.getResponseHeaders().set("Content-Type", NwpHttpHeaders.MIME_MANIFEST);
                    ex.getResponseHeaders().set(NwpHttpHeaders.NODE_TYPE, "anchor");
                    writeRaw(ex, 200, nwmJson);
                }
                case "/.schema", "/.schema/", "/actions", "/actions/" -> {
                    ex.getResponseHeaders().set("Content-Type", "application/json");
                    writeRaw(ex, 200, actionsJson);
                }
                case "/invoke", "/invoke/" -> {
                    if (!method.equals("POST")) { ex.sendResponseHeaders(405, -1); ex.close(); return; }
                    handleInvoke(ex);
                }
                case "/query", "/query/" -> {
                    if (!method.equals("POST")) { ex.sendResponseHeaders(405, -1); ex.close(); return; }
                    handleQuery(ex);
                }
                case "/subscribe", "/subscribe/" -> {
                    if (!method.equals("POST")) { ex.sendResponseHeaders(405, -1); ex.close(); return; }
                    handleSubscribe(ex);
                }
                default -> writeError(ex, 404, "NPS-CLIENT-NOT-FOUND", NwpErrorCodes.NWP_ACTION_NOT_FOUND, "unknown anchor sub-path.", null, null);
            }
        } finally {
            ex.close();
        }
    }

    // ── /query ───────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void handleQuery(HttpExchange ex) throws IOException {
        if (!checkTopologyCapability(ex)) return;
        Map<String, Object> body;
        try {
            body = readJson(ex);
        } catch (Exception e) {
            writeError(ex, 400, "NPS-CLIENT-BAD-REQUEST", NwpErrorCodes.NWP_QUERY_FILTER_INVALID, e.getMessage(), null, null);
            return;
        }
        if (!"topology.snapshot".equals(body.get("type"))) {
            String t = (String) body.get("type");
            String msg = t == null ? "Anchor /query requires a reserved type per NPS-2 §12."
                : "Reserved query type '" + t + "' is not implemented by this Anchor Node.";
            writeError(ex, 501, "NPS-SERVER-UNSUPPORTED", NwpErrorCodes.NWP_RESERVED_TYPE_UNSUPPORTED, msg, null, null);
            return;
        }
        if (deps.topologyService() == null) {
            writeError(ex, 501, "NPS-SERVER-UNSUPPORTED", NwpErrorCodes.NWP_NODE_UNAVAILABLE,
                "topology.snapshot is not available — no topology service registered.", null, null);
            return;
        }
        SnapshotRequest req;
        try {
            req = parseSnapshotRequest(body);
        } catch (TopologyProtocolException tpe) {
            writeTopologyError(ex, tpe);
            return;
        }
        TopologySnapshot snap = deps.topologyService().getSnapshot(req);
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("anchor_ref", SNAPSHOT_ANCHOR_REF);
        caps.put("count", 1);
        caps.put("data", List.of(snap));
        ex.getResponseHeaders().set("Content-Type", NwpHttpHeaders.MIME_CAPSULE);
        ex.getResponseHeaders().set(NwpHttpHeaders.NODE_TYPE, "anchor");
        ex.getResponseHeaders().set(NwpHttpHeaders.SCHEMA, SNAPSHOT_ANCHOR_REF);
        writeJson(ex, 200, caps);
    }

    // ── /subscribe ───────────────────────────────────────────────────────────────

    private void handleSubscribe(HttpExchange ex) throws IOException {
        if (!checkTopologyCapability(ex)) return;
        Map<String, Object> body;
        try {
            body = readJson(ex);
        } catch (Exception e) {
            writeError(ex, 400, "NPS-CLIENT-BAD-REQUEST", NwpErrorCodes.NWP_QUERY_FILTER_INVALID, e.getMessage(), null, null);
            return;
        }
        if (!"topology.stream".equals(body.get("type"))) {
            String t = (String) body.get("type");
            String msg = t == null ? "Anchor /subscribe requires a reserved type per NPS-2 §12."
                : "Reserved subscribe type '" + t + "' is not implemented by this Anchor Node.";
            writeError(ex, 501, "NPS-SERVER-UNSUPPORTED", NwpErrorCodes.NWP_RESERVED_TYPE_UNSUPPORTED, msg, null, null);
            return;
        }
        if (deps.topologyService() == null) {
            writeError(ex, 501, "NPS-SERVER-UNSUPPORTED", NwpErrorCodes.NWP_NODE_UNAVAILABLE,
                "topology.stream is not available — no topology service registered.", null, null);
            return;
        }
        StreamRequest req;
        String streamId;
        try {
            Object[] parsed = parseStreamRequest(body);
            req = (StreamRequest) parsed[0];
            streamId = (String) parsed[1];
        } catch (TopologyProtocolException tpe) {
            writeTopologyError(ex, tpe);
            return;
        }

        ex.getResponseHeaders().set("Content-Type", NwpHttpHeaders.MIME_CAPSULE);
        ex.getResponseHeaders().set(NwpHttpHeaders.NODE_TYPE, "anchor");
        ex.sendResponseHeaders(200, 0); // chunked
        try (OutputStream os = ex.getResponseBody()) {
            Map<String, Object> ack = new LinkedHashMap<>();
            ack.put("kind", "ack");
            ack.put("stream_id", streamId);
            ack.put("status", "subscribed");
            ack.put("last_seq", 0);
            ack.put("resumed", req.sinceVersion() != null);
            writeLine(os, ack);
            for (TopologyEvent evt : deps.topologyService().subscribe(req)) {
                writeLine(os, eventToEnvelope(streamId, evt));
                if (evt instanceof ResyncRequiredEvent) break;
            }
        }
    }

    // ── /invoke ──────────────────────────────────────────────────────────────────

    private void handleInvoke(HttpExchange ex) throws IOException {
        Map<String, Object> body;
        try {
            body = readJson(ex);
        } catch (Exception e) {
            writeError(ex, 400, "NPS-CLIENT-BAD-REQUEST", NwpErrorCodes.NWP_ACTION_PARAMS_INVALID, e.getMessage(), null, null);
            return;
        }
        String actionId = (String) body.get("action_id");
        if (actionId == null || actionId.isEmpty()) {
            writeError(ex, 400, "NPS-CLIENT-BAD-REQUEST", NwpErrorCodes.NWP_ACTION_PARAMS_INVALID,
                "invalid ActionFrame: missing action_id.", null, null);
            return;
        }
        ActionSpec spec = opt.actions.get(actionId);
        if (spec == null) {
            writeError(ex, 404, "NPS-CLIENT-NOT-FOUND", NwpErrorCodes.NWP_ACTION_NOT_FOUND,
                "Unknown action_id '" + actionId + "'.", null, null);
            return;
        }
        boolean async = Boolean.TRUE.equals(body.get("async"));
        if (async && !spec.async()) {
            writeError(ex, 400, "NPS-CLIENT-BAD-REQUEST", NwpErrorCodes.NWP_ACTION_PARAMS_INVALID,
                "action '" + actionId + "' does not support async execution.", null, null);
            return;
        }

        String agentNid = ex.getRequestHeaders().getFirst(NwpHttpHeaders.AGENT);
        String consumerKey = agentNid != null ? agentNid : "anonymous";
        int timeoutMs = body.get("timeout_ms") instanceof Number n ? n.intValue() : 0;
        int effectiveTimeout = clampTimeout(timeoutMs, spec);
        int budgetCgn = readEffectiveBudget(ex);
        int cgnCostHint = spec.estimatedCgn() != null ? spec.estimatedCgn() : 0;

        RateDecision rate = limiter.tryAcquire(consumerKey, cgnCostHint);
        if (!rate.allowed()) {
            Map<String, String> extra = rate.retryAfterSeconds() != null
                ? Map.of("Retry-After", String.valueOf(rate.retryAfterSeconds())) : null;
            String reason = rate.reason() != null ? rate.reason() : "rate limit exceeded.";
            writeError(ex, 429, "NPS-LIMIT-RATE", NwpErrorCodes.NWP_BUDGET_EXCEEDED, reason, null, extra);
            return;
        }
        try {
            AssuranceLevel assurance = extractIdentAssurance(ex);
            ReputationPolicy policy = opt.reputationPolicy;
            if (policy != null && policy.enabled && deps.reputationEvaluator() != null) {
                ReputationDecision decision;
                try {
                    decision = deps.reputationEvaluator().evaluate(consumerKey, assurance.wire(), policy);
                } catch (RuntimeException e) {
                    writeError(ex, 500, "NPS-SERVER-INTERNAL", NwpErrorCodes.NWP_NODE_UNAVAILABLE, "reputation evaluation failed.", null, null);
                    return;
                }
                if (applyReputation(ex, decision, policy, assurance)) return;
            }

            if (budgetCgn > 0 && cgnCostHint > 0 && cgnCostHint > budgetCgn) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("effective_budget", budgetCgn);
                details.put("estimated_cgn", cgnCostHint);
                writeError(ex, 400, "NPS-CLIENT-REQUEST-TOO-LARGE", NwpErrorCodes.NWP_CGN_LIMIT_EXCEEDED,
                    "estimated CGN " + cgnCostHint + " exceeds effective budget " + budgetCgn + ".", details, null);
                return;
            }

            if (deps.invokeHandler() == null) {
                writeError(ex, 501, "NPS-SERVER-UNSUPPORTED", NwpErrorCodes.NWP_NODE_UNAVAILABLE,
                    "no invoke handler registered on this Anchor Node.", null, null);
                return;
            }

            InvokeContext ctx = new InvokeContext(agentNid, effectiveTimeout, budgetCgn,
                opt.autoInjectTraceContext ? randomHex(16) : null,
                opt.autoInjectTraceContext ? randomHex(8) : null);
            Object params = body.get("params");

            if (async) {
                String taskId = randomHex(16);
                new Thread(() -> {
                    try { deps.invokeHandler().handle(actionId, params, ctx); } catch (Exception ignored) {}
                }).start();
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("task_id", taskId);
                resp.put("status", "pending");
                resp.put("poll_url", prefix + "/invoke");
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.getResponseHeaders().set(NwpHttpHeaders.NODE_TYPE, "anchor");
                writeJson(ex, 202, resp);
                return;
            }

            // Enforce the per-request timeout (parity with the Python port's asyncio.wait_for):
            // run the handler on a worker and bound the wait by effectiveTimeout, returning 504 on
            // overrun. Caveat: a synchronous handler that ignores interruption keeps running on the
            // worker thread after the 504 (Java cannot preempt blocking code) — the server's
            // observable behaviour (stop waiting, return 504) matches Python regardless.
            Object result;
            CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return deps.invokeHandler().handle(actionId, params, ctx);
                } catch (ActionException ae) {
                    throw new CompletionException(ae);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            });
            try {
                result = future.get(effectiveTimeout, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                future.cancel(true);
                writeError(ex, 504, "NPS-SERVER-TIMEOUT", NwpErrorCodes.NWP_NODE_UNAVAILABLE, "anchor task timed out.", null, null);
                return;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                writeError(ex, 500, "NPS-SERVER-INTERNAL", NwpErrorCodes.NWP_NODE_UNAVAILABLE, "anchor task interrupted.", null, null);
                return;
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause() instanceof CompletionException ce ? ce.getCause() : ee.getCause();
                if (cause instanceof ActionException ae) {
                    writeError(ex, ae.httpStatus, ae.npsStatus, ae.errorCode, ae.getMessage(), ae.details, null);
                } else {
                    writeError(ex, 500, "NPS-SERVER-INTERNAL", NwpErrorCodes.NWP_NODE_UNAVAILABLE, "anchor task execution failed.", null, null);
                }
                return;
            }

            Map<String, Object> caps = new LinkedHashMap<>();
            caps.put("anchor_ref", spec.resultAnchor() != null ? spec.resultAnchor() : "");
            caps.put("count", result == null ? 0 : 1);
            caps.put("data", result == null ? List.of() : List.of(result));
            ex.getResponseHeaders().set("Content-Type", NwpHttpHeaders.MIME_CAPSULE);
            ex.getResponseHeaders().set(NwpHttpHeaders.NODE_TYPE, "anchor");
            if (spec.resultAnchor() != null) ex.getResponseHeaders().set(NwpHttpHeaders.SCHEMA, spec.resultAnchor());
            if (spec.estimatedCgn() != null && spec.estimatedCgn() > 0)
                ex.getResponseHeaders().set(NwpHttpHeaders.TOKENS, String.valueOf(spec.estimatedCgn()));
            writeJson(ex, 200, caps);
        } finally {
            limiter.release(consumerKey);
        }
    }

    private boolean applyReputation(HttpExchange ex, ReputationDecision d, ReputationPolicy policy, AssuranceLevel assurance) throws IOException {
        // The reference ReputationDecision carries the matched rule rather than separate
        // incident/severity fields; derive them from matchedRule.
        String incident = d.matchedRule != null ? d.matchedRule.incident : null;
        String severity = d.matchedRule != null ? d.matchedRule.severity : null;
        switch (d.outcome) {
            case ACCEPT -> { return false; }
            case BAN -> {
                Map<String, Object> details = null;
                if (incident != null) {
                    details = new LinkedHashMap<>();
                    details.put("matched_incident", incident);
                    details.put("matched_severity", severity);
                }
                writeError(ex, 403, "NPS-AUTH-FORBIDDEN", NwpErrorCodes.NWP_REPUTATION_BANNED,
                    incident != null
                        ? "Request rejected: " + incident + " (" + severity + ") — NID temporarily banned."
                        : "Request rejected: NID temporarily banned.",
                    details, null);
                return true;
            }
            case REJECT -> {
                if (NwpErrorCodes.NWP_AUTH_ASSURANCE_TOO_LOW.equals(d.errorCode)) {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("matched_incident", null);
                    details.put("hint", opt.assuranceHintUrl);
                    writeError(ex, 403, "NPS-AUTH-FORBIDDEN", NwpErrorCodes.NWP_AUTH_ASSURANCE_TOO_LOW,
                        "Assurance level too low: requires '" + policy.minAssuranceLevel + "', caller declared '" + assurance.wire() + "'.",
                        details, null);
                } else {
                    Map<String, Object> details = null;
                    String msg = "Request rejected by reputation policy.";
                    if (incident != null) {
                        msg = "Request rejected: " + incident + " (" + severity + ").";
                        details = new LinkedHashMap<>();
                        details.put("matched_incident", incident);
                        details.put("matched_severity", severity);
                    }
                    writeError(ex, 403, "NPS-AUTH-FORBIDDEN", NwpErrorCodes.NWP_REPUTATION_REJECTED, msg, details, null);
                }
                return true;
            }
            case THROTTLE -> {
                Map<String, Object> details = null;
                String msg = "Request rate-limited by reputation policy.";
                if (incident != null) {
                    msg = "Request rate-limited: " + incident + " (" + severity + ").";
                    details = new LinkedHashMap<>();
                    details.put("matched_incident", incident);
                    details.put("matched_severity", severity);
                }
                writeError(ex, 429, "NPS-CLIENT-RATE-LIMITED", NwpErrorCodes.NWP_REPUTATION_THROTTLED,
                    msg, details, Map.of("Retry-After", "60"));
                return true;
            }
        }
        return false;
    }

    // ── Gates / helpers ────────────────────────────────────────────────────────────

    private static final Set<String> KNOWN_ROUTES = Set.of(
        "/.nwm", "/.nwm/", "/.schema", "/.schema/", "/actions", "/actions/",
        "/invoke", "/invoke/", "/query", "/query/", "/subscribe", "/subscribe/");

    private static boolean isKnownRoute(String sub) {
        return KNOWN_ROUTES.contains(sub);
    }

    private boolean checkTopologyCapability(HttpExchange ex) throws IOException {
        if (!opt.requireTopologyCapability) return true;
        String raw = ex.getRequestHeaders().getFirst(NwpHttpHeaders.CAPABILITIES);
        if (raw != null) {
            for (String c : raw.split(",")) {
                if (c.trim().equalsIgnoreCase("topology:read")) return true;
            }
        }
        writeError(ex, 403, "NPS-AUTH-FORBIDDEN", NwpErrorCodes.NWP_TOPOLOGY_UNAUTHORIZED,
            "Caller must declare 'topology:read' in X-NWP-Capabilities to access topology endpoints.", null, null);
        return false;
    }

    private int clampTimeout(int requested, ActionSpec spec) {
        int specMax = spec.timeoutMsMax() != null ? spec.timeoutMsMax() : opt.maxTimeoutMs;
        int hardMax = Math.min(specMax, opt.maxTimeoutMs);
        if (requested <= 0) {
            if (spec.timeoutMsDefault() != null) return spec.timeoutMsDefault();
            return opt.defaultTimeoutMs;
        }
        return Math.min(requested, hardMax);
    }

    private int readEffectiveBudget(HttpExchange ex) {
        int agentBudget = opt.defaultTokenBudget;
        String raw = ex.getRequestHeaders().getFirst(NwpHttpHeaders.BUDGET);
        if (raw != null) {
            try { agentBudget = Integer.parseInt(raw); } catch (NumberFormatException ignored) {}
        }
        int cgnLimit = opt.cgnLimit;
        if (cgnLimit == 0) return agentBudget;
        if (agentBudget == 0) return cgnLimit;
        return Math.min(cgnLimit, agentBudget);
    }

    private AssuranceLevel extractIdentAssurance(HttpExchange ex) {
        String raw = ex.getRequestHeaders().getFirst(NwpHttpHeaders.IDENT);
        if (raw == null || raw.isEmpty()) return AssuranceLevel.ANONYMOUS;
        try {
            JsonNode doc = MAPPER.readTree(raw);
            JsonNode lvl = doc.get("assurance_level");
            if (lvl != null && lvl.isTextual()) return AssuranceLevel.fromWire(lvl.asText());
        } catch (Exception ignored) {}
        return AssuranceLevel.ANONYMOUS;
    }

    // ── Response writers ─────────────────────────────────────────────────────────

    private void writeRaw(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private void writeJson(HttpExchange ex, int status, Object obj) throws IOException {
        writeRaw(ex, status, MAPPER.writeValueAsBytes(obj));
    }

    private void writeLine(OutputStream os, Object obj) throws IOException {
        os.write(MAPPER.writeValueAsBytes(obj));
        os.write('\n');
        os.flush();
    }

    private void writeError(HttpExchange ex, int status, String npsStatus, String errorCode,
                            String message, Object details, Map<String, String> extra) throws IOException {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("status", npsStatus);
        env.put("error", errorCode);
        env.put("message", message);
        if (details != null) env.put("details", details);
        if (extra != null) extra.forEach((k, v) -> ex.getResponseHeaders().set(k, v));
        ex.getResponseHeaders().set("Content-Type", "application/json");
        writeRaw(ex, status, MAPPER.writeValueAsBytes(env));
    }

    private void writeTopologyError(HttpExchange ex, TopologyProtocolException e) throws IOException {
        int status = "NPS-AUTH-FORBIDDEN".equals(e.npsStatus) ? 403 : 400;
        writeError(ex, status, e.npsStatus, e.nwpErrorCode, e.getMessage(), null, null);
    }

    // ── Manifest ───────────────────────────────────────────────────────────────────

    private Map<String, Object> actionsMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, ActionSpec> e : opt.actions.entrySet()) {
            ActionSpec spec = e.getValue();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("action_id", e.getKey());
            entry.put("async", spec.async());
            if (spec.description() != null) entry.put("description", spec.description());
            if (spec.paramsAnchor() != null) entry.put("params_anchor", spec.paramsAnchor());
            if (spec.resultAnchor() != null) entry.put("result_anchor", spec.resultAnchor());
            if (spec.estimatedCgn() != null) entry.put("cgn_est", spec.estimatedCgn());
            if (spec.timeoutMsDefault() != null) entry.put("timeout_ms_default", spec.timeoutMsDefault());
            if (spec.timeoutMsMax() != null) entry.put("timeout_ms_max", spec.timeoutMsMax());
            if (spec.requiredCapability() != null) entry.put("required_capability", spec.requiredCapability());
            out.put(e.getKey(), entry);
        }
        return out;
    }

    private Map<String, Object> buildManifest() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nwp", "0.4");
        m.put("node_id", opt.nodeId);
        m.put("node_type", "anchor");
        if (opt.displayName != null) m.put("display_name", opt.displayName);
        m.put("wire_formats", List.of("ncp-capsule", "json"));
        m.put("preferred_format", "json");
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("query", false);
        caps.put("stream", false);
        caps.put("subscribe", false);
        caps.put("vector_search", false);
        caps.put("token_budget_hint", true);
        caps.put("ext_frame", false);
        m.put("capabilities", caps);
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("required", opt.requireAuth);
        auth.put("identity_type", opt.requireAuth ? "nip-cert" : "none");
        if (opt.requiredCapabilities != null) auth.put("required_capabilities", opt.requiredCapabilities);
        m.put("auth", auth);
        m.put("endpoints", Map.of("invoke", prefix + "/invoke", "schema", prefix + "/.schema"));
        if (opt.cgnLimit > 0) m.put("token_budget", Map.of("cgn_limit", opt.cgnLimit, "profile", "cgn.v1"));
        if (opt.rateLimits != null) m.put("rate_limits", opt.rateLimits);
        if (opt.reputationPolicy != null && opt.reputationPolicy.enabled) m.put("reputation_policy", MAPPER.convertValue(opt.reputationPolicy, Map.class));
        if (opt.trustAnchors != null && !opt.trustAnchors.isEmpty()) m.put("trust_anchors", opt.trustAnchors);
        // actions array sorted by id for determinism
        Map<String, Object> am = new TreeMap<>(actionsMap());
        m.put("actions", new ArrayList<>(am.values()));
        return m;
    }

    // ── Topology wire helpers ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> eventToEnvelope(String streamId, TopologyEvent ev) {
        Map<String, Object> payload;
        boolean includeSeq = true;
        long seq = ev.version;
        if (ev instanceof MemberJoinedEvent mj) {
            payload = MAPPER.convertValue(mj.member, Map.class);
        } else if (ev instanceof MemberLeftEvent ml) {
            payload = new LinkedHashMap<>();
            payload.put("nid", ml.nid);
        } else if (ev instanceof MemberUpdatedEvent mu) {
            payload = new LinkedHashMap<>();
            payload.put("nid", mu.nid);
            payload.put("changes", MAPPER.convertValue(mu.changes, Map.class));
        } else if (ev instanceof AnchorStateEvent as) {
            payload = new LinkedHashMap<>();
            payload.put("field", as.field);
            payload.put("details", as.details);
        } else if (ev instanceof ResyncRequiredEvent rr) {
            payload = new LinkedHashMap<>();
            payload.put("reason", rr.reason);
            includeSeq = false;
        } else {
            payload = ev.payload != null ? ev.payload : new LinkedHashMap<>();
        }
        byte[] raw;
        try { raw = MAPPER.writeValueAsBytes(payload); } catch (Exception e) { raw = new byte[]{}; }
        int cgnEst = Math.max(1, raw.length / 4);
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("stream_id", streamId);
        env.put("event_type", ev instanceof ResyncRequiredEvent ? "resync_required" : eventKind(ev));
        env.put("timestamp", ISO.format(Instant.now()));
        env.put("payload", payload);
        env.put("cgn_est", cgnEst);
        if (includeSeq) env.put("seq", seq);
        return env;
    }

    private static String eventKind(TopologyEvent ev) {
        if (ev instanceof MemberJoinedEvent) return "member_joined";
        if (ev instanceof MemberLeftEvent) return "member_left";
        if (ev instanceof MemberUpdatedEvent) return "member_updated";
        if (ev instanceof AnchorStateEvent) return "anchor_state";
        if (ev instanceof ResyncRequiredEvent) return "resync_required";
        return ev.eventType;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readAllBytes();
        if (raw.length == 0) return new LinkedHashMap<>();
        return MAPPER.readValue(raw, Map.class);
    }

    @SuppressWarnings("unchecked")
    private SnapshotRequest parseSnapshotRequest(Map<String, Object> body) {
        Object topoObj = body.get("topology");
        if (!(topoObj instanceof Map)) {
            throw new TopologyProtocolException(NwpErrorCodes.NWP_TOPOLOGY_UNSUPPORTED_SCOPE, "NPS-CLIENT-BAD-PARAM",
                "topology.snapshot requires a 'topology' object per NPS-2 §12.1.");
        }
        Map<String, Object> topo = (Map<String, Object>) topoObj;
        String scope = parseScope(topo);
        Set<String> include = parseInclude(topo);
        int depth = parseDepth(topo);
        String targetNid = topo.get("target_nid") instanceof String s ? s : null;
        if ("member".equals(scope) && (targetNid == null || targetNid.isEmpty())) {
            throw new TopologyProtocolException(NwpErrorCodes.NWP_TOPOLOGY_UNSUPPORTED_SCOPE, "NPS-CLIENT-BAD-PARAM",
                "topology.target_nid is required when topology.scope = \"member\".");
        }
        return new SnapshotRequest(scope, include, depth, targetNid);
    }

    @SuppressWarnings("unchecked")
    private Object[] parseStreamRequest(Map<String, Object> body) {
        Object topoObj = body.get("topology");
        if (!(topoObj instanceof Map)) {
            throw new TopologyProtocolException(NwpErrorCodes.NWP_TOPOLOGY_UNSUPPORTED_SCOPE, "NPS-CLIENT-BAD-PARAM",
                "topology.stream requires a 'topology' object per NPS-2 §12.2.");
        }
        Map<String, Object> topo = (Map<String, Object>) topoObj;
        String scope = parseScope(topo);
        TopologyFilter filter = null;
        if (topo.get("filter") instanceof Map<?, ?> f) {
            validateFilterKeys((Map<String, Object>) f);
            filter = MAPPER.convertValue(f, TopologyFilter.class);
        }
        Long since = null;
        if (topo.get("since_version") instanceof Number n) since = n.longValue();
        else if (body.get("resume_from_seq") instanceof Number n) since = n.longValue();
        String streamId = body.get("stream_id") instanceof String s && !s.isEmpty() ? s : randomHex(16);
        return new Object[]{new StreamRequest(scope, filter, since), streamId};
    }

    private String parseScope(Map<String, Object> topo) {
        Object s = topo.get("scope");
        if (!(s instanceof String str) || str.isEmpty()) return "cluster";
        if (str.equals("cluster") || str.equals("member")) return str;
        throw new TopologyProtocolException(NwpErrorCodes.NWP_TOPOLOGY_UNSUPPORTED_SCOPE, "NPS-CLIENT-BAD-PARAM",
            "unknown topology.scope '" + str + "'.");
    }

    private Set<String> parseInclude(Map<String, Object> topo) {
        Set<String> out = new java.util.LinkedHashSet<>();
        Set<String> known = Set.of("members", "capabilities", "tags", "metrics");
        if (topo.get("include") instanceof List<?> arr) {
            for (Object v : arr) {
                if (v instanceof String s && known.contains(s)) out.add(s);
            }
        }
        if (out.isEmpty()) out.add("members");
        return out;
    }

    private int parseDepth(Map<String, Object> topo) {
        if (topo.get("depth") instanceof Number n && n.intValue() > 0) return n.intValue();
        return 1;
    }

    private void validateFilterKeys(Map<String, Object> filter) {
        for (String key : filter.keySet()) {
            switch (key) {
                case "tags_any", "tags_all", "node_roles" -> {}
                case "node_kind" -> throw new TopologyProtocolException(NwpErrorCodes.NWP_TOPOLOGY_FILTER_UNSUPPORTED,
                    "NPS-CLIENT-BAD-PARAM", "topology.filter.node_kind expired after alpha.5; use node_roles.");
                default -> throw new TopologyProtocolException(NwpErrorCodes.NWP_TOPOLOGY_FILTER_UNSUPPORTED,
                    "NPS-CLIENT-BAD-PARAM", "topology.filter key '" + key + "' is not recognized.");
            }
        }
    }

    private static String randomHex(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
