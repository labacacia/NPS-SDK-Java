// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

public final class LlmActionCodec {
    public static final String LLM_COMPLETE = "llm.complete";
    public static final String LLM_CONTEXT_STATUS = "llm.context.status";
    public static final String LLM_CONTEXT_RELEASE = "llm.context.release";
    public static final String CAPABILITY_LLM_COMPLETE = "llm:complete";
    public static final String CAPABILITY_LLM_CONTEXT = "llm:context";
    public static final String CAPABILITY_LLM_STREAM = "llm:stream";
    public static final String CAPABILITY_LLM_TOOL_CALL = "llm:tool_call";

    private static final ObjectMapper JSON = new ObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private LlmActionCodec() {}

    public static Map<String, Object> toMap(Object payload) {
        return JSON.convertValue(payload, MAP);
    }

    public static <T> T fromMap(Map<String, Object> payload, Class<T> type) {
        return JSON.convertValue(payload, type);
    }

    public static ActionFrame completeFrame(
        LlmCompleteActionRequest request, String idempotencyKey, Integer timeoutMs, String requestId) {
        return new ActionFrame(LLM_COMPLETE, toMap(request), false, idempotencyKey,
            timeoutMs == null ? 5000 : timeoutMs, null, null, requestId);
    }

    public static ActionFrame statusFrame(LlmContextStatusRequestDto request, String requestId) {
        return new ActionFrame(LLM_CONTEXT_STATUS, toMap(request), false, null, 5000,
            null, null, requestId);
    }

    public static ActionFrame releaseFrame(
        LlmContextReleaseRequestDto request, String idempotencyKey, String requestId) {
        return new ActionFrame(LLM_CONTEXT_RELEASE, toMap(request), false, idempotencyKey, 5000,
            null, null, requestId);
    }
}
