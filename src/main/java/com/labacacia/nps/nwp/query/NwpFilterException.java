// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp.query;

import com.labacacia.nps.nwp.NwpErrorCodes;

/** Thrown when a NWP filter cannot be translated to SQL (NPS-2 §5.2). */
public final class NwpFilterException extends RuntimeException {

    private final String nwpErrorCode;

    public NwpFilterException(String message) {
        this(message, NwpErrorCodes.NWP_QUERY_FILTER_INVALID);
    }

    public NwpFilterException(String message, String errorCode) {
        super(message);
        this.nwpErrorCode = errorCode;
    }

    /** Canonical NWP wire error code (e.g. {@code NWP-QUERY-FIELD-UNKNOWN}). */
    public String nwpErrorCode() { return nwpErrorCode; }
}
