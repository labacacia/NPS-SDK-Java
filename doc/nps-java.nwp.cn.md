[English Version](./nps-java.nwp.md) | 中文版

# `com.labacacia.nps.nwp` — 类与方法参考

> 规范：[NPS-2 NWP v0.4](https://github.com/labacacia/NPS-Release/blob/main/spec/NPS-2-NWP.md)

NWP 是面向 Agent 的 HTTP 层。本模块提供 `QueryFrame` +
`ActionFrame` + 基于 `java.net.http.HttpClient` 的阻塞式 `NwpClient`。

---

## 目录

- [`QueryFrame` (0x10)](#queryframe-0x10)
- [`ActionFrame` (0x11)](#actionframe-0x11)
- [`AsyncActionResponse`](#asyncactionresponse)
- [`NwpClient`](#nwpclient)
- [`NwpFrameRegistrar`](#nwpframeregistrar)

---

## `QueryFrame` (0x10)

针对 Memory Node 的分页/向量查询。

```java
public final class QueryFrame implements NpsFrame {
    public QueryFrame(
        String                    anchorRef,    // nullable —— sha256 锚点 id
        Map<String, Object>       filter,       // nullable —— NPS-2 §4 filter DSL
        Integer                   limit,        // nullable
        Integer                   offset,       // nullable
        List<Map<String, Object>> orderBy,      // nullable —— [{"field":"id", "dir":"asc"}, …]
        List<String>              fields,       // nullable —— 投影
        Map<String, Object>       vectorSearch, // nullable —— {"field", "vector", "k", "metric"}
        Integer                   depth         // nullable —— X-NWP-Depth，上限 5
    );
    public QueryFrame();   // 全部为 null
}
```

- `preferredTier()` → `MSGPACK`。
- 当 `vectorSearch` 非空时，`limit` 解释为 top-K。
- 服务端将 `depth` 截断为 5（规范 §6.3）。

---

## `ActionFrame` (0x11)

在节点上调用某个能力（action）。

```java
public final class ActionFrame implements NpsFrame {
    public ActionFrame(
        String              actionId,
        Map<String, Object> params,         // nullable
        Boolean             async_,         // nullable —— 线路默认 false
        String              idempotencyKey, // nullable —— 推荐 128 位 UUID
        Integer             timeoutMs       // nullable
    );
    public ActionFrame(String actionId);    // 其他字段均为 null
}
```

字段命名为 `async_` 以避免与 Java 未来版本的 `async` 候选
关键字冲突；在线路上序列化为 `"async"`。

---

## `AsyncActionResponse`

当帧 `async_ == true` 时由 `NwpClient.invoke` 返回。

```java
public record AsyncActionResponse(String taskId, String status, String pollUrl) {
    public static AsyncActionResponse fromDict(Map<String, Object> d);
}
```

`status` 为 NPS 异步状态之一（`"pending"`、`"running"` 等）。
通过轮询 `pollUrl` 或使用 `NopClient.wait` 等待终态。

---

## `NwpClient`

阻塞式 HTTP 客户端。所有方法抛出 `IOException` 和
`InterruptedException`。

```java
public final class NwpClient {
    public NwpClient(String baseUrl);
    public NwpClient(String baseUrl, EncodingTier tier,
                     FrameRegistry registry, HttpClient httpClient);

    public void             sendAnchor(AnchorFrame frame)
                                throws IOException, InterruptedException;

    public CapsFrame        query(QueryFrame frame)
                                throws IOException, InterruptedException;

    public List<StreamFrame> stream(QueryFrame frame)
                                throws IOException, InterruptedException;

    public Object           invoke(ActionFrame frame)
                                throws IOException, InterruptedException;
}
```

### 构造函数

- `baseUrl` —— 去掉末尾 `/`；所有请求 POST 到
  `{baseUrl}/{route}`。
- `tier` —— 默认 `EncodingTier.MSGPACK`；传 `null` 使用默认值。
- `registry` —— 默认为通过 `NcpFrameRegistrar` 与
  `NwpFrameRegistrar` 注册的 NCP + NWP 帧。传 `null` 使用默认值。
- `httpClient` —— 注入的 `HttpClient`；默认为
  `HttpClient.newHttpClient()`。

### HTTP 路由

| 方法         | 路径      | 请求体                        | 响应体 |
|--------------|-----------|------------------------------|---------------|
| `sendAnchor` | `/anchor` | 编码后的 `AnchorFrame`        | —（仅 2xx）  |
| `query`      | `/query`  | 编码后的 `QueryFrame`         | 编码后的 `CapsFrame` |
| `stream`     | `/stream` | 编码后的 `QueryFrame`         | 拼接的 `StreamFrame` 序列 |
| `invoke`     | `/invoke` | 编码后的 `ActionFrame`        | 编码后的帧*或* JSON（异步）|

请求和 NPS 编码响应的 Content-Type：`application/x-nps-frame`。

### `stream` 行为

`stream` 返回一个缓冲的 `List<StreamFrame>`：响应体被完整读取
后，通过逐帧查看帧头、解码直到 `isLast() == true` 来解析。
若需真正的分块消费，请在注入的 `HttpClient` 上构建自己的
响应处理器。

### `invoke` 返回值

- 请求 `async_ == true` → 响应以 JSON 解析并返回
  `AsyncActionResponse`。
- 响应 Content-Type 为 `application/x-nps-frame` → 返回
  `NpsFrame`（通常是 `CapsFrame` 或 `ErrorFrame`）。
- 其他情况 → 返回 `Map<String, Object>`（解码后的 JSON）。

强制转换前请用 `instanceof` 进行类型检查。

### 错误处理

非 2xx HTTP 响应抛出 `RuntimeException("NWP /{path} failed: HTTP {status}")`。
连接/TLS 失败以 `IOException` 向上抛出。

---

## `NwpFrameRegistrar`

向 `FrameRegistry` 注册 `QUERY` 和 `ACTION`：

```java
FrameRegistry r = new FrameRegistry();
NcpFrameRegistrar.register(r);
NwpFrameRegistrar.register(r);
```

---

## 端到端示例

```java
import com.labacacia.nps.nwp.*;
import com.labacacia.nps.ncp.CapsFrame;

var client = new NwpClient("http://node.example.com:17433");

CapsFrame caps = client.query(new QueryFrame(
    "sha256:<anchor-id>",
    Map.of("active", true),
    50, 0, null, null, null, null));
System.out.println(caps.count() + " rows");

Object result = client.invoke(new ActionFrame(
    "summarise", Map.of("maxTokens", 500), false, null, 30_000));

if (result instanceof NpsFrame f) {
    // 类型化的帧（通常是 CapsFrame）
} else if (result instanceof AsyncActionResponse async) {
    // 通过 NopClient.wait(async.taskId(), …) 跟进
}
```
