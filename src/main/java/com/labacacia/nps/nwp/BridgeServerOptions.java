// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.sun.net.httpserver.HttpExchange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP hosting configuration for {@link BridgeServerHandler} (NPS-CR-0010).
 *
 * <p>Extends the transport-independent {@link BridgeInboundOptions} with paths, the
 * host-context-bound verifier, and limits. The protocol servers never see this type.</p>
 */
public final class BridgeServerOptions extends BridgeInboundOptions {

    /** Verifies a caller NID against the live host context — e.g. a NIP client certificate. */
    @FunctionalInterface
    public interface AgentVerifier { boolean verify(String agentNid, HttpExchange exchange); }

    // ── Paths ────────────────────────────────────────────────────────────────
    public String pathPrefix       = "";
    public String mcpPath          = "/mcp";
    public String mcpSsePath       = "/mcp/sse";
    public String a2aPath          = "/a2a";
    public String a2aAgentCardPath = "/.well-known/agent.json";

    // ── Security ─────────────────────────────────────────────────────────────
    /**
     * If auth is required and no verifier is configured, every request is denied —
     * fail-closed. The verifier takes the full host context on purpose so a deployment
     * can bind the NID to a client certificate off the connection.
     */
    public AgentVerifier verifier;

    /** {@code 0} disables. Enforced twice: a Content-Length pre-check and a streaming cap. */
    public long maxRequestBodyBytes = 1024L * 1024L;

    /** {@code 0} disables. */
    public int dispatchTimeoutMs = 30_000;

    // ── In-process backend materialisation inputs ────────────────────────────
    public NwpNodeRole nodeRole = NwpNodeRole.ACTION;
    public Map<String, NwpActionDescriptor> actions = new LinkedHashMap<>();
    public InProcessNwpBackend.ActionDispatcher dispatch;
    public InProcessNwpBackend.QueryDispatcher  query;

    /** Remote nodes fronted over HTTP. */
    public List<NwpUpstream> upstreams = new ArrayList<>();

    /** {@link #pathPrefix} with trailing slashes removed. */
    public String normalisedPrefix() {
        return pathPrefix == null ? "" : pathPrefix.replaceAll("/+$", "");
    }
}
