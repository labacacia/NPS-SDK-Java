// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.x509;

import com.labacacia.nps.nip.AssuranceLevel;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Enumerated;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;

/**
 * Issues NPS X.509 NID certificates per NPS-RFC-0002 §4.
 *
 * <p>Two factory methods:
 * <ul>
 *   <li>{@link #issueLeaf} — leaf cert with critical NPS EKU + SAN URI = NID + assurance-level extension.</li>
 *   <li>{@link #issueRoot} — self-signed root for testing / private-CA use.</li>
 * </ul>
 *
 * <p>Both methods sign with native JCA Ed25519; BouncyCastle is used solely for
 * the X.509 builder API (the JDK does not expose one publicly).
 */
public final class NipX509Builder {

    private NipX509Builder() {}

    /** Role embedded in the leaf cert's ExtendedKeyUsage extension. */
    public enum LeafRole {
        AGENT(NpsX509Oids.EKU_AGENT_IDENTITY),
        NODE (NpsX509Oids.EKU_NODE_IDENTITY);

        private final org.bouncycastle.asn1.ASN1ObjectIdentifier eku;
        LeafRole(org.bouncycastle.asn1.ASN1ObjectIdentifier eku) { this.eku = eku; }
        public org.bouncycastle.asn1.ASN1ObjectIdentifier eku() { return eku; }
    }

    /**
     * Issues an NPS leaf cert (RFC-0002 §4).
     *
     * @param subjectNid       Subject NID, embedded as both CN and SAN URI.
     * @param subjectPubKeyRaw 32-byte raw Ed25519 public key of the subject.
     * @param caPrivateKey     CA's Ed25519 private key (JCA {@link PrivateKey}).
     * @param issuerNid        NID of the issuing CA (becomes the issuer DN's CN).
     * @param role             Agent or Node — selects EKU OID.
     * @param assuranceLevel   Embedded as ASN.1 ENUMERATED in the {@code id-nid-assurance-level} extension.
     * @param notBefore        Validity start.
     * @param notAfter         Validity end.
     * @param serialNumber     Cert serial number.
     */
    public static X509Certificate issueLeaf(
            String         subjectNid,
            byte[]         subjectPubKeyRaw,
            PrivateKey     caPrivateKey,
            String         issuerNid,
            LeafRole       role,
            AssuranceLevel assuranceLevel,
            Instant        notBefore,
            Instant        notAfter,
            BigInteger     serialNumber) {
        try {
            X500Name issuer  = nameFor(issuerNid);
            X500Name subject = nameFor(subjectNid);

            X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                issuer,
                serialNumber,
                Date.from(notBefore),
                Date.from(notAfter),
                subject,
                Ed25519PublicKeys.fromRawSpki(subjectPubKeyRaw));

            // BasicConstraints: not a CA, critical (RFC 5280 §4.2.1.9 strongly recommends critical when cA=false).
            builder.addExtension(Extension.basicConstraints, true,
                new BasicConstraints(false));

            // KeyUsage: digitalSignature only, critical.
            builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature));

            // ExtendedKeyUsage: agent-identity OR node-identity, critical (RFC-0002 §4.1).
            builder.addExtension(Extension.extendedKeyUsage, true,
                new ExtendedKeyUsage(KeyPurposeId.getInstance(role.eku())));

            // SubjectAlternativeName: URI = NID, non-critical (subject CN already carries NID).
            GeneralNames sanUri = new GeneralNames(
                new GeneralName(GeneralName.uniformResourceIdentifier, subjectNid));
            builder.addExtension(Extension.subjectAlternativeName, false, sanUri);

            // Custom extension: id-nid-assurance-level, ASN.1 ENUMERATED, non-critical (v0.1).
            byte[] assuranceDer = new ASN1Enumerated(assuranceLevel.rank()).getEncoded(ASN1Encoding.DER);
            builder.addExtension(NpsX509Oids.NID_ASSURANCE_LEVEL, false, assuranceDer);

            ContentSigner signer = new JcaContentSignerBuilder("Ed25519").build(caPrivateKey);
            return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        } catch (Exception e) {
            throw new RuntimeException("Failed to build NPS X.509 leaf cert: " + e.getMessage(), e);
        }
    }

    /**
     * Issues a self-signed CA root cert. Suitable for tests and private deployments.
     * Production deployments SHOULD use an offline CA root with proper key ceremony.
     */
    public static X509Certificate issueRoot(
            String     caNid,
            PrivateKey caPrivateKey,
            byte[]     caPubKeyRaw,
            Instant    notBefore,
            Instant    notAfter,
            BigInteger serialNumber) {
        try {
            X500Name dn = nameFor(caNid);

            X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                dn,
                serialNumber,
                Date.from(notBefore),
                Date.from(notAfter),
                dn,
                Ed25519PublicKeys.fromRawSpki(caPubKeyRaw));

            builder.addExtension(Extension.basicConstraints, true,
                new BasicConstraints(true));
            builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));

            ContentSigner signer = new JcaContentSignerBuilder("Ed25519").build(caPrivateKey);
            return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        } catch (Exception e) {
            throw new RuntimeException("Failed to build NPS X.509 root cert: " + e.getMessage(), e);
        }
    }

    /** Build an X500Name with a single CN attribute carrying the NID. */
    private static X500Name nameFor(String nid) {
        X500NameBuilder b = new X500NameBuilder(BCStyle.INSTANCE);
        b.addRDN(BCStyle.CN, nid);
        return b.build();
    }
}
