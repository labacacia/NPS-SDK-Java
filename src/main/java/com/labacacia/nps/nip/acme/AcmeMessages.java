// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.acme;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * ACME wire-level DTOs (RFC 8555 + NPS-RFC-0002 §4.4). All records use Jackson annotations
 * for JSON property names; null fields are omitted via {@link JsonInclude.Include#NON_NULL}.
 */
public final class AcmeMessages {

    private AcmeMessages() {}

    // ── Directory (RFC 8555 §7.1.1) ──────────────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Directory(
        @JsonProperty("newNonce")   String newNonce,
        @JsonProperty("newAccount") String newAccount,
        @JsonProperty("newOrder")   String newOrder,
        @JsonProperty("revokeCert") String revokeCert,
        @JsonProperty("keyChange")  String keyChange,
        @JsonProperty("meta")       DirectoryMeta meta) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DirectoryMeta(
        @JsonProperty("termsOfService")          String termsOfService,
        @JsonProperty("website")                 String website,
        @JsonProperty("caaIdentities")           List<String> caaIdentities,
        @JsonProperty("externalAccountRequired") Boolean externalAccountRequired) {}

    // ── Account (RFC 8555 §7.3) ──────────────────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NewAccountPayload(
        @JsonProperty("termsOfServiceAgreed") Boolean termsOfServiceAgreed,
        @JsonProperty("contact")              List<String> contact,
        @JsonProperty("onlyReturnExisting")   Boolean onlyReturnExisting) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Account(
        @JsonProperty("status")  String status,
        @JsonProperty("contact") List<String> contact,
        @JsonProperty("orders")  String orders) {}

    // ── Order (RFC 8555 §7.1.3) ──────────────────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Identifier(
        @JsonProperty("type")  String type,    // "nid" per NPS-RFC-0002 §4.4
        @JsonProperty("value") String value) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NewOrderPayload(
        @JsonProperty("identifiers") List<Identifier> identifiers,
        @JsonProperty("notBefore")   String notBefore,
        @JsonProperty("notAfter")    String notAfter) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Order(
        @JsonProperty("status")         String status,
        @JsonProperty("expires")        String expires,
        @JsonProperty("identifiers")    List<Identifier> identifiers,
        @JsonProperty("authorizations") List<String> authorizations,
        @JsonProperty("finalize")       String finalizeUrl,
        @JsonProperty("certificate")    String certificate,
        @JsonProperty("error")          ProblemDetail error) {}

    // ── Authorization & Challenge (RFC 8555 §7.5) ────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Authorization(
        @JsonProperty("status")     String status,
        @JsonProperty("expires")    String expires,
        @JsonProperty("identifier") Identifier identifier,
        @JsonProperty("challenges") List<Challenge> challenges) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Challenge(
        @JsonProperty("type")      String type,        // "agent-01" per NPS-RFC-0002 §4.4
        @JsonProperty("url")       String url,
        @JsonProperty("status")    String status,
        @JsonProperty("token")     String token,
        @JsonProperty("validated") String validated,   // ISO 8601 timestamp
        @JsonProperty("error")     ProblemDetail error) {}

    /** agent-01 challenge response payload. {@code agent_signature} = base64url(Ed25519(token)). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChallengeRespondPayload(
        @JsonProperty("agent_signature") String agentSignature) {}

    // ── Finalize (RFC 8555 §7.4) ─────────────────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FinalizePayload(
        @JsonProperty("csr") String csr) {}   // base64url(CSR DER)

    // ── Errors (RFC 8555 §6.7 + RFC 7807) ────────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProblemDetail(
        @JsonProperty("type")   String type,     // urn:ietf:params:acme:error:*
        @JsonProperty("detail") String detail,
        @JsonProperty("status") Integer status) {}

    // ── ACME status enumeration values (RFC 8555 §7.1.6) ─────────────────────
    public static final class Status {
        private Status() {}
        public static final String PENDING     = "pending";
        public static final String READY       = "ready";
        public static final String PROCESSING  = "processing";
        public static final String VALID       = "valid";
        public static final String INVALID     = "invalid";
        public static final String EXPIRED     = "expired";
        public static final String DEACTIVATED = "deactivated";
        public static final String REVOKED     = "revoked";
        public static final String SUBMITTED   = "submitted";
    }
}
