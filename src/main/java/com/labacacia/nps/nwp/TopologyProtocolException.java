// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.labacacia.nps.core.NpsStatusCodes;

/**
 * Server-side topology fault carrier. The transport catches it and renders it as an
 * ErrorFrame / HTTP error envelope carrying {@link #nwpErrorCode} and {@link #npsStatus}.
 *
 * <p>Used for the NPS-2 §12 topology request validation errors and, since NPS-CR-0009,
 * for the multi-Anchor HA faults {@code NWP-ANCHOR-NOT-LEADER} and
 * {@code NWP-ANCHOR-EPOCH-FENCED} (both {@code NPS-CLIENT-CONFLICT} ⇒ HTTP 409).</p>
 */
public class TopologyProtocolException extends RuntimeException {

    public final String nwpErrorCode;
    public final String npsStatus;

    public TopologyProtocolException(String nwpErrorCode, String npsStatus, String message) {
        super(message);
        this.nwpErrorCode = nwpErrorCode;
        this.npsStatus    = npsStatus;
    }

    /** HTTP status for this fault, per {@code spec/status-codes.md}. */
    public int httpStatus() { return NpsStatusCodes.toHttpStatus(npsStatus); }
}
