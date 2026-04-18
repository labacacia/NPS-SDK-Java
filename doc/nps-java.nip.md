English | [中文版](./nps-java.nip.cn.md)

# `com.labacacia.nps.nip` — Class and Method Reference

> Spec: [NPS-3 NIP v0.2](https://github.com/labacacia/NPS-Release/blob/main/spec/NPS-3-NIP.md)

NIP is the identity layer. This module ships the three NIP frames
(0x20–0x22) and an `Ed25519` identity helper (`NipIdentity`) that uses
the Java 15+ native `java.security` Ed25519 provider — no third-party
crypto dependency.

---

## Table of contents

- [`IdentFrame` (0x20)](#identframe-0x20)
- [`TrustFrame` (0x21)](#trustframe-0x21)
- [`RevokeFrame` (0x22)](#revokeframe-0x22)
- [`NipIdentity`](#nipidentity)
- [Key file format](#key-file-format)
- [`NipFrameRegistrar`](#nipframeregistrar)

---

## `IdentFrame` (0x20)

Node identity declaration.

```java
public final class IdentFrame implements NpsFrame {
    public IdentFrame(String nid, String pubKey,
                      Map<String, Object> metadata, String signature);

    public String              nid();         // urn:nps:node:{authority}:{name}
    public String              pubKey();      // "ed25519:{hex}"
    public Map<String, Object> metadata();    // free-form { "display_name", "org", … }
    public String              signature();   // "ed25519:{base64}"

    public Map<String, Object> unsignedDict();   // strips signature — use for signing
}
```

Signing workflow:

1. Construct with `signature = null` (or a placeholder).
2. `NipIdentity.sign(frame.unsignedDict())` → `ed25519:{base64}`.
3. Rebuild with the real signature, then encode + send.

---

## `TrustFrame` (0x21)

Delegation / trust assertion.

```java
public final class TrustFrame implements NpsFrame {
    public TrustFrame(String       issuerNid,
                      String       subjectNid,
                      List<String> scopes,
                      String       expiresAt,   // ISO 8601 UTC
                      String       signature);  // "ed25519:{base64}"
}
```

The canonical signing payload is the full dict **minus the signature
field** (same convention as `IdentFrame` / `AnnounceFrame`).

---

## `RevokeFrame` (0x22)

Revokes an NID — may precede or accompany an `AnnounceFrame` with
`ttl == 0`.

```java
public final class RevokeFrame implements NpsFrame {
    public RevokeFrame(String nid, String reason, String revokedAt);
    public RevokeFrame(String nid);   // reason + revokedAt = null
}
```

---

## `NipIdentity`

Ed25519 keypair plus canonical-JSON sign / verify.

```java
public final class NipIdentity {
    public static NipIdentity generate();
    public static NipIdentity load(Path path, String passphrase) throws IOException;

    public void save(Path path, String passphrase) throws IOException;

    public String  sign(Map<String, Object> payload);      // → "ed25519:{base64}"
    public boolean verify(Map<String, Object> payload, String signature);

    public String     pubKeyString();   // "ed25519:{hex}"
    public PublicKey  pubKey();
}
```

### Canonical signing payload

`sign` / `verify` wrap the payload in a `TreeMap` (sorted-keys) before
Jackson-serialising it to compact UTF-8 JSON. This is the **sorted-keys**
canonicaliser shared with the .NET and Python SDKs; RFC 8785 JCS is NOT
used here.

### Key lifecycle

- `generate()` — 32-byte Ed25519 keypair via
  `KeyPairGenerator.getInstance("Ed25519")`.
- `pubKeyString()` is the hex form used everywhere else in the SDK
  (e.g. `NdpAnnounceValidator.registerPublicKey`).
- The raw `PublicKey` / `PrivateKey` are `java.security` objects — usable
  with any JCA signer if you need a non-canonical signing scheme.

---

## Key file format

`save` / `load` use a JSON envelope with PBKDF2-SHA256 derived key and
AES-256-GCM authenticated encryption.

```json
{
  "version":     1,
  "salt":        "<hex 16 bytes>",
  "iv":          "<hex 12 bytes>",
  "ciphertext":  "<hex — encrypted PKCS#8 private key>",
  "pub_key":     "<hex — X.509 SubjectPublicKeyInfo>"
}
```

Parameters:

| Parameter       | Value |
|-----------------|-------|
| PBKDF2 algorithm | `PBKDF2WithHmacSHA256` |
| PBKDF2 iterations | 600 000 |
| Derived key size  | 32 bytes (256-bit) |
| Salt length       | 16 bytes (random) |
| IV length         | 12 bytes (random) |
| Cipher            | `AES/GCM/NoPadding` |
| GCM tag           | 128 bits |

The private key is stored in PKCS#8 form; the public key in X.509
SubjectPublicKeyInfo form — both are the JDK `Key.getEncoded()` outputs
for Ed25519.

---

## `NipFrameRegistrar`

Registers `IDENT`, `TRUST`, `REVOKE` against a `FrameRegistry`:

```java
FrameRegistry r = new FrameRegistry();
NcpFrameRegistrar.register(r);
NipFrameRegistrar.register(r);
```

---

## End-to-end example

```java
import com.labacacia.nps.nip.*;
import java.nio.file.Path;

var id  = NipIdentity.generate();
var nid = "urn:nps:node:api.example.com:products";

// Save & reload
id.save(Path.of("node.key"), System.getenv("KEY_PASS"));
var loaded = NipIdentity.load(Path.of("node.key"), System.getenv("KEY_PASS"));

// Sign an announce payload
var payload = Map.<String, Object>of(
    "action", "announce",
    "nid",    nid);
String sig  = loaded.sign(payload);
boolean ok  = loaded.verify(payload, sig);   // true
```
