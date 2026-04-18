[English Version](./nps-java.ncp.md) | 中文版

# `com.labacacia.nps.ncp` — 类与方法参考

> 规范：[NPS-1 NCP v0.4](https://github.com/labacacia/NPS-Release/blob/main/spec/NPS-1-NCP.md)

NCP 是线路与 schema 层。Java SDK 暴露五个核心帧 ——
`AnchorFrame`、`DiffFrame`、`StreamFrame`、`CapsFrame`、
`ErrorFrame` —— 以及用于构建 schema 的 `SchemaField` record。

所有帧类均为 `final`、实现 `NpsFrame`，并提供静态
`fromDict` 工厂方法用于解码。

---

## 目录

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
    String  semantic,  // nullable —— 可选的 NPS 语义标签
    Boolean nullable   // nullable
) {
    public SchemaField(String name, String type);  // semantic + nullable = null
    public Map<String, Object> toDict();
    public static SchemaField fromDict(Map<String, Object> d);
}
```

Schema 在线路上以 `Map<String, Object>` 形式携带（`toDict`/`fromDict`
的往返形态是 `{"fields": [{…}, {…}]}`）。`SchemaField`
只作为带类型的构建器使用；交给 `AnchorFrame` 之前总是先转换为 `Map`。

---

## `AnchorFrame` (0x01)

内容寻址的 schema 广告。

```java
public final class AnchorFrame implements NpsFrame {
    public AnchorFrame(String anchorId, Map<String, Object> schema, int ttl);
    public AnchorFrame(String anchorId, Map<String, Object> schema);  // ttl = 3600

    public String              anchorId();
    public Map<String, Object> schema();
    public int                 ttl();       // 秒 —— 0 表示仅本 session
}
```

- `preferredTier()` → `MSGPACK`。
- 当该帧为权威源时，`anchorId` 必须等于
  `AnchorFrameCache.computeAnchorId(schema)`；否则接收方的投毒
  检测会将其拒绝。

---

## `DiffFrame` (0x02)

锚定到先前 `AnchorFrame` 的增量数据补丁。

```java
public final class DiffFrame implements NpsFrame {
    public DiffFrame(String anchorRef, int baseSeq,
                     List<Map<String, Object>> patch, String entityId);

    public String                     anchorRef();
    public int                        baseSeq();
    public List<Map<String, Object>>  patch();     // RFC 6902 JSON-Patch 操作
    public String                     entityId();  // nullable
}
```

每个 patch 条目是一个含 `op` / `path` / `value` / `from` 键的 Map，
符合 RFC 6902。NPS-1 §3.2 中的二进制位图变体尚未作为一等
类型暴露 —— 需要时将原始字节嵌入单个 op 的 patch。

---

## `StreamFrame` (0x03)

流式分块帧。

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
    public Integer                    windowSize();  // nullable —— 背压提示
}
```

编解码器根据 `isLast()` 决定是否在帧头设置 `FINAL` flag。
非末尾 `StreamFrame` 以 `FINAL=0` 发送；末尾帧设 `FINAL=1`。

---

## `CapsFrame` (0x04)

Capsule —— 引用已缓存 schema 的完整结果页。

```java
public final class CapsFrame implements NpsFrame {
    public CapsFrame(String anchorRef, int count,
                     List<Map<String, Object>> data,
                     String  nextCursor,    // nullable
                     Integer tokenEst,      // nullable —— NPT 估算
                     Boolean cached,        // nullable
                     String  tokenizerUsed  // nullable —— tokenizer URN
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

`count` 应等于 `data.size()` —— .NET 与 Python 的校验器对此
强制检查。Java SDK 目前信任构造函数调用方；若需从不可信
对端解析 `CapsFrame`，请自行添加检查。

---

## `ErrorFrame` (0xFE)

所有 NPS 协议层共享的统一错误帧（NPS-0 §9）。

```java
public final class ErrorFrame implements NpsFrame {
    public ErrorFrame(String status, String error,
                      String message, Map<String, Object> details);

    public String              status();   // NPS 状态码，如 "NPS-CLIENT-NOT-FOUND"
    public String              error();    // 协议码，如 "NCP-ANCHOR-NOT-FOUND"
    public String              message();  // nullable
    public Map<String, Object> details();  // nullable
}
```

用 `frame instanceof ErrorFrame err` 判定错误信封。

---

## `NcpFrameRegistrar`

向 `FrameRegistry` 注册全部五个 NCP 帧：

```java
FrameRegistry reg = new FrameRegistry();
NcpFrameRegistrar.register(reg);
// 注册 ANCHOR, DIFF, STREAM, CAPS, ERROR
```

`NpsRegistries.createDefault()` / `createFull()` 会为你调用此方法。

---

## 端到端示例

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
