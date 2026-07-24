// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** In-memory registry mapping bridge protocol identifiers to dispatchers. */
public final class BridgeDispatcherRegistry {

    private final Map<String, BridgeDispatcher> dispatchers =
        new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    /** Create an empty dispatcher registry. */
    public BridgeDispatcherRegistry() {}

    /** Create a registry preloaded with dispatchers. */
    public BridgeDispatcherRegistry(Iterable<BridgeDispatcher> dispatchers) {
        for (BridgeDispatcher dispatcher : dispatchers) {
            register(dispatcher);
        }
    }

    /**
     * Create a registry with all built-in dispatchers: HTTP/HTTPS, gRPC JSON,
     * MCP JSON-RPC, and A2A JSON-RPC.
     */
    public static BridgeDispatcherRegistry createDefault(HttpClient client) {
        return new BridgeDispatcherRegistry()
            .register(new HttpBridgeDispatcher(client))
            .register(new GrpcBridgeDispatcher(client))
            .register(new McpBridgeDispatcher(client))
            .register(new A2aBridgeDispatcher(client));
    }

    /** The currently registered protocol identifiers. */
    public Collection<String> protocols() {
        return new ArrayList<>(dispatchers.keySet());
    }

    /** The registered protocol identifiers, sorted case-insensitively. */
    public List<String> sortedProtocols() {
        List<String> keys = new ArrayList<>(dispatchers.keySet());
        keys.sort(String.CASE_INSENSITIVE_ORDER);
        return keys;
    }

    /** Register or replace the dispatcher for its protocol. */
    public BridgeDispatcherRegistry register(BridgeDispatcher dispatcher) {
        if (dispatcher == null) {
            throw new NullPointerException("dispatcher");
        }
        if (dispatcher.protocol() == null || dispatcher.protocol().isBlank()) {
            throw new IllegalArgumentException("Bridge dispatcher protocol must not be empty.");
        }
        dispatchers.put(dispatcher.protocol(), dispatcher);
        return this;
    }

    /** Resolve a dispatcher for {@code protocol}. */
    public BridgeDispatcher resolve(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            throw new BridgeDispatchException(
                BridgeErrorCodes.TARGET_INVALID, "bridge_target.protocol is required.");
        }
        BridgeDispatcher dispatcher = dispatchers.get(protocol);
        if (dispatcher == null) {
            throw new BridgeDispatchException(
                BridgeErrorCodes.PROTOCOL_UNSUPPORTED,
                "Bridge protocol '" + protocol + "' is not registered.");
        }
        return dispatcher;
    }
}
