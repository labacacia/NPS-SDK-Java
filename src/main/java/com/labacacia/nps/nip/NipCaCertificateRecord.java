// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Operator-facing certificate inventory entry. */
public record NipCaCertificateRecord(
    @JsonProperty("nid") String nid,
    @JsonProperty("entity_type") String entityType,
    @JsonProperty("serial") String serial,
    @JsonProperty("pub_key") String pubKey,
    @JsonProperty("capabilities") List<String> capabilities,
    @JsonProperty("scope") Object scope,
    @JsonProperty("issued_by") String issuedBy,
    @JsonProperty("issued_at") String issuedAt,
    @JsonProperty("expires_at") String expiresAt,
    @JsonProperty("revoked_at") String revokedAt,
    @JsonProperty("revoke_reason") String revokeReason,
    @JsonProperty("nid_role") String nidRole,
    @JsonProperty("parent_nid") String parentNid
) {}
