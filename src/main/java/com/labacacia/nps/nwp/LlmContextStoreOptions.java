// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;

/** Configuration for the process-local NWP context reference store. */
public final class LlmContextStoreOptions {
    public int maxContextsPerPrincipal = 32;
    public int defaultTtlSeconds = 3600;
    public int maxTtlSeconds = 3600;
    public int tombstoneSeconds = 86400;
    public Duration idempotencyTtl = Duration.ofHours(24);
    public Set<LlmContextOperation> supportedOperations = EnumSet.allOf(LlmContextOperation.class);
    public Supplier<Instant> clock = Instant::now;
    public Supplier<String> contextIdFactory;
}
