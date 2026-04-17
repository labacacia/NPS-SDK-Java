# `com.labacacia.nps.ncp` — Class and Method Reference

> Spec: [NPS-1 NCP v0.4](https://github.com/labacacia/NPS-Release/blob/main/spec/NPS-1-NCP.md)

NCP is the wire-and-schema layer. The Java SDK exposes the five core
frames — `AnchorFrame`, `DiffFrame`, `StreamFrame`, `CapsFrame`,
`ErrorFrame` — plus the `SchemaField` record used to build schemas.

All frame classes are `final`, implement `NpsFrame`, and expose a static
`fromDict` factory for decoding.

---

## Table of contents

- [`SchemaField`](#schemafield)
- [`AnchorFrame` (0x01)](#anchorframe-0x01)
- [`DiffFrame` (0x02)](#diffframe-0x02)
- [`StreamFrame` (0x03)](#streamframe-0x03)
- [`CapsFrame` (0x04)](#capsframe-0x04)
- [`ErrorFrame` (0xFE)](#errorframe-0xfe)
- [`NcpFrameRegistrar`](#ncpframeregistrar)

---

## `SchemaField`

```java
public record SchemaField(
    String  name,
    String  type,      // "string" | "uint64" | "int64" | "decimal" | "bool" |
                       // "timestamp" | "bytes" | "object" | "array"
    String  semantic,  // nullable — optional NPS semantic tag
    Boolean nullable   // nullable
) {
    public SchemaField(String name, String type);  // semantic + nullable = null
    public Map<String, Object> toDict();
    public static SchemaField fromDict(Map<String, Object> d);
}
```

Schemas are carried as `Map<String, Object>` on the wire (`toDict`/`fromDict`
round-trips to the shape `{"fields": [{…}, {…}]}`). Use `SchemaField`
only as a typed builder; always convert to `Map` before handing to
`AnchorFrame`.

---

## `AnchorFrame` (0x01)

Content-addressed schema advertisement.

```java
public final class AnchorFrame implements NpsFrame {
    public AnchorFrame(String anchorId, Map<String, Object> schema, int ttl);
    public AnchorFrame(String anchorId, Map<String, Object> schema);  // ttl = 3600

    public String              anchorId();
    public Map<String, Object> schema();
    public int                 ttl();       // seconds — 0 = session-only
}
```

- `preferredTier()` → `MSGPACK`.
- `anchorId` MUST equal `AnchorFrameCache.computeAnchorId(schema)` when
  the frame is authoritative; otherwise poison detection will reject it
  on the receiver side.

---

## `DiffFrame` (0x02)

Incremental data patch anchored to a prior `AnchorFrame`.

```java
public final class DiffFrame implements NpsFrame {
    public DiffFrame(String anchorRef, int baseSeq,
                     List<Map<String, Object>> patch, String entityId);

    public String                     anchorRef();
    public int                        baseSeq();
    public List<Map<String, Object>>  patch();     // RFC 6902 JSON-Patch ops
    public String                     entityId();  // nullable
}
```

Each patch entry is a map with `op` / `path` / `value` / `from` keys
matching RFC 6902. The binary-bitset variant from NPS-1 §3.2 is not yet
surfaced as a first-class type — embed raw bytes in a single-op patch
when needed.

---

## `StreamFrame` (0x03)

Streaming chunk frame.

```java
public final class StreamFrame implements NpsFrame {
    public StreamFrame(String streamId, int seq, boolean isLast,
                       List<Map<String, Object>> data,
                       String anchorRef, Integer windowSize);

    public String                     streamId();
    public int                        seq();
    public boolean                    isLast();
    public List<Map<String, Object>>  data();
    public String                     anchorRef();   // nullable
    public Integer                    windowSize();  // nullable — back-pressure hint
}
```

The codec uses `isLast()` to decide whether to set the `FINAL` flag on
the header. Non-last `StreamFrame`s ship with `FINAL=0`; the terminal
frame with `FINAL=1`.

---

## `CapsFrame` (0x04)

Capsule — a complete result page referencing a cached schema.

```java
public final class CapsFrame implements NpsFrame {
    public CapsFrame(String anchorRef, int count,
                     List<Map<String, Object>> data,
                     String  nextCursor,    // nullable
                     Integer tokenEst,      // nullable — NPT estimate
                     Boolean cached,        // nullable
                     String  tokenizerUsed  // nullable — tokenizer URN
    );
    public CapsFrame(String anchorRef, int count, List<Map<String, Object>> data);

    public String                     anchorRef();
    public int                        count();
    public List<Map<String, Object>>  data();
    public String                     nextCursor();
    public Integer                    tokenEst();
    public Boolean                    cached();
    public String                     tokenizerUsed();
}
```

`count` SHOULD equal `data.size()` — the .NET and Python validators
enforce this. The Java SDK currently trusts the constructor caller; if
you parse `CapsFrame` from untrusted peers, add the check yourself.

---

## `ErrorFrame` (0xFE)

Unified error frame shared by every NPS protocol layer (NPS-0 §9).

```java
public final class ErrorFrame implements NpsFrame {
    public ErrorFrame(String status, String error,
                      String message, Map<String, Object> details);

    public String              status();   // NPS status code, e.g. "NPS-CLIENT-NOT-FOUND"
    public String              error();    // protocol code, e.g. "NCP-ANCHOR-NOT-FOUND"
    public String              message();  // nullable
    public Map<String, Object> details();  // nullable
}
```

Test for the error envelope with `frame instanceof ErrorFrame err`.

---

## `NcpFrameRegistrar`

Registers all five NCP frames against a `FrameRegistry`:

```java
FrameRegistry reg = new FrameRegistry();
NcpFrameRegistrar.register(reg);
// registers ANCHOR, DIFF, STREAM, CAPS, ERROR
```

`NpsRegistries.createDefault()` / `createFull()` call this for you.

---

## End-to-end example

```java
import com.labacacia.nps.core.codec.NpsFrameCodec;
import com.labacacia.nps.core.registry.NpsRegistries;
import com.labacacia.nps.core.cache.AnchorFrameCache;
import com.labacacia.nps.ncp.AnchorFrame;

var codec  = new NpsFrameCodec(NpsRegistries.createFull());
var schema = Map.<String, Object>of("fields", List.of(
    Map.of("name", "id",    "type", "uint64"),
    Map.of("name", "price", "type", "decimal")
));
var anchorId = AnchorFrameCache.computeAnchorId(schema);
var anchor   = new AnchorFrame(anchorId, schema, 3600);

byte[]      wire    = codec.encode(anchor);
AnchorFrame decoded = (AnchorFrame) codec.decode(wire);
```
