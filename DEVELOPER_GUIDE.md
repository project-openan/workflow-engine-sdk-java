# Developer Guide: Integrating a2at-engine-java SDK

## 1. Installation

Add to your `pom.xml`:

```xml
<dependencies>
    <dependency>
        <groupId>com.openan.a2at</groupId>
        <artifactId>a2at-engine-java</artifactId>
        <version>0.3.0</version>
    </dependency>
    <!-- A2A protocol SDK -->
    <dependency>
        <groupId>org.a2aproject.sdk</groupId>
        <artifactId>a2a-java-sdk-client</artifactId>
        <version>1.0.0.Beta1</version>
    </dependency>
    <dependency>
        <groupId>org.a2aproject.sdk</groupId>
        <artifactId>a2a-java-sdk-client-transport-rest</artifactId>
        <version>1.0.0.Beta1</version>
    </dependency>
    <!-- A2A-T extension SDK -->
    <dependency>
        <groupId>net.openan.a2a-t.sdk</groupId>
        <artifactId>a2a-t-client</artifactId>
        <version>1.0.0</version>
    </dependency>
</dependencies>
```

## 2. Core Concepts

| Layer | Entry Point | What It Handles | What You Provide |
|-------|------------|-----------------|-----------------|
| 2 (high) | `ExecutePsop.execute()` | Event collection, lifecycle, on_finish | ControlPoint + AgentCards + config |
| 1 (mid) | `WorkflowExecutor` | DAG traversal, context, ControlPoint dispatch | ControlPoint + EngineClient + Workflow |
| 0 (low) | `WorkflowEngineClient` | A2A send, response extraction | AgentCards + a2aClientRuntime |

## 3. Implement ControlPoint

Only two methods are required:

```java
public class MyControlPoint implements ControlPoint {
    @Override
    public CompletableFuture<TaskResponse> onTask(
            TaskRequest request, WorkflowEngineClient client) {
        // request.getMessage() = full assembled message (context + task + lang)
        return client.sendMessage(request.getAgentName(), request.getMessage())
                .thenApply(result -> TaskResponse.builder()
                        .success(true)
                        .output(result.getText())
                        .build());
    }

    @Override
    public CompletableFuture<RouteDecision> onRoute(
            String stepName, Map<String, Object> results,
            List<JumpCondition> conditions) {
        // Use your own LLM or business logic to pick a branch
        // conditions.get(i).getStep() = target step name
        // conditions.get(i).getCondition() = condition description
        return CompletableFuture.completedFuture(
                RouteDecision.builder()
                        .nextStep(conditions.get(0).getStep())
                        .reason("picked first")
                        .build());
    }
}
```

`onAuthorization` and `onNotification` have default implementations (approve / no-op).

## 4. Execute (Layer 2: ExecutePsop)

`ExecutePsop.execute(...)` has a full 14-argument overload that takes all
SSL, auth, and A2A-T configuration, plus a 10-argument "legacy" overload
(no `a2atEnvPath` / `credentialsConfigPath` / `sslVerify` / `caCertsPath`).
Prefer the full overload when you need Task-T prompt generation, agent
auth, or TLS control; the legacy overload hard-codes `sslVerify=false` and
passes `null` for auth/A2A-T config.

```java
// Get AgentCards
RegistryClient registry = new RegistryClient("https://127.0.0.1:5000", false); // (url, sslVerify)
List<Map<String, Object>> agentCards = registry.fetchAgentCards();

// Load workflow
Workflow workflow = LoadPsop.load("https://127.0.0.1:5001", "psop-id", "token", false);

// Create A2A client runtime (from a2a-java-sdk). May be null to fall back
// to raw HTTP JSON-RPC (no streaming, no typed AgentCard).
Object a2aRuntime = ...; // your A2AJavaClientRuntime instance

// Execute with event collection + persistence hook
CompletableFuture<ExecutionResult> future = ExecutePsop.execute(
    workflow,
    agentCards,
    new MyControlPoint(),
    null,                    // engineClient (null = auto-create)
    "Diagnose SPN fault",    // runtimeIntent
    "zh",                    // lang
    ".env",                  // a2atEnvPath (A2A-T SDK env, for Task-T prompts)
    "etc/conf/agent_credentials.json", // credentialsConfigPath
    false,                  // sslVerify (self-signed cert in dev)
    null,                   // caCertsPath (custom CA trust store, null = default)
    a2aRuntime,              // A2A client runtime
    new EventCallback() {    // event callback
        @Override
        public void onEvent(String type, Map<String, Object> data) {
            System.out.println("[" + type + "] " + data);
        }
    },
    (result, events) -> {    // on_finish (async): BiFunction returning CompletableFuture<Void>
        return saveToDatabase(result) // an async persist op, completed when done
            .thenRun(() -> {});
    },
    null                     // on_event transformer (null = pass through)
);

ExecutionResult result = future.join();
```

If you only need the legacy (synchronous `on_finish`) overload, the
`on_finish` arg is a `BiConsumer` (returns `void`) and `sslVerify` /
`a2atEnvPath` / `credentialsConfigPath` are omitted -- but then Task-T
prompt generation, agent auth, and TLS verification cannot be configured:

```java
// Legacy 10-arg overload: sslVerify=false, no auth, no A2A-T env
ExecutePsop.execute(
    workflow, agentCards, new MyControlPoint(), null,
    "Diagnose SPN fault", "zh",
    a2aRuntime,                       // A2A client runtime
    new EventCallback(),
    (result, events) -> persist(result), // BiConsumer (sync)
    null                              // on_event transformer
).join();
```

> **`on_event` transformer:** a `Function<Map<String,Object>, Object>`
> returning the event unchanged, `null` (skip this event), or a `List` of
> events (inject multiple). Semantics match the Python `on_event`.

## 5. Event Types

Events come from three layers: the runner (lifecycle bracket), the
executor (step/task/routing), and the engine client + extension handlers
(agent traffic, negotiation, authorization, notification).

| Event | Layer | When | Key Data |
|-------|-------|------|----------|
| `start` | runner | Workflow begins | `workflow`, `steps` |
| `step_start` | executor | Step begins | `step` |
| `task_request` | executor | A subtask is dispatched to `onTask` | `step`, `agent`, `task` |
| `task_response` | executor | `onTask` returned a `TaskResponse` | `step`, `agent`, `task`, `output` |
| `task_status_changed` | executor | Task status updated | `step`, `subtask_index`, `agent`, `status` |
| `route_decision` | executor | Branch chosen | `step`, `next`, `reason` |
| `step_complete` | executor | Step finished | `step`, `results` |
| `agent_request` | engine client | Message sent to agent | `agent`, `request`, `metadata` |
| `agent_response` | engine client | Response from agent | `agent`, `response` |
| `negotiation_request` | engine client | Agent needs clarification | `agent`, `round`, `concern` |
| `negotiation_resolved` | engine client | Clarification provided | `agent`, `round`, `clarification` |
| `negotiation_failed` | engine client | Negotiation failed | `agent`, `round`, `reason` |
| `authorization_request` | extension | Agent requests authorization | `agent`, `auth_request` |
| `authorization_resolved` | extension | Authorization decision | `agent`, `decision` |
| `notification` | extension | Agent pushes notification | `agent`, `notification` |
| `workflow_complete` | executor | Executor finished traversal (precedes `complete`/`error`) | (empty) |
| `complete` | runner | Workflow succeeded | `history`, `step_outputs` |
| `error` | runner or executor | Workflow failed | runner: `error`, `history`, `step_outputs`; executor: `step`, `results` |
| `close` | runner | Cleanup done | (empty) |

Compare with `EventType.STEP_START`, `EventType.TASK_REQUEST`,
`EventType.NEGOTIATION_RESOLVED`, etc. `EventType` defines a constant for
every event above.

> **`workflow_complete` vs `complete`, and duplicate `error`:** the
> executor emits `workflow_complete` as soon as DAG traversal ends, then
> the runner emits `complete` (success) or `error` (failure) with the
> final `ExecutionResult`. On a step failure the executor emits an
> `error` carrying `step`/`results`, and the runner later emits a second
> `error` carrying `history`/`step_outputs` -- check `data` keys to
> tell them apart.

## 6. Mid-Level (Layer 1: WorkflowExecutor)

```java
try (var client = new DefaultWorkflowEngineClient(agentCards, a2aRuntime,
        WorkflowEngineClientConfig.builder()
            .sslVerify(false)
            .credentialsConfigPath("etc/conf/agent_credentials.json")
            .a2atEnvPath(".env")
            .build())) {
    WorkflowExecutor executor = new WorkflowExecutor(
        workflow,
        new MyControlPoint(),
        client,
        new EventCallback(),
        "Diagnose fault",
        "zh"
    );
    ExecutionResult result = executor.run().join();
}
```

### 6.1 Manual Negotiation

```java
result = client.sendMessageWithNegotiation(
    "SPN Domain Agent", "Diagnose the fault", null, 3,
    (agentName, negotiationText, receiveResult) ->
        myLlm.generate("Agent " + agentName + " needs: " + negotiationText)
);
```

`NegotiationResolver` returns `CompletableFuture<String>`; return an empty
/ null string to fail the round (a `negotiation_failed` event is emitted
and the loop retries up to `maxRounds`).

### 6.2 Workflow Model Fields

| Field | Where | Meaning |
|-------|-------|---------|
| `steps[].stepType` | `WorkflowStep` | `AllSuccess` (default): every subtask must succeed; `AnySuccess`: the step succeeds as soon as one subtask succeeds (the rest are cancelled). |
| `steps[].subtasks[]` | `Task` | Each has `agent`, `skill`, `description`. One `onTask` call is made per subtask. |
| `steps[].next[]` | `List<JumpCondition>` | Branch targets. `JumpCondition.step` = next step name; `JumpCondition.condition` = rule text passed to `onRoute`. |
| `steps[].layer` | `WorkflowStep` | `layer == 0` starts the DAG (context = runtime intent only). Higher layers get upstream step results. |
| `steps[].contextFrom` | `WorkflowStep` | Optional step names whose outputs fold into context. `"*"` = all ancestors. When null, direct predecessors are used. |

`onRoute` receives `conditions` (the `next` list); `nextStep` must match
one of those `JumpCondition.step` values.

### 6.3 AgentCard Type Differences

The Java SDK treats AgentCards as `Map<String, Object>` throughout (no
protobuf). `RegistryClient.fetchAgentCards()` returns
`List<Map<String, Object>>` (already security-normalized). `load_psop`
returns a typed `Workflow`. If you build cards yourself, plain maps are
fine -- `AgentCardNormalizer.normalize(map)` converts OpenAPI-style
security schemes to the internal format.

## 7. Agent Authentication

When AgentCards declare `securitySchemes`, `DefaultWorkflowEngineClient`
logs in via `AgentCredentialService`, caches the token for `token_ttl`
seconds, and attaches the auth header to outbound requests. Configure it
through `WorkflowEngineClientConfig` (the same builder used for SSL and
A2A-T), not through `a2aClientRuntime` -- the runtime only carries A2A
transport; agent auth is owned by the engine client.

```json
{
  "SPN Domain Agent": {
    "bearerAuth": {
      "login_url": "https://127.0.0.1:8080/auth/login",
      "method": "POST",
      "request_fields": { "username": "...", "password": "..." },
      "token_field": "access_token",
      "token_ttl": 3600
    }
  }
}
```

Pass the file path via the config builder (the full `ExecutePsop.execute`
overload forwards `credentialsConfigPath` to the auto-created client):

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
    .sslVerify(false)                               // self-signed cert in dev
    .caCertsPath(null)                              // custom CA trust store
    .credentialsConfigPath("etc/conf/agent_credentials.json")
    .a2atEnvPath(".env")                            // A2A-T SDK env (Task-T)
    .build();

// Option A: let ExecutePsop auto-create the client (full 14-arg overload)
ExecutePsop.execute(workflow, cards, cp, null, intent, "zh",
    ".env", "etc/conf/agent_credentials.json", false, null,
    a2aRuntime, cb, onFinish, null);

// Option B: create the client yourself and pass it as engineClient
try (var client = new DefaultWorkflowEngineClient(cards, a2aRuntime, config)) {
    ExecutePsop.execute(workflow, cards, cp, client, intent, "zh",
        null, null, false, null, a2aRuntime, cb, onFinish, null);
}
```

### 7.1 Credential File Fields

| Field | Required | Default | Description |
|-------|----------|---------|-------------|
| `login_url` | Yes | - | URL to obtain the access token |
| `method` | No | `POST` | HTTP method |
| `content_type` | No | `application/json` | `application/json` or `application/x-www-form-urlencoded` |
| `request_fields` | No | - | Dict of body fields (overrides username/password) |
| `username` / `password` | No | - | Used when `request_fields` is absent |
| `username_field` / `password_field` | No | `username` / `password` | Body field names |
| `token_field` | No | `accessSession` | Dot-separated path to extract the token (e.g. `data.access_token`) |
| `token_ttl` | No | `3600` | Token cache TTL in seconds |
| `auth_header` | No | `Authorization` | Custom header name for the token |
| `auth_header_prefix` | No | (empty) | Prefix before the token (e.g. `Bearer `) |
| `accept_header` | No | - | Custom Accept header value |

- Agent name must match `AgentCard.name`; scheme name must match a key in
  `AgentCard.securitySchemes`; agents without `securitySchemes` need no entry.
- See `examples/agent_credentials.example.json` in the Python SDK for a
  complete example (the credential file format is identical across SDKs).

## 7b. SSL / TLS

Outbound TLS is controlled by `WorkflowEngineClientConfig.sslVerify` /
`caCertsPath`, and by the `sslVerify` arguments on `RegistryClient` and
`LoadPsop.load`. Set `sslVerify=false` only for dev with self-signed certs;
in production keep `true` and point `caCertsPath` at your CA trust store.

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
    .sslVerify(true)
    .caCertsPath("/etc/ssl/certs/ca-bundle.crt")
    .build();
```

`LoadPsop.load(baseUrl, psopId, accessToken, sslVerify)` and
`new RegistryClient(url, sslVerify)` also accept the TLS flag for their
own HTTP calls.

## 7c. A2A-T Environment (`.env`)

The `a2atEnvPath` config points to an `.env` file for the A2A-T SDK, used
by the Task-T handler to generate structured task prompts:

```ini
A2AT_LLM_PROVIDER=openai          # or "deepseek" (OpenAI-compatible)
A2AT_LLM_MODEL=deepseek-chat
A2AT_LLM_API_KEY=sk-...
A2AT_LLM_BASE_URL=https://api.deepseek.com
A2AT_LANGUAGE=zh-CN
```

When `a2atEnvPath` is null, Task-T prompt generation is skipped (the rest
of the SDK still works).

## 8. Integration Patterns

### SSE Server (Spring WebFlux)

```java
@GetMapping("/execute/{psopId}")
public Flux<String> execute(@PathVariable String psopId) {
    Workflow workflow = LoadPsop.load(baseUrl, psopId, token, false);
    List<Map<String, Object>> cards = registry.fetchAgentCards();

    return Flux.create(sink -> {
        ExecutePsop.execute(
            workflow, cards, new MyControlPoint(), null,
            intent, "zh",
            ".env", "etc/conf/agent_credentials.json", false, null, // A2A-T, auth, ssl
            a2aRuntime,
            new EventCallback() {
                @Override
                public void onEvent(String type, Map<String, Object> data) {
                    sink.next("data: " + toJson(type, data) + "\n\n");
                }
            },
            (result, events) -> persist(result),
            null
        ).thenAccept(r -> sink.complete());
    });
}
```

### Host Agent (Distributed Execution)

The host agent implements `ControlPoint` to decide when/how to send tasks
and which routes to take. The SDK handles all A2A communication.

### Cancellation

Unlike the Python SDK (which cancels the workflow when the async iterator
is closed via `GeneratorExit`), the Java SDK returns a
`CompletableFuture<ExecutionResult>` from `ExecutePsop.execute`. You can
`cancel(true)` it, but the internal executor does not actively interrupt a
running A2A call -- cancellation is best-effort and the future completes
with `CancellationException`. For SSE, drop the subscriber / close the
`Flux` and let `thenAccept` no-op. There is no `on_finish` guarantee on
cancellation (unlike Python), so persist critical state in `onEvent` if
you need it to survive a dropped stream.

## 9. Checklist

1. Add Maven dependencies
2. Implement `ControlPoint` (on_task + on_route)
3. Get AgentCards (from registry or custom source)
4. Load Workflow (via `LoadPsop` or build your own)
5. Create A2A client runtime (from a2a-java-sdk)
6. Call `ExecutePsop.execute()`
7. Handle events + on_finish persistence
