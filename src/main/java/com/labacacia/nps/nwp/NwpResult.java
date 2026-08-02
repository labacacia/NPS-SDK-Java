// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.labacacia.nps.core.NpsStatusCodes;

/**
 * Outcome of one {@link NwpBackend} call (NPS-CR-0010).
 *
 * <p>This type is why the §16.3 mapping works: a failure carries the NPS status forward
 * instead of an opaque body, so the protocol servers can choose between a foreign-protocol
 * error and a domain {@code isError} result without guessing.</p>
 */
public record NwpResult(boolean ok, JsonNode payload,
                        String npsStatus, String nwpError, String message) {

    public static NwpResult success(JsonNode payload) {
        return new NwpResult(true, payload, null, null, null);
    }

    public static NwpResult failure(String npsStatus, String nwpError, String message) {
        return new NwpResult(false, null, npsStatus, nwpError, message);
    }

    public static NwpResult failure(String npsStatus) {
        return failure(npsStatus, null, null);
    }

    /** A backend threw while dispatching — an internal fault, not a domain failure. */
    public static NwpResult dispatchFailed(String message) {
        return failure(NpsStatusCodes.NPS_SERVER_INTERNAL,
            BridgeErrorCodes.NWP_BRIDGE_SERVER_DISPATCH_FAILED, message);
    }
}
