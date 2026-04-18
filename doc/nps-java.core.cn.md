[English Version](./nps-java.core.md) | 中文版

# `com.labacacia.nps.core` — 类与方法参考

> 规范：[NPS-1 NCP v0.4](https://github.com/labacacia/NPS-Release/blob/main/spec/NPS-1-NCP.md) §2–§4

core 包提供所有协议构建所依赖的线路层原语：
帧头、编解码器、tier 实现、帧注册表、锚点缓存，
以及非受检异常层级。

---

## 目录

- [`FrameType`](#frametype)
- [`EncodingTier`](#encodingtier)
- [`FrameFlags`](#frameflags)
- [`FrameHeader`](#frameheader)
- [`NpsFrame`](#npsframe)
- [`NpsFrameCodec`](#npsframecodec)
- [`FrameRegistry` / `NpsRegistries`](#frameregistry--npsregistries)
- [`AnchorFrameCache`](#anchorframecache)
- [异常](#异常)

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
    // 共用
    ERROR       (0xFE);

    public final int code;
    public static FrameType fromCode(int code);   // 未知编码抛出 NpsFrameError
}
```

---

## `EncodingTier`

```java
public enum EncodingTier {
    JSON,       // Tier-1
    MSGPACK;    // Tier-2 — 生产环境默认
}
```

---

## `FrameFlags`

帧头中单字节 `flags` 字段的位常量。

```java
public final class FrameFlags {
    public static final int FINAL         = 0x01;   // 该逻辑消息的最后一帧
    public static final int EXT           = 0x02;   // 使用 8 字节帧头替代 4 字节
    public static final int TIER1_JSON    = 0x04;
    public static final int TIER2_MSGPACK = 0x08;
}
```

> **位布局说明：** Java SDK 的位分配仅供内部使用。其他
> NPS SDK（Python、TypeScript）将相同的逻辑 flag 打包在
> 不同的位布局中；不要在不同语言实现之间复制常量。

---

## `FrameHeader`

每个 NPS 线路帧前固定长度的帧头前缀。

```java
public final class FrameHeader {
    public static final int DEFAULT_HEADER_SIZE  = 4;   // 4 字节帧头（payload ≤ 65 535 B）
    public static final int EXTENDED_HEADER_SIZE = 8;   // 设置 EXT flag 后 payload 上限 4 GiB

    public final FrameType frameType;
    public final int       flags;
    public final long      payloadLength;
    public final boolean   isExtended;

    public FrameHeader(FrameType frameType, int flags, long payloadLength);

    public boolean       isFinal();
    public int           headerSize();
    public EncodingTier  encodingTier();   // 从 flags 推导

    public byte[]        toBytes();
    public static FrameHeader parse(byte[] data);

    public static int     buildFlags(EncodingTier tier, boolean isFinal, boolean isExtended);
    public static boolean needsExtended(long payloadLength);  // > 0xFFFF
}
```

### 线路布局

```
默认（4 字节）：
  [0]   frame_type
  [1]   flags
  [2-3] payload_length（uint16，大端）

扩展（EXT flag，8 字节）：
  [0]   frame_type
  [1]   flags
  [2-3] reserved（0x00 0x00）
  [4-7] payload_length（uint32，大端）
```

当缓冲区短于 `DEFAULT_HEADER_SIZE`，或设置了 EXT 但短于
`EXTENDED_HEADER_SIZE` 时，`parse` 抛出 `NpsFrameError`。

---

## `NpsFrame`

所有 payload 类型实现的接口。

```java
public interface NpsFrame {
    FrameType    frameType();
    EncodingTier preferredTier();
    Map<String, Object> toDict();
}
```

每个帧类还提供静态 `static fromDict(Map<String,Object>)`
工厂方法，供解码时编解码器使用。

---

## `NpsFrameCodec`

将编码/解码分派到对应 tier 实现，并拼装带 4 字节或 8 字节帧头的线路字节流。

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

### 行为

- `encode` 选择 `overrideTier ?: frame.preferredTier()`，对
  payload 编码，当 `payload.length > 0xFFFF` 时设置 `EXT`，并设置 `FINAL`
  （除非该帧是非末尾的 `StreamFrame` —— `isLast()==false`）。
- 当 payload 超过 `maxPayload` 时抛出 `NpsCodecError`。
- `decode` 通过 `FrameHeader.parse` 读取帧头、拷贝 payload，
  并委托 tier 编解码器 + `FrameRegistry` 重建帧。
- `peekHeader` 是一个便捷方法，供路由代码在解码 payload
  前先按 `frameType` 分派。

### Tier 编解码器

内部使用两个 `private final` 编解码器：

```java
Tier1JsonCodec    jsonCodec;     // Jackson
Tier2MsgPackCodec msgpackCodec;  // msgpack-core
```

它们实现相同的双方法契约 —— 不属于公共 API，应用代码
不应引用。

---

## `FrameRegistry` / `NpsRegistries`

将 `FrameType` → 解码函数做映射。每个帧模块提供静态的
`{Ncp,Nwp,Nip,Ndp,Nop}FrameRegistrar.register(reg)` 辅助方法。

```java
public final class FrameRegistry {
    @FunctionalInterface
    public interface FrameDecoder {
        NpsFrame decode(Map<String, Object> dict);
    }

    public void         register(FrameType type, FrameDecoder decoder);
    public FrameDecoder resolve(FrameType type);      // 缺失时抛出 NpsFrameError
    public boolean      isRegistered(FrameType type);
}
```

工厂辅助方法：

```java
FrameRegistry def  = NpsRegistries.createDefault();   // 仅 NCP
FrameRegistry full = NpsRegistries.createFull();      // NCP + NWP + NIP + NDP + NOP
```

自定义注册表用于窄子集场景：

```java
FrameRegistry r = new FrameRegistry();
NcpFrameRegistrar.register(r);
NwpFrameRegistrar.register(r);
```

---

## `AnchorFrameCache`

线程安全的 `AnchorFrame` 缓存，以 `sha256:{hex}` 锚点 ID 为键，
在读取时惰性执行 TTL 过期。

```java
public final class AnchorFrameCache {
    public LongSupplier clock;       // 默认 System::currentTimeMillis —— 测试时可替换

    public static String computeAnchorId(Map<String, Object> schema);

    public void        set(AnchorFrame frame);
    public AnchorFrame get(String anchorId);                  // 不存在/已过期返回 null
    public AnchorFrame getRequired(String anchorId);          // 抛出 NpsAnchorNotFoundError
    public void        invalidate(String anchorId);
    public int         size();                                // 读取时作为副作用清除已过期项
}
```

### 规范化锚点 ID

`computeAnchorId` 按 `name` 对 `schema.fields` 排序，将每个 field
序列化为 `{"name":"…","type":"…"}`、包裹为数组，然后用 SHA-256
哈希。这是按键排序形式 —— 与 Python/TS 的 sort-keys
规范化器以及 .NET 参考实现一致。

### 投毒检测

`set` 比较新 `schema` 与同 ID 的现有条目。不匹配抛出
`NpsAnchorPoisonError`；完全相同的 schema 视为幂等（保留现有条目）。

---

## 异常

所有 NPS 错误通过 `NpsError` 继承 `RuntimeException`。

```java
public class NpsError              extends RuntimeException { … }
public class NpsFrameError         extends NpsError { … }    // 结构/帧头
public class NpsCodecError         extends NpsError { … }    // 编码/解码
public class NpsAnchorNotFoundError extends NpsError { String getAnchorId(); }
public class NpsAnchorPoisonError   extends NpsError { String getAnchorId(); }
```

`NwpClient` / `NopClient` 中的 HTTP 层失败以携带状态码和路径的
普通 `RuntimeException` 形式报告。
