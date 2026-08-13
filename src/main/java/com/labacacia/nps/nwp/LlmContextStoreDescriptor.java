// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import java.util.List;

/** Immutable capability surface advertised by an LLM context store. */
public record LlmContextStoreDescriptor(
    List<LlmContextOperation> operations,
    String persistence,
    int maxContextsPerPrincipal,
    int maxTtlSeconds,
    int tombstoneSeconds) {

    public LlmContextStoreDescriptor {
        operations = List.copyOf(operations);
    }
}
