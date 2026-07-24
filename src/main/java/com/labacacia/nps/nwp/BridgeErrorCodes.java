// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

/** NWP error codes used by Bridge dispatchers. */
public final class BridgeErrorCodes {

    private BridgeErrorCodes() {}

    /** The invocation does not contain a valid {@code bridge_target}. */
    public static final String TARGET_INVALID = "NWP-BRIDGE-TARGET-INVALID";

    /** The requested bridge protocol has no registered dispatcher. */
    public static final String PROTOCOL_UNSUPPORTED = "NWP-BRIDGE-PROTOCOL-UNSUPPORTED";

    /** The target endpoint is invalid or disallowed. */
    public static final String ENDPOINT_INVALID = "NWP-BRIDGE-ENDPOINT-INVALID";

    /** The external call failed or returned an unusable response. */
    public static final String UPSTREAM_FAILED = "NWP-BRIDGE-UPSTREAM-FAILED";

    /** An inbound Bridge server request named a tool/action that is not exposed. */
    public static final String SERVER_TOOL_NOT_FOUND = "NWP-BRIDGE-SERVER-TOOL-NOT-FOUND";

    /** An inbound Bridge server was not configured with a local action dispatcher. */
    public static final String SERVER_DISPATCHER_MISSING = "NWP-BRIDGE-SERVER-DISPATCHER-MISSING";

    /** An inbound Bridge server local action dispatch failed unexpectedly. */
    public static final String SERVER_DISPATCH_FAILED = "NWP-BRIDGE-SERVER-DISPATCH-FAILED";
}
