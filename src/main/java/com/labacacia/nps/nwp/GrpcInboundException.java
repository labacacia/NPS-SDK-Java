// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

/**
 * Transport-neutral stand-in for a gRPC {@code RpcException} raised by
 * {@link GrpcInboundService}. A hosting layer that does bind grpc-java rethrows this as
 * {@code StatusRuntimeException} with the same code and detail string.
 *
 * <p>The detail is {@code "{npsStatus} {nwpError}: {message}"} so that a caller can
 * recover the exact NPS fault, not just the coarse gRPC class. NPS-CR-0010 explicitly
 * fixed the old ingress behaviour of collapsing 401 and 403 both onto PERMISSION_DENIED
 * and every 5xx onto UNAVAILABLE — §16.3 forbids collapsing distinct NPS status classes.</p>
 */
public final class GrpcInboundException extends RuntimeException {

    public final GrpcStatusCode status;

    public GrpcInboundException(GrpcStatusCode status, String detail) {
        super(detail);
        this.status = status;
    }

    /** Build the detail string from an NPS failure triple. */
    public static GrpcInboundException of(String npsStatus, String nwpError, String message) {
        return new GrpcInboundException(BridgeErrorMap.toGrpcStatus(npsStatus),
            npsStatus + " " + nwpError + ": " + message);
    }
}
