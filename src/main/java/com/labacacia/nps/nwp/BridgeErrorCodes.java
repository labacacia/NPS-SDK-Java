// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.labacacia.nps.core.NpsStatusCodes;

import java.util.Map;

/**
 * Bridge Node NWP error codes (NPS-CR-0001 outbound, NPS-CR-0010 inbound).
 *
 * <p>NPS-CR-0010 removed a set of invented statuses that a port MUST NOT reintroduce:
 * {@code NPS-SERVER-NOT-IMPLEMENTED}, {@code NPS-SERVER-ERROR},
 * {@code NPS-CLIENT-UNAUTHORIZED}, {@code NPS-CLIENT-BAD-REQUEST},
 * {@code NPS-SERVER-UPSTREAM-FAILED}. Every code below maps onto a real NPS status.</p>
 */
public interface BridgeErrorCodes {

    // ── Both directions (new in NPS-CR-0010) ─────────────────────────────────
    /** The Bridge does not declare this protocol in the requested direction. */
    String NWP_BRIDGE_DIRECTION_UNSUPPORTED   = "NWP-BRIDGE-DIRECTION-UNSUPPORTED";

    // ── Outbound: NPS → external ─────────────────────────────────────────────
    String NWP_BRIDGE_TARGET_INVALID          = "NWP-BRIDGE-TARGET-INVALID";
    String NWP_BRIDGE_PROTOCOL_UNSUPPORTED    = "NWP-BRIDGE-PROTOCOL-UNSUPPORTED";
    String NWP_BRIDGE_ENDPOINT_INVALID        = "NWP-BRIDGE-ENDPOINT-INVALID";
    String NWP_BRIDGE_UPSTREAM_FAILED         = "NWP-BRIDGE-UPSTREAM-FAILED";

    // ── Inbound: external → NPS ──────────────────────────────────────────────
    /** No fronted node/action matches the requested tool, skill, or resource URI. */
    String NWP_BRIDGE_SERVER_TOOL_NOT_FOUND   = "NWP-BRIDGE-SERVER-TOOL-NOT-FOUND";
    /**
     * A node is exposed but no dispatcher was configured behind it. Deliberately loud:
     * the tool appears in {@code tools/list} and the call fails with this code rather
     * than the node silently looking like it exposes nothing.
     */
    String NWP_BRIDGE_SERVER_DISPATCHER_MISSING = "NWP-BRIDGE-SERVER-DISPATCHER-MISSING";
    /** The configured dispatcher threw. */
    String NWP_BRIDGE_SERVER_DISPATCH_FAILED  = "NWP-BRIDGE-SERVER-DISPATCH-FAILED";

    /** Bridge error code → NPS status. */
    Map<String, String> BRIDGE_TO_NPS_STATUS = Map.ofEntries(
        Map.entry(NWP_BRIDGE_DIRECTION_UNSUPPORTED,   NpsStatusCodes.NPS_SERVER_UNSUPPORTED),
        Map.entry(NWP_BRIDGE_TARGET_INVALID,          NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE),
        Map.entry(NWP_BRIDGE_PROTOCOL_UNSUPPORTED,    NpsStatusCodes.NPS_SERVER_UNSUPPORTED),
        Map.entry(NWP_BRIDGE_ENDPOINT_INVALID,        NpsStatusCodes.NPS_CLIENT_UNPROCESSABLE),
        Map.entry(NWP_BRIDGE_UPSTREAM_FAILED,         NpsStatusCodes.NPS_DOWNSTREAM_UNAVAILABLE),
        Map.entry(NWP_BRIDGE_SERVER_TOOL_NOT_FOUND,   NpsStatusCodes.NPS_CLIENT_NOT_FOUND),
        Map.entry(NWP_BRIDGE_SERVER_DISPATCHER_MISSING, NpsStatusCodes.NPS_SERVER_INTERNAL),
        Map.entry(NWP_BRIDGE_SERVER_DISPATCH_FAILED,  NpsStatusCodes.NPS_SERVER_INTERNAL)
    );
}
