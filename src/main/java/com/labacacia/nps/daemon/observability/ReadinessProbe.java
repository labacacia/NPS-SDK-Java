// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.daemon.observability;

/**
 * One readiness check — daemons register one per backing dependency (storage,
 * key material, …). {@code /readyz} returns 503 if any probe fails. Port of the
 * .NET {@code IReadinessProbe}. Probes MUST be fast.
 */
public interface ReadinessProbe {

    /** Short name used in the JSON response (e.g. {@code "storage"}). */
    String name();

    /** Returns null on success, a short reason string on failure. */
    String check();
}
