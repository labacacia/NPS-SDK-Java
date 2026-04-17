# `com.labacacia.nps.core` — Class and Method Reference

> Spec: [NPS-1 NCP v0.4](https://github.com/labacacia/NPS-Release/blob/main/spec/NPS-1-NCP.md) §2–§4

The core package ships the wire primitives every protocol builds on:
frame header, codec, tier implementations, frame registry, anchor cache,
and the unchecked exception hierarchy.

---

## Table of contents

- [`FrameType`](#frametype)
- [`EncodingTier`](#encodingtier)
- [`FrameFlags`](#frameflags)
- [`FrameHeader`](#frameheader)
- [`NpsFrame`](#npsframe)
- [`NpsFrameCodec`](#npsframecodec)
- [`FrameRegistry` / `NpsRegistries`](#frameregistry--npsregistries)
- [`AnchorFrameCache`](#anchorframecache)
- [Exceptions](#exceptions)

---

## `FrameType`

```java
public enum FrameType {
    // NCP
    ANCHOR      (0x01), DIFF     (0x02), STREAM  (0x03), CAPS     (0x04),
    // NWP
    QUERY       (0x10), ACTION   (0x11),
    // NIP
    IDENT       (0x20), TRUST    (0x21), REVOKE  (0x22),
    // NDP
    ANNOUNCE    (0x30), RESOLVE  (0x31), GRAPH   (0x32),
    // NOP
    TASK        (0x40), DELEGATE (0x41), SYNC    (0x42), ALIGN_STREAM(0x43),
    // Shared
    ERROR       (0xFE);

    public final int code;
    public static FrameType fromCode(int code);   // throws NpsFrameError on unknown code
}
```

---

## `EncodingTier`

```java
public enum EncodingTier {
    JSON,       // Tier-1
    MSGPACK;    // Tier-2 — production default
}
```

---

## `FrameFlags`

Bit constants for the one-byte `flags` field in the frame header.

```java
public final class FrameFlags {
    public static final int FINAL         = 0x01;   // last frame in logical message
    public static final int EXT           = 0x02;   // use 8-byte header instead of 4-byte
    public static final int TIER1_JSON    = 0x04;
    public static final int TIER2_MSGPACK = 0x08;
}
```

> **Layout notice.** The Java SDK's bit assignments are internal. Other
> NPS SDKs (Python, TypeScript) pack the same logical flags in a
> different bit layout; do not copy constants between language runtimes.

---

## `FrameHeader`

Fixed-size header prefix on every NPS wire frame.

```java
public final class FrameHeader {
    public static final int DEFAULT_HEADER_SIZE  = 4;   // 4-byte header (payload ≤ 65 535 B)
    public static final int EXTENDED_HEADER_SIZE = 8;   // EXT flag set → 4 GiB payload max

    public final FrameType frameType;
    public final int       flags;
    public final long      payloadLength;
    public final boolean   isExtended;

    public FrameHeader(FrameType frameType, int flags, long payloadLength);

    public boolean       isFinal();
    public int           headerSize();
    public EncodingTier  encodingTier();   // derived from flags

    public byte[]        toBytes();
    public static FrameHeader parse(byte[] data);

    public static int     buildFlags(EncodingTier tier, boolean isFinal, boolean isExtended);
    public static boolean needsExtended(long payloadLength);  // > 0xFFFF
}
```

### Wire layout

```
Default (4 bytes):
  [0]   frame_type
  [1]   flags
  [2-3] payload_length (uint16, big-endian)

Extended (EXT flag, 8 bytes):
  [0]   frame_type
  [1]   flags
  [2-3] reserved (0x00 0x00)
  [4-7] payload_length (uint32, big-endian)
```

`parse` throws `NpsFrameError` when the buffer is shorter than
`DEFAULT_HEADER_SIZE`, or shorter than `EXTENDED_HEADER_SIZE` with EXT set.

---

## `NpsFrame`

Interface implemented by every payload type.

```java
public interface NpsFrame {
    FrameType    frameType();
    EncodingTier preferredTier();
    Map<String, Object> toDict();
}
```

Every frame class also exposes a `static fromDict(Map<String,Object>)`
factory used by the codec during decoding.

---

## `NpsFrameCodec`

Dispatches encode/decode to the tier implementation and assembles the
wire bytes with a 4-byte or 8-byte header.

```java
public final class NpsFrameCodec {
    public static final long DEFAULT_MAX_PAYLOAD = 10L * 1024 * 1024;  // 10 MiB

    public NpsFrameCodec(FrameRegistry registry);
    public NpsFrameCodec(FrameRegistry registry, long maxPayload);

    public byte[]   encode(NpsFrame frame);
    public byte[]   encode(NpsFrame frame, EncodingTier overrideTier);
    public NpsFrame decode(byte[] wire);

    public static FrameHeader peekHeader(byte[] wire);
}
```

### Behaviour

- `encode` picks `overrideTier ?: frame.preferredTier()`, encodes the
  payload, sets `EXT` when `payload.length > 0xFFFF`, and sets `FINAL`
  (unless the frame is a non-last `StreamFrame` — `isLast()==false`).
- `NpsCodecError` is thrown when the payload exceeds `maxPayload`.
- `decode` reads the header via `FrameHeader.parse`, copies the payload,
  and delegates to the tier codec + `FrameRegistry` to rebuild the frame.
- `peekHeader` is a convenience for routing code that needs to dispatch
  on `frameType` before decoding the payload.

### Tier codecs

Internally, two `private final` codecs are used:

```java
Tier1JsonCodec    jsonCodec;     // Jackson
Tier2MsgPackCodec msgpackCodec;  // msgpack-core
```

They implement the same two-method contract — they are not part of the
public API and should not be referenced from application code.

---

## `FrameRegistry` / `NpsRegistries`

Maps `FrameType` → decoder function. Every frame module exposes a static
`{Ncp,Nwp,Nip,Ndp,Nop}FrameRegistrar.register(reg)` helper.

```java
public final class FrameRegistry {
    @FunctionalInterface
    public interface FrameDecoder {
        NpsFrame decode(Map<String, Object> dict);
    }

    public void         register(FrameType type, FrameDecoder decoder);
    public FrameDecoder resolve(FrameType type);      // throws NpsFrameError if missing
    public boolean      isRegistered(FrameType type);
}
```

Factory helpers:

```java
FrameRegistry def  = NpsRegistries.createDefault();   // NCP only
FrameRegistry full = NpsRegistries.createFull();      // NCP + NWP + NIP + NDP + NOP
```

Custom registry for a narrow subset:

```java
FrameRegistry r = new FrameRegistry();
NcpFrameRegistrar.register(r);
NwpFrameRegistrar.register(r);
```

---

## `AnchorFrameCache`

Thread-safe `AnchorFrame` cache keyed by `sha256:{hex}` anchor ID, with
TTL eviction evaluated lazily on read.

```java
public final class AnchorFrameCache {
    public LongSupplier clock;       // default System::currentTimeMillis — swap in tests

    public static String computeAnchorId(Map<String, Object> schema);

    public void        set(AnchorFrame frame);
    public AnchorFrame get(String anchorId);                  // null if absent / expired
    public AnchorFrame getRequired(String anchorId);          // throws NpsAnchorNotFoundError
    public void        invalidate(String anchorId);
    public int         size();                                // evicts expired as side effect
}
```

### Canonical anchor id

`computeAnchorId` sorts `schema.fields` by `name`, serialises each field
as `{"name":"…","type":"…"}`, wraps the array, and hashes with SHA-256.
This is a sorted-keys form — it matches the Python/TS sort-keys
canonicaliser and the .NET reference implementation.

### Poison detection

`set` compares the new `schema` to the existing entry (same id). A
mismatch throws `NpsAnchorPoisonError`; an identical schema is treated
as idempotent (existing entry kept).

---

## Exceptions

All NPS errors extend `RuntimeException` through `NpsError`.

```java
public class NpsError              extends RuntimeException { … }
public class NpsFrameError         extends NpsError { … }    // structural / header
public class NpsCodecError         extends NpsError { … }    // encode / decode
public class NpsAnchorNotFoundError extends NpsError { String getAnchorId(); }
public class NpsAnchorPoisonError   extends NpsError { String getAnchorId(); }
```

HTTP-layer failures in `NwpClient` / `NopClient` are reported as plain
`RuntimeException` carrying the status code and path.
