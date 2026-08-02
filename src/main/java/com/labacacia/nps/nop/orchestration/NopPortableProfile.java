// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.orchestration;

import com.labacacia.nps.nop.NopErrorCodes;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transport-independent NOP 0.9 deterministic orchestration and runtime
 * conformance profile.
 */
public final class NopPortableProfile {
    private static final String CLUSTER_SPLIT = "NDP-CLUSTER-SPLIT";
    private static final Pattern CONDITION = Pattern.compile(
        "^\\$\\.(?<path>[A-Za-z0-9_.-]+)\\s*(?<op>==|!=|>=|<=|>|<)\\s*(?<literal>.+)$");

    private NopPortableProfile() {}

    /** Runs one shared deterministic orchestration transcript. */
    public static Map<String, Object> evaluateOrchestration(Map<String, Object> task) {
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        for (Object raw : list(task.get("nodes"))) {
            Map<String, Object> node = map(raw);
            String id = string(node.get("id"));
            if (nodes.putIfAbsent(id, node) != null) {
                return emptyFailure(NopErrorCodes.NOP_TASK_DAG_INVALID);
            }
        }
        List<String> topo = stableTopology(nodes);
        if (topo == null) return emptyFailure(NopErrorCodes.NOP_TASK_DAG_CYCLE);

        List<String> events = new ArrayList<>();
        if (Boolean.TRUE.equals(task.get("preflight"))) {
            events.add("task:preflight");
            if (topo.stream().anyMatch(id ->
                    Boolean.FALSE.equals(nodes.get(id).get("preflight_available")))) {
                events.add("task:failed");
                return result(events, "failed", NopErrorCodes.NOP_RESOURCE_INSUFFICIENT,
                    null, Map.of(), Map.of(), Map.of(), List.of());
            }
        }
        events.add("task:running");
        Map<String, Object> results = new HashMap<>();
        Map<String, String> states = new HashMap<>();
        Map<String, Integer> attempts = new HashMap<>();
        Map<String, Object> mapped = new HashMap<>();
        int taskRetries = integer(task.get("max_retries"), 0);

        for (String id : topo) {
            Map<String, Object> node = nodes.get(id);
            if (id.equals(task.get("cancel_before"))) {
                events.add("task:cancelled");
                return result(events, "cancelled", NopErrorCodes.NOP_TASK_CANCELLED,
                    null, states, attempts, mapped, List.of());
            }

            if (node.get("condition") instanceof String condition) {
                Boolean conditionResult = evaluateCondition(condition, results);
                if (conditionResult == null) {
                    states.put(id, "failed");
                    attempts.put(id, 0);
                    events.add(id + ":failed");
                    events.add("task:failed");
                    return result(events, "failed", NopErrorCodes.NOP_CONDITION_EVAL_ERROR,
                        null, states, attempts, mapped, List.of());
                }
                if (!conditionResult) {
                    states.put(id, "skipped");
                    attempts.put(id, 0);
                    events.add(id + ":skipped");
                    continue;
                }
            }

            if (node.get("input_mapping") instanceof Map<?, ?> rawMapping) {
                Map<String, Object> params = new LinkedHashMap<>();
                boolean valid = true;
                for (Map.Entry<?, ?> entry : rawMapping.entrySet()) {
                    Object value = resolvePath(results, string(entry.getValue()));
                    if (value == null) {
                        valid = false;
                        break;
                    }
                    params.put(string(entry.getKey()), value);
                }
                if (!valid) {
                    states.put(id, "failed");
                    attempts.put(id, 0);
                    events.add(id + ":failed");
                    events.add("task:failed");
                    return result(events, "failed", NopErrorCodes.NOP_INPUT_MAPPING_ERROR,
                        null, states, attempts, mapped, List.of());
                }
                mapped.put(id, params);
            }

            int maxRetries = integer(node.get("max_retries"), taskRetries);
            List<Object> scripted = list(node.get("attempts"));
            String finalError = null;
            boolean completed = false;
            int count = 0;
            for (int index = 0; index < scripted.size() && index <= maxRetries; index++) {
                Map<String, Object> outcome = map(scripted.get(index));
                count++;
                events.add(id + ":attempt:" + count);
                String kind = string(outcome.get("kind"));
                if ("success".equals(kind)) {
                    results.put(id, outcome.getOrDefault("result", Map.of()));
                    states.put(id, "completed");
                    events.add(id + ":completed");
                    completed = true;
                    break;
                }
                finalError = "timeout".equals(kind)
                    ? NopErrorCodes.NOP_DELEGATE_TIMEOUT
                    : string(outcome.getOrDefault(
                        "error_code", NopErrorCodes.NOP_DELEGATE_REJECTED));
                boolean retryable = "timeout".equals(kind)
                    || Boolean.TRUE.equals(outcome.get("retryable"));
                List<Object> retryOn = node.containsKey("retry_on")
                    ? list(node.get("retry_on")) : null;
                boolean selected = retryOn == null || retryOn.contains(finalError);
                if (retryable && selected && count <= maxRetries
                        && index + 1 < scripted.size()) {
                    events.add(id + ":retrying");
                    continue;
                }
                states.put(id, "failed");
                events.add(id + ":failed");
                break;
            }
            attempts.put(id, count);
            if (completed) continue;

            Compensation compensation = compensate(
                task, id, topo, nodes, states, events);
            events.add("task:failed");
            return result(events, "failed",
                compensation.error() != null
                    ? compensation.error()
                    : finalError != null ? finalError : NopErrorCodes.NOP_DELEGATE_REJECTED,
                null, states, attempts, mapped, compensation.order());
        }

        Object aggregate = aggregate(task, topo, nodes, states, results);
        events.add("task:completed");
        return result(events, "completed", null, aggregate,
            states, attempts, mapped, List.of());
    }

    /** Evaluates one shared runtime/security vector category. */
    public static Map<String, Object> evaluateRuntime(
            String category, Map<String, Object> input) {
        return switch (category) {
            case "callback" -> evaluateCallback(input);
            case "hmac" -> evaluateHmac(input);
            case "lease" -> evaluateLease(input);
            case "delegation" -> evaluateDelegation(input);
            case "spawn_spec" -> evaluateSpawnSpec(input);
            case "lifecycle" -> evaluateLifecycle(input);
            case "dedup_key" -> Map.of("value", computeDedupKey(
                string(input.get("task_id")), string(input.get("dag_hash"))));
            default -> throw new IllegalArgumentException(
                "Unknown NOP profile category: " + category);
        };
    }

    /** Computes SHA-256(task_id + NUL + dag_hash) as lowercase hex. */
    public static String computeDedupKey(String taskId, String dagHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(taskId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(dagHash.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static List<String> stableTopology(
            Map<String, Map<String, Object>> nodes) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        nodes.keySet().forEach(id -> {
            indegree.put(id, 0);
            outgoing.put(id, new ArrayList<>());
        });
        for (Map.Entry<String, Map<String, Object>> entry : nodes.entrySet()) {
            for (Object rawDependency : list(entry.getValue().get("depends_on"))) {
                String dependency = string(rawDependency);
                if (!nodes.containsKey(dependency)) return null;
                indegree.compute(entry.getKey(), (key, value) -> value + 1);
                outgoing.get(dependency).add(entry.getKey());
            }
        }
        PriorityQueue<String> ready = new PriorityQueue<>();
        indegree.forEach((id, value) -> {
            if (value == 0) ready.add(id);
        });
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.remove();
            order.add(id);
            outgoing.get(id).stream().sorted().forEach(next -> {
                int value = indegree.compute(next, (key, previous) -> previous - 1);
                if (value == 0) ready.add(next);
            });
        }
        return order.size() == nodes.size() ? order : null;
    }

    private static Boolean evaluateCondition(
            String expression, Map<String, Object> results) {
        Matcher matcher = CONDITION.matcher(expression);
        if (!matcher.matches()) return null;
        Object left = resolvePath(results, "$." + matcher.group("path"));
        if (left == null) return null;
        String literal = matcher.group("literal");
        Object right;
        if ("true".equals(literal)) right = true;
        else if ("false".equals(literal)) right = false;
        else if ("null".equals(literal)) right = null;
        else if (literal.startsWith("\"") && literal.endsWith("\"")) {
            right = literal.substring(1, literal.length() - 1);
        } else {
            try {
                right = Double.parseDouble(literal);
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        String operator = matcher.group("op");
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            double l = leftNumber.doubleValue();
            double r = rightNumber.doubleValue();
            return switch (operator) {
                case "==" -> l == r;
                case "!=" -> l != r;
                case ">" -> l > r;
                case ">=" -> l >= r;
                case "<" -> l < r;
                case "<=" -> l <= r;
                default -> null;
            };
        }
        return switch (operator) {
            case "==" -> java.util.Objects.equals(left, right);
            case "!=" -> !java.util.Objects.equals(left, right);
            default -> null;
        };
    }

    private static Object resolvePath(Map<String, Object> root, String path) {
        if (!path.startsWith("$.")) return null;
        Object current = root;
        for (String segment : path.substring(2).split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }

    private static Compensation compensate(
            Map<String, Object> task,
            String failedId,
            List<String> topo,
            Map<String, Map<String, Object>> nodes,
            Map<String, String> states,
            List<String> events) {
        String policy = stringOrNull(task.get("compensation_policy"));
        if (!"best_effort".equals(policy) && !"strict".equals(policy)) {
            return new Compensation(List.of(), null);
        }
        Set<String> ancestors = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(failedId);
        while (!pending.isEmpty()) {
            for (Object raw : list(nodes.get(pending.remove()).get("depends_on"))) {
                String dependency = string(raw);
                if (ancestors.add(dependency)) pending.add(dependency);
            }
        }
        List<String> candidates = topo.stream()
            .filter(id -> ancestors.contains(id) && "completed".equals(states.get(id)))
            .sorted(Comparator.comparingInt(topo::indexOf).reversed())
            .toList();
        if ("strict".equals(policy)
                && candidates.stream().anyMatch(id ->
                    !nodes.get(id).containsKey("compensate_action"))) {
            return new Compensation(List.of(), NopErrorCodes.NOP_COMPENSATION_NOT_SUPPORTED);
        }
        List<String> order = new ArrayList<>();
        for (String id : candidates) {
            Map<String, Object> node = nodes.get(id);
            if (!node.containsKey("compensate_action")) continue;
            order.add(id);
            events.add(id + ":compensating");
            if ("failure".equals(node.get("compensation_outcome"))) {
                states.put(id, "compensation_failed");
                events.add(id + ":compensation_failed");
                if ("strict".equals(policy)) {
                    return new Compensation(order, NopErrorCodes.NOP_COMPENSATION_FAILED);
                }
            } else {
                states.put(id, "compensated");
                events.add(id + ":compensated");
            }
        }
        return new Compensation(order, null);
    }

    private static Object aggregate(
            Map<String, Object> task,
            List<String> topo,
            Map<String, Map<String, Object>> nodes,
            Map<String, String> states,
            Map<String, Object> results) {
        Set<String> hasOutgoing = new HashSet<>();
        nodes.values().forEach(node -> list(node.get("depends_on"))
            .forEach(dependency -> hasOutgoing.add(string(dependency))));
        List<Object> values = topo.stream()
            .filter(id -> !hasOutgoing.contains(id)
                && "completed".equals(states.get(id))
                && results.containsKey(id))
            .map(results::get)
            .toList();
        if (values.isEmpty()) return null;
        if ("all".equals(task.get("aggregate"))) return values;
        Map<String, Object> output = new LinkedHashMap<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> object)) continue;
            for (Map.Entry<?, ?> entry : object.entrySet()) {
                String key = string(entry.getKey());
                if ("merge_all".equals(task.get("aggregate"))
                        && output.get(key) instanceof List<?> existing
                        && entry.getValue() instanceof List<?> incoming) {
                    List<Object> combined = new ArrayList<>(existing);
                    combined.addAll(incoming);
                    output.put(key, combined);
                } else {
                    output.put(key, entry.getValue());
                }
            }
        }
        return output;
    }

    private static Map<String, Object> evaluateCallback(Map<String, Object> input) {
        boolean allowed = callbackAllowed(
            string(input.get("url")), list(input.get("resolved_ips")));
        if (allowed && input.containsKey("redirect_url")) {
            allowed = callbackAllowed(
                string(input.get("redirect_url")),
                list(input.get("redirect_resolved_ips")));
        }
        return nullableMap("allowed", allowed, "error",
            allowed ? null : NopErrorCodes.NOP_CALLBACK_INVALID);
    }

    private static boolean callbackAllowed(String value, List<Object> addresses) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || addresses.isEmpty()) return false;
            for (Object raw : addresses) {
                String addressText = string(raw);
                if (!addressText.matches("[0-9a-fA-F:.]+")) return false;
                InetAddress address = InetAddress.getByName(addressText);
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) return false;
            }
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private static Map<String, Object> evaluateHmac(Map<String, Object> input) {
        if (input.get("signature") == null) {
            return nullableMap("valid", false, "error",
                NopErrorCodes.NOP_CALLBACK_HMAC_MISSING);
        }
        boolean valid = false;
        try {
            byte[] key = Base64.getUrlDecoder().decode(padBase64(
                string(input.get("secret_base64url"))));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(
                mac.doFinal(string(input.get("raw_body"))
                    .getBytes(StandardCharsets.UTF_8)));
            valid = key.length == 32 && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                string(input.get("signature")).getBytes(StandardCharsets.US_ASCII));
        } catch (Exception ignored) {
            valid = false;
        }
        return nullableMap("valid", valid, "error",
            valid ? null : NopErrorCodes.NOP_CALLBACK_HMAC_INVALID);
    }

    private static Map<String, Object> evaluateLease(Map<String, Object> input) {
        Map<String, Lease> leases = new HashMap<>();
        Set<String> terminal = new HashSet<>();
        List<String> outcomes = new ArrayList<>();
        for (Object raw : list(input.get("events"))) {
            Map<String, Object> event = map(raw);
            long at = ((Number) event.get("at")).longValue();
            String op = string(event.get("op"));
            if ("claim".equals(op)) {
                String taskId = string(event.get("task_id"));
                String runner = string(event.get("runner_nid"));
                int seconds = clamp(integer(event.get("lease_seconds"), 10));
                Lease lease = leases.get(taskId);
                if (lease != null && lease.expiresAt() > at) {
                    if (lease.runnerNid().equals(runner)) {
                        leases.put(taskId, new Lease(runner, at + seconds));
                        outcomes.add("granted");
                    } else outcomes.add("conflict");
                } else {
                    leases.put(taskId, new Lease(runner, at + seconds));
                    outcomes.add(lease == null ? "granted" : "reclaimed");
                }
            } else if ("renew".equals(op)) {
                String taskId = string(event.get("task_id"));
                String runner = string(event.get("runner_nid"));
                int seconds = clamp(integer(event.get("lease_seconds"), 10));
                Lease lease = leases.get(taskId);
                if (lease != null && lease.expiresAt() > at
                        && lease.runnerNid().equals(runner)) {
                    leases.put(taskId, new Lease(runner, at + seconds));
                    outcomes.add("granted");
                } else outcomes.add("conflict");
            } else if ("mark_terminal".equals(op)) {
                terminal.add(terminalKey(event));
                outcomes.add("recorded");
            } else if ("is_terminal".equals(op)) {
                outcomes.add(terminal.contains(terminalKey(event))
                    ? "terminal" : "pending");
            }
        }
        return Map.of("outcomes", outcomes);
    }

    private static Map<String, Object> evaluateDelegation(Map<String, Object> input) {
        Map<String, Object> parent = map(input.get("parent_scope"));
        Map<String, Object> delegated = map(input.get("delegated_scope"));
        if (!subset(list(delegated.get("nodes")), list(parent.get("nodes")))
                || !subset(list(delegated.get("actions")), list(parent.get("actions")))
                || ((Number) delegated.get("max_token_budget")).longValue()
                    > ((Number) parent.get("max_token_budget")).longValue()) {
            return nullableMap("targets", List.of(), "error",
                NopErrorCodes.NOP_DELEGATE_SCOPE_VIOLATION);
        }
        List<String> targets = new ArrayList<>();
        for (Object rawAttempt : list(input.get("attempts"))) {
            List<Map<String, Object>> live = list(map(rawAttempt).get("candidates")).stream()
                .map(NopPortableProfile::map)
                .filter(candidate -> Boolean.TRUE.equals(candidate.get("live")))
                .toList();
            if (live.isEmpty()) {
                return nullableMap("targets", targets, "error",
                    NopErrorCodes.NOP_DELEGATE_REJECTED);
            }
            long highest = live.stream()
                .mapToLong(candidate -> ((Number) candidate.get("cluster_epoch")).longValue())
                .max().orElseThrow();
            List<Map<String, Object>> leaders = live.stream()
                .filter(candidate ->
                    ((Number) candidate.get("cluster_epoch")).longValue() == highest)
                .toList();
            if (leaders.size() != 1) {
                return nullableMap("targets", targets, "error", CLUSTER_SPLIT);
            }
            targets.add(string(leaders.getFirst().get("nid")));
        }
        return nullableMap("targets", targets, "error", null);
    }

    private static Map<String, Object> evaluateSpawnSpec(Map<String, Object> input) {
        Map<String, Object> spec = map(input.get("spawn_spec"));
        boolean valid = spec.get("image") instanceof String image && !image.isBlank();
        if (valid && spec.containsKey("idle_timeout_seconds")
                && spec.containsKey("max_runtime_seconds")
                && ((Number) spec.get("idle_timeout_seconds")).longValue()
                    > ((Number) spec.get("max_runtime_seconds")).longValue()) {
            valid = false;
        }
        return nullableMap("error",
            valid ? null : NopErrorCodes.NOP_SPAWN_SPEC_INVALID);
    }

    private static Map<String, Object> evaluateLifecycle(Map<String, Object> input) {
        String state;
        String error;
        if (((Number) input.get("elapsed_seconds")).longValue()
                >= ((Number) input.get("max_runtime_seconds")).longValue()) {
            state = "failed";
            error = NopErrorCodes.NOP_RUNTIME_MAX_RUNTIME;
        } else if (((Number) input.get("idle_seconds")).longValue()
                >= ((Number) input.get("idle_timeout_seconds")).longValue()) {
            state = "failed";
            error = NopErrorCodes.NOP_RUNTIME_IDLE_TIMEOUT;
        } else if ("done".equals(input.get("worker_terminal"))) {
            state = "completed";
            error = null;
        } else {
            state = "failed";
            error = NopErrorCodes.NOP_DELEGATE_REJECTED;
        }
        return nullableMap("state", state, "error", error);
    }

    private static Map<String, Object> result(
            List<String> events,
            String state,
            String error,
            Object aggregate,
            Map<String, String> states,
            Map<String, Integer> attempts,
            Map<String, Object> mapped,
            List<String> compensation) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("events", new ArrayList<>(events));
        output.put("terminal_state", state);
        output.put("error_code", error);
        output.put("aggregate", aggregate);
        output.put("node_states", new TreeMap<>(states));
        output.put("attempt_counts", new TreeMap<>(attempts));
        output.put("mapped_params", new TreeMap<>(mapped));
        output.put("compensation_order", new ArrayList<>(compensation));
        return output;
    }

    private static Map<String, Object> emptyFailure(String error) {
        return result(List.of("task:failed"), "failed", error, null,
            Map.of(), Map.of(), Map.of(), List.of());
    }

    private static boolean subset(Collection<?> values, Collection<?> allowed) {
        return new HashSet<>(allowed).containsAll(values);
    }

    private static String terminalKey(Map<String, Object> event) {
        return string(event.get("dedup_key")) + "\0" + string(event.get("node_id"));
    }

    private static int clamp(int value) {
        return Math.min(600, Math.max(10, value));
    }

    private static String padBase64(String value) {
        return value + "=".repeat((4 - value.length() % 4) % 4);
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static String string(Object value) {
        return String.valueOf(value);
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static Map<String, Object> nullableMap(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(string(values[index]), values[index + 1]);
        }
        return result;
    }

    private record Compensation(List<String> order, String error) {}
    private record Lease(String runnerNid, long expiresAt) {}
}
