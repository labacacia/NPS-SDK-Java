// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.core.NpsStatusCodes;
import com.labacacia.nps.ncp.StreamFrame;

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
    public static final String COMPLETE_STREAM_ANCHOR = "nps:system:llm.complete:stream";
    public static final String STATUS_RESPONSE_ANCHOR = "nps:system:llm.context.status:response";
    public static final String RELEASE_RESPONSE_ANCHOR = "nps:system:llm.context.release:response";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Authorization checkpoint for context-bearing actions. */
    public enum AuthorizationStage { ADMISSION, COMMIT }

    /** Deployment-owned NIP check for every supplied capability; absence fails closed. */
    @FunctionalInterface
    public interface ContextAuthorizer {
        void authorize(LlmContextOwner owner, String actionId, AuthorizationStage stage,
                       List<String> requiredCapabilities,
                       ActionNodeServer.ActionContext context) throws Exception;
    }

    /** Deployment-owned settings that are never sourced from request payloads. */
    public static final class Options {
        public final String securityScope;
        public final String runtimeRevision;
        public String providerName;
        public String defaultModel;
        public boolean supportsTools;
        public boolean supportsStream;
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
        profile.put("supports_stream", options.supportsStream);
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
        if (LlmActionCodec.LLM_COMPLETE.equals(frame.actionId()) && Boolean.TRUE.equals(frame.async_())) {
            LlmCompleteActionRequest request = decodeComplete(frame);
            if (request.stream()) throw paramsError("stream=true cannot be combined with async=true");
        }
        checkAuthorization(
            owner(context), frame.actionId(), AuthorizationStage.ADMISSION,
            requiredCapabilities(frame), context);
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
        if (request.stream() && !options.supportsStream)
            throw paramsError("This node does not advertise LLM streaming support.");
        if (request.stream() && Boolean.TRUE.equals(frame.async_()))
            throw paramsError("stream=true cannot be combined with async=true");
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
        if (request.stream()) {
            if (providerResult == null || providerResult.stream == null) {
                guard.abort(NwpErrorCodes.NWP_NODE_UNAVAILABLE);
                throw internalError("Stateful streaming llm.complete returned no StreamFrame sequence.");
            }
            var coordinated = new ActionNodeServer.ActionExecutionResult();
            coordinated.stream = coordinateStream(
                providerResult.stream, guard, owner, frame, context);
            coordinated.anchorRef = providerResult.anchorRef == null
                ? COMPLETE_STREAM_ANCHOR : providerResult.anchorRef;
            coordinated.tokenEst = providerResult.tokenEst;
            return coordinated;
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
            checkAuthorization(
                owner, frame.actionId(), AuthorizationStage.COMMIT,
                requiredCapabilities(frame), context);
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

    private ActionNodeServer.ActionStream coordinateStream(
        ActionNodeServer.ActionStream source,
        ReservationGuard guard,
        LlmContextOwner owner,
        ActionFrame requestFrame,
        ActionNodeServer.ActionContext actionContext) {
        return (cancellation, emit) -> {
            StringBuilder content = new StringBuilder();
            List<LlmToolCallDto> toolCalls = new ArrayList<>();
            boolean[] terminalSeen = {false};
            try {
                source.write(cancellation, frame -> {
                    if (terminalSeen[0]) throw internalError("LLM stream emitted frames after terminal.");
                    if (frame == null) throw internalError("LLM stream emitted a null frame.");
                    List<LlmCompleteStreamChunkDto> chunks = new ArrayList<>();
                    try {
                        for (Map<String, Object> payload : frame.data()) {
                            chunks.add(JSON.convertValue(payload, LlmCompleteStreamChunkDto.class));
                        }
                    } catch (IllegalArgumentException error) {
                        throw internalError(
                            "Stateful llm.complete returned an invalid stream payload: " + error.getMessage());
                    }
                    if (!frame.isLast()) {
                        for (var chunk : chunks) {
                            if (chunk.stopReason() != null || chunk.error() != null ||
                                chunk.usage() != null || chunk.context() != null) {
                                throw internalError(
                                    "LLM stream stop_reason, error, usage, and context are terminal-only fields.");
                            }
                        }
                    }
                    for (var chunk : chunks) {
                        if (chunk.contentDelta() != null) content.append(chunk.contentDelta());
                        if (chunk.toolCalls() != null) toolCalls.addAll(chunk.toolCalls());
                    }
                    List<LlmCompleteStreamChunkDto> sanitized = chunks.stream()
                        .map(chunk -> new LlmCompleteStreamChunkDto(
                            chunk.contentDelta(), chunk.toolCalls(), chunk.stopReason(),
                            chunk.error(), chunk.usage(), null))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
                    if (!frame.isLast()) {
                        emit.emit(rewriteStreamFrame(frame, sanitized, null));
                        return;
                    }

                    terminalSeen[0] = true;
                    int terminalIndex = -1;
                    boolean failed = frame.errorCode() != null;
                    for (int i = 0; i < sanitized.size(); i++) {
                        var chunk = sanitized.get(i);
                        if (chunk.stopReason() != null) terminalIndex = i;
                        if (chunk.stopReason() == LlmStopReason.ERROR || chunk.error() != null) failed = true;
                    }
                    if (failed) {
                        String code = frame.errorCode() == null
                            ? NwpErrorCodes.NWP_NODE_UNAVAILABLE : frame.errorCode();
                        guard.abort(code);
                        emit.emit(rewriteStreamFrame(frame, sanitized, code));
                        return;
                    }
                    if (terminalIndex < 0)
                        throw internalError("Successful LLM stream terminal frame requires stop_reason.");

                    try {
                        checkAuthorization(
                            owner, requestFrame.actionId(), AuthorizationStage.COMMIT,
                            requiredCapabilities(requestFrame), actionContext);
                    } catch (Exception error) {
                        guard.abort(errorCode(error));
                        throw error;
                    }
                    if (cancellation.isCancelled())
                        throw new InterruptedException("LLM context stream was cancelled");
                    LlmContextReceiptDto receipt = guard.commit(new LlmMessageDto(
                        "assistant", content.isEmpty() ? null : content.toString(),
                        null, null, toolCalls.isEmpty() ? null : List.copyOf(toolCalls)));
                    var terminal = sanitized.get(terminalIndex);
                    sanitized.set(terminalIndex, new LlmCompleteStreamChunkDto(
                        terminal.contentDelta(), terminal.toolCalls(), terminal.stopReason(),
                        terminal.error(), terminal.usage(), receipt));
                    emit.emit(rewriteStreamFrame(frame, sanitized, null));
                });
                if (!terminalSeen[0])
                    throw internalError("Stateful llm.complete stream ended without a terminal frame.");
            } finally {
                guard.abort(NwpErrorCodes.NWP_NODE_UNAVAILABLE);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static StreamFrame rewriteStreamFrame(
        StreamFrame frame,
        List<LlmCompleteStreamChunkDto> chunks,
        String errorCode) {
        List<Map<String, Object>> payloads = new ArrayList<>();
        for (var chunk : chunks) payloads.add(JSON.convertValue(chunk, Map.class));
        return new StreamFrame(
            frame.streamId(), frame.seq(), frame.isLast() || errorCode != null,
            payloads, frame.anchorRef(), frame.windowSize(),
            errorCode == null ? frame.errorCode() : errorCode);
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
        List<String> requiredCapabilities,
        ActionNodeServer.ActionContext context) throws Exception {
        if (options.authorizer == null) {
            throw new ActionNodeServer.ActionExecutionException(
                403, NpsStatusCodes.NPS_AUTH_FORBIDDEN,
                NwpErrorCodes.NWP_LLM_CONTEXT_FORBIDDEN,
                "Stateful LLM context authorization is not configured.");
        }
        options.authorizer.authorize(
            owner, actionId, stage, List.copyOf(requiredCapabilities), context);
    }

    private static List<String> requiredCapabilities(ActionFrame frame) {
        if (LlmActionCodec.LLM_CONTEXT_STATUS.equals(frame.actionId()) ||
            LlmActionCodec.LLM_CONTEXT_RELEASE.equals(frame.actionId())) {
            return List.of(LlmActionCodec.CAPABILITY_LLM_CONTEXT);
        }
        var capabilities = new ArrayList<String>();
        capabilities.add(LlmActionCodec.CAPABILITY_LLM_COMPLETE);
        capabilities.add(LlmActionCodec.CAPABILITY_LLM_CONTEXT);
        Map<String, Object> params = frame.params();
        if (params != null && Boolean.TRUE.equals(params.get("stream")))
            capabilities.add(LlmActionCodec.CAPABILITY_LLM_STREAM);
        if (params != null && params.get("tools") instanceof List<?> tools && !tools.isEmpty())
            capabilities.add(LlmActionCodec.CAPABILITY_LLM_TOOL_CALL);
        return capabilities;
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
