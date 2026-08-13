// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.core.NpsStatusCodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Action Server coordinator for the NWP 0.21 stateful LLM context contract. */
public final class StatefulLlmActionProvider implements ActionNodeServer.Provider {
    public static final String COMPLETE_REQUEST_ANCHOR = "nps:system:llm.complete:request";
    public static final String STATUS_REQUEST_ANCHOR = "nps:system:llm.context.status:request";
    public static final String RELEASE_REQUEST_ANCHOR = "nps:system:llm.context.release:request";
    public static final String COMPLETE_RESPONSE_ANCHOR = "nps:system:llm.complete:response";
    public static final String STATUS_RESPONSE_ANCHOR = "nps:system:llm.context.status:response";
    public static final String RELEASE_RESPONSE_ANCHOR = "nps:system:llm.context.release:response";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Authorization checkpoint for context-bearing actions. */
    public enum AuthorizationStage { ADMISSION, COMMIT }

    /** Deployment-owned NIP/capability check. */
    @FunctionalInterface
    public interface ContextAuthorizer {
        void authorize(LlmContextOwner owner, String actionId, AuthorizationStage stage,
                       ActionNodeServer.ActionContext context) throws Exception;
    }

    /** Deployment-owned settings that are never sourced from request payloads. */
    public static final class Options {
        public final String securityScope;
        public final String runtimeRevision;
        public String providerName;
        public String defaultModel;
        public boolean supportsTools;
        public boolean supportsJsonMode;
        public String reasoningVisibility;
        public ContextAuthorizer authorizer;

        public Options(String securityScope, String runtimeRevision) {
            if (securityScope == null || securityScope.isBlank())
                throw new IllegalArgumentException("securityScope must not be empty");
            if (runtimeRevision == null || runtimeRevision.isBlank())
                throw new IllegalArgumentException("runtimeRevision must not be empty");
            this.securityScope = securityScope;
            this.runtimeRevision = runtimeRevision;
        }
    }

    private final ActionNodeServer.Provider inner;
    private final InMemoryLlmContextStore store;
    private final Options options;

    public StatefulLlmActionProvider(
        ActionNodeServer.Provider inner,
        InMemoryLlmContextStore store,
        Options options) {
        this.inner = Objects.requireNonNull(inner, "inner");
        this.store = Objects.requireNonNull(store, "store");
        this.options = Objects.requireNonNull(options, "options");
    }

    public InMemoryLlmContextStore store() { return store; }

    /** Registers the exact actions and truthful process-local NWM profile. */
    public void configureNode(ActionNodeServer.Options node) {
        boolean existed = node.actions.containsKey(LlmActionCodec.LLM_COMPLETE);
        var complete = node.actions.getOrDefault(
            LlmActionCodec.LLM_COMPLETE, new ActionNodeServer.ActionSpec());
        if (complete.description == null) complete.description = "Complete an LLM request";
        complete.paramsAnchor = COMPLETE_REQUEST_ANCHOR;
        complete.resultAnchor = COMPLETE_RESPONSE_ANCHOR;
        if (!existed) complete.async = true;
        complete.idempotent = true;
        complete.requiredCapability = LlmActionCodec.CAPABILITY_LLM_COMPLETE;
        node.actions.put(LlmActionCodec.LLM_COMPLETE, complete);

        var status = new ActionNodeServer.ActionSpec();
        status.description = "Inspect an LLM context or retained create outcome";
        status.paramsAnchor = STATUS_REQUEST_ANCHOR;
        status.resultAnchor = STATUS_RESPONSE_ANCHOR;
        status.requiredCapability = LlmActionCodec.CAPABILITY_LLM_CONTEXT;
        node.actions.put(LlmActionCodec.LLM_CONTEXT_STATUS, status);

        var release = new ActionNodeServer.ActionSpec();
        release.description = "Release an LLM context";
        release.paramsAnchor = RELEASE_REQUEST_ANCHOR;
        release.resultAnchor = RELEASE_RESPONSE_ANCHOR;
        release.idempotent = true;
        release.requiredCapability = LlmActionCodec.CAPABILITY_LLM_CONTEXT;
        node.actions.put(LlmActionCodec.LLM_CONTEXT_RELEASE, release);

        var descriptor = store.descriptor();
        var context = new LinkedHashMap<String, Object>();
        context.put("supported", true);
        context.put("operations", descriptor.operations());
        context.put("persistence", descriptor.persistence());
        context.put("max_contexts_per_principal", descriptor.maxContextsPerPrincipal());
        context.put("max_ttl_seconds", descriptor.maxTtlSeconds());
        context.put("tombstone_seconds", descriptor.tombstoneSeconds());
        var profile = new LinkedHashMap<String, Object>();
        profile.put("profile_version", "0.2");
        profile.put("actions", List.of(
            LlmActionCodec.LLM_COMPLETE,
            LlmActionCodec.LLM_CONTEXT_STATUS,
            LlmActionCodec.LLM_CONTEXT_RELEASE));
        profile.put("supports_stream", false);
        profile.put("supports_tools", options.supportsTools);
        profile.put("supports_json_mode", options.supportsJsonMode);
        profile.put("context", context);
        if (options.providerName != null) profile.put("provider", options.providerName);
        if (options.defaultModel != null) profile.put("default_model", options.defaultModel);
        if (options.reasoningVisibility != null)
            profile.put("reasoning_visibility", options.reasoningVisibility);
        node.profiles.put("llm", profile);
    }

    @Override
    public void authorize(ActionFrame frame, ActionNodeServer.ActionContext context) throws Exception {
        inner.authorize(frame, context);
        boolean requiresContext = LlmActionCodec.LLM_CONTEXT_STATUS.equals(frame.actionId()) ||
            LlmActionCodec.LLM_CONTEXT_RELEASE.equals(frame.actionId()) ||
            (LlmActionCodec.LLM_COMPLETE.equals(frame.actionId()) &&
                frame.params() != null && frame.params().containsKey("context"));
        if (!requiresContext) return;
        checkAuthorization(owner(context), frame.actionId(), AuthorizationStage.ADMISSION, context);
    }

    @Override
    public ActionNodeServer.ActionExecutionResult execute(
        ActionFrame frame,
        ActionNodeServer.ActionContext context) throws Exception {
        return switch (frame.actionId()) {
            case LlmActionCodec.LLM_COMPLETE -> complete(frame, context);
            case LlmActionCodec.LLM_CONTEXT_STATUS -> status(frame, context);
            case LlmActionCodec.LLM_CONTEXT_RELEASE -> release(frame, context);
            default -> inner.execute(frame, context);
        };
    }

    private ActionNodeServer.ActionExecutionResult complete(
        ActionFrame frame,
        ActionNodeServer.ActionContext context) throws Exception {
        LlmCompleteActionRequest request = decodeComplete(frame);
        if (!options.supportsTools && request.tools() != null && !request.tools().isEmpty())
            throw paramsError("This node does not advertise LLM tool-definition support.");
        if (request.context() == null) return inner.execute(frame, context);
        if (request.stream())
            throw paramsError("The Action Server context coordinator supports unary/async completion, not streaming.");
        if ((request.context().operation() == LlmContextOperation.APPEND ||
            request.context().operation() == LlmContextOperation.FORK ||
            request.context().operation() == LlmContextOperation.RESET) &&
            (request.context().contextId() == null || request.context().baseVersion() == null))
            throw paramsError("append/fork/reset require context_id and base_version");

        LlmContextOwner owner = owner(context);
        LlmContextBinding binding = resolveBinding(owner, request);
        LlmContextMutationReservation reservation;
        try {
            reservation = store.reserve(new LlmContextMutationRequest(
                request.context().operation(), owner, request.context().contextId(),
                request.context().baseVersion(), binding, request.messages(),
                request.context().ttlSeconds(), value(frame.idempotencyKey()), value(frame.requestId())));
        } catch (LlmContextStoreException error) {
            throw mapStoreError(error);
        }

        ReservationGuard guard = new ReservationGuard(store, reservation);
        context.cancellation().onCancel(() -> guard.abort(NwpErrorCodes.NWP_NODE_UNAVAILABLE));
        ActionNodeServer.ActionExecutionResult providerResult;
        try {
            providerResult = inner.execute(frame, context);
        } catch (Exception error) {
            guard.abort(errorCode(error));
            throw error;
        }
        if (context.cancellation().isCancelled() || Thread.currentThread().isInterrupted()) {
            guard.abort(NwpErrorCodes.NWP_NODE_UNAVAILABLE);
            throw new InterruptedException("LLM context completion was cancelled");
        }
        if (providerResult == null || providerResult.result == null) {
            guard.abort(NwpErrorCodes.NWP_NODE_UNAVAILABLE);
            throw internalError("Stateful llm.complete returned no official response object.");
        }

        LlmCompleteActionResponse response;
        try {
            response = JSON.convertValue(providerResult.result, LlmCompleteActionResponse.class);
            if (response.stopReason() == null) throw new IllegalArgumentException("stop_reason is required");
        } catch (IllegalArgumentException error) {
            guard.abort(NwpErrorCodes.NWP_NODE_UNAVAILABLE);
            throw internalError("Stateful llm.complete returned an invalid official response: " + error.getMessage());
        }
        if (response.stopReason() == LlmStopReason.ERROR) {
            guard.abort(NwpErrorCodes.NWP_NODE_UNAVAILABLE);
            return result(withContext(response, null), providerResult);
        }

        try {
            checkAuthorization(owner, frame.actionId(), AuthorizationStage.COMMIT, context);
        } catch (Exception error) {
            guard.abort(errorCode(error));
            throw error;
        }
        if (context.cancellation().isCancelled() || Thread.currentThread().isInterrupted()) {
            guard.abort(NwpErrorCodes.NWP_NODE_UNAVAILABLE);
            throw new InterruptedException("LLM context completion was cancelled");
        }

        LlmContextReceiptDto receipt;
        try {
            receipt = guard.commit(new LlmMessageDto(
                "assistant", response.content(), null, null, response.toolCalls()));
        } catch (LlmContextStoreException error) {
            throw mapStoreError(error);
        }
        return result(withContext(response, receipt), providerResult);
    }

    private ActionNodeServer.ActionExecutionResult status(
        ActionFrame frame,
        ActionNodeServer.ActionContext context) throws ActionNodeServer.ActionExecutionException {
        LlmContextStatusRequestDto request;
        try {
            request = LlmActionCodec.fromMap(requireParams(frame), LlmContextStatusRequestDto.class);
        } catch (IllegalArgumentException error) {
            throw paramsError(error.getMessage());
        }
        try {
            return result(store.status(owner(context), request.contextId(), request.idempotencyKey()),
                STATUS_RESPONSE_ANCHOR, 0);
        } catch (LlmContextStoreException error) {
            throw mapStoreError(error);
        }
    }

    private ActionNodeServer.ActionExecutionResult release(
        ActionFrame frame,
        ActionNodeServer.ActionContext context) throws ActionNodeServer.ActionExecutionException {
        LlmContextReleaseRequestDto request;
        try {
            var params = requireParams(frame);
            if (!params.containsKey("base_version"))
                throw new IllegalArgumentException("base_version is required");
            request = LlmActionCodec.fromMap(params, LlmContextReleaseRequestDto.class);
            if (request.contextId() == null || request.contextId().isBlank())
                throw new IllegalArgumentException("context_id is required");
        } catch (IllegalArgumentException error) {
            throw paramsError(error.getMessage());
        }
        try {
            return result(store.release(
                owner(context), request.contextId(), request.baseVersion(), value(frame.idempotencyKey())),
                RELEASE_RESPONSE_ANCHOR, 0);
        } catch (LlmContextStoreException error) {
            throw mapStoreError(error);
        }
    }

    private LlmContextBinding resolveBinding(
        LlmContextOwner owner,
        LlmCompleteActionRequest request) throws ActionNodeServer.ActionExecutionException {
        if (request.context().operation() == LlmContextOperation.APPEND ||
            request.context().operation() == LlmContextOperation.FORK) {
            if (request.context().contextId() == null || request.context().baseVersion() == null)
                throw paramsError("append/fork require context_id and base_version");
            try {
                var snapshot = store.snapshot(owner, request.context().contextId());
                return new LlmContextBinding(request.model(), snapshot.binding().systemMessages(),
                    request.tools() == null ? snapshot.binding().tools() : request.tools(),
                    options.runtimeRevision);
            } catch (LlmContextStoreException error) {
                throw mapStoreError(error);
            }
        }
        var systemMessages = new ArrayList<LlmMessageDto>();
        for (var message : request.messages()) {
            if ("system".equalsIgnoreCase(message.role())) systemMessages.add(message);
        }
        return new LlmContextBinding(request.model(), systemMessages, request.tools(), options.runtimeRevision);
    }

    private LlmCompleteActionRequest decodeComplete(ActionFrame frame)
        throws ActionNodeServer.ActionExecutionException {
        try {
            var request = LlmActionCodec.fromMap(requireParams(frame), LlmCompleteActionRequest.class);
            if (!LlmActionCodec.LLM_COMPLETE.equals(request.kind()))
                throw new IllegalArgumentException("kind must be llm.complete");
            if (request.model() == null || request.model().isBlank())
                throw new IllegalArgumentException("llm.complete requires a non-empty model");
            if (request.messages() == null)
                throw new IllegalArgumentException("messages must be an array");
            for (var message : request.messages()) {
                if (message == null || message.role() == null || message.role().isBlank())
                    throw new IllegalArgumentException("each message requires a non-empty role");
            }
            if (request.context() != null && request.context().operation() == null)
                throw new IllegalArgumentException("context.operation is invalid");
            return request;
        } catch (IllegalArgumentException error) {
            throw paramsError(error.getMessage());
        }
    }

    private LlmContextOwner owner(ActionNodeServer.ActionContext context)
        throws ActionNodeServer.ActionExecutionException {
        if (context.agentNid() == null || context.agentNid().isBlank()) {
            throw new ActionNodeServer.ActionExecutionException(
                401, NpsStatusCodes.NPS_AUTH_UNAUTHENTICATED,
                NwpErrorCodes.NWP_AUTH_NID_SCOPE_VIOLATION,
                "Stateful LLM context actions require an authenticated agent NID.");
        }
        return new LlmContextOwner(context.agentNid(), options.securityScope);
    }

    private void checkAuthorization(
        LlmContextOwner owner,
        String actionId,
        AuthorizationStage stage,
        ActionNodeServer.ActionContext context) throws Exception {
        if (options.authorizer != null) options.authorizer.authorize(owner, actionId, stage, context);
    }

    private static Map<String, Object> requireParams(ActionFrame frame) {
        if (frame.params() == null) throw new IllegalArgumentException("action requires an object params payload");
        return frame.params();
    }

    private static ActionNodeServer.ActionExecutionResult result(
        Object payload, String anchor, int tokenEst) {
        return new ActionNodeServer.ActionExecutionResult(payload, anchor, tokenEst);
    }

    private static ActionNodeServer.ActionExecutionResult result(
        LlmCompleteActionResponse response,
        ActionNodeServer.ActionExecutionResult providerResult) {
        return result(response,
            providerResult.anchorRef == null ? COMPLETE_RESPONSE_ANCHOR : providerResult.anchorRef,
            providerResult.tokenEst);
    }

    private static LlmCompleteActionResponse withContext(
        LlmCompleteActionResponse response,
        LlmContextReceiptDto context) {
        return new LlmCompleteActionResponse(response.stopReason(), response.content(),
            response.toolCalls(), response.error(), response.usage(), context);
    }

    private static String value(String value) { return value == null ? "" : value; }

    private static String errorCode(Exception error) {
        return error instanceof ActionNodeServer.ActionExecutionException actionError
            ? actionError.errorCode() : NwpErrorCodes.NWP_NODE_UNAVAILABLE;
    }

    private static ActionNodeServer.ActionExecutionException paramsError(String message) {
        return new ActionNodeServer.ActionExecutionException(
            422, NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE,
            NwpErrorCodes.NWP_ACTION_PARAMS_INVALID, message);
    }

    private static ActionNodeServer.ActionExecutionException internalError(String message) {
        return new ActionNodeServer.ActionExecutionException(
            500, NpsStatusCodes.NPS_SERVER_INTERNAL,
            NwpErrorCodes.NWP_NODE_UNAVAILABLE, message);
    }

    private static ActionNodeServer.ActionExecutionException mapStoreError(
        LlmContextStoreException error) {
        String status = NwpErrorCodes.NWP_TO_NPS_STATUS.getOrDefault(
            error.errorCode(), NpsStatusCodes.NPS_SERVER_INTERNAL);
        return new ActionNodeServer.ActionExecutionException(
            NpsStatusCodes.toHttpStatus(status), status, error.errorCode(), error.getMessage());
    }

    private enum ReservationState { ACTIVE, COMMITTING, ABORTED, COMMITTED }

    private static final class ReservationGuard {
        private final InMemoryLlmContextStore store;
        private final LlmContextMutationReservation reservation;
        private final AtomicReference<ReservationState> state =
            new AtomicReference<>(ReservationState.ACTIVE);

        ReservationGuard(InMemoryLlmContextStore store, LlmContextMutationReservation reservation) {
            this.store = store;
            this.reservation = reservation;
        }

        void abort(String errorCode) {
            if (!state.compareAndSet(ReservationState.ACTIVE, ReservationState.ABORTED)) return;
            try { store.abort(reservation, errorCode); }
            catch (IllegalStateException ignored) { /* a terminal transition won the race */ }
        }

        LlmContextReceiptDto commit(LlmMessageDto assistant) {
            if (!state.compareAndSet(ReservationState.ACTIVE, ReservationState.COMMITTING))
                throw new IllegalStateException("LLM context reservation is no longer active");
            try {
                var receipt = store.commit(reservation, assistant);
                state.set(ReservationState.COMMITTED);
                return receipt;
            } catch (RuntimeException error) {
                state.set(ReservationState.ABORTED);
                throw error;
            }
        }
    }
}
