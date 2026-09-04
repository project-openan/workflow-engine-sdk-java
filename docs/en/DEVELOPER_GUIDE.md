# Developer Guide

This guide is for contributors and advanced users who want to understand the internal architecture, extend the SDK, or
contribute patches.

## 1. Installation

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>net.openan.workflow.sdk</groupId>
    <artifactId>workflow-engine</artifactId>
    <version>1.0.0</version>
</dependency>
```

The engine pulls in A2A protocol transports and a2a-t-core only. Host agents generating A2A-T content explicitly add
a2a-t-client; dispatched-agent services that validate received content also add a2a-t-server.

## 2. Core Concepts

| Layer    | Entry Point             | What It Handles                                      | What You Provide                       |
|----------|-------------------------|------------------------------------------------------|----------------------------------------|
| 2 (high) | `ExecutePsop.builder()` | Event collection, lifecycle, onFinish                | ControlPoint + AgentCards + config     |
| 1 (mid)  | `WorkflowExecutor`      | DAG traversal, context, dispatch (onTask/onSelfTask) | ControlPoint + EngineClient + Workflow |
| 0 (low)  | `WorkflowEngineClient`  | A2A send, response extraction                        | AgentCards + A2AJavaClientRuntime      |

## 3. Implement ControlPoint

```java
interface ControlPoint {
    CompletableFuture<MessageContent> onTask(TaskRequest request);
    CompletableFuture<TaskResult> onSelfTask(TaskRequest request);
    CompletableFuture<RouteDecision> onRoute(RouteRequest request);
    CompletableFuture<NegotiationReply> onNegotiation(NegotiationRequest request);
}
```

onTask returns final parts/metadata/extensions; the engine sends them without generating or rewriting content.
onSelfTask returns local TaskResult, onRoute selects an allowed candidate, and onNegotiation returns Send or Stop.
Unimplemented callbacks fail explicitly. No echo-success, first-branch choice or automatic consent.
See [Business callback contract](BUSINESS_CALLBACKS.md) for fields and working examples.

```java
ControlPoint callbacks = ControlPoint.builder()
    .onTask(request -> CompletableFuture.completedFuture(
        MessageContent.text(request.getInstruction())))
    .onSelfTask(request -> CompletableFuture.completedFuture(
        TaskResult.success(List.of(Map.of(
            "sourceResults", request.getWorkflowInput().upstreamResults())))))
    .onRoute(request -> CompletableFuture.failedFuture(
        new IllegalStateException("Supply a routing policy for " + request.stepName())))
    .onNegotiation(request -> CompletableFuture.completedFuture(
        new NegotiationReply.Stop("manual.required", "Manual confirmation required")))
    .build();
```

## 4. Execute via Builder (recommended)

```java
ExecutionResult result = ExecutePsop.builder()
        .psop(workflow)
        .agentCards(agentCards)
        .controlPoint(new MyControlPoint())
        .runtimeIntent("Analyze a service anomaly")
        .lang("zh")
        .sslVerify(false)
        .credentialsConfigPath("agent_credentials.json")
        .eventCallback(new EventCallback())
        .onFinish((r, e) -> {
            persist(r);
            return CompletableFuture.completedFuture(null);
        })
        .execute()
        .join();
```

Required: `psop`, `controlPoint`. All others have sensible defaults.
`onFinish` accepts both the async `BiFunction<..., CompletableFuture<Void>>`
and a sync `BiConsumer` overload.

## 5. Event Types

Events come from three layers: the runner (lifecycle bracket), the executor (step/task/routing), and the engine client
(agent traffic, negotiation).

| Event                   | Layer              | When                                                     | Key Data                                                |
|-------------------------|--------------------|----------------------------------------------------------|---------------------------------------------------------|
| `start`                 | runner             | Workflow begins                                          | `workflow`, `steps`                                     |
| `step_start`            | executor           | Step begins                                              | `step`                                                  |
| `task_request`          | executor           | A subtask is dispatched to `onTask`/`onSelfTask`         | `step`, `agent`, `task`                                 |
| `task_response`         | executor           | Remote task completed or onSelfTask returned TaskResult  | `step`, `agent`, `task`, `outputs`                      |
| `task_status_changed`   | executor           | Task status changed (pending → running → success/failed) | `step`, `agent`, `task`, `status`                       |
| `route_decision`        | executor           | Branch chosen                                            | `step`, `next`, `reason`                                |
| `step_complete`         | executor           | Step finished                                            | `step`, `results`                                       |
| `workflow_complete`     | executor           | All steps finished                                       | `history`, `step_outputs`                               |
| `agent_request`         | engine client      | Dispatch intent, not a wire observation                  | `agent`, `content`                                      |
| `agent_response`        | engine client      | Remote response assembled                                | `agent`, `response`, `receivedMessages`                 |
| `agent_status_update`   | engine client      | Agent SSE status update                                  | `agent`, `state`, `is_final`                            |
| `agent_artifact_update` | engine client      | Agent SSE artifact update                                | `agent`, `artifact_name`, `text`                        |
| `negotiation_request`   | engine client      | Valid Propose enters host callback                       | `agent`, `request`, `exchange`                          |
| `negotiation_resolved`  | engine client      | Host Send passed association checks, not task success    | `agent`, `reply`, `exchange`                            |
| `negotiation_failed`    | engine client      | Local negotiation interaction failed                     | `agent`, `exchange`, `errorType`                        |
| `complete`              | runner             | Workflow succeeded                                       | `history`, `step_outputs`                               |
| `error`                 | runner or executor | Workflow failed                                          | runner: `error`, `history`; executor: `step`, `results` |
| `close`                 | runner             | Cleanup done                                             | (empty)                                                 |

## 6. Mid-Level (Layer 1: WorkflowExecutor)

```java
try(var client = new DefaultWorkflowEngineClient(agentCards, a2aRuntime,
        WorkflowEngineClientConfig.builder()
                .sslVerify(false)
                .credentialsConfigPath("etc/conf/agent_credentials.json")
                .build())){
WorkflowExecutor executor = new WorkflowExecutor(
        workflow,
        new MyControlPoint(),
        client,
        new EventCallback(),
        "Analyze request",
        "zh"
);
ExecutionResult result = executor.run().join();
}
```

### 6.1 Negotiation Auto-Loop

Only a remote `INPUT_REQUIRED` carrying valid Negotiation-T Propose enters `onNegotiation`. Terminal responses never
restart negotiation; ordinary `INPUT_REQUIRED` fails explicitly. The host validates/interprets the proposal and
generates the final Accept/Reject/Abort with its own A2A-T client. Use `A2atMessages.contextOf(request.received())` to
obtain the received context; reply with the same id, round and maxRounds. The last allowed round can still be answered.
Do not call nextRound for an ending reply or return a new Propose.

Return `new NegotiationReply.Send(content)` to send that exact content. Return `new NegotiationReply.Stop(code, reason)`
to stop locally without a generated Abort. Repeated task/session/round events do not repeat the callback or submission.
Unchanged waiting state is observed with getTask.
`maxNegotiationExchanges` (default 3) bounds local interactions, independently of the SDK context's maxRounds. Timeout,
exhausted budget or a missing handler fails locally; no implicit Accept or synthesized Abort. Accept/Reject ACKs in
SUBMITTED/WORKING remain pending and are observed without resending the command. A business-sent Abort is never
task success, even if the dispatched agent acknowledges it with COMPLETED.

### 6.2 Workflow Model Fields

| Field                 | Where                 | Meaning                                                                                                                                                                                                         |
|-----------------------|-----------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `steps[].stepType`    | `WorkflowStep`        | `AllSuccess` (default): every subtask must succeed; `AnySuccess`: any subtask success suffices; `SelfLoop`: the host agent handles the step locally via `onSelfTask` (no A2A-T message to the named agent). |
| `steps[].subtasks[]`  | `Task`                | Each has `agent`, `skill`, `description`. One `onTask` (or `onSelfTask` for SelfLoop) call per subtask.                                                                                                         |
| `steps[].next[]`      | `List<JumpCondition>` | Branch targets. `step` = next step name; `condition` = rule text.                                                                                                                                               |
| `steps[].layer`       | `WorkflowStep`        | Orchestration-level hint; actual readiness is derived from DAG predecessors.                                                                                                                                    |
| `steps[].contextFrom` | `WorkflowStep`        | Selects steps exposed through `workflowInput.upstreamResults`; omitted = direct predecessors, `[]` = none, `"*"` = all ancestors, or explicit ancestor names.                                                   |

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
  "Dispatched Agent": {
    "bearerAuth": {
      "login_url": "https://127.0.0.1:8080/auth/login",
      "method": "POST",
      "request_fields": {
        "username": "...",
        "password": "..."
      },
      "token_field": "access_token",
      "token_ttl": 3600
    }
  }
}
```

Passwords can be AES-GCM encrypted with `enc:<iv>:<ciphertext>` prefix. The decryption key is read from `A2AT_CRED_KEY`
(explicit instance configuration > OS environment > JVM property). The host owns loading; the engine does not read `.env` automatically.

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

| Field                | Required | Default            | Description                               |
|----------------------|----------|--------------------|-------------------------------------------|
| `login_url`          | Yes      | -                  | URL to obtain the access token            |
| `method`             | No       | `POST`             | HTTP method                               |
| `content_type`       | No       | `application/json` | Content type                              |
| `request_fields`     | No       | -                  | Body fields (overrides username/password) |
| `token_field`        | No       | `accessSession`    | Dot-separated token path                  |
| `token_ttl`          | No       | `3600`             | Token cache TTL (seconds)                 |
| `auth_header`        | No       | `Authorization`    | Custom header name                        |
| `auth_header_prefix` | No       | (empty)            | Prefix before token                       |
| `accept_header`      | No       | -                  | Custom Accept header                      |

## 8. SSL / TLS

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
        .sslVerify(true)
        .caCertsPath("/etc/ssl/certs/ca-bundle.crt")
        .build();
```

Set `sslVerify=false` only for dev with self-signed certs.

## 9. A2A-T Environment (.env)

The engine does not read A2A-T .env files or create LLM clients. If host-agent callbacks use A2A-T, initialize
A2ATClient/A2ATServer with a host-owned environment file containing provider/model/key/base URL and A2AT_LANGUAGE. A
sample-specific a2atEnvPath is not an engine builder option. Keep dispatched-agent credential decryption separate from
LLM configuration: pass the secret explicitly through WorkflowEngineClientConfig.builder()
.credentialEncryptionKey (key) when using encrypted built-in credentials, then pass that configured engineClient to
ExecutePsop. Custom AuthProvider owns its own token/configuration. Tests use the current SDK SPI with an offline
provider, not template overrides or production fallbacks.

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
                .onFinish((r, e) -> {
                    sink.complete();
                    return CompletableFuture.completedFuture(null);
                })
                .execute();
    });
}
```

### Cancellation

ExecutePsop.builder().execute() returns a CompletableFuture. Cancellation prevents late callback results from being
 sent and requests conversation cleanup. Host agents must separately cancel their own LLM/business work. A dispatched task already
submitted may require an explicit cancelTask operation; local cancellation is not protocol Abort.

## 11. Checklist

1. Add Maven dependencies
2. Implement `ControlPoint` (implement final onTask content plus local tasks, conditional routes and negotiation when
   required)
3. Get AgentCards (from registry or JSON files)
4. Load Workflow (via `LoadPsop` or build your own)
5. Configure `.env` and credentials file
6. Call `ExecutePsop.builder().execute()`
7. Handle events + onFinish persistence

### Pretty protocol log display

Protocol logs default to pretty display: one header value per line and indented JSON bodies. SSE keeps event controls
(id/event/comments); JSON appears between `=== SSE data(JSON display; not wire text) ===`
and `=== End SSE data ===`, without repeating `data:` on every JSON line. Event boundaries remain separate. Set the
environment variable or JVM property `WORKFLOW_ENGINE_PROTOCOL_PRETTY=false` to retain redacted raw body text.
Formatting changes presentation only (JSON whitespace and SSE display labels), never transmitted bytes, metadata, number
 tokens or extension headers. The raw observer content remains unchanged. SSE pretty output is a display, not a
 replayable packet capture. Escaped newlines inside JSON strings stay escaped; invalid/incomplete or non-JSON content
 stays raw. Redaction and capacity limits still apply. To verify negotiation traffic, look for
 `A2A-Extensions: .../Negotiation-T/v1`, the matching metadata key and `negotiationContext`; there is no dedicated
 Negotiation-T HTTP header.
