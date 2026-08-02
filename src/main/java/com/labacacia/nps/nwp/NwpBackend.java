// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * One NWP node fronted by an inbound Bridge (NPS-CR-0010).
 *
 * <p>The consolidation CR-0010 performs is a <strong>backend abstraction, not a
 * deletion</strong>: two deployment shapes, one interface. {@link InProcessNwpBackend}
 * dispatches to delegates in the same process (the SDK's shape);
 * {@link HttpNwpBackend} speaks HTTP to a remote node (the {@code compat/*-ingress}
 * shape). The MCP / A2A / gRPC inbound servers are written against this interface alone
 * and are unaware of which shape they are talking to.</p>
 */
public interface NwpBackend {

    /** Identity and role of the fronted node. */
    NwpNodeDescriptor getDescriptor();

    /** The raw {@code /.nwm} manifest. */
    NwpResult getManifest();

    /** Actions this node exposes; empty when the node is not invokable. */
    List<NwpActionDescriptor> getActions();

    /** Run a query against the node. */
    NwpResult query(JsonNode query);

    /** Invoke an action on the node. */
    NwpResult invoke(String actionId, JsonNode arguments, boolean async);
}
