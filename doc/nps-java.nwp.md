# `com.labacacia.nps.nwp` — Class and Method Reference

> Spec: [NPS-2 NWP v0.4](https://github.com/labacacia/NPS-Release/blob/main/spec/NPS-2-NWP.md)

NWP is the agent-facing HTTP layer. This module provides `QueryFrame` +
`ActionFrame` + a blocking `NwpClient` built on `java.net.http.HttpClient`.

---

## Table of contents

- [`QueryFrame` (0x10)](#queryframe-0x10)
- [`ActionFrame` (0x11)](#actionframe-0x11)
- [`AsyncActionResponse`](#asyncactionresponse)
- [`NwpClient`](#nwpclient)
- [`NwpFrameRegistrar`](#nwpframeregistrar)

---

## `QueryFrame` (0x10)

Paginated / vector query against a Memory Node.

```java
public final class QueryFrame implements NpsFrame {
    public QueryFrame(
        String                    anchorRef,    // nullable — sha256 anchor id
        Map<String, Object>       filter,       // nullable — NPS-2 §4 filter DSL
        Integer                   limit,        // nullable
        Integer                   offset,       // nullable
        List<Map<String, Object>> orderBy,      // nullable — [{"field":"id", "dir":"asc"}, …]
        List<String>              fields,       // nullable — projection
        Map<String, Object>       vectorSearch, // nullable — {"field", "vector", "k", "metric"}
        Integer                   depth         // nullable — X-NWP-Depth, max 5
    );
    public QueryFrame();   // all-null
}
```

- `preferredTier()` → `MSGPACK`.
- When `vectorSearch` is populated, `limit` is interpreted as top-K.
- `depth` is clamped to 5 by the server (spec §6.3).

---

## `ActionFrame` (0x11)

Invoke an action (capability) on a node.

```java
public final class ActionFrame implements NpsFrame {
    public ActionFrame(
        String              actionId,
        Map<String, Object> params,         // nullable
        Boolean             async_,         // nullable — default false on wire
        String              idempotencyKey, // nullable — 128-bit UUID recommended
        Integer             timeoutMs       // nullable
    );
    public ActionFrame(String actionId);    // all other fields null
}
```

The field is named `async_` to avoid clashing with Java's `async` keyword
candidate in future versions; it serialises to `"async"` on the wire.

---

## `AsyncActionResponse`

Returned from `NwpClient.invoke` when the frame has `async_ == true`.

```java
public record AsyncActionResponse(String taskId, String status, String pollUrl) {
    public static AsyncActionResponse fromDict(Map<String, Object> d);
}
```

`status` is one of the NPS async states (`"pending"`, `"running"`, …).
Poll `pollUrl` or use `NopClient.wait` to reach a terminal state.

---

## `NwpClient`

Blocking HTTP client. All methods throw `IOException` and
`InterruptedException`.

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

### Constructor

- `baseUrl` — trailing `/` is stripped; all requests POST to
  `{baseUrl}/{route}`.
- `tier` — default `EncodingTier.MSGPACK`; pass `null` to accept the
  default.
- `registry` — defaults to NCP + NWP frames registered via
  `NcpFrameRegistrar` and `NwpFrameRegistrar`. Pass `null` for the
  default.
- `httpClient` — an injected `HttpClient`; defaults to
  `HttpClient.newHttpClient()`.

### HTTP routes

| Method       | Path      | Request body                 | Response body |
|--------------|-----------|------------------------------|---------------|
| `sendAnchor` | `/anchor` | Encoded `AnchorFrame`        | — (2xx only)  |
| `query`      | `/query`  | Encoded `QueryFrame`         | Encoded `CapsFrame` |
| `stream`     | `/stream` | Encoded `QueryFrame`         | Concatenated `StreamFrame`s |
| `invoke`     | `/invoke` | Encoded `ActionFrame`        | Encoded frame *or* JSON (async) |

Content-Type: `application/x-nps-frame` on requests and NPS-encoded
responses.

### `stream` behaviour

`stream` returns a buffered `List<StreamFrame>`: the response body is
read fully, then parsed into frames by peeking each header and decoding
until `isLast() == true`. For true chunked consumption, build your own
response handler on the injected `HttpClient`.

### `invoke` return value

- `async_ == true` on the request → response is parsed as JSON and
  returned as `AsyncActionResponse`.
- Response Content-Type is `application/x-nps-frame` → returned as
  `NpsFrame` (usually `CapsFrame` or `ErrorFrame`).
- Otherwise → returned as `Map<String, Object>` (decoded JSON).

Type-check with `instanceof` before casting.

### Error handling

Non-2xx HTTP responses throw `RuntimeException("NWP /{path} failed: HTTP {status}")`.
Connection / TLS failures bubble up as `IOException`.

---

## `NwpFrameRegistrar`

Registers `QUERY` and `ACTION` against a `FrameRegistry`:

```java
FrameRegistry r = new FrameRegistry();
NcpFrameRegistrar.register(r);
NwpFrameRegistrar.register(r);
```

---

## End-to-end example

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
    // typed frame (likely CapsFrame)
} else if (result instanceof AsyncActionResponse async) {
    // follow-up via NopClient.wait(async.taskId(), …)
}
```
