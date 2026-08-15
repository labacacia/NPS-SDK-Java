// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

final class LlmContextStoreConformanceTest {
    private static final LlmContextOwner ALICE =
        new LlmContextOwner("urn:nps:agent:labacacia:alice", "workspace-a");
    private static final LlmContextOwner BOB =
        new LlmContextOwner("urn:nps:agent:labacacia:bob", "workspace-a");

    @TestFactory Stream<DynamicTest> sharedVectors() throws IOException {
        var root = new ObjectMapper().readTree(Files.readAllBytes(findFixture()));
        var tests = new ArrayList<DynamicTest>();
        for (var vector : root.path("vectors")) {
            String id = vector.path("id").asText();
            tests.add(DynamicTest.dynamicTest(id, () -> execute(vector)));
        }
        return tests.stream();
    }

    @Test void reservationAndSnapshotDefensivelyCopyLists() {
        var h = new Harness();
        var messages = new ArrayList<>(List.of(system("Be concise."), user("Original")));
        var request = h.request(LlmContextOperation.CREATE, "immutable", null, null,
            binding(), messages, null);
        var reservation = h.store.reserve(request);
        messages.set(1, user("Tampered"));
        var receipt = h.store.commit(reservation, assistant("Stable"));
        var snapshot = h.store.snapshot(ALICE, receipt.contextId());
        assertEquals("Original", snapshot.transcript().get(1).content());
        assertThrows(UnsupportedOperationException.class,
            () -> snapshot.transcript().add(user("Mutation")));
    }

    private static void execute(com.fasterxml.jackson.databind.JsonNode vector) {
        String id = vector.path("id").asText();
        assertFixtureContract(vector);
        switch (id) {
            case "nwp.llm-context.001" -> stateless();
            case "nwp.llm-context.002" -> create();
            case "nwp.llm-context.003" -> append();
            case "nwp.llm-context.004" -> cas();
            case "nwp.llm-context.005" -> fork();
            case "nwp.llm-context.006" -> reset();
            case "nwp.llm-context.007" -> bindingMismatch();
            case "nwp.llm-context.008" -> ownerBoundary();
            case "nwp.llm-context.009" -> abort();
            case "nwp.llm-context.010" -> lostCreate();
            case "nwp.llm-context.011" -> releaseExpiry();
            case "nwp.llm-context.012" -> usage();
            case "nwp.llm-context.013" -> advertised();
            case "nwp.llm-context.014" -> restart();
            case "nwp.llm-context.015" -> idempotency();
            case "nwp.llm-context.016" -> revocation();
            case "nwp.llm-context.017" -> limit();
            case "nwp.llm-context.018" -> advertised();
            case "nwp.llm-context.019" -> missingKey();
            default -> fail("Unimplemented shared vector: " + id);
        }
    }

    private static void assertFixtureContract(com.fasterxml.jackson.databind.JsonNode vector) {
        String id = vector.path("id").asText();
        var input = vector.path("input");
        var expected = vector.path("expected");
        assertTrue(input.size() > 0, id + " input must not be empty");
        assertTrue(expected.size() > 0, id + " expected must not be empty");
        switch (id.substring(id.length() - 3)) {
            case "001" -> {
                assertTrue(input.path("params").path("context").isMissingNode());
                assertEquals("stateless", text(expected, "mode"));
                assertTrue(bool(expected, "dispatched"));
                assertFalse(bool(expected, "context_mutated"));
            }
            case "002" -> {
                assertEquals(text(input, "owner_nid"), text(expected, "owner_nid"));
                assertEquals(1, number(expected, "version"));
                assertTrue(bool(expected, "committed"));
            }
            case "003" -> {
                var pre = input.path("pre_state");
                var params = input.path("params");
                assertEquals(number(pre, "version") + 1, number(expected, "version"));
                assertEquals(params.path("messages").size(), number(expected, "accepted_delta_message_count"));
                assertEquals(pre.path("messages").size() + params.path("messages").size() + 1,
                    number(expected, "post_message_count"));
            }
            case "004" -> {
                assertEquals(number(input.path("pre_state"), "version"), number(expected, "post_version"));
                assertEquals(number(input.path("pre_state"), "version"), number(expected.path("hint"), "current_version"));
                assertEquals(NwpErrorCodes.NWP_LLM_CONTEXT_VERSION_CONFLICT, text(expected, "error"));
            }
            case "005" -> {
                assertEquals(number(input.path("request"), "base_version"), number(expected, "parent_version"));
                assertEquals(number(input, "parent_version_at_child_commit"), number(expected, "post_parent_version"));
                assertEquals(1, number(expected, "version"));
            }
            case "006" -> {
                assertEquals(number(input.path("pre_state"), "version") + 1, number(expected, "version"));
                assertEquals(text(input.path("request"), "model"), text(expected, "resolved_model"));
            }
            case "007" -> {
                assertEquals(number(input.path("pre_state"), "version"), number(expected, "post_version"));
                assertEquals(NwpErrorCodes.NWP_LLM_CONTEXT_BINDING_MISMATCH, text(expected, "error"));
                assertFalse(bool(expected, "provider_dispatched") || bool(expected, "stateless_fallback"));
            }
            case "008" -> {
                assertNotEquals(text(input, "owner_nid"), text(input, "caller_nid"));
                assertFalse(arrayContains(input.path("caller_capabilities"), LlmActionCodec.CAPABILITY_LLM_CONTEXT));
                assertEquals(NwpErrorCodes.NWP_LLM_CONTEXT_FORBIDDEN, text(expected, "error"));
            }
            case "009" -> {
                assertEquals(number(input.path("pre_state"), "version"), number(expected, "post_version"));
                assertFalse(bool(expected, "committed"));
                assertTrue(bool(expected, "reservation_released"));
            }
            case "010" -> {
                var sequence = input.path("status_sequence");
                var terminal = sequence.path(sequence.size() - 1);
                assertFalse(bool(expected.path("running_status"), "context_id_present"));
                assertEquals(text(terminal, "context_id"), text(expected.path("completed_status"), "context_id"));
                assertEquals(number(terminal, "version"), number(expected.path("completed_status"), "version"));
            }
            case "011" -> {
                assertEquals(number(input.path("pre_state"), "version") + 1,
                    number(expected.path("release_receipt"), "version"));
                assertEquals(number(input.path("expiry_branch"), "active_version"),
                    number(expected.path("expiry_tombstone"), "version"));
            }
            case "012" -> {
                var usage = input.path("usage");
                assertEquals(number(usage, "input_tokens"),
                    number(usage, "reused_tokens") + number(usage, "evaluated_tokens"));
                assertTrue(number(usage, "wire_input_bytes") < number(input, "stateless_wire_input_bytes"));
                assertTrue(bool(expected, "usage_equation_valid") && bool(expected, "wire_input_smaller_than_stateless"));
            }
            case "013" -> {
                var context = input.path("manifest").path("context");
                assertEquals(input.path("implemented_operations"), context.path("operations"));
                assertEquals(text(input, "implemented_persistence"), text(context, "persistence"));
                assertTrue(bool(expected, "manifest_valid"));
                assertEquals(LlmActionCodec.CAPABILITY_LLM_CONTEXT, text(expected, "requires_capability"));
            }
            case "014" -> {
                assertEquals("process", text(input, "persistence"));
                assertEquals("process_restart", text(input, "event"));
                assertEquals(NwpErrorCodes.NWP_LLM_CONTEXT_NOT_FOUND, text(expected, "error"));
                assertFalse(bool(expected, "replacement_created") || bool(expected, "stateless_fallback"));
            }
            case "015" -> {
                var original = input.path("original");
                var content = new StringBuilder();
                original.path("chunks").forEach(chunk -> content.append(chunk.asText()));
                assertEquals(content.toString(), text(expected, "ordered_content"));
                assertNotEquals(text(original, "stream_id"), text(input, "replay_stream_id"));
                assertEquals(0, number(expected, "provider_invocations") + number(expected, "additional_context_commits"));
            }
            case "016" -> {
                assertEquals("valid", text(input, "authorization_at_admission"));
                assertEquals("revoked", text(input, "authorization_at_commit"));
                assertEquals(number(input.path("pre_state"), "version"), number(expected, "post_version"));
                assertEquals(NwpErrorCodes.NWP_AUTH_NID_REVOKED, text(expected, "error"));
            }
            case "017" -> {
                assertEquals(number(input, "max_contexts_per_principal"), number(input, "live_contexts"));
                assertEquals(NwpErrorCodes.NWP_LLM_CONTEXT_LIMIT_EXCEEDED, text(expected, "error"));
                assertFalse(bool(expected, "context_allocated"));
            }
            case "018" -> {
                assertFalse(arrayContains(input.path("advertised_operations"), text(input.path("request"), "operation")));
                assertEquals(NwpErrorCodes.NWP_LLM_CONTEXT_OPERATION_UNSUPPORTED, text(expected, "error"));
            }
            case "019" -> {
                assertFalse(bool(input, "idempotency_key_present"));
                assertEquals(NwpErrorCodes.NWP_ACTION_PARAMS_INVALID, text(expected, "error"));
                assertFalse(bool(expected, "context_allocated") || bool(expected, "provider_dispatched"));
            }
            default -> fail("Unimplemented fixture contract: " + id);
        }
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return node.path(field).asText();
    }
    private static int number(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return node.path(field).asInt();
    }
    private static boolean bool(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return node.path(field).asBoolean();
    }
    private static boolean arrayContains(com.fasterxml.jackson.databind.JsonNode array, String value) {
        for (var item : array) if (value.equals(item.asText())) return true;
        return false;
    }

    private static void stateless() {
        var request = new LlmCompleteActionRequest(null, "willow-small", null, false,
            List.of(user("Hello")), null, null);
        assertNull(request.context());
    }

    private static void create() {
        var h = new Harness();
        var reservation = h.store.reserve(h.request(LlmContextOperation.CREATE, "create-1"));
        var busy = h.store.status(ALICE, null, "create-1");
        assertEquals(LlmContextState.BUSY, busy.state());
        assertNull(busy.contextId());
        h.advance(5);
        var receipt = h.store.commit(reservation, assistant("First"));
        assertEquals(1, receipt.version());
        assertEquals(h.now.plusSeconds(3600), Instant.parse(receipt.expiresAt()));
        assertEquals(3, h.store.snapshot(ALICE, receipt.contextId()).transcript().size());
    }

    private static void append() {
        var h = new Harness();
        var created = h.create("create-1", null);
        var request = h.request(LlmContextOperation.APPEND, "append-1",
            created.contextId(), 1L, binding(), List.of(user("Two")), null);
        var receipt = h.store.commit(h.store.reserve(request), assistant("Second"));
        var snapshot = h.store.snapshot(ALICE, created.contextId());
        assertEquals(2, receipt.version());
        assertEquals(5, snapshot.transcript().size());
        assertEquals("Two", snapshot.transcript().get(3).content());
    }

    private static void cas() {
        var h = new Harness();
        var created = h.create("create-1", null);
        var winner = h.store.reserve(h.request(LlmContextOperation.APPEND,
            "winner", created.contextId(), 1L));
        assertCode(NwpErrorCodes.NWP_LLM_CONTEXT_VERSION_CONFLICT, () -> h.store.reserve(
            h.request(LlmContextOperation.APPEND, "loser", created.contextId(), 1L)));
        h.store.abort(winner, null);
        assertCode(NwpErrorCodes.NWP_LLM_CONTEXT_VERSION_CONFLICT, () -> h.store.reserve(
            h.request(LlmContextOperation.APPEND, "stale", created.contextId(), 0L)));
    }

    private static void fork() {
        var h = new Harness();
        var parent = h.create("create-1", null);
        var fork = h.store.reserve(h.request(LlmContextOperation.FORK, "fork-1",
            parent.contextId(), 1L, binding(), List.of(), null));
        var parentAppend = h.store.reserve(h.request(LlmContextOperation.APPEND,
            "parent-append", parent.contextId(), 1L));
        h.store.commit(parentAppend, assistant("Parent moved"));
        var child = h.store.commit(fork, assistant("Branch"));
        assertEquals(1L, child.parentVersion());
        assertEquals(2, h.store.snapshot(ALICE, parent.contextId()).version());
        assertEquals(4, h.store.snapshot(ALICE, child.contextId()).transcript().size());
    }

    private static void reset() {
        var h = new Harness();
        var created = h.create("create-1", null);
        var replacement = new LlmContextBinding("willow-medium",
            List.of(system("Use JSON.")), null, "runtime-2");
        var request = h.request(LlmContextOperation.RESET, "reset-1", created.contextId(), 1L,
            replacement, List.of(system("Use JSON."), user("Restart")), null);
        h.store.commit(h.store.reserve(request), assistant("{}"));
        var snapshot = h.store.snapshot(ALICE, created.contextId());
        assertEquals("willow-medium", snapshot.binding().model());
        assertEquals(3, snapshot.transcript().size());
    }

    private static void bindingMismatch() {
        var h = new Harness();
        var created = h.create("create-1", null);
        var changed = new LlmContextBinding("willow-large", List.of(system("Use JSON.")), null, "runtime-1");
        assertCode(NwpErrorCodes.NWP_LLM_CONTEXT_BINDING_MISMATCH, () -> h.store.reserve(
            h.request(LlmContextOperation.APPEND, "bad-binding", created.contextId(), 1L,
                changed, List.of(user("Continue")), null)));
    }

    private static void ownerBoundary() {
        var h = new Harness();
        var created = h.create("create-1", null);
        assertCode(NwpErrorCodes.NWP_LLM_CONTEXT_FORBIDDEN,
            () -> h.store.status(BOB, created.contextId(), null));
    }

    private static void abort() {
        var h = new Harness(10, 5, 32, null);
        var created = h.create("create-1", 10);
        var reservation = h.store.reserve(h.request(LlmContextOperation.APPEND,
            "abort-1", created.contextId(), 1L));
        h.advance(11);
        h.store.abort(reservation, "NPS-SERVER-TIMEOUT");
        assertEquals(LlmContextState.EXPIRED, h.store.status(ALICE, created.contextId(), null).state());
        assertEquals("NPS-SERVER-TIMEOUT", h.store.status(ALICE, null, "abort-1").errorCode());
    }

    private static void lostCreate() {
        var h = new Harness(10, 5, 32, null);
        var reservation = h.store.reserve(h.request(LlmContextOperation.CREATE, "lost-create"));
        h.store.commit(reservation, assistant("First"));
        var active = h.store.status(ALICE, null, "lost-create");
        h.advance(16);
        h.store.sweepExpired();
        assertEquals(active.contextId(), h.store.status(ALICE, null, "lost-create").contextId());
    }

    private static void releaseExpiry() {
        var h = new Harness(10, 5, 32, null);
        var created = h.create("create-1", 10);
        var released = h.store.release(ALICE, created.contextId(), 1, "create-1");
        assertEquals(2, released.version());
        assertEquals(released, h.store.release(ALICE, created.contextId(), 1, "create-1"));
        assertCode(NwpErrorCodes.NWP_ACTION_IDEMPOTENCY_CONFLICT,
            () -> h.store.release(ALICE, "ERITFBUWFxgZGhscHR4fIA", 1, "create-1"));
        assertCode(NwpErrorCodes.NWP_LLM_CONTEXT_NOT_FOUND, () -> h.store.reserve(
            h.request(LlmContextOperation.APPEND, "after-release", created.contextId(), 2L)));
        var expiring = h.create("create-expiring", 10);
        h.advance(11);
        h.store.sweepExpired();
        assertCode(NwpErrorCodes.NWP_LLM_CONTEXT_EXPIRED,
            () -> h.store.snapshot(ALICE, expiring.contextId()));
        h.advance(6);
        h.store.sweepExpired();
        assertCode(NwpErrorCodes.NWP_LLM_CONTEXT_NOT_FOUND,
            () -> h.store.status(ALICE, expiring.contextId(), null));
    }

    private static void usage() {
        var value = new LlmUsageDto(1200, 80, true, 1000, 200, 384L);
        assertEquals(value.inputTokens(), value.reusedTokens() + value.evaluatedTokens());
        assertTrue(value.cacheHit() && value.wireInputBytes() < 4096);
    }

    private static void advertised() {
        var supported = EnumSet.allOf(LlmContextOperation.class);
        supported.remove(LlmContextOperation.FORK);
        var h = new Harness(3600, 86400, 32, supported);
        var created = h.create("create-1", null);
        assertCode(NwpErrorCodes.NWP_LLM_CONTEXT_OPERATION_UNSUPPORTED, () -> h.store.reserve(
            h.request(LlmContextOperation.FORK, "fork-disabled", created.contextId(), 1L,
                binding(), List.of(), null)));
    }

    private static void restart() {
        var first = new Harness();
        var created = first.create("create-1", null);
        var restarted = new Harness();
        assertCode(NwpErrorCodes.NWP_LLM_CONTEXT_NOT_FOUND, () -> restarted.store.reserve(
            restarted.request(LlmContextOperation.APPEND, "after-restart", created.contextId(), 1L)));
    }

    private static void idempotency() {
        var h = new Harness();
        var created = h.create("stream-replay", null);
        assertCode(NwpErrorCodes.NWP_ACTION_IDEMPOTENCY_CONFLICT,
            () -> h.store.reserve(h.request(LlmContextOperation.CREATE, "stream-replay")));
        assertEquals(1, h.store.snapshot(ALICE, created.contextId()).version());
    }

    private static void revocation() {
        var h = new Harness();
        var created = h.create("create-1", null);
        var reservation = h.store.reserve(h.request(LlmContextOperation.APPEND,
            "revoked", created.contextId(), 1L));
        h.store.abort(reservation, NwpErrorCodes.NWP_AUTH_NID_REVOKED);
        assertEquals(NwpErrorCodes.NWP_AUTH_NID_REVOKED,
            h.store.status(ALICE, null, "revoked").errorCode());
    }

    private static void limit() {
        var h = new Harness(3600, 86400, 1, null);
        h.create("create-1", null);
        assertCode(NwpErrorCodes.NWP_LLM_CONTEXT_LIMIT_EXCEEDED,
            () -> h.store.reserve(h.request(LlmContextOperation.CREATE, "over-limit")));
    }

    private static void missingKey() {
        var h = new Harness();
        assertCode(NwpErrorCodes.NWP_ACTION_PARAMS_INVALID,
            () -> h.store.reserve(h.request(LlmContextOperation.CREATE, "")));
    }

    private static void assertCode(String code, Runnable operation) {
        var error = assertThrows(LlmContextStoreException.class, operation::run);
        assertEquals(code, error.errorCode());
    }

    private static Path findFixture() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("spec/conformance/nwp/llm_context_vectors.json");
            if (Files.exists(candidate)) return candidate;
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate llm_context_vectors.json");
    }

    private static LlmContextBinding binding() {
        return new LlmContextBinding("willow-small", List.of(system("Be concise.")), null, "runtime-1");
    }

    private static LlmMessageDto system(String value) { return message("system", value); }
    private static LlmMessageDto user(String value) { return message("user", value); }
    private static LlmMessageDto assistant(String value) { return message("assistant", value); }
    private static LlmMessageDto message(String role, String value) {
        return new LlmMessageDto(role, value, null, null, null);
    }

    private static final class Harness {
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        final ArrayDeque<String> ids = new ArrayDeque<>(List.of(
            "AQIDBAUGBwgJCgsMDQ4PEA", "ERITFBUWFxgZGhscHR4fIA",
            "ISIjJCUmJygpKissLS4vMA", "MTIzNDU2Nzg5Ojs8PT4_QA"));
        final InMemoryLlmContextStore store;

        Harness() { this(3600, 86400, 32, null); }
        Harness(int ttl, int tombstone, int maxContexts, EnumSet<LlmContextOperation> supported) {
            var options = new LlmContextStoreOptions();
            options.defaultTtlSeconds = ttl;
            options.tombstoneSeconds = tombstone;
            options.maxContextsPerPrincipal = maxContexts;
            if (supported != null) options.supportedOperations = supported;
            options.clock = () -> now;
            options.contextIdFactory = ids::removeFirst;
            store = new InMemoryLlmContextStore(options);
        }

        LlmContextMutationRequest request(LlmContextOperation operation, String key) {
            return request(operation, key, null, null, binding(),
                operation == LlmContextOperation.CREATE
                    ? List.of(system("Be concise."), user("One")) : List.of(user("Continue")), null);
        }

        LlmContextMutationRequest request(
            LlmContextOperation operation, String key, String contextId, Long baseVersion) {
            return request(operation, key, contextId, baseVersion, binding(),
                List.of(user("Continue")), null);
        }

        LlmContextMutationRequest request(
            LlmContextOperation operation, String key, String contextId, Long baseVersion,
            LlmContextBinding selectedBinding, List<LlmMessageDto> messages, Integer ttl) {
            return new LlmContextMutationRequest(operation, ALICE, contextId, baseVersion,
                selectedBinding, messages, ttl, key, "req-" + key);
        }

        LlmContextReceiptDto create(String key, Integer ttl) {
            var request = request(LlmContextOperation.CREATE, key);
            if (ttl != null) request = new LlmContextMutationRequest(request.operation(), request.owner(),
                null, null, request.binding(), request.messages(), ttl, key, request.requestId());
            return store.commit(store.reserve(request), assistant("First"));
        }

        void advance(long seconds) { now = now.plusSeconds(seconds); }
    }
}
