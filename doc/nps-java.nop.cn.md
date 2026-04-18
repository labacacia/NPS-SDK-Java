[English Version](./nps-java.nop.md) | 中文版

# `com.labacacia.nps.nop` — 类与方法参考

> 规范：[NPS-5 NOP v0.3](https://github.com/labacacia/NPS-Release/blob/main/spec/NPS-5-NOP.md)

NOP 是编排层 —— 提交由若干委托子任务构成的 DAG、等待完成、
把结果以流式方式回送。本模块提供四个 NOP 帧（0x40–0x43）、
任务状态/退避枚举，以及阻塞式 `NopClient` + `NopTaskStatus` 辅助类。

---

## 目录

- [`TaskState`](#taskstate)
- [`BackoffStrategy`](#backoffstrategy)
- [`TaskFrame` (0x40)](#taskframe-0x40)
- [`DelegateFrame` (0x41)](#delegateframe-0x41)
- [`SyncFrame` (0x42)](#syncframe-0x42)
- [`AlignStreamFrame` (0x43)](#alignstreamframe-0x43)
- [`NopClient`](#nopclient)
- [`NopTaskStatus`](#noptaskstatus)
- [`NopFrameRegistrar`](#nopframeregistrar)

---

## `TaskState`

```java
public enum TaskState {
    PENDING       ("pending"),
    PREFLIGHT     ("preflight"),
    RUNNING       ("running"),
    WAITING_SYNC  ("waiting_sync"),
    COMPLETED     ("completed"),
    FAILED        ("failed"),
    CANCELLED     ("cancelled"),
    SKIPPED       ("skipped");

    public final String value;

    public static TaskState fromValue(String v);   // 抛出 IllegalArgumentException
    public boolean          isTerminal();          // COMPLETED | FAILED | CANCELLED
}
```

---

## `BackoffStrategy`

```java
public enum BackoffStrategy {
    FIXED("fixed"), LINEAR("linear"), EXPONENTIAL("exponential");

    public final String value;

    public static long computeDelayMs(
        BackoffStrategy strategy, long baseMs, long maxMs, int attempt);
}
```

`computeDelayMs`（attempt 从 0 开始）：

| 策略          | 公式                        |
|--------------|----------------------------|
| `FIXED`      | `baseMs`                   |
| `LINEAR`     | `baseMs * (attempt + 1)`   |
| `EXPONENTIAL`| `baseMs * 2^attempt`       |

结果截断到 `maxMs`。

---

## `TaskFrame` (0x40)

提交一个 DAG 去执行。DAG 本身是符合 NPS-5 线路形态的自由格式
`Map<String, Object>`（`{"nodes": [...], "edges": [...]}`）。

```java
public final class TaskFrame implements NpsFrame {
    public TaskFrame(
        String              taskId,
        Map<String, Object> dag,
        Integer             timeoutMs,     // nullable
        String              callbackUrl,   // nullable —— 由编排器做 SSRF 校验
        Map<String, Object> context,       // nullable —— { "session_key", "requester_nid", "trace_id" }
        String              priority,      // nullable —— "low" | "normal" | "high"
        Integer             depth          // nullable —— 委托链深度，上限 3
    );
    public TaskFrame(String taskId, Map<String, Object> dag);  // 其他字段均为 null
}
```

编排器强制执行的规范限制（NPS-5 §8.2）：单 DAG 最多 32 个节点、
委托链最多 3 层、最大 timeout 3 600 000 ms（1 小时）。

---

## `DelegateFrame` (0x41)

编排器向各 Agent 发出的单节点调用。

```java
public final class DelegateFrame implements NpsFrame {
    public DelegateFrame(
        String              taskId,
        String              subtaskId,
        String              action,
        String              agentNid,
        Map<String, Object> inputs,         // nullable
        Map<String, Object> params,         // nullable
        String              idempotencyKey  // nullable
    );
}
```

---

## `SyncFrame` (0x42)

Fan-in 屏障 —— 等待 N 个上游子任务中的 K 个完成。

```java
public final class SyncFrame implements NpsFrame {
    public SyncFrame(
        String       taskId,
        String       syncId,
        List<String> waitFor,
        int          minRequired,    // 0 = waitFor 全部（严格 fan-in）
        String       aggregate,      // "merge" | "first" | "fastest_k" | "all"
        Integer      timeoutMs       // nullable
    );
    public SyncFrame(String taskId, String syncId, List<String> waitFor);
    // → minRequired = 0, aggregate = "merge", timeoutMs = null
}
```

`minRequired` 语义：

| 值    | 含义 |
|-------|---------|
| `0`   | 等待 `waitFor` 全部（严格 fan-in）。 |
| `K`   | 一旦有 K 个上游子任务完成即继续。 |

---

## `AlignStreamFrame` (0x43)

某个委托子任务的流式进度/部分结果帧。

```java
public final class AlignStreamFrame implements NpsFrame {
    public AlignStreamFrame(
        String              streamId,
        String              taskId,
        String              subtaskId,
        int                 seq,
        boolean             isFinal,
        String              senderNid,
        Map<String, Object> data,         // nullable —— 不透明 payload
        Map<String, Object> error,        // nullable —— { "error_code", "message" }
        Integer             windowSize    // nullable
    );

    public String errorCode();       // 便捷方法 —— error == null 时返回 null
    public String errorMessage();
}
```

`AlignStreamFrame` 替代已弃用的 `AlignFrame (0x05)` —— 它携带
任务上下文（`taskId` + `subtaskId`），并绑定到特定 `senderNid`。

---

## `NopClient`

面向 NOP 编排器的阻塞式 HTTP 客户端。所有调用抛出
`IOException` 和 `InterruptedException`。

```java
public final class NopClient {
    public NopClient(String baseUrl);
    public NopClient(String baseUrl, EncodingTier tier,
                     FrameRegistry registry, HttpClient httpClient);

    public String        submit(TaskFrame frame)
                             throws IOException, InterruptedException;   // → taskId

    public NopTaskStatus getStatus(String taskId)
                             throws IOException, InterruptedException;

    public void          cancel(String taskId)
                             throws IOException, InterruptedException;

    public NopTaskStatus wait(String taskId)
                             throws IOException, InterruptedException;   // 默认 1 s 轮询 / 30 s 超时

    public NopTaskStatus wait(String taskId, long pollIntervalMs, long timeoutMs)
                             throws IOException, InterruptedException;
}
```

### HTTP 路由

| 方法         | 路径                         | 请求                | 响应 |
|-------------|------------------------------|--------------------|----------|
| `submit`    | `POST /task`                 | 编码后的 `TaskFrame`| JSON `{ "task_id": … }` |
| `getStatus` | `GET  /task/{taskId}`        | —                  | JSON 状态 dict |
| `cancel`    | `POST /task/{taskId}/cancel` | 空                  | — |
| `wait`      | 轮询 `getStatus` 直到终态或超时；超时抛 `RuntimeException` |

`submit` 请求 Content-Type 为 `application/x-nps-frame`，
`cancel` 为 `application/json`。响应始终是 JSON。

---

## `NopTaskStatus`

基于编排器 JSON 响应的轻量视图。

```java
public final class NopTaskStatus {
    public NopTaskStatus(Map<String, Object> raw);

    public String              taskId();
    public TaskState           state();
    public boolean             isTerminal();
    public Object              aggregatedResult();
    public String              errorCode();      // nullable
    public String              errorMessage();   // nullable
    public Map<String, Object> nodeResults();    // 缺失时返回空 map
    public Map<String, Object> raw();            // 原始 payload
}
```

`raw()` 提供完整 payload，当你需要访问不在 `NopTaskStatus`
上一等暴露的编排器专属字段时使用。

---

## `NopFrameRegistrar`

向 `FrameRegistry` 注册 `TASK`、`DELEGATE`、`SYNC`、`ALIGN_STREAM`。

---

## 端到端示例

```java
import com.labacacia.nps.nop.*;

Map<String, Object> dag = Map.of(
    "nodes", List.of(
        Map.of("id", "fetch",    "action", "fetch",
               "agent", "urn:nps:node:ingest.example.com:http"),
        Map.of("id", "classify", "action", "classify",
               "agent", "urn:nps:node:ml.example.com:classifier",
               "input_from", List.of("fetch"),
               "retry_policy", Map.of(
                   "max_retries",   3,
                   "backoff",       BackoffStrategy.EXPONENTIAL.value,
                   "base_delay_ms", 500))),
    "edges", List.of(
        Map.of("from", "fetch", "to", "classify")));

var nop    = new NopClient("http://orchestrator.example.com:17433");
var taskId = nop.submit(new TaskFrame("job-42", dag, 60_000, null, null, null, null));
var status = nop.wait(taskId, 500, 60_000);

System.out.println(status.state() + " → " + status.aggregatedResult());
```
