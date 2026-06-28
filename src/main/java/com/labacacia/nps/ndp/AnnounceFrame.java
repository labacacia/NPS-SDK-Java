// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

import com.labacacia.nps.core.EncodingTier;
import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AnnounceFrame implements NpsFrame {

    private final String             nid;
    private final List<Map<String,Object>> addresses;
    private final List<String>       capabilities;
    private final int                ttl;
    private final String             timestamp;
    private final String             signature;
    private final String             nodeType;            // nullable
    private final List<String>       nodeRoles;           // nullable
    private final String             clusterAnchor;       // nullable
    private final String             spawnSpecRef;        // nullable
    private final List<String>       bridgeProtocols;     // nullable
    private final String             activationMode;      // nullable
    private final Map<String,Object> activationEndpoint;  // nullable; same shape as addresses[] entry
    private final int                heartbeatIntervalMs; // NDP v0.9; default 60000
    // NDP v0.9 liveness — wire-only, EXCLUDED from the signed canonical form
    // (last_seen updates every heartbeat → must not require re-signing; §3.2.1).
    private final String             health;              // "healthy"/"degraded"/"draining"; nullable
    private final String             lastSeen;            // ISO 8601 UTC liveness beat; nullable

    public AnnounceFrame(String nid, List<Map<String,Object>> addresses,
                         List<String> capabilities, int ttl, String timestamp,
                         String signature, String nodeType) {
        this(nid, addresses, capabilities, ttl, timestamp, signature, nodeType, 60_000);
    }

    public AnnounceFrame(String nid, List<Map<String,Object>> addresses,
                         List<String> capabilities, int ttl, String timestamp,
                         String signature, String nodeType, int heartbeatIntervalMs) {
        this(nid, addresses, capabilities, ttl, timestamp, signature, nodeType, heartbeatIntervalMs, null, null);
    }

    public AnnounceFrame(String nid, List<Map<String,Object>> addresses,
                         List<String> capabilities, int ttl, String timestamp,
                         String signature, String nodeType, int heartbeatIntervalMs,
                         String health, String lastSeen) {
        this(nid, addresses, capabilities, ttl, timestamp, signature, nodeType,
            null, null, null, null, null, null, heartbeatIntervalMs, health, lastSeen);
    }

    public AnnounceFrame(String nid, List<Map<String,Object>> addresses,
                         List<String> capabilities, int ttl, String timestamp,
                         String signature, String nodeType, List<String> nodeRoles,
                         String clusterAnchor, String spawnSpecRef,
                         List<String> bridgeProtocols, String activationMode,
                         Map<String,Object> activationEndpoint, int heartbeatIntervalMs,
                         String health, String lastSeen) {
        this.nid                  = nid;
        this.addresses            = addresses;
        this.capabilities         = capabilities;
        this.ttl                  = ttl;
        this.timestamp            = timestamp;
        this.signature            = signature;
        this.nodeType             = nodeType;
        this.nodeRoles            = nodeRoles;
        this.clusterAnchor        = clusterAnchor;
        this.spawnSpecRef         = spawnSpecRef;
        this.bridgeProtocols      = bridgeProtocols;
        this.activationMode       = activationMode;
        this.activationEndpoint   = activationEndpoint;
        this.heartbeatIntervalMs  = heartbeatIntervalMs;
        this.health               = health;
        this.lastSeen             = lastSeen;
    }

    @Override public FrameType    frameType()    { return FrameType.ANNOUNCE; }
    @Override public EncodingTier preferredTier() { return EncodingTier.MSGPACK; }

    public String nid()               { return nid; }
    public List<Map<String,Object>> addresses() { return addresses; }
    public List<String> capabilities(){ return capabilities; }
    public int ttl()                  { return ttl; }
    public String timestamp()         { return timestamp; }
    public String signature()         { return signature; }
    public String nodeType()          { return nodeType; }
    public List<String> nodeRoles()   { return nodeRoles; }
    public String clusterAnchor()     { return clusterAnchor; }
    public String spawnSpecRef()      { return spawnSpecRef; }
    public List<String> bridgeProtocols() { return bridgeProtocols; }
    public String activationMode()    { return activationMode; }
    public Map<String,Object> activationEndpoint() { return activationEndpoint; }
    public int heartbeatIntervalMs()  { return heartbeatIntervalMs; }
    public String health()            { return health; }
    public String lastSeen()          { return lastSeen; }

    public Map<String, Object> unsignedDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nid",          nid);
        m.put("addresses",    addresses);
        m.put("capabilities", capabilities);
        m.put("ttl",          ttl);
        m.put("timestamp",    timestamp);
        m.put("heartbeat_interval_ms", heartbeatIntervalMs);
        if (nodeType != null)           m.put("node_type", nodeType);
        if (nodeRoles != null)          m.put("node_roles", nodeRoles);
        if (clusterAnchor != null)      m.put("cluster_anchor", clusterAnchor);
        if (spawnSpecRef != null)       m.put("spawn_spec_ref", spawnSpecRef);
        if (bridgeProtocols != null)    m.put("bridge_protocols", bridgeProtocols);
        if (activationMode != null)     m.put("activation_mode", activationMode);
        if (activationEndpoint != null) m.put("activation_endpoint", activationEndpoint);
        return m;
    }

    @Override
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>(unsignedDict());
        m.put("signature", signature);
        // Liveness fields live on the wire only (not in unsignedDict → not signed).
        if (health != null)   m.put("health", health);
        if (lastSeen != null) m.put("last_seen", lastSeen);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static AnnounceFrame fromDict(Map<String, Object> d) {
        Object hb = d.get("heartbeat_interval_ms");
        int hbMs = hb instanceof Number n ? n.intValue() : 60_000;
        Object rolesRaw = d.containsKey("node_roles") ? d.get("node_roles") : d.get("node_kind");
        return new AnnounceFrame(
            (String) d.get("nid"),
            (List<Map<String,Object>>) d.get("addresses"),
            (List<String>) d.get("capabilities"),
            ((Number) d.get("ttl")).intValue(),
            (String) d.get("timestamp"),
            (String) d.get("signature"),
            (String) d.get("node_type"),
            rolesRaw instanceof String role ? List.of(role) : (List<String>) rolesRaw,
            (String) d.get("cluster_anchor"),
            (String) d.get("spawn_spec_ref"),
            (List<String>) d.get("bridge_protocols"),
            (String) d.get("activation_mode"),
            (Map<String,Object>) d.get("activation_endpoint"),
            hbMs,
            (String) d.get("health"),
            (String) d.get("last_seen")
        );
    }
}
