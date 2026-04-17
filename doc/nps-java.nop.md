# `com.labacacia.nps.nop` — Class and Method Reference

> Spec: [NPS-5 NOP v0.3](https://github.com/labacacia/NPS-Release/blob/main/spec/NPS-5-NOP.md)

NOP is the orchestration layer — submit a DAG of delegated subtasks,
wait for completion, stream results back. This module ships the four
NOP frames (0x40–0x43), the task state / backoff enums, and the blocking
`NopClient` + `NopTaskStatus` helpers.

---

## Table of contents

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

    public static TaskState fromValue(String v);   // throws IllegalArgumentException
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

`computeDelayMs` (0-indexed attempt):

| Strategy     | Formula                    |
|--------------|----------------------------|
| `FIXED`      | `baseMs`                   |
| `LINEAR`     | `baseMs * (attempt + 1)`   |
| `EXPONENTIAL`| `baseMs * 2^attempt`       |

Result is clamped at `maxMs`.

---

## `TaskFrame` (0x40)

Submit a DAG for execution. The DAG itself is a free-form
`Map<String, Object>` matching the NPS-5 wire shape
(`{"nodes": [...], "edges": [...]}`).

```java
public final class TaskFrame implements NpsFrame {
    public TaskFrame(
        String              taskId,
        Map<String, Object> dag,
        Integer             timeoutMs,     // nullable
        String              callbackUrl,   // nullable — SSRF-validated by orchestrator
        Map<String, Object> context,       // nullable — { "session_key", "requester_nid", "trace_id" }
        String              priority,      // nullable — "low" | "normal" | "high"
        Integer             depth          // nullable — delegate chain depth, max 3
    );
    public TaskFrame(String taskId, Map<String, Object> dag);  // all others null
}
```

Spec limits enforced by the orchestrator (NPS-5 §8.2): max 32 nodes per
DAG, max 3 levels of delegate chain, max timeout 3 600 000 ms (1 h).

---

## `DelegateFrame` (0x41)

Per-node invocation emitted by the orchestrator to each agent.

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

Fan-in barrier — waits for K-of-N upstream subtasks.

```java
public final class SyncFrame implements NpsFrame {
    public SyncFrame(
        String       taskId,
        String       syncId,
        List<String> waitFor,
        int          minRequired,    // 0 = all of waitFor (strict fan-in)
        String       aggregate,      // "merge" | "first" | "fastest_k" | "all"
        Integer      timeoutMs       // nullable
    );
    public SyncFrame(String taskId, String syncId, List<String> waitFor);
    // → minRequired = 0, aggregate = "merge", timeoutMs = null
}
```

`minRequired` semantics:

| Value | Meaning |
|-------|---------|
| `0`   | Wait for all of `waitFor` (strict fan-in). |
| `K`   | Proceed as soon as K upstream subtasks have completed. |

---

## `AlignStreamFrame` (0x43)

Streaming progress / partial result frame for a delegated subtask.

```java
public final class AlignStreamFrame implements NpsFrame {
    public AlignStreamFrame(
        String              streamId,
        String              taskId,
        String              subtaskId,
        int                 seq,
        boolean             isFinal,
        String              senderNid,
        Map<String, Object> data,         // nullable — opaque payload
        Map<String, Object> error,        // nullable — { "error_code", "message" }
        Integer             windowSize    // nullable
    );

    public String errorCode();       // convenience — null when error == null
    public String errorMessage();
}
```

`AlignStreamFrame` replaces the deprecated `AlignFrame (0x05)` — it
carries task context (`taskId` + `subtaskId`) and is bound to a specific
`senderNid`.

---

## `NopClient`

Blocking HTTP client for an NOP orchestrator. All calls throw
`IOException` and `InterruptedException`.

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
                             throws IOException, InterruptedException;   // default 1 s poll / 30 s timeout

    public NopTaskStatus wait(String taskId, long pollIntervalMs, long timeoutMs)
                             throws IOException, InterruptedException;
}
```

### HTTP routes

| Method      | Path                         | Request            | Response |
|-------------|------------------------------|--------------------|----------|
| `submit`    | `POST /task`                 | encoded `TaskFrame`| JSON `{ "task_id": … }` |
| `getStatus` | `GET  /task/{taskId}`        | —                  | JSON status dict |
| `cancel`    | `POST /task/{taskId}/cancel` | empty              | — |
| `wait`      | polls `getStatus` until terminal or deadline; throws `RuntimeException` on timeout |

Request Content-Type is `application/x-nps-frame` for `submit`,
`application/json` for `cancel`. Responses are always JSON.

---

## `NopTaskStatus`

Thin view over the orchestrator's JSON response.

```java
public final class NopTaskStatus {
    public NopTaskStatus(Map<String, Object> raw);

    public String              taskId();
    public TaskState           state();
    public boolean             isTerminal();
    public Object              aggregatedResult();
    public String              errorCode();      // nullable
    public String              errorMessage();   // nullable
    public Map<String, Object> nodeResults();    // empty map when absent
    public Map<String, Object> raw();            // untouched payload
}
```

`raw()` gives you the full payload if you need orchestrator-specific
fields that aren't first-class on `NopTaskStatus`.

---

## `NopFrameRegistrar`

Registers `TASK`, `DELEGATE`, `SYNC`, `ALIGN_STREAM` against a
`FrameRegistry`.

---

## End-to-end example

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
