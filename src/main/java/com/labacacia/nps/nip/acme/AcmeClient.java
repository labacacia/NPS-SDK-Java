// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.acme;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labacacia.nps.nip.acme.AcmeMessages.Authorization;
import com.labacacia.nps.nip.acme.AcmeMessages.Challenge;
import com.labacacia.nps.nip.acme.AcmeMessages.Directory;
import com.labacacia.nps.nip.acme.AcmeMessages.FinalizePayload;
import com.labacacia.nps.nip.acme.AcmeMessages.Identifier;
import com.labacacia.nps.nip.acme.AcmeMessages.NewAccountPayload;
import com.labacacia.nps.nip.acme.AcmeMessages.NewOrderPayload;
import com.labacacia.nps.nip.acme.AcmeMessages.Order;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.security.auth.x500.X500Principal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.List;

/**
 * ACME client implementing the {@code agent-01} challenge type per NPS-RFC-0002 §4.4.
 *
 * <p>Flow: newNonce → newAccount → newOrder → fetch authz → sign challenge token →
 * finalize with CSR → fetch leaf cert.
 */
public final class AcmeClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder URL_ENC = Base64.getUrlEncoder().withoutPadding();

    private final HttpClient http;
    private final URI directoryUrl;
    private final KeyPair accountKey;

    private Directory directory;
    private String accountUrl;
    private String lastNonce;

    /**
     * @param http         A configured {@link HttpClient}; pass one with a shared executor for tests.
     * @param directoryUrl ACME directory endpoint URL.
     * @param accountKey   Ed25519 keypair used for both account JWK and challenge signatures.
     */
    public AcmeClient(HttpClient http, URI directoryUrl, KeyPair accountKey) {
        this.http         = http;
        this.directoryUrl = directoryUrl;
        this.accountKey   = accountKey;
    }

    public String accountUrl() { return accountUrl; }

    /**
     * Drive the full agent-01 flow for {@code nid}. Returns the issued PEM cert chain
     * (leaf + root, concatenated).
     */
    public String issueAgentCert(String nid) throws Exception {
        ensureDirectory();
        if (accountUrl == null) newAccount();
        Order order = newOrder(nid);
        Authorization authz = fetchAuthz(order.authorizations().get(0));
        respondAgent01(authz);
        Order finalized = finalizeOrder(order, nid);
        return downloadPem(finalized.certificate());
    }

    // ── Stages ───────────────────────────────────────────────────────────────

    private void ensureDirectory() throws Exception {
        if (directory != null) return;
        HttpResponse<String> resp = http.send(
            HttpRequest.newBuilder(directoryUrl).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        ensureSuccess(resp);
        directory = MAPPER.readValue(resp.body(), Directory.class);
        refreshNonce();
    }

    private void refreshNonce() throws Exception {
        HttpResponse<Void> resp = http.send(
            HttpRequest.newBuilder(URI.create(directory.newNonce()))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.discarding());
        ensureSuccess(resp);
        lastNonce = resp.headers().firstValue("Replay-Nonce")
            .orElseThrow(() -> new IllegalStateException("server omitted Replay-Nonce"));
    }

    private void newAccount() throws Exception {
        AcmeJws.Jwk jwk = AcmeJws.jwkFromPublicKey(accountKey.getPublic());
        AcmeJws.ProtectedHeader header = new AcmeJws.ProtectedHeader(
            AcmeJws.ALG_EDDSA, lastNonce, directory.newAccount(), jwk, null);
        AcmeJws.Envelope env = AcmeJws.sign(header, new NewAccountPayload(true, null, null), accountKey.getPrivate());

        HttpResponse<String> resp = http.send(
            buildPostRequest(directory.newAccount(), env),
            HttpResponse.BodyHandlers.ofString());
        ensureSuccess(resp);
        accountUrl = resp.headers().firstValue("Location")
            .orElseThrow(() -> new IllegalStateException("server omitted account Location"));
        captureNonce(resp);
    }

    private Order newOrder(String nid) throws Exception {
        AcmeJws.ProtectedHeader header = new AcmeJws.ProtectedHeader(
            AcmeJws.ALG_EDDSA, lastNonce, directory.newOrder(), null, accountUrl);
        AcmeJws.Envelope env = AcmeJws.sign(header,
            new NewOrderPayload(List.of(new Identifier(AcmeWire.IDENTIFIER_TYPE_NID, nid)), null, null),
            accountKey.getPrivate());

        HttpResponse<String> resp = http.send(
            buildPostRequest(directory.newOrder(), env),
            HttpResponse.BodyHandlers.ofString());
        ensureSuccess(resp);
        captureNonce(resp);
        return MAPPER.readValue(resp.body(), Order.class);
    }

    private Authorization fetchAuthz(String url) throws Exception {
        // POST-as-GET (RFC 8555 §6.3): empty payload.
        AcmeJws.ProtectedHeader header = new AcmeJws.ProtectedHeader(
            AcmeJws.ALG_EDDSA, lastNonce, url, null, accountUrl);
        AcmeJws.Envelope env = AcmeJws.sign(header, null, accountKey.getPrivate());
        HttpResponse<String> resp = http.send(
            buildPostRequest(url, env), HttpResponse.BodyHandlers.ofString());
        ensureSuccess(resp);
        captureNonce(resp);
        return MAPPER.readValue(resp.body(), Authorization.class);
    }

    private void respondAgent01(Authorization authz) throws Exception {
        Challenge ch = authz.challenges().stream()
            .filter(c -> AcmeWire.CHALLENGE_AGENT_01.equals(c.type()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "authz has no agent-01 challenge"));

        // Sign the challenge token with the NID private key.
        java.security.Signature signer = java.security.Signature.getInstance("Ed25519");
        signer.initSign(accountKey.getPrivate());
        signer.update(ch.token().getBytes(StandardCharsets.UTF_8));
        String agentSig = URL_ENC.encodeToString(signer.sign());

        AcmeJws.ProtectedHeader header = new AcmeJws.ProtectedHeader(
            AcmeJws.ALG_EDDSA, lastNonce, ch.url(), null, accountUrl);
        AcmeJws.Envelope env = AcmeJws.sign(header,
            new AcmeMessages.ChallengeRespondPayload(agentSig),
            accountKey.getPrivate());

        HttpResponse<String> resp = http.send(
            buildPostRequest(ch.url(), env),
            HttpResponse.BodyHandlers.ofString());
        ensureSuccess(resp);
        captureNonce(resp);
    }

    private Order finalizeOrder(Order order, String nid) throws Exception {
        // Build a CSR with subject CN = NID, SAN URI = NID, signed by accountKey.
        byte[] csrDer = buildCsr(nid);

        AcmeJws.ProtectedHeader header = new AcmeJws.ProtectedHeader(
            AcmeJws.ALG_EDDSA, lastNonce, order.finalizeUrl(), null, accountUrl);
        AcmeJws.Envelope env = AcmeJws.sign(header,
            new FinalizePayload(URL_ENC.encodeToString(csrDer)),
            accountKey.getPrivate());

        HttpResponse<String> resp = http.send(
            buildPostRequest(order.finalizeUrl(), env),
            HttpResponse.BodyHandlers.ofString());
        ensureSuccess(resp);
        captureNonce(resp);
        return MAPPER.readValue(resp.body(), Order.class);
    }

    private String downloadPem(String certUrl) throws Exception {
        AcmeJws.ProtectedHeader header = new AcmeJws.ProtectedHeader(
            AcmeJws.ALG_EDDSA, lastNonce, certUrl, null, accountUrl);
        AcmeJws.Envelope env = AcmeJws.sign(header, null, accountKey.getPrivate());
        HttpResponse<String> resp = http.send(
            buildPostRequest(certUrl, env), HttpResponse.BodyHandlers.ofString());
        ensureSuccess(resp);
        captureNonce(resp);
        return resp.body();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private byte[] buildCsr(String nid) throws Exception {
        PrivateKey priv = accountKey.getPrivate();
        PublicKey pub  = accountKey.getPublic();

        JcaPKCS10CertificationRequestBuilder builder =
            new JcaPKCS10CertificationRequestBuilder(new X500Principal("CN=" + nid), pub);

        // SAN URI extension on CSR (RFC 5280 §4.2.1.6) — wrapped in PKCS#10 attribute.
        org.bouncycastle.asn1.x509.ExtensionsGenerator extGen = new org.bouncycastle.asn1.x509.ExtensionsGenerator();
        extGen.addExtension(
            org.bouncycastle.asn1.x509.Extension.subjectAlternativeName,
            false,
            new GeneralNames(new GeneralName(GeneralName.uniformResourceIdentifier, nid)));
        builder.addAttribute(
            org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.pkcs_9_at_extensionRequest,
            extGen.generate());

        ContentSigner signer = new JcaContentSignerBuilder("Ed25519").build(priv);
        PKCS10CertificationRequest csr = builder.build(signer);
        return csr.getEncoded();
    }

    private HttpRequest buildPostRequest(String url, AcmeJws.Envelope env) throws Exception {
        byte[] body = MAPPER.writeValueAsBytes(env);
        return HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", AcmeWire.CONTENT_TYPE_JOSE_JSON)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    }

    private void captureNonce(HttpResponse<?> resp) {
        resp.headers().firstValue("Replay-Nonce").ifPresent(n -> lastNonce = n);
    }

    private static void ensureSuccess(HttpResponse<?> resp) {
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            String body = resp.body() instanceof String s ? s : "";
            throw new RuntimeException("ACME " + resp.uri() + " HTTP " + resp.statusCode() + ": " + body);
        }
    }
}
