// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

/** HTTP header name constants for the NWP protocol. */
public interface NwpHeaders {

    /** Response header carrying the uint32 manifest version on GET /.nwm responses (NWP v0.14). */
    String X_NWM_VERSION = "X-NWM-Version";
}
