// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.daemon.observability;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates graceful daemon shutdown. Portable port of the .NET
 * {@code GracefulShutdown}: on the shutdown signal it (1) flips a
 * {@link ShutdownState} liveness gate so health probes fail early, (2) logs an
 * info line, (3) runs registered drain callbacks, bounded by a drain timeout
 * (default 30s, matching NPS-Dev #45), then (4) logs completion.
 *
 * <p>Since the Java SDK has no host framework (no Spring / Kestrel), the signal
 * source is a JVM shutdown hook when {@link #installSignalHook()} is called;
 * tests drive {@link #triggerShutdown()} directly.</p>
 */
public final class GracefulShutdown {

    /** Default drain timeout for NPS daemons (NPS-Dev #45). */
    public static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(30);

    private final ShutdownState state;
    private final Duration drainTimeout;
    private final JsonStructuredLogger log;
    private final List<Runnable> drainTasks = new CopyOnWriteArrayList<>();
    private final AtomicBoolean fired = new AtomicBoolean(false);
    private final CountDownLatch completed = new CountDownLatch(1);

    public GracefulShutdown() {
        this(new ShutdownState(), DEFAULT_DRAIN_TIMEOUT, null);
    }

    public GracefulShutdown(ShutdownState state, Duration drainTimeout, JsonStructuredLogger log) {
        this.state = state;
        this.drainTimeout = drainTimeout == null ? DEFAULT_DRAIN_TIMEOUT : drainTimeout;
        this.log = log;
    }

    /** The liveness gate flipped on shutdown; wire it into a {@link ReadinessProbe}. */
    public ShutdownState state() { return state; }

    /** Registers a drain callback run (in registration order) on shutdown. */
    public GracefulShutdown onDrain(Runnable task) {
        drainTasks.add(task);
        return this;
    }

    /** Installs a JVM shutdown hook that invokes {@link #triggerShutdown()}. */
    public GracefulShutdown installSignalHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::triggerShutdown, "nps-graceful-shutdown"));
        return this;
    }

    /**
     * Runs the shutdown sequence. Idempotent — only the first call executes the
     * drain; later calls block until the first completes.
     */
    public void triggerShutdown() {
        if (!fired.compareAndSet(false, true)) {
            await();
            return;
        }
        state.markStopping();
        if (log != null)
            log.info("shutdown signal received; draining for up to "
                + drainTimeout.toSeconds() + "s");

        Thread drain = new Thread(() -> {
            for (Runnable t : drainTasks) {
                try { t.run(); }
                catch (Exception ex) {
                    if (log != null) log.error("drain task failed", ex);
                }
            }
        }, "nps-drain");
        drain.setDaemon(true);
        drain.start();
        try {
            drain.join(Math.max(1, drainTimeout.toMillis()));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        if (log != null) log.info("shutdown complete");
        completed.countDown();
    }

    /** Blocks until the drain has completed (or the drain timeout elapses). */
    public boolean await() {
        try {
            return completed.await(drainTimeout.toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Whether the shutdown sequence has been triggered. */
    public boolean isStopping() { return state.isStopping(); }
}
