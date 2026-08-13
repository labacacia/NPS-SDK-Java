// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import com.labacacia.nps.core.NpsStatusCodes;

final class LlmContextContractTest {
    @Test void contextErrorsUseTheSharedResourceLimitStatus() {
        assertEquals(NpsStatusCodes.NPS_LIMIT_RESOURCE,
            NwpErrorCodes.NWP_TO_NPS_STATUS.get(NwpErrorCodes.NWP_LLM_CONTEXT_LIMIT_EXCEEDED));
        assertEquals(429, NpsStatusCodes.toHttpStatus(NpsStatusCodes.NPS_LIMIT_RESOURCE));
    }

    @Test void statefulCompletionUsesCanonicalWireFields() {
        var request = new LlmCompleteActionRequest(null, "willow-small", null, false,
            List.of(new LlmMessageDto("user", "Hello", null, null, null)), null,
            new LlmContextRequestDto(LlmContextOperation.CREATE, null, null, 600));
        var wire = LlmActionCodec.toMap(request);
        assertEquals("llm.complete", wire.get("kind"));
        @SuppressWarnings("unchecked")
        var context = (java.util.Map<String, Object>) wire.get("context");
        assertEquals("create", context.get("operation"));
        assertEquals(600, context.get("ttl_seconds"));

        var decoded = LlmActionCodec.fromMap(wire, LlmCompleteActionRequest.class);
        assertEquals(LlmContextOperation.CREATE, decoded.context().operation());
    }

    @Test void lifecycleHelpersUseCanonicalActionIds() {
        var status = LlmActionCodec.statusFrame(new LlmContextStatusRequestDto(null, "create-1"), null);
        assertEquals(LlmActionCodec.LLM_CONTEXT_STATUS, status.actionId());
        assertEquals("create-1", status.params().get("idempotency_key"));

        var release = LlmActionCodec.releaseFrame(
            new LlmContextReleaseRequestDto("AQIDBAUGBwgJCgsMDQ4PEA", 7), "release-1", null);
        assertEquals(LlmActionCodec.LLM_CONTEXT_RELEASE, release.actionId());
        assertEquals("release-1", release.idempotencyKey());
        assertEquals(7L, release.params().get("base_version"));
    }
}
