# NPS Java SDK

[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](../../LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-ED8B00)](https://openjdk.org/)

Java client library for the **Neural Protocol Suite (NPS)** — a complete internet protocol stack purpose-built for AI Agents and models.

Group: `com.labacacia.nps` · Java 21+ · Gradle 8+ · Kotlin DSL

---

## NPS Repositories

| Repo | Role | Language |
|------|------|----------|
| [NPS-Release](https://github.com/labacacia/NPS-Release) | Protocol specifications (authoritative) | Markdown / YAML |
| [NPS-sdk-dotnet](https://github.com/labacacia/NPS-sdk-dotnet) | Reference implementation | C# / .NET 10 |
| [NPS-sdk-py](https://github.com/labacacia/NPS-sdk-py) | Async Python SDK | Python 3.11+ |
| [NPS-sdk-ts](https://github.com/labacacia/NPS-sdk-ts) | Node/browser SDK | TypeScript |
| **[NPS-sdk-java](https://github.com/labacacia/NPS-sdk-java)** (this repo) | JVM SDK | Java 21+ |
| [NPS-sdk-rust](https://github.com/labacacia/NPS-sdk-rust) | Async SDK | Rust stable |
| [NPS-sdk-go](https://github.com/labacacia/NPS-sdk-go) | Go SDK | Go 1.23+ |

---

## Status

**v1.0.0-alpha.1 — Phase 1 release**

Covers all five NPS protocols: NCP + NWP + NIP + NDP + NOP. 87 tests passing.

## Requirements

- Java 21+
- Gradle 8.x (Kotlin DSL)
- Runtime dependencies (managed by Gradle):
  - `org.msgpack:msgpack-core` 0.9.x
  - `com.fasterxml.jackson.core:jackson-databind` 2.17.x
  - `org.slf4j:slf4j-api` 2.0.x

## Building

```bash
./gradlew test     # run all 87 tests
./gradlew build    # build JAR + sources + javadoc
```

## Modules

| Package | Description |
|---------|-------------|
| `com.labacacia.nps.core` | Frame header, codec (Tier-1 JSON / Tier-2 MsgPack), frame registry, anchor cache, exceptions |
| `com.labacacia.nps.ncp`  | NCP frames: `AnchorFrame`, `DiffFrame`, `StreamFrame`, `CapsFrame`, `ErrorFrame` |
| `com.labacacia.nps.nwp`  | NWP frames: `QueryFrame`, `ActionFrame`, `AsyncActionResponse`; `NwpClient` (HTTP) |
| `com.labacacia.nps.nip`  | NIP frames: `IdentFrame`, `TrustFrame`, `RevokeFrame`; `NipIdentity` (Ed25519) |
| `com.labacacia.nps.ndp`  | NDP frames: `AnnounceFrame`, `ResolveFrame`, `GraphFrame`; `InMemoryNdpRegistry`, `NdpAnnounceValidator` |
| `com.labacacia.nps.nop`  | NOP frames: `TaskFrame`, `DelegateFrame`, `SyncFrame`, `AlignStreamFrame`; `BackoffStrategy`, `NopTaskStatus` |

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

byte[] wire = codec.encode(frame);                   // Tier-2 MsgPack
var    back = (AnchorFrame) codec.decode(wire);
```

### NWP Client — Query

```java
import com.labacacia.nps.nwp.NwpClient;
import com.labacacia.nps.nwp.QueryFrame;
import com.labacacia.nps.ncp.CapsFrame;

var client = new NwpClient("http://node.example.com:17433");
CapsFrame caps = client.query(new QueryFrame("sha256:abc123", null, null, null, 50, null));
```

### NWP Client — Action

```java
import com.labacacia.nps.nwp.ActionFrame;

var action = new ActionFrame("run", java.util.Map.of("input", "hello"), null, false);
Object result = client.invoke(action);
```

### NWP Client — Stream

```java
for (var sf : client.stream(query)) {
    System.out.println(sf.payload());
    if (sf.isLast()) break;
}
```

### NIP Identity — Sign & Verify

```java
import com.labacacia.nps.nip.NipIdentity;
import java.nio.file.Path;

var identity = NipIdentity.generate();
var sig      = identity.sign(java.util.Map.of("nid", "urn:nps:node:example.com:data"));
boolean ok   = identity.verify(java.util.Map.of("nid", "urn:nps:node:example.com:data"), sig);

// Persist encrypted (AES-256-GCM + PBKDF2)
identity.save(Path.of("my-node.key"), "my-passphrase");
var loaded = NipIdentity.load(Path.of("my-node.key"), "my-passphrase");
```

### NDP — Registry & Validator

```java
import com.labacacia.nps.ndp.*;

var addrs = java.util.List.of(java.util.Map.of(
    "host", "example.com", "port", 17433, "protocol", "nwp"));
var tmp   = new AnnounceFrame("urn:nps:node:example.com:data", addrs,
    java.util.List.of("nwp/query"), 300, "2026-01-01T00:00:00Z", "placeholder", null);
var sig   = identity.sign(tmp.unsignedDict());
var frame = new AnnounceFrame("urn:nps:node:example.com:data", addrs,
    java.util.List.of("nwp/query"), 300, "2026-01-01T00:00:00Z", sig, null);

var registry = new InMemoryNdpRegistry();
registry.announce(frame);

var validator = new NdpAnnounceValidator();
validator.registerPublicKey("urn:nps:node:example.com:data", identity.pubKeyString());
var result = validator.validate(frame);
```

### NOP — Backoff

```java
import com.labacacia.nps.nop.BackoffStrategy;

long delayMs = BackoffStrategy.computeDelayMs(
    BackoffStrategy.EXPONENTIAL, /*baseMs*/ 1000, /*maxMs*/ 30_000, /*attempt*/ 2);
// → 4000 ms (1000 * 2^2, capped at maxMs)
```

## Frame Type Reference

| Frame | Code | Protocol | Purpose |
|-------|------|----------|---------|
| `AnchorFrame`      | 0x01 | NCP | Schema anchor |
| `DiffFrame`        | 0x02 | NCP | Schema diff / patch |
| `StreamFrame`      | 0x03 | NCP | Streaming chunk (FINAL flag = last) |
| `CapsFrame`        | 0x04 | NCP | Capability / response envelope |
| `ErrorFrame`       | 0xFE | NCP | Unified error frame |
| `QueryFrame`       | 0x10 | NWP | Query with anchor ref + filter |
| `ActionFrame`      | 0x11 | NWP | Action invocation (sync or async) |
| `IdentFrame`       | 0x20 | NIP | Node identity declaration |
| `TrustFrame`       | 0x21 | NIP | Trust delegation |
| `RevokeFrame`      | 0x22 | NIP | Revocation notice |
| `AnnounceFrame`    | 0x30 | NDP | Node announcement with TTL |
| `ResolveFrame`     | 0x31 | NDP | Address resolution |
| `GraphFrame`       | 0x32 | NDP | Network topology snapshot |
| `TaskFrame`        | 0x40 | NOP | DAG task definition |
| `DelegateFrame`    | 0x41 | NOP | Subtask delegation |
| `SyncFrame`        | 0x42 | NOP | K-of-N sync barrier |
| `AlignStreamFrame` | 0x43 | NOP | Streaming alignment update |

## Encoding

| Tier | Enum | Description |
|------|------|-------------|
| Tier-1 | `EncodingTier.JSON` | Human-readable JSON (debug, interop) |
| Tier-2 | `EncodingTier.MSGPACK` | MsgPack binary (default, ~60% smaller) |

## Error Handling

| Exception | When |
|-----------|------|
| `NpsCodecError` | Unknown frame type, payload too large, decode failure |
| `AnchorFrameCache.AnchorNotFoundError` | `getRequired()` on missing/expired anchor |
| `AnchorFrameCache.AnchorPoisonError` | Schema mismatch for existing anchor_id |
| `IOException` | Network error in `NwpClient` |
| `RuntimeException` | Non-2xx HTTP response from `NwpClient` |

## NIP CA Server

A standalone NIP Certificate Authority server is bundled under [`nip-ca-server/`](./nip-ca-server/) — Spring Boot 3.4, SQLite-backed, Docker-ready.

## Testing

87 tests across all five protocols:
- `AnchorFrameCacheTest` — 12 tests
- `FrameHeaderTest` — 8 tests
- `NpsFrameCodecTest` — 11 tests
- `NdpTest` — 25 tests (frames, registry, validator, matching)
- `NipIdentityTest` — 13 tests (keygen, sign/verify, persist)
- `NopTest` — 18 tests (backoff, frames, task status)

```bash
./gradlew test
```

## License

Apache 2.0 — see [LICENSE](../../LICENSE) and [NOTICE](../../NOTICE).

Copyright 2026 INNO LOTUS PTY LTD
