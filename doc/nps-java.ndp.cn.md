[English Version](./nps-java.ndp.md) | 中文版

# `com.labacacia.nps.ndp` — 类与方法参考

> 规范：[NPS-4 NDP v0.2](https://github.com/labacacia/NPS-Release/blob/main/spec/NPS-4-NDP.md)

NDP 是发现层 —— NPS 中对应 DNS 的组件。本模块提供三个 NDP
帧类型、一个带惰性 TTL 过期的线程安全内存注册表，以及一个
announce 签名校验器。

---

## 目录

- [`AnnounceFrame` (0x30)](#announceframe-0x30)
- [`ResolveFrame` (0x31)](#resolveframe-0x31)
- [`GraphFrame` (0x32)](#graphframe-0x32)
- [`InMemoryNdpRegistry`](#inmemoryndpregistry)
- [`NdpAnnounceValidator`](#ndpannouncevalidator)
- [`NdpAnnounceResult`](#ndpannounceresult)
- [`NdpFrameRegistrar`](#ndpframeregistrar)

---

## `AnnounceFrame` (0x30)

发布节点的物理可达性与 TTL（NPS-4 §3.1）。

```java
public final class AnnounceFrame implements NpsFrame {
    public AnnounceFrame(
        String                    nid,
        List<Map<String, Object>> addresses,    // [{"host", "port", "protocol"}, …]
        List<String>              capabilities,
        int                       ttl,          // 秒 —— 0 表示有序下线
        String                    timestamp,    // ISO 8601 UTC
        String                    signature,    // "ed25519:{base64}"
        String                    nodeType      // nullable —— "memory" | "action" | …
    );

    public Map<String, Object> unsignedDict();   // 剥离 signature 用于签名
}
```

签名流程：

1. 构造时使用占位签名。
2. `id.sign(frame.unsignedDict())` —— 使用该 NID 自己的私钥。
3. 用真实签名重建帧，然后发布。

发布 `ttl = 0` 必须在有序下线前完成，以便订阅者清除该条目。

---

## `ResolveFrame` (0x31)

解析 `nwp://` URL 的请求/响应信封。

```java
public final class ResolveFrame implements NpsFrame {
    public ResolveFrame(String target, String requesterNid,
                        Map<String, Object> resolved);
    public ResolveFrame(String target);   // requesterNid + resolved = null

    public String              target();         // "nwp://api.example.com/products"
    public String              requesterNid();   // nullable
    public Map<String, Object> resolved();       // nullable —— 响应时填充
}
```

---

## `GraphFrame` (0x32)

注册表之间的拓扑同步。

```java
public final class GraphFrame implements NpsFrame {
    public GraphFrame(int seq, boolean initialSync,
                      List<Map<String, Object>> nodes,    // nullable —— 全量快照
                      List<Map<String, Object>> patch);   // nullable —— RFC 6902 操作
}
```

`seq` 必须在每个发布者内严格单调递增。出现跳号会触发重新
同步请求，信号为 `NDP-GRAPH-SEQ-GAP`。

---

## `InMemoryNdpRegistry`

线程安全、按 TTL 过期的注册表。过期是在每次读取时**惰性**
评估的 —— 没有后台定时器。

```java
public final class InMemoryNdpRegistry {
    public LongSupplier clock;     // 默认 System::currentTimeMillis —— 测试时可替换

    public void          announce(AnnounceFrame frame);
    public AnnounceFrame getByNid(String nid);       // 不存在/已过期返回 null
    public ResolveResult resolve(String target);     // 无匹配返回 null
    public List<AnnounceFrame> getAll();             // 仅活跃条目

    public static boolean nwpTargetMatchesNid(String nid, String target);

    public record ResolveResult(String host, int port, int ttl) {}
}
```

### 行为

- `announce` 当 `ttl == 0` 时立即清除该 NID；否则以绝对过期
  时间 `clock.getAsLong() + ttl * 1000L` 插入/刷新条目。
- `resolve` 扫描活跃条目，返回覆盖 `target` 的**第一个** NID 及其
  **第一个**广告地址，包装为 `ResolveResult`。
- `getByNid` 精确查询 NID，读取时按需清理。
- 为可重现测试覆写 `clock`：
  `registry.clock = () -> 1_000_000L;`

### `nwpTargetMatchesNid(nid, target)`（静态）

NID ↔ target 覆盖规则：

```
NID:    urn:nps:node:{authority}:{path}
Target: nwp://{authority}/{path}[/subpath]
```

节点 NID 覆盖某 target 的条件：

1. Target scheme 为 `nwp://`。
2. NID 的 `{authority}` 与 target 的 authority 完全相等（区分大小写）。
3. Target 的 path 完全等于 `{path}`，或以 `{path}/` 开头。

输入格式错误时返回 `false` 而非抛异常。

---

## `NdpAnnounceValidator`

使用已注册的 Ed25519 公钥校验 `AnnounceFrame.signature`。
线程安全 —— 密钥保存在 `ConcurrentHashMap`。

```java
public final class NdpAnnounceValidator {
    public void registerPublicKey(String nid, String encodedPubKey);
    public void removePublicKey(String nid);
    public Map<String, String> knownPublicKeys();      // 快照副本

    public NdpAnnounceResult validate(AnnounceFrame frame);
}
```

`validate`（NPS-4 §7.1）：

1. 在已注册密钥中查找 `frame.nid()`。缺失 →
   `NdpAnnounceResult.fail("NDP-ANNOUNCE-NID-MISMATCH", …)`。
   期望的工作流程：先验证广告方的 `IdentFrame`，然后将其
   `pubKeyString()` 注册到此处。
2. 要求 `signature` 以 `"ed25519:"` 开头 —— 否则返回
   `NDP-ANNOUNCE-SIG-INVALID`。
3. 使用 `TreeMap`（按键排序规范形式）通过 Jackson 序列化
   `frame.unsignedDict()`，重建签名 payload。
4. 运行 JDK 的 `Signature.getInstance("Ed25519")` 验证。
5. 成功返回 `NdpAnnounceResult.ok()`；失败或抛异常返回
   `NdpAnnounceResult.fail("NDP-ANNOUNCE-SIG-INVALID", …)`。

编码后的密钥**必须**使用 `NipIdentity.pubKeyString()` 产生的
`ed25519:{hex}` 形式。

---

## `NdpAnnounceResult`

```java
public record NdpAnnounceResult(boolean isValid, String errorCode, String message) {
    public static NdpAnnounceResult ok();
    public static NdpAnnounceResult fail(String errorCode, String message);
}
```

---

## `NdpFrameRegistrar`

向 `FrameRegistry` 注册 `ANNOUNCE`、`RESOLVE`、`GRAPH`。

---

## 端到端示例

```java
import com.labacacia.nps.nip.NipIdentity;
import com.labacacia.nps.ndp.*;

var id  = NipIdentity.generate();
var nid = "urn:nps:node:api.example.com:products";

// 构造并签名 announce
var addresses = List.<Map<String, Object>>of(
    Map.of("host", "10.0.0.5", "port", 17433, "protocol", "nwp+tls"));
var unsigned  = new AnnounceFrame(nid, addresses,
    List.of("nwp:query", "nwp:stream"),
    300, java.time.Instant.now().toString(),
    "placeholder", "memory");
String sig    = id.sign(unsigned.unsignedDict());
var signed    = new AnnounceFrame(nid, addresses,
    unsigned.capabilities(), unsigned.ttl(), unsigned.timestamp(),
    sig, unsigned.nodeType());

// 校验并注册
var validator = new NdpAnnounceValidator();
validator.registerPublicKey(nid, id.pubKeyString());
NdpAnnounceResult res = validator.validate(signed);
if (!res.isValid()) throw new RuntimeException(res.errorCode());

var registry = new InMemoryNdpRegistry();
registry.announce(signed);

var resolved = registry.resolve("nwp://api.example.com/products/items/42");
// → ResolveResult[host=10.0.0.5, port=17433, ttl=300]
```
