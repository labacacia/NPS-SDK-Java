// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import java.util.List;

/** Opaque, single-use reservation returned after atomic admission. */
public final class LlmContextMutationReservation {
    final String reservationId;
    final LlmContextMutationRequest request;
    final String bindingFingerprint;
    final List<LlmMessageDto> baseTranscript;
    final Integer effectiveTtlSeconds;
    final String parentContextId;
    final Long parentVersion;

    LlmContextMutationReservation(
        String reservationId,
        LlmContextMutationRequest request,
        String bindingFingerprint,
        List<LlmMessageDto> baseTranscript,
        Integer effectiveTtlSeconds,
        String parentContextId,
        Long parentVersion) {
        this.reservationId = reservationId;
        this.request = request;
        this.bindingFingerprint = bindingFingerprint;
        this.baseTranscript = baseTranscript;
        this.effectiveTtlSeconds = effectiveTtlSeconds;
        this.parentContextId = parentContextId;
        this.parentVersion = parentVersion;
    }

    public LlmContextOperation operation() { return request.operation(); }
    public String requestId() { return request.requestId(); }
}
