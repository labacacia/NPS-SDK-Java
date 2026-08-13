// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Thread-safe process-local implementation of the NWP 0.21 context state machine. */
public final class InMemoryLlmContextStore {
    private static final String COMPLETE_ACTION = "llm.complete";
    private static final String RELEASE_ACTION = "llm.context.release";
    private static final Pattern CONTEXT_ID = Pattern.compile("^[A-Za-z0-9_-]{22,128}$");
    private static final ObjectMapper JSON = JsonMapper.builder()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Object gate = new Object();
    private final int maxContexts;
    private final int defaultTtl;
    private final int maxTtl;
    private final int tombstoneSeconds;
    private final Duration idempotencyTtl;
    private final Set<LlmContextOperation> supported;
    private final Supplier<Instant> clock;
    private final Supplier<String> contextIdFactory;
    private final Map<String, Entry> contexts = new HashMap<>();
    private final Map<String, IdempotencyEntry> idempotency = new HashMap<>();
    private final Map<String, LlmContextMutationReservation> reservations = new HashMap<>();

    public InMemoryLlmContextStore() { this(new LlmContextStoreOptions()); }

    public InMemoryLlmContextStore(LlmContextStoreOptions options) {
        Objects.requireNonNull(options, "options");
        maxContexts = positive(options.maxContextsPerPrincipal, "maxContextsPerPrincipal");
        defaultTtl = positive(options.defaultTtlSeconds, "defaultTtlSeconds");
        maxTtl = positive(options.maxTtlSeconds, "maxTtlSeconds");
        tombstoneSeconds = positive(options.tombstoneSeconds, "tombstoneSeconds");
        idempotencyTtl = Objects.requireNonNull(options.idempotencyTtl, "idempotencyTtl");
        if (idempotencyTtl.isZero() || idempotencyTtl.isNegative()) {
            throw new IllegalArgumentException("idempotencyTtl must be positive.");
        }
        supported = EnumSet.noneOf(LlmContextOperation.class);
        if (options.supportedOperations != null) supported.addAll(options.supportedOperations);
        clock = Objects.requireNonNull(options.clock, "clock");
        contextIdFactory = options.contextIdFactory == null
            ? InMemoryLlmContextStore::newContextId
            : options.contextIdFactory;
    }

    /** Returns an immutable capability snapshot in canonical operation order. */
    public LlmContextStoreDescriptor descriptor() {
        synchronized (gate) {
            var operations = java.util.Arrays.stream(LlmContextOperation.values())
                .filter(supported::contains)
                .toList();
            return new LlmContextStoreDescriptor(
                operations, "process", maxContexts, maxTtl, tombstoneSeconds);
        }
    }

    public LlmContextMutationReservation reserve(LlmContextMutationRequest request) {
        synchronized (gate) {
            sweepLocked(now());
            validateRequest(request);
            ensureSupported(request.operation());
            String key = ownerKey(request.owner(), COMPLETE_ACTION, request.idempotencyKey());
            if (idempotency.containsKey(key)) {
                throw error(NwpErrorCodes.NWP_ACTION_IDEMPOTENCY_CONFLICT,
                    "An outcome already exists for this idempotency key.", null);
            }

            LlmContextMutationReservation reservation;
            if (request.operation() == LlmContextOperation.CREATE) {
                ensureAllocationAvailable(request.owner());
                reservation = newReservation(request, List.of(),
                    clamp(request.ttlSeconds() == null ? defaultTtl : request.ttlSeconds()), null, null);
            } else {
                Entry entry = requireMutable(request.owner(), request.contextId());
                if (entry.reservationId != null || entry.version != request.baseVersion()) {
                    throw error(NwpErrorCodes.NWP_LLM_CONTEXT_VERSION_CONFLICT,
                        "The context version is stale or a mutation is running.", entry.version);
                }
                String fingerprint = fingerprint(request.binding());
                if ((request.operation() == LlmContextOperation.APPEND ||
                    request.operation() == LlmContextOperation.FORK) &&
                    !entry.bindingFingerprint.equals(fingerprint)) {
                    throw error(NwpErrorCodes.NWP_LLM_CONTEXT_BINDING_MISMATCH,
                        "The request binding differs from the retained binding.", null);
                }
                if (request.operation() == LlmContextOperation.FORK) {
                    ensureAllocationAvailable(request.owner());
                }
                reservation = newReservation(request, entry.transcript, effectiveTtl(request, entry),
                    request.operation() == LlmContextOperation.FORK ? entry.contextId : null,
                    request.operation() == LlmContextOperation.FORK ? entry.version : null);
                if (request.operation() != LlmContextOperation.FORK) {
                    entry.reservationId = reservation.reservationId;
                }
            }
            reservations.put(reservation.reservationId, reservation);
            idempotency.put(key, new IdempotencyEntry("busy", now().plus(idempotencyTtl),
                request.requestId(), reservation.reservationId, null, null, null, null));
            return reservation;
        }
    }

    public LlmContextReceiptDto commit(
        LlmContextMutationReservation reservation,
        LlmMessageDto assistantResult) {
        synchronized (gate) {
            var current = requireReservation(reservation);
            var request = current.request;
            Instant expiry = current.effectiveTtlSeconds == null
                ? null : now().plusSeconds(current.effectiveTtlSeconds);
            Entry entry;
            String contextId;
            long version;
            if (request.operation() == LlmContextOperation.CREATE ||
                request.operation() == LlmContextOperation.FORK) {
                contextId = nextContextId();
                version = 1;
                var transcript = request.operation() == LlmContextOperation.FORK
                    ? mutableMessages(current.baseTranscript) : new ArrayList<LlmMessageDto>();
                transcript.addAll(copyMessages(request.messages()));
                transcript.add(copyMessage(assistantResult));
                entry = new Entry(contextId, request.owner(), version, LlmContextState.ACTIVE,
                    copyBinding(request.binding()), current.bindingFingerprint, transcript,
                    current.effectiveTtlSeconds == null ? 0 : current.effectiveTtlSeconds, expiry);
                contexts.put(contextId, entry);
            } else {
                entry = requireEntry(request.contextId());
                contextId = entry.contextId;
                version = Math.addExact(entry.version, 1);
                entry.version = version;
                entry.state = LlmContextState.ACTIVE;
                entry.reservationId = null;
                entry.expiresAt = expiry;
                entry.ttlSeconds = current.effectiveTtlSeconds == null ? 0 : current.effectiveTtlSeconds;
                if (request.operation() == LlmContextOperation.RESET) {
                    entry.binding = copyBinding(request.binding());
                    entry.bindingFingerprint = current.bindingFingerprint;
                    entry.transcript = mutableMessages(request.messages());
                    entry.transcript.add(copyMessage(assistantResult));
                } else {
                    entry.transcript.addAll(copyMessages(request.messages()));
                    entry.transcript.add(copyMessage(assistantResult));
                }
            }
            var receipt = new LlmContextReceiptDto(contextId, version, request.operation(),
                LlmContextState.ACTIVE, expiry == null ? null : expiry.toString(),
                current.parentContextId, current.parentVersion);
            completeIdempotency(current, receipt);
            reservations.remove(current.reservationId);
            return receipt;
        }
    }

    public void abort(LlmContextMutationReservation reservation, String errorCode) {
        synchronized (gate) {
            var current = requireReservation(reservation);
            clearReservation(current);
            reservations.remove(current.reservationId);
            var request = current.request;
            idempotency.put(ownerKey(request.owner(), COMPLETE_ACTION, request.idempotencyKey()),
                new IdempotencyEntry("failed", now().plus(idempotencyTtl), request.requestId(),
                    null, errorCode, null, null, null));
            sweepLocked(now());
        }
    }

    public LlmContextReceiptDto release(
        LlmContextOwner owner,
        String contextId,
        long baseVersion,
        String idempotencyKey) {
        synchronized (gate) {
            sweepLocked(now());
            ensureSupported(LlmContextOperation.RELEASE);
            validateContextId(contextId);
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw paramsInvalid("release requires idempotency_key.");
            }
            String key = ownerKey(owner, RELEASE_ACTION, idempotencyKey);
            var prior = idempotency.get(key);
            if (prior != null) {
                if (prior.receipt != null && Objects.equals(prior.contextId, contextId) &&
                    Objects.equals(prior.baseVersion, baseVersion)) {
                    return prior.receipt;
                }
                throw error(NwpErrorCodes.NWP_ACTION_IDEMPOTENCY_CONFLICT,
                    "A release with this idempotency key already exists.", null);
            }
            Entry entry = requireMutable(owner, contextId);
            if (entry.reservationId != null || entry.version != baseVersion) {
                throw error(NwpErrorCodes.NWP_LLM_CONTEXT_VERSION_CONFLICT,
                    "The context version is stale or a mutation is running.", entry.version);
            }
            entry.version = Math.addExact(entry.version, 1);
            entry.state = LlmContextState.RELEASED;
            entry.expiresAt = null;
            entry.tombstoneUntil = now().plusSeconds(tombstoneSeconds);
            var receipt = new LlmContextReceiptDto(contextId, entry.version,
                LlmContextOperation.RELEASE, LlmContextState.RELEASED, null, null, null);
            idempotency.put(key, new IdempotencyEntry("completed", now().plus(idempotencyTtl),
                null, null, null, receipt, contextId, baseVersion));
            return receipt;
        }
    }

    public LlmContextStatusDto status(
        LlmContextOwner owner,
        String contextId,
        String idempotencyKey) {
        synchronized (gate) {
            sweepLocked(now());
            if ((contextId == null) == (idempotencyKey == null)) {
                throw paramsInvalid("status requires exactly one locator.");
            }
            if (idempotencyKey != null) {
                var outcome = idempotency.get(ownerKey(owner, COMPLETE_ACTION, idempotencyKey));
                if (outcome == null) throw notFound();
                return switch (outcome.state) {
                    case "busy" -> new LlmContextStatusDto(LlmContextState.BUSY,
                        null, null, null, outcome.requestId, null);
                    case "failed" -> new LlmContextStatusDto(LlmContextState.FAILED,
                        null, null, null, outcome.requestId, outcome.errorCode);
                    default -> statusFromReceiptLocked(owner, outcome.receipt);
                };
            }
            return statusByContextLocked(owner, contextId);
        }
    }

    public LlmContextSnapshot snapshot(LlmContextOwner owner, String contextId) {
        synchronized (gate) {
            sweepLocked(now());
            Entry entry = requireMutable(owner, contextId);
            return new LlmContextSnapshot(entry.contextId, entry.version, entry.state,
                copyMessages(entry.transcript), copyBinding(entry.binding), entry.expiresAt);
        }
    }

    public int sweepExpired() {
        synchronized (gate) { return sweepLocked(now()); }
    }

    private LlmContextMutationReservation newReservation(
        LlmContextMutationRequest request,
        List<LlmMessageDto> transcript,
        Integer ttl,
        String parentId,
        Long parentVersion) {
        var retained = copyRequest(request);
        return new LlmContextMutationReservation(UUID.randomUUID().toString(), retained,
            fingerprint(retained.binding()), copyMessages(transcript), ttl, parentId, parentVersion);
    }

    private void validateRequest(LlmContextMutationRequest request) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.operation(), "request.operation");
        Objects.requireNonNull(request.owner(), "request.owner");
        Objects.requireNonNull(request.binding(), "request.binding");
        Objects.requireNonNull(request.binding().model(), "request.binding.model");
        Objects.requireNonNull(request.binding().systemMessages(), "request.binding.systemMessages");
        Objects.requireNonNull(request.binding().runtimeRevision(), "request.binding.runtimeRevision");
        Objects.requireNonNull(request.messages(), "request.messages");
        if (request.operation() == LlmContextOperation.RELEASE) {
            throw paramsInvalid("release uses the lifecycle action.");
        }
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw paramsInvalid("A stateful request requires idempotency_key.");
        }
        if (request.ttlSeconds() != null && request.ttlSeconds() <= 0) {
            throw paramsInvalid("ttl_seconds must be greater than zero.");
        }
        if (request.operation() == LlmContextOperation.CREATE) {
            if (request.contextId() != null || request.baseVersion() != null) {
                throw paramsInvalid("create forbids context_id and base_version.");
            }
        } else {
            if (request.contextId() == null || request.baseVersion() == null) {
                throw paramsInvalid("append/fork/reset require context_id and base_version.");
            }
            validateContextId(request.contextId());
        }
        if (request.operation() != LlmContextOperation.FORK && request.messages().isEmpty()) {
            throw paramsInvalid("Only fork may carry an empty message delta.");
        }
        if ((request.operation() == LlmContextOperation.APPEND ||
            request.operation() == LlmContextOperation.FORK) &&
            request.messages().stream().anyMatch(message -> "system".equalsIgnoreCase(message.role()))) {
            throw error(NwpErrorCodes.NWP_LLM_CONTEXT_BINDING_MISMATCH,
                "append/fork deltas must not contain system messages.", null);
        }
    }

    private Integer effectiveTtl(LlmContextMutationRequest request, Entry entry) {
        if (request.ttlSeconds() != null) return clamp(request.ttlSeconds());
        if (request.operation() == LlmContextOperation.FORK) {
            if (entry.expiresAt == null) return null;
            Duration remaining = Duration.between(now(), entry.expiresAt);
            long seconds = remaining.getSeconds() + (remaining.getNano() == 0 ? 0 : 1);
            return Math.max(1, Math.toIntExact(seconds));
        }
        return entry.ttlSeconds == 0 ? null : entry.ttlSeconds;
    }

    private void ensureAllocationAvailable(LlmContextOwner owner) {
        long live = contexts.values().stream()
            .filter(entry -> entry.owner.equals(owner) && entry.state == LlmContextState.ACTIVE).count();
        long pending = reservations.values().stream()
            .filter(item -> item.request.owner().equals(owner) &&
                (item.operation() == LlmContextOperation.CREATE || item.operation() == LlmContextOperation.FORK))
            .count();
        if (live + pending >= maxContexts) {
            throw error(NwpErrorCodes.NWP_LLM_CONTEXT_LIMIT_EXCEEDED,
                "The principal's live context limit has been reached.", null);
        }
    }

    private void ensureSupported(LlmContextOperation operation) {
        if (!supported.contains(operation)) {
            throw error(NwpErrorCodes.NWP_LLM_CONTEXT_OPERATION_UNSUPPORTED,
                "Context operation is not advertised: " + operation, null);
        }
    }

    private Entry requireMutable(LlmContextOwner owner, String contextId) {
        Entry entry = requireEntry(contextId);
        if (!entry.owner.equals(owner)) {
            throw error(NwpErrorCodes.NWP_LLM_CONTEXT_FORBIDDEN,
                "The caller does not own this context.", null);
        }
        if (entry.state == LlmContextState.EXPIRED) {
            throw error(NwpErrorCodes.NWP_LLM_CONTEXT_EXPIRED, "The context expired.", entry.version);
        }
        if (entry.state == LlmContextState.RELEASED) throw notFound();
        return entry;
    }

    private Entry requireEntry(String contextId) {
        Entry entry = contexts.get(contextId);
        if (entry == null) throw notFound();
        return entry;
    }

    private LlmContextMutationReservation requireReservation(LlmContextMutationReservation value) {
        if (value == null || reservations.get(value.reservationId) != value) {
            throw new IllegalStateException("The context reservation is not active.");
        }
        return value;
    }

    private void clearReservation(LlmContextMutationReservation reservation) {
        if (reservation.request.contextId() == null) return;
        Entry entry = contexts.get(reservation.request.contextId());
        if (entry != null && Objects.equals(entry.reservationId, reservation.reservationId)) {
            entry.reservationId = null;
        }
    }

    private void completeIdempotency(
        LlmContextMutationReservation reservation,
        LlmContextReceiptDto receipt) {
        var request = reservation.request;
        idempotency.put(ownerKey(request.owner(), COMPLETE_ACTION, request.idempotencyKey()),
            new IdempotencyEntry("completed", now().plus(idempotencyTtl), request.requestId(),
                null, null, receipt, null, null));
    }

    private LlmContextStatusDto statusFromReceiptLocked(
        LlmContextOwner owner,
        LlmContextReceiptDto receipt) {
        if (contexts.containsKey(receipt.contextId())) {
            return statusByContextLocked(owner, receipt.contextId());
        }
        return new LlmContextStatusDto(receipt.state(), receipt.contextId(), receipt.version(),
            receipt.expiresAt(), null, null);
    }

    private LlmContextStatusDto statusByContextLocked(LlmContextOwner owner, String contextId) {
        validateContextId(contextId);
        Entry entry = contexts.get(contextId);
        if (entry == null) throw notFound();
        if (!entry.owner.equals(owner)) {
            throw error(NwpErrorCodes.NWP_LLM_CONTEXT_FORBIDDEN,
                "The caller does not own this context.", null);
        }
        var active = entry.reservationId == null ? null : reservations.get(entry.reservationId);
        return new LlmContextStatusDto(active == null ? entry.state : LlmContextState.BUSY,
            entry.contextId, entry.version, entry.expiresAt == null ? null : entry.expiresAt.toString(),
            active == null ? null : active.requestId(), null);
    }

    private String nextContextId() {
        for (int attempt = 0; attempt < 8; attempt++) {
            String value = contextIdFactory.get();
            validateContextId(value);
            if (!contexts.containsKey(value)) return value;
        }
        throw new IllegalStateException("Context ID factory repeatedly produced collisions.");
    }

    private int sweepLocked(Instant now) {
        int changed = 0;
        for (Entry entry : contexts.values()) {
            if (entry.state == LlmContextState.ACTIVE && entry.reservationId == null &&
                entry.expiresAt != null && !entry.expiresAt.isAfter(now)) {
                entry.state = LlmContextState.EXPIRED;
                entry.expiresAt = null;
                entry.tombstoneUntil = now.plusSeconds(tombstoneSeconds);
                changed++;
            }
        }
        var removeContexts = contexts.entrySet().stream()
            .filter(item -> (item.getValue().state == LlmContextState.EXPIRED ||
                item.getValue().state == LlmContextState.RELEASED) &&
                item.getValue().tombstoneUntil != null && !item.getValue().tombstoneUntil.isAfter(now))
            .map(Map.Entry::getKey).toList();
        for (String key : removeContexts) { contexts.remove(key); changed++; }
        var removeOutcomes = idempotency.entrySet().stream()
            .filter(item -> !"busy".equals(item.getValue().state) &&
                !item.getValue().retainUntil.isAfter(now))
            .map(Map.Entry::getKey).toList();
        for (String key : removeOutcomes) { idempotency.remove(key); changed++; }
        return changed;
    }

    private String fingerprint(LlmContextBinding binding) {
        try {
            var value = new LinkedHashMap<String, Object>();
            value.put("model", binding.model());
            value.put("runtime_revision", binding.runtimeRevision());
            value.put("system_messages", binding.systemMessages());
            value.put("tools", binding.tools());
            byte[] encoded = JSON.writeValueAsBytes(value);
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (NoSuchAlgorithmException | java.io.IOException exception) {
            throw new IllegalStateException("Cannot fingerprint LLM context binding.", exception);
        }
    }

    private static LlmContextMutationRequest copyRequest(LlmContextMutationRequest value) {
        return new LlmContextMutationRequest(value.operation(), value.owner(), value.contextId(),
            value.baseVersion(), copyBinding(value.binding()), copyMessages(value.messages()),
            value.ttlSeconds(), value.idempotencyKey(), value.requestId());
    }

    private static LlmContextBinding copyBinding(LlmContextBinding value) {
        return new LlmContextBinding(value.model(), copyMessages(value.systemMessages()),
            copyTools(value.tools()), value.runtimeRevision());
    }

    private static List<LlmMessageDto> copyMessages(List<LlmMessageDto> values) {
        if (values == null) return List.of();
        return values.stream().map(InMemoryLlmContextStore::copyMessage).toList();
    }

    private static ArrayList<LlmMessageDto> mutableMessages(List<LlmMessageDto> values) {
        return new ArrayList<>(copyMessages(values));
    }

    private static LlmMessageDto copyMessage(LlmMessageDto value) {
        return new LlmMessageDto(value.role(), value.content(), value.toolCallId(), value.toolName(),
            value.toolCalls() == null ? null : List.copyOf(value.toolCalls()));
    }

    private static List<LlmToolDefinitionDto> copyTools(List<LlmToolDefinitionDto> values) {
        if (values == null) return null;
        return values.stream().map(tool -> new LlmToolDefinitionDto(tool.name(), tool.description(),
            tool.parameters() == null ? null : List.copyOf(tool.parameters()))).toList();
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive.");
        return value;
    }

    private int clamp(int value) { return Math.min(value, maxTtl); }
    private Instant now() { return clock.get(); }

    private static void validateContextId(String value) {
        if (value == null || !CONTEXT_ID.matcher(value).matches()) {
            throw paramsInvalid("context_id must be a 22-128 character unpadded base64url locator.");
        }
    }

    private static String ownerKey(LlmContextOwner owner, String action, String key) {
        return owner.nid() + '\u001f' + owner.securityScope() + '\u001f' + action + '\u001f' + key;
    }

    private static String newContextId() {
        byte[] value = new byte[16];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static LlmContextStoreException paramsInvalid(String message) {
        return error(NwpErrorCodes.NWP_ACTION_PARAMS_INVALID, message, null);
    }

    private static LlmContextStoreException notFound() {
        return error(NwpErrorCodes.NWP_LLM_CONTEXT_NOT_FOUND,
            "The context or retained outcome was not found.", null);
    }

    private static LlmContextStoreException error(String code, String message, Long version) {
        return new LlmContextStoreException(code, message, version);
    }

    private static final class Entry {
        final String contextId;
        final LlmContextOwner owner;
        long version;
        LlmContextState state;
        LlmContextBinding binding;
        String bindingFingerprint;
        List<LlmMessageDto> transcript;
        int ttlSeconds;
        Instant expiresAt;
        Instant tombstoneUntil;
        String reservationId;

        Entry(String contextId, LlmContextOwner owner, long version, LlmContextState state,
              LlmContextBinding binding, String fingerprint, List<LlmMessageDto> transcript,
              int ttlSeconds, Instant expiresAt) {
            this.contextId = contextId;
            this.owner = owner;
            this.version = version;
            this.state = state;
            this.binding = binding;
            this.bindingFingerprint = fingerprint;
            this.transcript = transcript;
            this.ttlSeconds = ttlSeconds;
            this.expiresAt = expiresAt;
        }
    }

    private record IdempotencyEntry(
        String state,
        Instant retainUntil,
        String requestId,
        String reservationId,
        String errorCode,
        LlmContextReceiptDto receipt,
        String contextId,
        Long baseVersion) {}
}
