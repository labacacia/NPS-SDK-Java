[English Version](./overview.md) | 中文版

# `nps-java` — API 参考总览

[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](../LICENSE)

NPS Java SDK 是 .NET 参考实现的 JVM 移植。本文档是各模块 API 参考的入口 ——
每个协议单独成文，见下方列表。

---

## 包结构

```
com.labacacia.nps
├── .core         # 线缆原语：FrameHeader、FrameType、codecs、AnchorFrameCache、异常
├── .ncp          # NCP 帧：Anchor、Diff、Stream、Caps、Error、SchemaField
├── .nwp          # NWP 帧 + NwpClient（java.net.http）
├── .nip          # NIP 帧 + NipIdentity（Ed25519）
├── .ndp          # NDP 帧 + InMemoryNdpRegistry + NdpAnnounceValidator
└── .nop          # NOP 帧 + TaskState + BackoffStrategy + NopClient
```

## 参考文档

| 包 | 模块 | 参考文档 |
|----|------|----------|
| —                             | 根构建信息与注册表工厂 | 本文件 |
| `com.labacacia.nps.core`      | 帧头、编解码、AnchorFrame 缓存、异常 | [`nps-java.core.cn.md`](./nps-java.core.cn.md) |
| `com.labacacia.nps.ncp`       | NCP 帧集（`AnchorFrame`、`DiffFrame`、`StreamFrame`、`CapsFrame`、`ErrorFrame`） | [`nps-java.ncp.cn.md`](./nps-java.ncp.cn.md) |
| `com.labacacia.nps.nwp`       | `QueryFrame`、`ActionFrame`、`NwpClient` | [`nps-java.nwp.cn.md`](./nps-java.nwp.cn.md) |
| `com.labacacia.nps.nip`       | `IdentFrame`、`TrustFrame`、`RevokeFrame`、`NipIdentity` | [`nps-java.nip.cn.md`](./nps-java.nip.cn.md) |
| `com.labacacia.nps.ndp`       | `AnnounceFrame`、`ResolveFrame`、`GraphFrame`、注册表、验证器 | [`nps-java.ndp.cn.md`](./nps-java.ndp.cn.md) |
| `com.labacacia.nps.nop`       | 任务状态 / 退避、`TaskFrame`、`DelegateFrame`、`SyncFrame`、`AlignStreamFrame`、`NopClient` | [`nps-java.nop.cn.md`](./nps-java.nop.cn.md) |

---

## 安装

Gradle（Kotlin DSL）：

```kotlin
dependencies {
    implementation("com.labacacia.nps:nps-java:1.0.0-alpha.1")
}
```

Maven：

```xml
<dependency>
    <groupId>com.labacacia.nps</groupId>
    <artifactId>nps-java</artifactId>
    <version>1.0.0-alpha.1</version>
</dependency>
```

要求 **Java 21+**。运行时依赖通过传递性解析：

- `org.msgpack:msgpack-core` 0.9.x
- `com.fasterxml.jackson.core:jackson-databind` 2.17.x
- `org.slf4j:slf4j-api` 2.0.x

Ed25519 由 Java 15+ JDK（`java.security`）原生提供，无需外部
加密库。

---

## 注册表工厂

```java
import com.labacacia.nps.core.registry.FrameRegistry;
import com.labacacia.nps.core.registry.NpsRegistries;

FrameRegistry defaultReg = NpsRegistries.createDefault();  // 只含 NCP
FrameRegistry fullReg    = NpsRegistries.createFull();     // 全部 5 个协议
```

- `createDefault()` —— 注册 NCP 帧（`ANCHOR`、`DIFF`、`STREAM`、`CAPS`、`ERROR`）。
- `createFull()` —— 注册 NCP + NWP + NIP + NDP + NOP。

当编解码器可能会收到超过一个协议的帧时（例如代理 NWP + NOP 流量
的编排器），使用 `createFull()`。

---

## 最小端到端示例

```java
import com.labacacia.nps.core.codec.NpsFrameCodec;
import com.labacacia.nps.core.registry.NpsRegistries;
import com.labacacia.nps.nwp.NwpClient;
import com.labacacia.nps.nwp.QueryFrame;
import com.labacacia.nps.ncp.CapsFrame;

var client = new NwpClient("http://node.example.com:17433");

// 分页查询
QueryFrame q = new QueryFrame(
    "sha256:<anchor-id>",
    java.util.Map.of("active", true),
    50, 0, null, null, null, null);
CapsFrame caps = client.query(q);
System.out.println(caps.count() + " rows");
```

---

## 编码分层

每个 `NpsFrame` 都声明 `preferredTier()`。MsgPack 是生产默认；
JSON Tier 留给调试和跨语言诊断。

| Tier | `EncodingTier` | Flags 位 | 说明 |
|------|----------------|-----------|------|
| Tier-1 JSON    | `EncodingTier.JSON`    | `0x04` | UTF-8 JSON，开发与兼容 |
| Tier-2 MsgPack | `EncodingTier.MSGPACK` | `0x08` | MessagePack。**生产默认** —— 约小 60% |

```java
var codec = new NpsFrameCodec(NpsRegistries.createFull());
byte[] wire = codec.encode(frame, EncodingTier.JSON);  // 覆盖 Tier
```

> ⚠ **Java 位布局与 TS/Python 不同。** Java 使用
> `FINAL=0x01, EXT=0x02, TIER1_JSON=0x04, TIER2_MSGPACK=0x08`；跨 SDK
> 规范仍能正确解码，因为线缆上的 `flags` 字节作为不透明位域传输 ——
> 但不要跨 SDK 复制标志位常量。

---

## 同步 I/O

`NwpClient` 和 `NopClient` 都基于 `java.net.http.HttpClient`
暴露 **阻塞** 方法：

- 每次网络调用都可能抛出 `IOException` 和 `InterruptedException`。
- 异步用法需要调用方自行用 `CompletableFuture`、虚拟线程或
  `ExecutorService` 包裹 —— SDK 不做这层封装。

---

## 异常层级

```
RuntimeException
└── NpsError                          com.labacacia.nps.core.exception
    ├── NpsFrameError                 —— 帧头解析 / 结构错误
    ├── NpsCodecError                 —— 编解码失败（含负载过大）
    ├── NpsAnchorNotFoundError        —— AnchorFrame 不在缓存（含 getAnchorId()）
    └── NpsAnchorPoisonError          —— anchor_id / schema 不一致（含 getAnchorId()）
```

`NwpClient` / `NopClient` 的 HTTP 错误以携带状态码和路径的普通
`RuntimeException` 形式抛出。

---

## 参考规范

| 模块 | 规范 |
|------|------|
| `core` + `ncp` | [NPS-1 NCP v0.4](https://github.com/labacacia/NPS-Release/blob/main/spec/NPS-1-NCP.cn.md) |
| `nwp`          | [NPS-2 NWP v0.4](https://github.com/labacacia/NPS-Release/blob/main/spec/NPS-2-NWP.cn.md) |
| `nip`          | [NPS-3 NIP v0.2](https://github.com/labacacia/NPS-Release/blob/main/spec/NPS-3-NIP.cn.md) |
| `ndp`          | [NPS-4 NDP v0.2](https://github.com/labacacia/NPS-Release/blob/main/spec/NPS-4-NDP.cn.md) |
| `nop`          | [NPS-5 NOP v0.3](https://github.com/labacacia/NPS-Release/blob/main/spec/NPS-5-NOP.cn.md) |
