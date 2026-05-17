// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

/**
 * Surfaces an Anchor-side topology error to the caller.
 * <p>
 * {@link #nwpErrorCode} matches one of the {@code NWP-TOPOLOGY-*} codes (or
 * another NWP error returned mid-stream); {@link #npsStatus} is the
 * corresponding NPS status code per {@code spec/status-codes.md}.
 * </p>
 */
public class AnchorTopologyException extends Exception {

    public final String nwpErrorCode;
    public final String npsStatus;

    public AnchorTopologyException(String nwpErrorCode, String npsStatus, String message) {
        super(message);
        this.nwpErrorCode = nwpErrorCode;
        this.npsStatus    = npsStatus;
    }
}
