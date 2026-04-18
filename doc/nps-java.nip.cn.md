[English Version](./nps-java.nip.md) | 中文版

# `com.labacacia.nps.nip` — 类与方法参考

> 规范：[NPS-3 NIP v0.2](https://github.com/labacacia/NPS-Release/blob/main/spec/NPS-3-NIP.md)

NIP 是身份层。本模块提供三个 NIP 帧（0x20–0x22）以及使用
Java 15+ 原生 `java.security` Ed25519 provider 的身份辅助类
`NipIdentity` —— 无第三方加密依赖。

---

## 目录

- [`IdentFrame` (0x20)](#identframe-0x20)
- [`TrustFrame` (0x21)](#trustframe-0x21)
- [`RevokeFrame` (0x22)](#revokeframe-0x22)
- [`NipIdentity`](#nipidentity)
- [密钥文件格式](#密钥文件格式)
- [`NipFrameRegistrar`](#nipframeregistrar)

---

## `IdentFrame` (0x20)

节点身份声明。

```java
public final class IdentFrame implements NpsFrame {
    public IdentFrame(String nid, String pubKey,
                      Map<String, Object> metadata, String signature);

    public String              nid();         // urn:nps:node:{authority}:{name}
    public String              pubKey();      // "ed25519:{hex}"
    public Map<String, Object> metadata();    // 自由格式 { "display_name", "org", … }
    public String              signature();   // "ed25519:{base64}"

    public Map<String, Object> unsignedDict();   // 剥离 signature —— 用于签名
}
```

签名流程：

1. 构造时 `signature = null`（或占位符）。
2. `NipIdentity.sign(frame.unsignedDict())` → `ed25519:{base64}`。
3. 用真实签名重新构造，然后编码并发送。

---

## `TrustFrame` (0x21)

委托/信任断言。

```java
public final class TrustFrame implements NpsFrame {
    public TrustFrame(String       issuerNid,
                      String       subjectNid,
                      List<String> scopes,
                      String       expiresAt,   // ISO 8601 UTC
                      String       signature);  // "ed25519:{base64}"
}
```

规范化签名 payload 是**剥离 signature 字段后**的完整 dict
（与 `IdentFrame` / `AnnounceFrame` 约定一致）。

---

## `RevokeFrame` (0x22)

吊销某个 NID —— 可在 `ttl == 0` 的 `AnnounceFrame` 之前或同时发送。

```java
public final class RevokeFrame implements NpsFrame {
    public RevokeFrame(String nid, String reason, String revokedAt);
    public RevokeFrame(String nid);   // reason + revokedAt = null
}
```

---

## `NipIdentity`

Ed25519 密钥对，以及规范化 JSON 签名/验证。

```java
public final class NipIdentity {
    public static NipIdentity generate();
    public static NipIdentity load(Path path, String passphrase) throws IOException;

    public void save(Path path, String passphrase) throws IOException;

    public String  sign(Map<String, Object> payload);      // → "ed25519:{base64}"
    public boolean verify(Map<String, Object> payload, String signature);

    public String     pubKeyString();   // "ed25519:{hex}"
    public PublicKey  pubKey();
}
```

### 规范化签名 payload

`sign` / `verify` 先将 payload 包装为 `TreeMap`（按键排序）再
用 Jackson 序列化为紧凑 UTF-8 JSON。这是与 .NET 和 Python
SDK 共用的**按键排序**规范化器；此处**不**使用 RFC 8785 JCS。

### 密钥生命周期

- `generate()` —— 通过 `KeyPairGenerator.getInstance("Ed25519")`
  生成 32 字节 Ed25519 密钥对。
- `pubKeyString()` 返回 SDK 其他位置（如
  `NdpAnnounceValidator.registerPublicKey`）使用的十六进制形式。
- 原始 `PublicKey` / `PrivateKey` 是 `java.security` 对象 ——
  如果需要非规范化的签名方案，可直接用于任何 JCA 签名器。

---

## 密钥文件格式

`save` / `load` 使用 JSON 信封，带 PBKDF2-SHA256 派生密钥和
AES-256-GCM 认证加密。

```json
{
  "version":     1,
  "salt":        "<hex 16 字节>",
  "iv":          "<hex 12 字节>",
  "ciphertext":  "<hex —— 加密的 PKCS#8 私钥>",
  "pub_key":     "<hex —— X.509 SubjectPublicKeyInfo>"
}
```

参数：

| 参数             | 值 |
|-----------------|-------|
| PBKDF2 算法       | `PBKDF2WithHmacSHA256` |
| PBKDF2 迭代次数    | 600 000 |
| 派生密钥长度       | 32 字节（256 位） |
| Salt 长度         | 16 字节（随机） |
| IV 长度           | 12 字节（随机） |
| 加密算法          | `AES/GCM/NoPadding` |
| GCM tag           | 128 位 |

私钥以 PKCS#8 形式存储；公钥以 X.509 SubjectPublicKeyInfo
形式存储 —— 两者都是 Ed25519 在 JDK 中 `Key.getEncoded()` 的输出。

---

## `NipFrameRegistrar`

向 `FrameRegistry` 注册 `IDENT`、`TRUST`、`REVOKE`：

```java
FrameRegistry r = new FrameRegistry();
NcpFrameRegistrar.register(r);
NipFrameRegistrar.register(r);
```

---

## 端到端示例

```java
import com.labacacia.nps.nip.*;
import java.nio.file.Path;

var id  = NipIdentity.generate();
var nid = "urn:nps:node:api.example.com:products";

// 保存并重新加载
id.save(Path.of("node.key"), System.getenv("KEY_PASS"));
var loaded = NipIdentity.load(Path.of("node.key"), System.getenv("KEY_PASS"));

// 签名一个 announce payload
var payload = Map.<String, Object>of(
    "action", "announce",
    "nid",    nid);
String sig  = loaded.sign(payload);
boolean ok  = loaded.verify(payload, sig);   // true
```
