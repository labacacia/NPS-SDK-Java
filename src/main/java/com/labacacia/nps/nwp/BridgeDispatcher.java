// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.labacacia.nps.ncp.CapsFrame;

/**
 * Translates one NWP action invocation into a concrete non-NPS protocol call
 * (the <b>NPS → external</b> direction).
 */
public interface BridgeDispatcher {

    /** Bridge protocol identifier served by this dispatcher. */
    String protocol();

    /** Dispatch an action frame to the requested external target. */
    CapsFrame dispatch(ActionFrame frame, BridgeTarget target);
}
