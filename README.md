# NPS Java SDK (`nps-java`)

Java client library for the **Neural Protocol Suite (NPS)** — a complete internet protocol stack designed for AI agents and models.

Package group: `com.labacacia.nps` | Java 21+ | Gradle 8+

## Status

**v0.1.0 — Phase 1 initial release (Alpha)**

Covers all five NPS protocols: NCP + NWP + NIP + NDP + NOP.

## Requirements

- Java 21+
- Gradle 8.x (Kotlin DSL)
- Dependencies (managed by Gradle):
  - `org.msgpack:msgpack-core:0.9.8`
  - `com.fasterxml.jackson.core:jackson-databind:2.17.2`
  - `org.slf4j:slf4j-api:2.0.13`

## Building

```bash
# Run all tests
./gradlew test

# Build JAR
./gradlew build
```

## Modules

| Package | Description |
|---------|-------------|
| `com.labacacia.nps.core` | Frame header, codec (Tier-1 JSON / Tier-2 MsgPack), frame registry, anchor cache, exceptions |
| `com.labacacia.nps.ncp`  | NCP frames: `AnchorFrame`, `DiffFrame`, `StreamFrame`, `CapsFrame`, `ErrorFrame` |
| `com.labacacia.nps.nwp`  | NWP frames: `QueryFrame`, `ActionFrame`, `AsyncActionResponse`; `NwpClient` (HTTP) |
| `com.labacacia.nps.nip`  | NIP frames: `IdentFrame`, `TrustFrame`, `RevokeFrame`; `NipIdentity` (Ed25519 key management) |
| `com.labacacia.nps.ndp`  | NDP frames: `AnnounceFrame`, `ResolveFrame`, `GraphFrame`; `InMemoryNdpRegistry`; `NdpAnnounceValidator` |
| `com.labacacia.nps.nop`  | NOP frames: `TaskFrame`, `DelegateFrame`, `SyncFrame`, `AlignStreamFrame`; `BackoffStrategy`; `NopTaskStatus` |

## Quick Start

### Encoding / Decoding NCP Frames

```java
import com.labacacia.nps.core.codec.NpsFrameCodec;
import com.labacacia.nps.core.registry.NpsRegistries;
import com.labacacia.nps.ncp.AnchorFrame;

var codec  = new NpsFrameCodec(NpsRegistries.createFull());
var schema = java.util.Map.of(
    "fields", java.util.List.of(
        java.util.Map.of("name", "id",    "type", "uint64"),
        java.util.Map.of("name", "price", "type", "decimal")
    )
);
var frame  = new AnchorFrame("sha256:abc123", schema, null, null, null, 3600);

byte[] wire   = codec.encode(frame);          // Tier-2 MsgPack by default
var    result = (AnchorFrame) codec.decode(wire);
```

### NWP Client — Query

```java
import com.labacacia.nps.nwp.NwpClient;
import com.labacacia.nps.nwp.QueryFrame;
import com.labacacia.nps.ncp.CapsFrame;

var client = new NwpClient("http://node.example.com:17433");
var query  = new QueryFrame("sha256:abc123", null, null, null, 50, null);
CapsFrame caps = client.query(query);
```

### NWP Client — Action (invoke)

```java
import com.labacacia.nps.nwp.ActionFrame;

var action = new ActionFrame("run", java.util.Map.of("input", "hello"), null, false);
Object result = client.invoke(action);   // NpsFrame or Map<String,Object>
```

### NWP Client — Stream

```java
import com.labacacia.nps.ncp.StreamFrame;
import java.util.List;

List<StreamFrame> frames = client.stream(query);
for (var sf : frames) {
    System.out.println(sf.payload());
    if (sf.isLast()) break;
}
```

### NIP Identity — Sign & Verify

```java
import com.labacacia.nps.nip.NipIdentity;
import java.nio.file.Path;
import java.util.Map;

// Generate keypair
var identity = NipIdentity.generate();
System.out.println(identity.pubKeyString()); // "ed25519:<hex>"

// Sign a payload
var payload = Map.<String, Object>of("nid", "urn:nps:node:example.com:data");
String sig  = identity.sign(payload);        // "ed25519:<base64>"
boolean ok  = identity.verify(payload, sig); // true

// Persist and load (AES-256-GCM + PBKDF2)
identity.save(Path.of("my-node.key"), "my-passphrase");
var loaded = NipIdentity.load(Path.of("my-node.key"), "my-passphrase");
```

### NDP Registry — Announce & Resolve

```java
import com.labacacia.nps.ndp.AnnounceFrame;
import com.labacacia.nps.ndp.InMemoryNdpRegistry;
import java.util.List;
import java.util.Map;

var addrs    = List.of(Map.of("host", "example.com", "port", 17433, "protocol", "nwp"));
var caps     = List.of("nwp/query", "nwp/stream");
var identity = NipIdentity.generate();

var tmp = new AnnounceFrame("urn:nps:node:example.com:data",
    addrs, caps, 300, "2026-01-01T00:00:00Z", "placeholder", null);
var sig   = identity.sign(tmp.unsignedDict());
var frame = new AnnounceFrame("urn:nps:node:example.com:data",
    addrs, caps, 300, "2026-01-01T00:00:00Z", sig, null);

var registry = new InMemoryNdpRegistry();
registry.announce(frame);

var resolved = registry.resolve("nwp://example.com/data/sub");
// resolved.host() == "example.com", resolved.port() == 17433
```

### NDP Announce Validator

```java
import com.labacacia.nps.ndp.NdpAnnounceValidator;

var validator = new NdpAnnounceValidator();
validator.registerPublicKey("urn:nps:node:example.com:data", identity.pubKeyString());

var result = validator.validate(frame);
if (result.isValid()) {
    System.out.println("Announce accepted");
} else {
    System.out.println("Rejected: " + result.errorCode() + " — " + result.message());
}
```

### NOP — Backoff Strategy

```java
import com.labacacia.nps.nop.BackoffStrategy;

long delayMs = BackoffStrategy.computeDelayMs(BackoffStrategy.EXPONENTIAL, 1000, 30_000, 2);
// Returns 4000 (2^2 * 1000), capped at maxMs
```

## Frame Type Reference

| Frame | Type Code | Protocol | Description |
|-------|-----------|----------|-------------|
| `AnchorFrame`       | 0x01 | NCP | Schema anchor (cached schema definition) |
| `DiffFrame`         | 0x02 | NCP | Schema diff / patch |
| `StreamFrame`       | 0x03 | NCP | Streaming data chunk (FINAL flag = last) |
| `CapsFrame`         | 0x04 | NCP | Capability advertisement |
| `ErrorFrame`        | 0xFE | NCP | Unified error frame (all protocols) |
| `QueryFrame`        | 0x10 | NWP | Data query with anchor ref + filter |
| `ActionFrame`       | 0x11 | NWP | Action invocation (sync or async) |
| `IdentFrame`        | 0x20 | NIP | Node identity declaration (signed) |
| `TrustFrame`        | 0x21 | NIP | Trust delegation between nodes |
| `RevokeFrame`       | 0x22 | NIP | Revocation notice |
| `AnnounceFrame`     | 0x30 | NDP | Node announcement with TTL |
| `ResolveFrame`      | 0x31 | NDP | Address resolution request/response |
| `GraphFrame`        | 0x32 | NDP | Network topology snapshot |
| `TaskFrame`         | 0x40 | NOP | Orchestration DAG task |
| `DelegateFrame`     | 0x41 | NOP | Subtask delegation |
| `SyncFrame`         | 0x42 | NOP | K-of-N synchronization barrier |
| `AlignStreamFrame`  | 0x43 | NOP | Streaming alignment update |

## Encoding

The codec supports two encoding tiers:

| Tier | Enum | Description |
|------|------|-------------|
| Tier-1 | `EncodingTier.JSON` | Human-readable JSON (debug, interop) |
| Tier-2 | `EncodingTier.MSGPACK` | MsgPack binary (default, ~60% smaller) |

```java
import com.labacacia.nps.core.EncodingTier;

byte[] jsonWire    = codec.encode(frame, EncodingTier.JSON);
byte[] msgpackWire = codec.encode(frame, EncodingTier.MSGPACK); // default
```

## Anchor Cache

The `AnchorFrameCache` stores schemas by anchor ID with TTL eviction. The anchor ID is computed as SHA-256 over the canonical (sorted-field) JSON representation of the schema.

```java
import com.labacacia.nps.core.cache.AnchorFrameCache;

var cache = new AnchorFrameCache();
var frame = new AnchorFrame("sha256:...", schema, null, null, null, 3600);
cache.set(frame);

var retrieved = cache.get("sha256:...");
// Returns null if expired
```

## Error Handling

All codec and registry errors throw `NpsCodecError` (unchecked). Network errors from `NwpClient` throw `IOException` or `InterruptedException`.

| Exception | When |
|-----------|------|
| `NpsCodecError` | Unknown frame type, payload too large, decode failure |
| `AnchorFrameCache.AnchorNotFoundError` | `getRequired()` called for a missing or expired anchor |
| `AnchorFrameCache.AnchorPoisonError` | Attempt to overwrite a cached anchor with a different schema |
| `IOException` | Network error in `NwpClient` |
| `RuntimeException` | Non-2xx HTTP response from `NwpClient` |

## Testing

87 tests across all protocols, run with:

```bash
./gradlew test
```

Test classes:
- `AnchorFrameCacheTest` — 12 tests
- `FrameHeaderTest` — 8 tests
- `NpsFrameCodecTest` — 11 tests
- `NdpTest` — 25 tests (frames, registry, validator, matching)
- `NipIdentityTest` — 13 tests (keygen, sign/verify, persist, frames)
- `NopTest` — 18 tests (backoff, frames, task status)

## License

[Apache 2.0](../../LICENSE) © 2026 INNO LOTUS PTY LTD
