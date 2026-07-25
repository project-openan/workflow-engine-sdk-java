# Developer Guide

This guide is for contributors and advanced users who want to understand the
internal architecture, extend the SDK, or contribute patches.

## 1. Installation

Add to your `pom.xml`:

```xml
<dependencies>
    <dependency>
        <groupId>com.openan.a2at</groupId>
        <artifactId>a2at-engine</artifactId>
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
| 2 (high) | `ExecutePsop.builder()` | Event collection, lifecycle, onFinish | ControlPoint + AgentCards + config |
| 1 (mid) | `WorkflowExecutor` | DAG traversal, context, ControlPoint dispatch | ControlPoint + EngineClient + Workflow |
| 0 (low) | `WorkflowEngineClient` | A2A send, response extraction | AgentCards + A2AJavaClientRuntime |

## 3. Implement ControlPoint

Only two methods are required:

```java
public class MyControlPoint implements ControlPoint {
    @Override
    public CompletableFuture<TaskResponse> onTask(
            TaskRequest request, WorkflowEngineClient client) {
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
        return CompletableFuture.completedFuture(
                RouteDecision.builder()
                        .nextStep(conditions.get(0).getStep())
                        .reason("picked first")
                        .build());
    }
}
```

`onAuthorization` and `onNotification` have default implementations.
`onNegotiation` has a default that returns a generic clarification.

## 4. Execute via Builder (recommended)

```java
ExecutionResult result = ExecutePsop.builder()
    .psop(workflow)
    .agentCards(agentCards)
    .controlPoint(new MyControlPoint())
    .runtimeIntent("Diagnose SPN fault")
    .lang("zh")
    .sslVerify(false)
    .a2atEnvPath(".env")
    .credentialsConfigPath("agent_credentials.json")
    .eventCallback(new EventCallback())
    .onFinish((r, e) -> { persist(r); return CompletableFuture.completedFuture(null); })
    .execute()
    .join();
```

Required: `psop`, `controlPoint`. All others have sensible defaults.
`onFinish` accepts both the async `BiFunction<..., CompletableFuture<Void>>`
and a sync `BiConsumer` overload.

## 5. Event Types

Events come from three layers: the runner (lifecycle bracket), the
executor (step/task/routing), and the engine client (agent traffic,
negotiation).

| Event | Layer | When | Key Data |
|-------|-------|------|----------|
| `start` | runner | Workflow begins | `workflow`, `steps` |
| `step_start` | executor | Step begins | `step` |
| `task_request` | executor | A subtask is dispatched to `onTask` | `step`, `agent`, `task` |
| `task_response` | executor | `onTask` returned a `TaskResponse` | `step`, `agent`, `task`, `output` |
| `route_decision` | executor | Branch chosen | `step`, `next`, `reason` |
| `step_complete` | executor | Step finished | `step`, `results` |
| `agent_request` | engine client | Message sent to agent | `agent`, `request`, `metadata` |
| `agent_response` | engine client | Response from agent | `agent`, `response` |
| `agent_status_update` | engine client | Agent SSE status update | `agent`, `state`, `is_final` |
| `agent_artifact_update` | engine client | Agent SSE artifact update | `agent`, `artifact_name`, `text` |
| `negotiation_request` | engine client | Agent needs clarification | `agent`, `round`, `concern` |
| `negotiation_resolved` | engine client | Clarification provided | `agent`, `round`, `clarification` |
| `negotiation_failed` | engine client | Negotiation failed | `agent`, `round`, `reason` |
| `complete` | runner | Workflow succeeded | `history`, `step_outputs` |
| `error` | runner or executor | Workflow failed | runner: `error`, `history`; executor: `step`, `results` |
| `close` | runner | Cleanup done | (empty) |

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

### 6.1 Negotiation Auto-Loop

The engine client's `sendMessage()` automatically handles negotiation:
when the agent returns `INPUT_REQUIRED`, the engine extracts the
negotiation text from response metadata, calls `ControlPoint.onNegotiation()`
for a clarification, and sends it back as a follow-up message. The loop
repeats up to `maxNegotiationRounds` (default 3).

Override `onNegotiation()` in your `ControlPoint` to provide
business-specific clarifications:

```java
@Override
public CompletableFuture<String> onNegotiation(
        String agentName, String negotiationText,
        Map<String, Object> receiveResult) {
    return myLlm.generate("Agent " + agentName + " needs: " + negotiationText)
        .thenApply(Response::text);
}
```

Return an empty/null string to fail the round (a `negotiation_failed` event
is emitted and the loop retries).

### 6.2 Workflow Model Fields

| Field | Where | Meaning |
|-------|-------|---------|
| `steps[].stepType` | `WorkflowStep` | `AllSuccess` (default): every subtask must succeed; `AnySuccess`: the step succeeds as soon as one subtask succeeds. |
| `steps[].subtasks[]` | `Task` | Each has `agent`, `skill`, `description`. One `onTask` call per subtask. |
| `steps[].next[]` | `List<JumpCondition>` | Branch targets. `step` = next step name; `condition` = rule text. |
| `steps[].layer` | `WorkflowStep` | `layer == 0` starts the DAG (context = runtime intent only). Higher layers get upstream results. |
| `steps[].contextFrom` | `WorkflowStep` | Optional step names whose outputs fold into context. `"*"` = all ancestors. |

### 6.3 AgentCard Type

The Java SDK uses `org.a2aproject.sdk.spec.AgentCard` (strongly typed record)
throughout. `RegistryClient.fetchAgentCards()` returns
`List<Map<String, Object>>` (normalized from OpenAPI format). Use
`AgentCardJacksonModule` with Jackson to deserialize JSON to `AgentCard`:

```java
ObjectMapper mapper = new ObjectMapper()
    .registerModule(new AgentCardJacksonModule());
AgentCard card = mapper.readValue(json, AgentCard.class);
```

## 7. Agent Authentication

When AgentCards declare `securitySchemes`, `DefaultWorkflowEngineClient`
logs in via `AgentCredentialService`, caches the token for `token_ttl`
seconds, and attaches the auth header to outbound requests.

### 7.1 Credential File

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

Passwords can be AES-GCM encrypted with `enc:<iv>:<ciphertext>` prefix.
The decryption key is read from `A2AT_CRED_KEY` (env var or system property,
loaded from `.env` by `EnvFileLoader`).

### 7.2 Custom AuthProvider

For non-standard auth (SSO, API keys, custom headers):

```java
WorkflowEngineClientConfig.builder()
    .authProvider((agentName, agentCard, headers) -> {
        headers.put("Authorization", "Bearer " + mySsoToken);
        headers.put("X-Custom", "value");
    })
    .build();
```

### 7.3 Credential File Fields

| Field | Required | Default | Description |
|-------|----------|---------|-------------|
| `login_url` | Yes | - | URL to obtain the access token |
| `method` | No | `POST` | HTTP method |
| `content_type` | No | `application/json` | Content type |
| `request_fields` | No | - | Body fields (overrides username/password) |
| `token_field` | No | `accessSession` | Dot-separated token path |
| `token_ttl` | No | `3600` | Token cache TTL (seconds) |
| `auth_header` | No | `Authorization` | Custom header name |
| `auth_header_prefix` | No | (empty) | Prefix before token |
| `accept_header` | No | - | Custom Accept header |

## 8. SSL / TLS

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
    .sslVerify(true)
    .caCertsPath("/etc/ssl/certs/ca-bundle.crt")
    .build();
```

Set `sslVerify=false` only for dev with self-signed certs.

## 9. A2A-T Environment (.env)

```ini
A2AT_LLM_PROVIDER=openai
A2AT_LLM_MODEL=deepseek-v4-flash
A2AT_LLM_API_KEY=sk-...
A2AT_LLM_BASE_URL=https://api.deepseek.com
A2AT_LANGUAGE=zh-CN
A2AT_CRED_KEY=<32-byte hex>
```

When `a2atEnvPath` is null, Task-T prompt generation is skipped.

## 10. Integration Patterns

### SSE Server (Spring WebFlux)

```java
@GetMapping("/execute/{psopId}")
public Flux<String> execute(@PathVariable String psopId) {
    Workflow workflow = LoadPsop.load(baseUrl, psopId, token, false);

    return Flux.create(sink -> {
        ExecutePsop.builder()
            .psop(workflow)
            .agentCards(cards)
            .controlPoint(cp)
            .eventCallback(new EventCallback() {
                @Override
                public void onEvent(String type, Map<String, Object> data) {
                    sink.next("data: " + toJson(type, data) + "\n\n");
                }
            })
            .onFinish((r, e) -> { sink.complete(); return CompletableFuture.completedFuture(null); })
            .execute();
    });
}
```

### Cancellation

`ExecutePsop.builder().execute()` returns a `CompletableFuture`. You can
`cancel(true)` it, but the internal executor does not actively interrupt a
running A2A call. For SSE, drop the subscriber and let the future complete.

## 11. Checklist

1. Add Maven dependencies
2. Implement `ControlPoint` (onTask + onRoute)
3. Get AgentCards (from registry or JSON files)
4. Load Workflow (via `LoadPsop` or build your own)
5. Configure `.env` and credentials file
6. Call `ExecutePsop.builder().execute()`
7. Handle events + onFinish persistence
