[English Version](./README.md) | 中文版

# NPS Java SDK (`nps-java`)

面向 **Neural Protocol Suite (NPS)** 的 Java 客户端库 —— 为 AI Agent 与模型设计的完整互联网协议栈。

包命名空间：`com.labacacia.nps` | Java 21+ | Gradle 8+

## 状态

**v1.0.0-alpha.5 —— NWP 错误码 + NIP gossip 错误码**

覆盖 NCP + NWP + NIP + NDP + NOP 五个协议，加完整 **NPS-RFC-0002** X.509 + ACME `agent-01` NID 证书原语（`com.labacacia.nps.nip.x509` + `com.labacacia.nps.nip.acme`）。

**alpha.5 新增：**

- `NwpErrorCodes` —— 新 `com.labacacia.nps.nwp.NwpErrorCodes` 类，包含 30 个 NWP wire 错误码常量。
- `NipErrorCodes.REPUTATION_GOSSIP_FORK` / `.REPUTATION_GOSSIP_SIG_INVALID` —— RFC-0004 Phase 3 gossip 错误码。
- `AssuranceLevel.fromWire("")` 改为返回 `ANONYMOUS`（spec §5.1.1 修复）。

## 环境要求

- Java 21+
- Gradle 8.x（Kotlin DSL）
- 依赖（Gradle 管理）：
  - `org.msgpack:msgpack-core:0.9.8`
  - `com.fasterxml.jackson.core:jackson-databind:2.17.2`
  - `org.slf4j:slf4j-api:2.0.13`
  - `org.bouncycastle:bcprov-jdk18on:1.79` *（RFC-0002，仅用 X.509 builder API）*
  - `org.bouncycastle:bcpkix-jdk18on:1.79` *（RFC-0002，仅用 X.509 builder API）*

## 构建

```bash
# 运行全部测试
./gradlew test

# 构建 JAR
./gradlew build
```

## 模块

| Package | 说明 |
|---------|------|
| `com.labacacia.nps.core` | 帧头、编解码器（Tier-1 JSON / Tier-2 MsgPack）、帧注册表、anchor 缓存、异常 |
| `com.labacacia.nps.ncp`  | NCP 帧：`AnchorFrame`、`DiffFrame`、`StreamFrame`、`CapsFrame`、`HelloFrame`、`ErrorFrame` |
| `com.labacacia.nps.nwp`  | NWP 帧：`QueryFrame`、`ActionFrame`、`AsyncActionResponse`；`NwpClient`（HTTP）；`NwpErrorCodes` |
| `com.labacacia.nps.nip`         | NIP 帧：`IdentFrame`、`TrustFrame`、`RevokeFrame`；`NipIdentity`（Ed25519 密钥管理）；`NipIdentVerifier`（RFC-0002 §8.1 双信任）；`AssuranceLevel`（RFC-0003） |
| `com.labacacia.nps.nip.x509`    | RFC-0002 X.509 NID 证书：`NipX509Builder` / `NipX509Verifier` / `Ed25519PublicKeys` / `NpsX509Oids` |
| `com.labacacia.nps.nip.acme`    | RFC-0002 ACME `agent-01`：`AcmeClient` / `AcmeServer`（进程内） / `AcmeJws` / `AcmeMessages` |
| `com.labacacia.nps.ndp`  | NDP 帧：`AnnounceFrame`、`ResolveFrame`、`GraphFrame`；`InMemoryNdpRegistry`；`NdpAnnounceValidator`；`resolveViaDns`（DNS TXT 回退）；`DnsTxtLookup`、`SystemDnsTxtLookup`、`NpsDnsTxt` |
| `com.labacacia.nps.nop`  | NOP 帧：`TaskFrame`、`DelegateFrame`、`SyncFrame`、`AlignStreamFrame`；`BackoffStrategy`；`NopTaskStatus` |

## 快速开始

### NCP 帧编解码

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

byte[] wire   = codec.encode(frame);          // 默认 Tier-2 MsgPack
var    result = (AnchorFrame) codec.decode(wire);
```

### NWP 客户端 —— 查询

```java
import com.labacacia.nps.nwp.NwpClient;
import com.labacacia.nps.nwp.QueryFrame;
import com.labacacia.nps.ncp.CapsFrame;

var client = new NwpClient("http://node.example.com:17433");
var query  = new QueryFrame("sha256:abc123", null, null, null, 50, null);
CapsFrame caps = client.query(query);
```

### NWP 客户端 —— Action 调用

```java
import com.labacacia.nps.nwp.ActionFrame;

var action = new ActionFrame("run", java.util.Map.of("input", "hello"), null, false);
Object result = client.invoke(action);   // NpsFrame 或 Map<String,Object>
```

### NWP 客户端 —— 流式

```java
import com.labacacia.nps.ncp.StreamFrame;
import java.util.List;

List<StreamFrame> frames = client.stream(query);
for (var sf : frames) {
    System.out.println(sf.payload());
    if (sf.isLast()) break;
}
```

### NIP 身份 —— 签名 & 验签

```java
import com.labacacia.nps.nip.NipIdentity;
import java.nio.file.Path;
import java.util.Map;

// 生成密钥对
var identity = NipIdentity.generate();
System.out.println(identity.pubKeyString()); // "ed25519:<hex>"

// 对 payload 签名
var payload = Map.<String, Object>of("nid", "urn:nps:node:example.com:data");
String sig  = identity.sign(payload);        // "ed25519:<base64>"
boolean ok  = identity.verify(payload, sig); // true

// 持久化与加载（AES-256-GCM + PBKDF2）
identity.save(Path.of("my-node.key"), "my-passphrase");
var loaded = NipIdentity.load(Path.of("my-node.key"), "my-passphrase");
```

### NDP 注册表 —— announce & resolve

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

### NDP Announce 校验器

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

### NOP —— 退避策略

```java
import com.labacacia.nps.nop.BackoffStrategy;

long delayMs = BackoffStrategy.computeDelayMs(BackoffStrategy.EXPONENTIAL, 1000, 30_000, 2);
// 返回 4000（2^2 * 1000），受 maxMs 上限约束
```

## 帧类型对照

| 帧 | 类型码 | 协议 | 说明 |
|----|--------|------|------|
| `AnchorFrame`       | 0x01 | NCP | Schema anchor（缓存的 schema 定义） |
| `DiffFrame`         | 0x02 | NCP | Schema diff / patch |
| `StreamFrame`       | 0x03 | NCP | 流式数据块（FINAL 标志 = 末尾） |
| `CapsFrame`         | 0x04 | NCP | Capability 公告 |
| `HelloFrame`        | 0x06 | NCP | 原生模式握手（客户端 → 节点，JSON） |
| `ErrorFrame`        | 0xFE | NCP | 统一错误帧（所有协议共用） |
| `QueryFrame`        | 0x10 | NWP | 携带 anchor_ref + 过滤条件的数据查询 |
| `ActionFrame`       | 0x11 | NWP | Action 调用（同步或异步） |
| `IdentFrame`        | 0x20 | NIP | 节点身份声明（已签名） |
| `TrustFrame`        | 0x21 | NIP | 节点间信任委托 |
| `RevokeFrame`       | 0x22 | NIP | 吊销通知 |
| `AnnounceFrame`     | 0x30 | NDP | 节点公告（含 TTL） |
| `ResolveFrame`      | 0x31 | NDP | 地址解析请求 / 响应 |
| `GraphFrame`        | 0x32 | NDP | 网络拓扑快照 |
| `TaskFrame`         | 0x40 | NOP | 编排 DAG 任务 |
| `DelegateFrame`     | 0x41 | NOP | 子任务委托 |
| `SyncFrame`         | 0x42 | NOP | K-of-N 同步屏障 |
| `AlignStreamFrame`  | 0x43 | NOP | 流式对齐更新 |

## 编码

编解码器支持两个 Tier：

| Tier | 枚举 | 说明 |
|------|------|------|
| Tier-1 | `EncodingTier.JSON` | 可读 JSON（调试 / 互操作） |
| Tier-2 | `EncodingTier.MSGPACK` | MsgPack 二进制（默认，约 60% 压缩） |

```java
import com.labacacia.nps.core.EncodingTier;

byte[] jsonWire    = codec.encode(frame, EncodingTier.JSON);
byte[] msgpackWire = codec.encode(frame, EncodingTier.MSGPACK); // 默认
```

## Anchor 缓存

`AnchorFrameCache` 按 anchor ID 存储 schema，支持 TTL 过期。anchor ID 为 schema 规范化（字段排序）JSON 的 SHA-256。

```java
import com.labacacia.nps.core.cache.AnchorFrameCache;

var cache = new AnchorFrameCache();
var frame = new AnchorFrame("sha256:...", schema, null, null, null, 3600);
cache.set(frame);

var retrieved = cache.get("sha256:...");
// 过期时返回 null
```

## 错误处理

编解码 / 注册表错误抛 `NpsCodecError`（unchecked）。`NwpClient` 的网络错误抛 `IOException` 或 `InterruptedException`。

| 异常 | 触发条件 |
|------|---------|
| `NpsCodecError` | 未知帧类型、payload 过大、解码失败 |
| `AnchorFrameCache.AnchorNotFoundError` | `getRequired()` 请求缺失或过期的 anchor |
| `AnchorFrameCache.AnchorPoisonError` | 尝试用不同 schema 覆盖已缓存的 anchor |
| `IOException` | `NwpClient` 网络错误 |
| `RuntimeException` | `NwpClient` 收到非 2xx HTTP 响应 |

## 测试

5 个协议 + RFC-0002 共 122 个测试，运行：

```bash
./gradlew test
```

测试类：
- `AnchorFrameCacheTest` — 12
- `FrameHeaderTest` — 8
- `NpsFrameCodecTest` — 15
- `NdpTest` — 35（帧、注册表、校验器、匹配、DNS TXT 回退）
- `NipIdentityTest` — 13（密钥生成、签名/验签、持久化、帧）
- `NipX509Tests` — 5（RFC-0002：builder + verifier 正路径 + 4 反路径）
- `AcmeAgent01Tests` — 2（RFC-0002：完整 ACME round-trip + 篡改签名）
- `NopTest` — 18（退避、帧、任务状态）

## 许可证

[Apache 2.0](../../LICENSE) © 2026 INNO LOTUS PTY LTD
