// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

/** Materialises the {@link NwpBackend} list from {@link BridgeServerOptions} (NPS-CR-0010). */
public final class BridgeServerBackends {

    private BridgeServerBackends() {}

    /**
     * Both deployment shapes may coexist in one Bridge.
     *
     * <p>An in-process backend is added iff a dispatcher, a query handler, <em>or at least
     * one declared action</em> is present. That last clause is deliberate: a deployment
     * that declares actions but forgets the dispatcher still gets the backend, so the
     * tools appear in {@code tools/list} and the call fails loudly with
     * {@code NWP-BRIDGE-SERVER-DISPATCHER-MISSING} rather than the node looking like it
     * exposes nothing.</p>
     *
     * @throws IllegalStateException when upstreams are configured but no HTTP client was supplied
     */
    public static List<NwpBackend> create(BridgeServerOptions options, HttpClient httpClient) {
        if (options == null) throw new IllegalArgumentException("options is required");
        List<NwpBackend> backends = new ArrayList<>();

        boolean hasInProcess = options.dispatch != null
            || options.query != null
            || (options.actions != null && !options.actions.isEmpty());
        if (hasInProcess) {
            NwpNodeDescriptor descriptor = new NwpNodeDescriptor(
                options.nodeId, options.nodeRole, options.serverName, options.description);
            List<NwpActionDescriptor> actions = options.actions == null
                ? List.of() : List.copyOf(options.actions.values());
            backends.add(new InProcessNwpBackend(descriptor, actions, options.dispatch, options.query));
        }

        if (options.upstreams != null && !options.upstreams.isEmpty()) {
            if (httpClient == null) {
                throw new IllegalStateException(
                    "Bridge upstreams are configured but no HttpClient was supplied.");
            }
            for (NwpUpstream upstream : options.upstreams) {
                backends.add(new HttpNwpBackend(upstream, httpClient));
            }
        }
        return backends;
    }
}
