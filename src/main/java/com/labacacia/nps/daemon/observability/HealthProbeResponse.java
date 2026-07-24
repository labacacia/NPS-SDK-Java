// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.daemon.observability;

/**
 * Transport-neutral health/readiness response. Port of the .NET
 * {@code HealthProbeResponse} record. {@code reason} is null on success.
 */
public record HealthProbeResponse(
    int statusCode,
    String contentType,
    String body,
    String status,
    String reason) {
}
