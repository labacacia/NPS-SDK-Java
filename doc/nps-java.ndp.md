# `com.labacacia.nps.ndp` — Class and Method Reference

> Spec: [NPS-4 NDP v0.2](https://github.com/labacacia/NPS-Release/blob/main/spec/NPS-4-NDP.md)

NDP is the discovery layer — the NPS analogue of DNS. This module
provides the three NDP frame types, a thread-safe in-memory registry
with lazy TTL eviction, and an announce-signature validator.

---

## Table of contents

- [`AnnounceFrame` (0x30)](#announceframe-0x30)
- [`ResolveFrame` (0x31)](#resolveframe-0x31)
- [`GraphFrame` (0x32)](#graphframe-0x32)
- [`InMemoryNdpRegistry`](#inmemoryndpregistry)
- [`NdpAnnounceValidator`](#ndpannouncevalidator)
- [`NdpAnnounceResult`](#ndpannounceresult)
- [`NdpFrameRegistrar`](#ndpframeregistrar)

---

## `AnnounceFrame` (0x30)

Publishes a node's physical reachability and TTL (NPS-4 §3.1).

```java
public final class AnnounceFrame implements NpsFrame {
    public AnnounceFrame(
        String                    nid,
        List<Map<String, Object>> addresses,    // [{"host", "port", "protocol"}, …]
        List<String>              capabilities,
        int                       ttl,          // seconds — 0 = orderly shutdown
        String                    timestamp,    // ISO 8601 UTC
        String                    signature,    // "ed25519:{base64}"
        String                    nodeType      // nullable — "memory" | "action" | …
    );

    public Map<String, Object> unsignedDict();   // strips signature for signing
}
```

Signing workflow:

1. Construct with a placeholder signature.
2. `id.sign(frame.unsignedDict())` using the NID's own private key.
3. Rebuild the frame with the real signature before publishing.

Publishing `ttl = 0` MUST be done before orderly shutdown so that
subscribers evict the entry.

---

## `ResolveFrame` (0x31)

Request / response envelope for resolving an `nwp://` URL.

```java
public final class ResolveFrame implements NpsFrame {
    public ResolveFrame(String target, String requesterNid,
                        Map<String, Object> resolved);
    public ResolveFrame(String target);   // requesterNid + resolved = null

    public String              target();         // "nwp://api.example.com/products"
    public String              requesterNid();   // nullable
    public Map<String, Object> resolved();       // nullable — populated on response
}
```

---

## `GraphFrame` (0x32)

Topology sync between registries.

```java
public final class GraphFrame implements NpsFrame {
    public GraphFrame(int seq, boolean initialSync,
                      List<Map<String, Object>> nodes,    // nullable — full snapshot
                      List<Map<String, Object>> patch);   // nullable — RFC 6902 ops
}
```

`seq` must be strictly monotonic per publisher. Gaps trigger a re-sync
request signalled with `NDP-GRAPH-SEQ-GAP`.

---

## `InMemoryNdpRegistry`

Thread-safe, TTL-evicting registry. Expiry is evaluated **lazily** on
every read — no background timer.

```java
public final class InMemoryNdpRegistry {
    public LongSupplier clock;     // default System::currentTimeMillis — swap in tests

    public void          announce(AnnounceFrame frame);
    public AnnounceFrame getByNid(String nid);       // null if absent / expired
    public ResolveResult resolve(String target);     // null if no match
    public List<AnnounceFrame> getAll();             // live entries only

    public static boolean nwpTargetMatchesNid(String nid, String target);

    public record ResolveResult(String host, int port, int ttl) {}
}
```

### Behaviour

- `announce` with `ttl == 0` immediately evicts the NID; otherwise the
  entry is inserted / refreshed with absolute expiry
  `clock.getAsLong() + ttl * 1000L`.
- `resolve` scans live entries, returns the **first** NID that covers
  `target` and its **first** advertised address wrapped in a
  `ResolveResult`.
- `getByNid` does exact NID lookup with on-demand purge.
- Override `clock` for deterministic tests:
  `registry.clock = () -> 1_000_000L;`

### `nwpTargetMatchesNid(nid, target)` *(static)*

NID ↔ target covering rule:

```
NID:    urn:nps:node:{authority}:{path}
Target: nwp://{authority}/{path}[/subpath]
```

A node NID covers a target when:

1. The target scheme is `nwp://`.
2. The NID's `{authority}` equals the target authority (exact, case-sensitive).
3. The target path equals `{path}` exactly, or starts with `{path}/`.

Returns `false` for malformed inputs rather than throwing.

---

## `NdpAnnounceValidator`

Verifies an `AnnounceFrame.signature` using a registered Ed25519 public
key. Thread-safe — keys live in a `ConcurrentHashMap`.

```java
public final class NdpAnnounceValidator {
    public void registerPublicKey(String nid, String encodedPubKey);
    public void removePublicKey(String nid);
    public Map<String, String> knownPublicKeys();      // snapshot copy

    public NdpAnnounceResult validate(AnnounceFrame frame);
}
```

`validate` (NPS-4 §7.1):

1. Looks up `frame.nid()` in the registered keys. Missing →
   `NdpAnnounceResult.fail("NDP-ANNOUNCE-NID-MISMATCH", …)`. Expected
   workflow: verify the announcer's `IdentFrame` first, then register
   its `pubKeyString()` here.
2. Requires `signature` to start with `"ed25519:"` — otherwise
   `NDP-ANNOUNCE-SIG-INVALID`.
3. Rebuilds the signing payload from `frame.unsignedDict()` using a
   `TreeMap` (sorted-keys canonical form) serialised via Jackson.
4. Runs the JDK `Signature.getInstance("Ed25519")` verify.
5. Returns `NdpAnnounceResult.ok()` on success,
   `NdpAnnounceResult.fail("NDP-ANNOUNCE-SIG-INVALID", …)` on failure
   or any exception.

The encoded key MUST use the `ed25519:{hex}` form produced by
`NipIdentity.pubKeyString()`.

---

## `NdpAnnounceResult`

```java
public record NdpAnnounceResult(boolean isValid, String errorCode, String message) {
    public static NdpAnnounceResult ok();
    public static NdpAnnounceResult fail(String errorCode, String message);
}
```

---

## `NdpFrameRegistrar`

Registers `ANNOUNCE`, `RESOLVE`, `GRAPH` against a `FrameRegistry`.

---

## End-to-end example

```java
import com.labacacia.nps.nip.NipIdentity;
import com.labacacia.nps.ndp.*;

var id  = NipIdentity.generate();
var nid = "urn:nps:node:api.example.com:products";

// Build & sign the announce
var addresses = List.<Map<String, Object>>of(
    Map.of("host", "10.0.0.5", "port", 17433, "protocol", "nwp+tls"));
var unsigned  = new AnnounceFrame(nid, addresses,
    List.of("nwp:query", "nwp:stream"),
    300, java.time.Instant.now().toString(),
    "placeholder", "memory");
String sig    = id.sign(unsigned.unsignedDict());
var signed    = new AnnounceFrame(nid, addresses,
    unsigned.capabilities(), unsigned.ttl(), unsigned.timestamp(),
    sig, unsigned.nodeType());

// Validate + register
var validator = new NdpAnnounceValidator();
validator.registerPublicKey(nid, id.pubKeyString());
NdpAnnounceResult res = validator.validate(signed);
if (!res.isValid()) throw new RuntimeException(res.errorCode());

var registry = new InMemoryNdpRegistry();
registry.announce(signed);

var resolved = registry.resolve("nwp://api.example.com/products/items/42");
// → ResolveResult[host=10.0.0.5, port=17433, ttl=300]
```
