// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.reputation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable representation of a single NPS-RFC-0004 reputation log entry.
 * Use {@link Builder} to construct instances.
 */
public final class ReputationLogEntry {

    private final int    v;
    private final String logId;
    private final long   seq;
    private final String timestamp;
    private final String subjectNid;
    private final IncidentType incident;
    /** Preserved wire string when {@link #incident} == {@link IncidentType#OTHER} and the original value was unknown. */
    private final String incidentRaw;
    private final Severity severity;
    private final ObservationWindow window;
    private final Map<String, Object> observation;
    private final String evidenceRef;
    private final String evidenceSha256;
    private final String issuerNid;
    private final String signature;

    private ReputationLogEntry(Builder b) {
        this.v             = b.v;
        this.logId         = b.logId;
        this.seq           = b.seq;
        this.timestamp     = b.timestamp;
        this.subjectNid    = b.subjectNid;
        this.incident      = b.incident;
        this.incidentRaw   = b.incidentRaw;
        this.severity      = b.severity;
        this.window        = b.window;
        this.observation   = b.observation;
        this.evidenceRef   = b.evidenceRef;
        this.evidenceSha256 = b.evidenceSha256;
        this.issuerNid     = b.issuerNid;
        this.signature     = b.signature;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public int             getV()             { return v; }
    public String          getLogId()         { return logId; }
    public long            getSeq()           { return seq; }
    public String          getTimestamp()     { return timestamp; }
    public String          getSubjectNid()    { return subjectNid; }
    public IncidentType    getIncident()      { return incident; }
    public String          getIncidentRaw()   { return incidentRaw; }
    public Severity        getSeverity()      { return severity; }
    public ObservationWindow getWindow()      { return window; }
    public Map<String, Object> getObservation() { return observation; }
    public String          getEvidenceRef()   { return evidenceRef; }
    public String          getEvidenceSha256(){ return evidenceSha256; }
    public String          getIssuerNid()     { return issuerNid; }
    public String          getSignature()     { return signature; }

    // ── Serialization ─────────────────────────────────────────────────────────

    /**
     * Returns a {@link Map} with snake_case keys and null fields omitted.
     * Suitable for JSON serialization via {@link com.labacacia.nps.nip.NipCanonicalJson}.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v",           v);
        m.put("log_id",      logId);
        m.put("seq",         seq);
        m.put("timestamp",   timestamp);
        m.put("subject_nid", subjectNid);
        // Use incidentRaw when present (preserves unknown forward-compat wire string)
        m.put("incident",    incidentRaw != null ? incidentRaw : incident.wire);
        m.put("severity",    severity.wire);
        if (window != null)        m.put("window",        window.toMap());
        if (observation != null)   m.put("observation",   observation);
        if (evidenceRef != null)   m.put("evidence_ref",  evidenceRef);
        if (evidenceSha256 != null) m.put("evidence_sha256", evidenceSha256);
        m.put("issuer_nid",  issuerNid);
        if (signature != null)     m.put("signature",     signature);
        return m;
    }

    /** Deserialize from a snake_case map (e.g. parsed from JSON). */
    @SuppressWarnings("unchecked")
    public static ReputationLogEntry fromMap(Map<String, Object> m) {
        Builder b = new Builder();
        b.v(toInt(m.get("v")));
        b.logId((String) m.get("log_id"));
        b.seq(toLong(m.get("seq")));
        b.timestamp((String) m.get("timestamp"));
        b.subjectNid((String) m.get("subject_nid"));

        String incidentWire = (String) m.get("incident");
        IncidentType it = IncidentType.fromWire(incidentWire);
        b.incident(it);
        // If resolved to OTHER but original wire wasn't "other", preserve raw
        if (it == IncidentType.OTHER && !"other".equals(incidentWire)) {
            b.incidentRaw(incidentWire);
        }

        b.severity(Severity.fromWire((String) m.get("severity")));

        Object win = m.get("window");
        if (win instanceof Map) {
            b.window(ObservationWindow.fromMap((Map<String, Object>) win));
        }

        Object obs = m.get("observation");
        if (obs instanceof Map) {
            b.observation((Map<String, Object>) obs);
        }

        b.evidenceRef((String) m.get("evidence_ref"));
        b.evidenceSha256((String) m.get("evidence_sha256"));
        b.issuerNid((String) m.get("issuer_nid"));
        b.signature((String) m.get("signature"));
        return b.build();
    }

    private static int toInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) return Integer.parseInt((String) o);
        return 0;
    }

    private static long toLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof String) return Long.parseLong((String) o);
        return 0L;
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private int    v = 1;
        private String logId;
        private long   seq;
        private String timestamp;
        private String subjectNid;
        private IncidentType incident = IncidentType.OTHER;
        private String incidentRaw;
        private Severity severity = Severity.INFO;
        private ObservationWindow window;
        private Map<String, Object> observation;
        private String evidenceRef;
        private String evidenceSha256;
        private String issuerNid;
        private String signature;

        public Builder v(int v)                           { this.v = v;                     return this; }
        public Builder logId(String logId)                { this.logId = logId;             return this; }
        public Builder seq(long seq)                      { this.seq = seq;                 return this; }
        public Builder timestamp(String timestamp)        { this.timestamp = timestamp;     return this; }
        public Builder subjectNid(String subjectNid)      { this.subjectNid = subjectNid;   return this; }
        public Builder incident(IncidentType incident)    { this.incident = incident;       return this; }
        public Builder incidentRaw(String incidentRaw)    { this.incidentRaw = incidentRaw; return this; }
        public Builder severity(Severity severity)        { this.severity = severity;       return this; }
        public Builder window(ObservationWindow window)   { this.window = window;           return this; }
        public Builder observation(Map<String, Object> o) { this.observation = o;           return this; }
        public Builder evidenceRef(String evidenceRef)    { this.evidenceRef = evidenceRef; return this; }
        public Builder evidenceSha256(String s)           { this.evidenceSha256 = s;        return this; }
        public Builder issuerNid(String issuerNid)        { this.issuerNid = issuerNid;     return this; }
        public Builder signature(String signature)        { this.signature = signature;     return this; }

        public ReputationLogEntry build() { return new ReputationLogEntry(this); }
    }
}
