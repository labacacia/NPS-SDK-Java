[English Version](./README.md) | 中文版

# NPS Java SDK

[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](../../LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-ED8B00)](https://openjdk.org/)

**Neural Protocol Suite (NPS)** 的 Java 客户端库 —— 专为 AI Agent 与神经模型设计的完整互联网协议栈。

Group：`com.labacacia.nps` · Java 21+ · Gradle 8+ · Kotlin DSL

---

## NPS 仓库导航

| 仓库 | 职责 | 语言 |
|------|------|------|
| [NPS-Release](https://github.com/labacacia/NPS-Release) | 协议规范（权威来源） | Markdown / YAML |
| [NPS-sdk-dotnet](https://github.com/labacacia/NPS-sdk-dotnet) | 参考实现 | C# / .NET 10 |
| [NPS-sdk-py](https://github.com/labacacia/NPS-sdk-py) | 异步 Python SDK | Python 3.11+ |
| [NPS-sdk-ts](https://github.com/labacacia/NPS-sdk-ts) | Node/浏览器 SDK | TypeScript |
| **[NPS-sdk-java](https://github.com/labacacia/NPS-sdk-java)**（本仓库） | JVM SDK | Java 21+ |
| [NPS-sdk-rust](https://github.com/labacacia/NPS-sdk-rust) | 异步 SDK | Rust stable |
| [NPS-sdk-go](https://github.com/labacacia/NPS-sdk-go) | Go SDK | Go 1.23+ |

---

## 状态

**v1.0.0-alpha.1 — Phase 1 发布**

覆盖 NPS 全部五个协议：NCP + NWP + NIP + NDP + NOP，87 个测试全部通过。

## 运行要求

- Java 21+
- Gradle 8.x（Kotlin DSL）
- 运行时依赖（由 Gradle 管理）：
  - `org.msgpack:msgpack-core` 0.9.x
  - `com.fasterxml.jackson.core:jackson-databind` 2.17.x
  - `org.slf4j:slf4j-api` 2.0.x

## 构建

```bash
./gradlew test     # 运行 87 个测试
./gradlew build    # 构建 JAR + sources + javadoc
```

## 模块

| 包 | 说明 | 参考文档 |
|----|------|----------|
| `com.labacacia.nps.core` | 帧头、编解码（Tier-1 JSON / Tier-2 MsgPack）、帧注册表、AnchorFrame 缓存、异常 | [`doc/nps-java.core.cn.md`](./doc/nps-java.core.cn.md) |
| `com.labacacia.nps.ncp`  | NCP 帧：`AnchorFrame`、`DiffFrame`、`StreamFrame`、`CapsFrame`、`ErrorFrame` | [`doc/nps-java.ncp.cn.md`](./doc/nps-java.ncp.cn.md) |
| `com.labacacia.nps.nwp`  | NWP 帧：`QueryFrame`、`ActionFrame`、`AsyncActionResponse`；`NwpClient`（HTTP） | [`doc/nps-java.nwp.cn.md`](./doc/nps-java.nwp.cn.md) |
| `com.labacacia.nps.nip`  | NIP 帧：`IdentFrame`、`TrustFrame`、`RevokeFrame`；`NipIdentity`（Ed25519） | [`doc/nps-java.nip.cn.md`](./doc/nps-java.nip.cn.md) |
| `com.labacacia.nps.ndp`  | NDP 帧：`AnnounceFrame`、`ResolveFrame`、`GraphFrame`；`InMemoryNdpRegistry`、`NdpAnnounceValidator` | [`doc/nps-java.ndp.cn.md`](./doc/nps-java.ndp.cn.md) |
| `com.labacacia.nps.nop`  | NOP 帧：`TaskFrame`、`DelegateFrame`、`SyncFrame`、`AlignStreamFrame`；`BackoffStrategy`、`NopTaskStatus` | [`doc/nps-java.nop.cn.md`](./doc/nps-java.nop.cn.md) |

完整 API 参考（按模块分的类与方法文档）见 [`doc/`](./doc/) —— 从 [`doc/overview.cn.md`](./doc/overview.cn.md) 入门。

## 快速开始

### 编解码 NCP 帧

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

### NWP 客户端 —— Query

```java
import com.labacacia.nps.nwp.NwpClient;
import com.labacacia.nps.nwp.QueryFrame;
import com.labacacia.nps.ncp.CapsFrame;

var client = new NwpClient("http://node.example.com:17433");
CapsFrame caps = client.query(new QueryFrame("sha256:abc123", null, null, null, 50, null));
```

### NWP 客户端 —— Action

```java
import com.labacacia.nps.nwp.ActionFrame;

var action = new ActionFrame("run", java.util.Map.of("input", "hello"), null, false);
Object result = client.invoke(action);
```

### NWP 客户端 —— 流式

```java
for (var sf : client.stream(query)) {
    System.out.println(sf.payload());
    if (sf.isLast()) break;
}
```

### NIP 身份 —— 签名与验签

```java
import com.labacacia.nps.nip.NipIdentity;
import java.nio.file.Path;

var identity = NipIdentity.generate();
var sig      = identity.sign(java.util.Map.of("nid", "urn:nps:node:example.com:data"));
boolean ok   = identity.verify(java.util.Map.of("nid", "urn:nps:node:example.com:data"), sig);

// 以加密形式持久化（AES-256-GCM + PBKDF2）
identity.save(Path.of("my-node.key"), "my-passphrase");
var loaded = NipIdentity.load(Path.of("my-node.key"), "my-passphrase");
```

### NDP —— 注册表与验证器

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

### NOP —— 退避

```java
import com.labacacia.nps.nop.BackoffStrategy;

long delayMs = BackoffStrategy.computeDelayMs(
    BackoffStrategy.EXPONENTIAL, /*baseMs*/ 1000, /*maxMs*/ 30_000, /*attempt*/ 2);
// → 4000 ms（1000 * 2^2，受 maxMs 限制）
```

## 帧类型速查

| 帧 | 代码 | 协议 | 用途 |
|----|------|------|------|
| `AnchorFrame`      | 0x01 | NCP | Schema 锚点 |
| `DiffFrame`        | 0x02 | NCP | Schema diff / patch |
| `StreamFrame`      | 0x03 | NCP | 流式分片（FINAL 标志 = 最后一帧） |
| `CapsFrame`        | 0x04 | NCP | 能力 / 响应包装 |
| `ErrorFrame`       | 0xFE | NCP | 统一错误帧 |
| `QueryFrame`       | 0x10 | NWP | 带 AnchorFrame 引用 + 过滤条件的查询 |
| `ActionFrame`      | 0x11 | NWP | Action 调用（同步 / 异步） |
| `IdentFrame`       | 0x20 | NIP | 节点身份声明 |
| `TrustFrame`       | 0x21 | NIP | 信任委托 |
| `RevokeFrame`      | 0x22 | NIP | 吊销通告 |
| `AnnounceFrame`    | 0x30 | NDP | 带 TTL 的节点公告 |
| `ResolveFrame`     | 0x31 | NDP | 地址解析 |
| `GraphFrame`       | 0x32 | NDP | 网络拓扑快照 |
| `TaskFrame`        | 0x40 | NOP | DAG 任务定义 |
| `DelegateFrame`    | 0x41 | NOP | 子任务委托 |
| `SyncFrame`        | 0x42 | NOP | K-of-N 同步屏障 |
| `AlignStreamFrame` | 0x43 | NOP | 流式对齐更新 |

## 编码

| Tier | 枚举 | 说明 |
|------|------|------|
| Tier-1 | `EncodingTier.JSON` | 可读 JSON（调试、互操作） |
| Tier-2 | `EncodingTier.MSGPACK` | MsgPack 二进制（默认，约小 60%） |

## 错误处理

| 异常 | 场景 |
|------|------|
| `NpsCodecError` | 未知帧类型、负载过大、解码失败 |
| `AnchorFrameCache.AnchorNotFoundError` | `getRequired()` 命中缺失 / 过期 AnchorFrame |
| `AnchorFrameCache.AnchorPoisonError` | 相同 anchor_id 的 schema 不一致 |
| `IOException` | `NwpClient` 网络错误 |
| `RuntimeException` | `NwpClient` 收到非 2xx HTTP 响应 |

## NIP CA Server

`nip-ca-server/` 目录提供一个独立 NIP 证书颁发机构服务 —— Spring Boot 3.4，SQLite 存储，开箱即用的 Docker 部署。

## 测试

87 个测试覆盖全部五个协议：
- `AnchorFrameCacheTest` —— 12 个测试
- `FrameHeaderTest` —— 8 个测试
- `NpsFrameCodecTest` —— 11 个测试
- `NdpTest` —— 25 个测试（帧、注册表、验证器、匹配）
- `NipIdentityTest` —— 13 个测试（密钥生成、签名 / 验签、持久化）
- `NopTest` —— 18 个测试（退避、帧、任务状态）

```bash
./gradlew test
```

## 许可证

Apache 2.0 —— 详见 [LICENSE](../../LICENSE) 与 [NOTICE](../../NOTICE)。

Copyright 2026 INNO LOTUS PTY LTD
