// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.daemon.observability;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Liveness flag flipped when shutdown begins; read by health probes so
 * {@code /healthz} (or {@code /readyz}) can fail early during drain, letting a
 * load balancer stop routing before listeners actually close. Port of the .NET
 * {@code ShutdownState}.
 */
public final class ShutdownState {

    private final AtomicBoolean stopping = new AtomicBoolean(false);

    public boolean isStopping() { return stopping.get(); }

    public void markStopping() { stopping.set(true); }
}
