// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.ca;

import java.time.Instant;
import java.util.List;

/**
 * Persisted record of a NIP certificate (NPS-3 §5.1, NPS-CR-0003 §5.1.3).
 * Immutable; use {@link Builder} to construct and {@link #revoked} to derive a
 * revoked copy.
 */
public final class NipCertRecord {

    private final String       nid;
    private final String       entityType;   // "agent" | "node" | "operator"
    private final String       serial;
    private final String       pubKey;
    private final List<String> capabilities;
    private final String       scopeJson;    // JSON blob
    private final String       issuedBy;
    private final Instant      issuedAt;
    private final Instant      expiresAt;
    private final Instant      revokedAt;    // nullable
    private final String       revokeReason; // nullable
    private final String       metadataJson; // nullable
    private final String       nidRole;      // "group" | "session" | null (NPS-CR-0003)
    private final String       parentNid;    // nullable
    private final String       lineageJson;  // nullable, canonical signed lineage

    private NipCertRecord(Builder b) {
        this.nid          = b.nid;
        this.entityType   = b.entityType;
        this.serial       = b.serial;
        this.pubKey       = b.pubKey;
        this.capabilities = b.capabilities == null ? List.of() : List.copyOf(b.capabilities);
        this.scopeJson    = b.scopeJson;
        this.issuedBy     = b.issuedBy;
        this.issuedAt     = b.issuedAt;
        this.expiresAt    = b.expiresAt;
        this.revokedAt    = b.revokedAt;
        this.revokeReason = b.revokeReason;
        this.metadataJson = b.metadataJson;
        this.nidRole      = b.nidRole;
        this.parentNid    = b.parentNid;
        this.lineageJson  = b.lineageJson;
    }

    public String       nid()          { return nid; }
    public String       entityType()   { return entityType; }
    public String       serial()       { return serial; }
    public String       pubKey()       { return pubKey; }
    public List<String> capabilities() { return capabilities; }
    public String       scopeJson()    { return scopeJson; }
    public String       issuedBy()     { return issuedBy; }
    public Instant      issuedAt()     { return issuedAt; }
    public Instant      expiresAt()    { return expiresAt; }
    public Instant      revokedAt()    { return revokedAt; }
    public String       revokeReason() { return revokeReason; }
    public String       metadataJson() { return metadataJson; }
    public String       nidRole()      { return nidRole; }
    public String       parentNid()    { return parentNid; }
    public String       lineageJson()  { return lineageJson; }

    public boolean isRevoked() { return revokedAt != null; }

    /** Returns a copy of this record marked revoked at {@code at} with {@code reason}. */
    public NipCertRecord revoked(Instant at, String reason) {
        return toBuilder().revokedAt(at).revokeReason(reason).build();
    }

    public Builder toBuilder() {
        return new Builder()
            .nid(nid).entityType(entityType).serial(serial).pubKey(pubKey)
            .capabilities(capabilities).scopeJson(scopeJson).issuedBy(issuedBy)
            .issuedAt(issuedAt).expiresAt(expiresAt).revokedAt(revokedAt)
            .revokeReason(revokeReason).metadataJson(metadataJson)
            .nidRole(nidRole).parentNid(parentNid).lineageJson(lineageJson);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for {@link NipCertRecord}. */
    public static final class Builder {
        private String nid, entityType, serial, pubKey, scopeJson, issuedBy;
        private List<String> capabilities;
        private Instant issuedAt, expiresAt, revokedAt;
        private String revokeReason, metadataJson, nidRole, parentNid, lineageJson;

        public Builder nid(String v)          { this.nid = v; return this; }
        public Builder entityType(String v)   { this.entityType = v; return this; }
        public Builder serial(String v)       { this.serial = v; return this; }
        public Builder pubKey(String v)       { this.pubKey = v; return this; }
        public Builder capabilities(List<String> v) { this.capabilities = v; return this; }
        public Builder scopeJson(String v)    { this.scopeJson = v; return this; }
        public Builder issuedBy(String v)     { this.issuedBy = v; return this; }
        public Builder issuedAt(Instant v)    { this.issuedAt = v; return this; }
        public Builder expiresAt(Instant v)   { this.expiresAt = v; return this; }
        public Builder revokedAt(Instant v)   { this.revokedAt = v; return this; }
        public Builder revokeReason(String v) { this.revokeReason = v; return this; }
        public Builder metadataJson(String v) { this.metadataJson = v; return this; }
        public Builder nidRole(String v)      { this.nidRole = v; return this; }
        public Builder parentNid(String v)    { this.parentNid = v; return this; }
        public Builder lineageJson(String v)  { this.lineageJson = v; return this; }

        public NipCertRecord build() { return new NipCertRecord(this); }
    }
}
