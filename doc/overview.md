English | [中文版](./overview.cn.md)

# `nps-java` — API Reference Overview

[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](../LICENSE)

The NPS Java SDK is a JVM port of the .NET reference implementation. This
document is the entry point for the per-module API references — each
protocol has its own file below.

---

## Package layout

```
com.labacacia.nps
├── .core         # Wire primitives: FrameHeader, FrameType, codecs, AnchorFrameCache, exceptions
├── .ncp          # NCP frames: Anchor, Diff, Stream, Caps, Error, SchemaField
├── .nwp          # NWP frames + NwpClient (java.net.http)
├── .nip          # NIP frames + NipIdentity (Ed25519)
├── .ndp          # NDP frames + InMemoryNdpRegistry + NdpAnnounceValidator
└── .nop          # NOP frames + TaskState + BackoffStrategy + NopClient
```

## Reference documents

| Package | Module | Reference |
|---------|--------|-----------|
| —                             | Root build info & registry factories | this file |
| `com.labacacia.nps.core`      | Frame header, codec, anchor cache, exceptions | [`nps-java.core.md`](./nps-java.core.md) |
| `com.labacacia.nps.ncp`       | NCP frame set (`AnchorFrame`, `DiffFrame`, `StreamFrame`, `CapsFrame`, `ErrorFrame`) | [`nps-java.ncp.md`](./nps-java.ncp.md) |
| `com.labacacia.nps.nwp`       | `QueryFrame`, `ActionFrame`, `NwpClient` | [`nps-java.nwp.md`](./nps-java.nwp.md) |
| `com.labacacia.nps.nip`       | `IdentFrame`, `TrustFrame`, `RevokeFrame`, `NipIdentity` | [`nps-java.nip.md`](./nps-java.nip.md) |
| `com.labacacia.nps.ndp`       | `AnnounceFrame`, `ResolveFrame`, `GraphFrame`, registry, validator | [`nps-java.ndp.md`](./nps-java.ndp.md) |
| `com.labacacia.nps.nop`       | Task state/backoff, `TaskFrame`, `DelegateFrame`, `SyncFrame`, `AlignStreamFrame`, `NopClient` | [`nps-java.nop.md`](./nps-java.nop.md) |

---

## Install

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation("com.labacacia.nps:nps-java:1.0.0-alpha.1")
}
```

Maven:

```xml
<dependency>
    <groupId>com.labacacia.nps</groupId>
    <artifactId>nps-java</artifactId>
    <version>1.0.0-alpha.1</version>
</dependency>
```

Requires **Java 21+**. Runtime dependencies are resolved transitively:

- `org.msgpack:msgpack-core` 0.9.x
- `com.fasterxml.jackson.core:jackson-databind` 2.17.x
- `org.slf4j:slf4j-api` 2.0.x

Ed25519 is provided by the Java 15+ JDK (`java.security`); no external
crypto library is required.

---

## Registry factories

```java
import com.labacacia.nps.core.registry.FrameRegistry;
import com.labacacia.nps.core.registry.NpsRegistries;

FrameRegistry defaultReg = NpsRegistries.createDefault();  // NCP only
FrameRegistry fullReg    = NpsRegistries.createFull();     // all 5 protocols
```

- `createDefault()` — registers NCP frames (`ANCHOR`, `DIFF`, `STREAM`, `CAPS`, `ERROR`).
- `createFull()` — registers NCP + NWP + NIP + NDP + NOP.

Use `createFull()` whenever the codec may receive frames from more than
one protocol (for example, an orchestrator proxying NWP + NOP traffic).

---

## Minimal end-to-end example

```java
import com.labacacia.nps.core.codec.NpsFrameCodec;
import com.labacacia.nps.core.registry.NpsRegistries;
import com.labacacia.nps.nwp.NwpClient;
import com.labacacia.nps.nwp.QueryFrame;
import com.labacacia.nps.ncp.CapsFrame;

var client = new NwpClient("http://node.example.com:17433");

// Paginated query
QueryFrame q = new QueryFrame(
    "sha256:<anchor-id>",
    java.util.Map.of("active", true),
    50, 0, null, null, null, null);
CapsFrame caps = client.query(q);
System.out.println(caps.count() + " rows");
```

---

## Encoding tiers

Every `NpsFrame` declares a `preferredTier()`. MsgPack is the production
default; JSON tier stays available for debugging and cross-language
diagnostics.

| Tier | `EncodingTier` | Flags bit | Description |
|------|----------------|-----------|-------------|
| Tier-1 JSON    | `EncodingTier.JSON`    | `0x04` | UTF-8 JSON. Dev & compat. |
| Tier-2 MsgPack | `EncodingTier.MSGPACK` | `0x08` | MessagePack. **Production default** — ~60% smaller. |

```java
var codec = new NpsFrameCodec(NpsRegistries.createFull());
byte[] wire = codec.encode(frame, EncodingTier.JSON);  // override tier
```

> ⚠ **Java bit layout differs from TS/Python.** Java uses
> `FINAL=0x01, EXT=0x02, TIER1_JSON=0x04, TIER2_MSGPACK=0x08`; the cross-SDK
> spec still decodes correctly because the on-wire `flags` byte is carried
> as an opaque bitfield — but do not copy flag constants between SDKs.

---

## Synchronous I/O

Both `NwpClient` and `NopClient` expose **blocking** methods built on
`java.net.http.HttpClient`:

- Each network call throws `IOException` and `InterruptedException`.
- For async usage wrap calls in a `CompletableFuture`, virtual thread, or
  `ExecutorService` — the SDK does not wrap them itself.

---

## Error hierarchy

```
RuntimeException
└── NpsError                          com.labacacia.nps.core.exception
    ├── NpsFrameError                 — header parse / structural error
    ├── NpsCodecError                 — encode/decode failure (includes payload-too-large)
    ├── NpsAnchorNotFoundError        — anchor not in cache (has getAnchorId())
    └── NpsAnchorPoisonError          — anchor_id / schema mismatch (has getAnchorId())
```

HTTP errors from `NwpClient`/`NopClient` surface as plain
`RuntimeException` with the status code and path.

---

## Reference specs

| Module | Spec |
|--------|------|
| `core` + `ncp` | [NPS-1 NCP v0.4](https://github.com/labacacia/NPS-Release/blob/main/spec/NPS-1-NCP.md) |
| `nwp`          | [NPS-2 NWP v0.4](https://github.com/labacacia/NPS-Release/blob/main/spec/NPS-2-NWP.md) |
| `nip`          | [NPS-3 NIP v0.2](https://github.com/labacacia/NPS-Release/blob/main/spec/NPS-3-NIP.md) |
| `ndp`          | [NPS-4 NDP v0.2](https://github.com/labacacia/NPS-Release/blob/main/spec/NPS-4-NDP.md) |
| `nop`          | [NPS-5 NOP v0.3](https://github.com/labacacia/NPS-Release/blob/main/spec/NPS-5-NOP.md) |
