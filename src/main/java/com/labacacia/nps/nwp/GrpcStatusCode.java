// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

/**
 * The canonical gRPC status codes, declared locally so that the §16.3 mapping and the
 * inbound gRPC service logic carry no grpc-java dependency. Ordinals match the wire
 * numbers defined by the gRPC specification.
 */
public enum GrpcStatusCode {
    OK(0),
    CANCELLED(1),
    UNKNOWN(2),
    INVALID_ARGUMENT(3),
    DEADLINE_EXCEEDED(4),
    NOT_FOUND(5),
    ALREADY_EXISTS(6),
    PERMISSION_DENIED(7),
    RESOURCE_EXHAUSTED(8),
    FAILED_PRECONDITION(9),
    ABORTED(10),
    OUT_OF_RANGE(11),
    UNIMPLEMENTED(12),
    INTERNAL(13),
    UNAVAILABLE(14),
    DATA_LOSS(15),
    UNAUTHENTICATED(16);

    private final int value;

    GrpcStatusCode(int value) { this.value = value; }

    public int value() { return value; }

    public static GrpcStatusCode fromValue(int v) {
        for (GrpcStatusCode c : values()) if (c.value == v) return c;
        return UNKNOWN;
    }
}
