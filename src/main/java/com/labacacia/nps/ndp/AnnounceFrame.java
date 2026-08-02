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
    /**
     * NPS-CR-0009 multi-Anchor HA. Epoch under which this Anchor owns its
     * {@link #clusterAnchor} cluster; starts at 1 and strictly increases on every
     * ownership transfer — a fencing token (NDP §9).
     *
     * <p>Optional; {@code null} means "absent", which readers MUST interpret as
     * {@code 1} (single-Anchor). The key is omitted entirely when null so that the
     * canonical bytes of a single-Anchor frame stay bit-identical to pre-CR-0009
     * frames. It IS part of the signed canonical form — changing it requires
     * re-signing, which is what stops replay of an old announce with an inflated
     * epoch. Contrast the NDP v0.9 liveness fields below, which are excluded.</p>
     */
    private final Long               clusterEpoch;        // nullable; uint64 semantics
    private final String             spawnSpecRef;        // nullable
    private final List<String>       bridgeProtocols;     // nullable — OUTBOUND: NPS → external
    /**
     * NPS-CR-0010 bidirectional Bridge. The external protocols this Bridge Node accepts
     * INBOUND (external → NPS), over the same value domain as {@link #bridgeProtocols}
     * ({@code http}, {@code grpc}, {@code mcp}, {@code a2a}). The two sets are
     * independent; a protocol MAY appear in both.
     *
     * <p>Receivers MUST treat an absent key as {@code []} — a pre-alpha.16 outbound-only
     * Bridge Node. A node declaring {@code node_roles: ["bridge"]} MUST have at least one
     * of the two non-empty. Signed, and omitted entirely (never {@code null}) when
     * unset.</p>
     */
    private final List<String>       bridgeInboundProtocols; // nullable — INBOUND: external → NPS
    private final String             activationMode;      // nullable
    private final Map<String,Object> activationEndpoint;  // nullable; same shape as addresses[] entry
    private final int                heartbeatIntervalMs; // NDP v0.9; default 60000
    // NDP v0.9 liveness — wire-only, EXCLUDED from the signed canonical form
    // (last_seen updates every heartbeat → must not require re-signing; §3.2.1).
    private final String             health;              // "healthy"/"degraded"/"draining"; nullable
    private final String             lastSeen;            // ISO 8601 UTC liveness beat; nullable
    private Long                     graphSeq;            // nullable; signed NDP registry sequence

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

    /**
     * Pre-CR-0009 full constructor — delegates with {@code cluster_epoch = null}
     * (absent ⇒ 1, single-Anchor).
     */
    public AnnounceFrame(String nid, List<Map<String,Object>> addresses,
                         List<String> capabilities, int ttl, String timestamp,
                         String signature, String nodeType, List<String> nodeRoles,
                         String clusterAnchor, String spawnSpecRef,
                         List<String> bridgeProtocols, String activationMode,
                         Map<String,Object> activationEndpoint, int heartbeatIntervalMs,
                         String health, String lastSeen) {
        this(nid, addresses, capabilities, ttl, timestamp, signature, nodeType, nodeRoles,
            clusterAnchor, null, spawnSpecRef, bridgeProtocols, activationMode,
            activationEndpoint, heartbeatIntervalMs, health, lastSeen);
    }

    /**
     * Constructor including the NPS-CR-0009 {@code cluster_epoch} — delegates with
     * {@code bridge_inbound_protocols = null} (absent ⇒ outbound-only Bridge Node).
     */
    public AnnounceFrame(String nid, List<Map<String,Object>> addresses,
                         List<String> capabilities, int ttl, String timestamp,
                         String signature, String nodeType, List<String> nodeRoles,
                         String clusterAnchor, Long clusterEpoch, String spawnSpecRef,
                         List<String> bridgeProtocols, String activationMode,
                         Map<String,Object> activationEndpoint, int heartbeatIntervalMs,
                         String health, String lastSeen) {
        this(nid, addresses, capabilities, ttl, timestamp, signature, nodeType, nodeRoles,
            clusterAnchor, clusterEpoch, spawnSpecRef, bridgeProtocols, null, activationMode,
            activationEndpoint, heartbeatIntervalMs, health, lastSeen);
    }

    /**
     * Full constructor including the NPS-CR-0009 {@code cluster_epoch} and the
     * NPS-CR-0010 {@code bridge_inbound_protocols}.
     */
    public AnnounceFrame(String nid, List<Map<String,Object>> addresses,
                         List<String> capabilities, int ttl, String timestamp,
                         String signature, String nodeType, List<String> nodeRoles,
                         String clusterAnchor, Long clusterEpoch, String spawnSpecRef,
                         List<String> bridgeProtocols, List<String> bridgeInboundProtocols,
                         String activationMode,
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
        this.clusterEpoch         = clusterEpoch;
        this.spawnSpecRef         = spawnSpecRef;
        this.bridgeProtocols      = bridgeProtocols;
        this.bridgeInboundProtocols = bridgeInboundProtocols;
        this.activationMode       = activationMode;
        this.activationEndpoint   = activationEndpoint;
        this.heartbeatIntervalMs  = heartbeatIntervalMs;
        this.health               = health;
        this.lastSeen             = lastSeen;
        this.graphSeq             = null;
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
    /** NPS-CR-0009 {@code cluster_epoch}; {@code null} ⇒ absent ⇒ interpret as 1. */
    public Long   clusterEpoch()      { return clusterEpoch; }
    /** {@code cluster_epoch} with the NDP §9 default applied: absent ⇒ 1. */
    public long   effectiveClusterEpoch() { return clusterEpoch == null ? 1L : clusterEpoch; }
    public String spawnSpecRef()      { return spawnSpecRef; }
    public List<String> bridgeProtocols() { return bridgeProtocols; }
    /** NPS-CR-0010 {@code bridge_inbound_protocols}; {@code null} ⇒ absent ⇒ treat as {@code []}. */
    public List<String> bridgeInboundProtocols() { return bridgeInboundProtocols; }
    public String activationMode()    { return activationMode; }
    public Map<String,Object> activationEndpoint() { return activationEndpoint; }
    public int heartbeatIntervalMs()  { return heartbeatIntervalMs; }
    public String health()            { return health; }
    public String lastSeen()          { return lastSeen; }
    public Long graphSeq()            { return graphSeq; }

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
        // CR-0009: signed, and omitted entirely when null so that a frame that never
        // carried an epoch produces byte-identical canonical bytes to before the change.
        if (clusterEpoch != null)       m.put("cluster_epoch", clusterEpoch);
        if (spawnSpecRef != null)       m.put("spawn_spec_ref", spawnSpecRef);
        if (bridgeProtocols != null)    m.put("bridge_protocols", bridgeProtocols);
        // CR-0010: signed alongside bridge_protocols, omitted entirely when unset.
        if (bridgeInboundProtocols != null) m.put("bridge_inbound_protocols", bridgeInboundProtocols);
        if (activationMode != null)     m.put("activation_mode", activationMode);
        if (activationEndpoint != null) m.put("activation_endpoint", activationEndpoint);
        if (graphSeq != null)           m.put("graph_seq", graphSeq);
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
        Object epochRaw = d.get("cluster_epoch");
        Long epoch = epochRaw instanceof Number n ? n.longValue() : null;
        AnnounceFrame frame = new AnnounceFrame(
            (String) d.get("nid"),
            (List<Map<String,Object>>) d.get("addresses"),
            (List<String>) d.get("capabilities"),
            ((Number) d.get("ttl")).intValue(),
            (String) d.get("timestamp"),
            (String) d.get("signature"),
            (String) d.get("node_type"),
            rolesRaw instanceof String role ? List.of(role) : (List<String>) rolesRaw,
            (String) d.get("cluster_anchor"),
            epoch,
            (String) d.get("spawn_spec_ref"),
            (List<String>) d.get("bridge_protocols"),
            (List<String>) d.get("bridge_inbound_protocols"),
            (String) d.get("activation_mode"),
            (Map<String,Object>) d.get("activation_endpoint"),
            hbMs,
            (String) d.get("health"),
            (String) d.get("last_seen")
        );
        Object graphSeqRaw = d.get("graph_seq");
        frame.graphSeq = graphSeqRaw instanceof Number n ? n.longValue() : null;
        return frame;
    }
}
