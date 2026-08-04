# A2A-T Workflow Execution Engine - API Reference

## Package Overview

| Package                           | Description                                     |
|-----------------------------------|-------------------------------------------------|
| `dev.openan.workflow.engine.client`   | A2A message transport, auth, extensions, config |
| `dev.openan.workflow.engine.control`  | User decision points and event system           |
| `dev.openan.workflow.engine.model`    | Data models (Workflow, Task, results)           |
| `dev.openan.workflow.engine.registry` | PSOP loading and AgentCard registry             |
| `dev.openan.workflow.engine.runner`   | Entry point for workflow execution              |

---

## dev.openan.workflow.engine.runner

### ExecutePsop

Entry point for executing a PSOP workflow. Uses the Builder pattern.

#### ExecutePsop.Builder

| Method                                   | Type     | Default     | Description                                  |
|------------------------------------------|----------|-------------|----------------------------------------------|
| `psop(Workflow)`                         | required | -           | PSOP workflow definition                     |
| `agentCards(List<AgentCard>)`            | required | `List.of()` | Agent cards for all agents in the workflow   |
| `controlPoint(ControlPoint)`             | required | -           | User decision implementation                 |
| `engineClient(WorkflowEngineClient)`    | optional | null        | Pre-configured client (null = auto-create)  |
| `runtimeIntent(String)`                  | optional | `""`        | Natural-language intent for context assembly |
| `lang(String)`                           | optional | `"zh"`      | Language hint (`"zh"` or `"en"`)             |
| `a2atEnvPath(String)`                    | optional | null        | Path to `.env` file for A2A-T SDK            |
| `credentialsConfigPath(String)`          | optional | null        | Path to credentials JSON file                |
| `sslVerify(boolean)`                     | optional | `true`      | Whether to verify TLS certificates           |
| `caCertsPath(String)`                    | optional | null        | Path to CA certificates PEM file             |
| `a2aClientRuntime(A2AJavaClientRuntime)` | optional | null        | Custom runtime (null = auto-create)          |
| `eventCallback(EventCallback)`           | optional | null        | Real-time event callback                     |
| `onFinish(BiConsumer)`                   | optional | null        | Called when execution completes              |
| `onEvent(Function)`                      | optional | null        | Per-event transformation hook                |

```java
ExecutionResult result = ExecutePsop.builder()
        .psop(workflow)
        .agentCards(cards)
        .controlPoint(cp)
        .runtimeIntent("diagnose fault")
        .a2atEnvPath(".env")
        .sslVerify(false)
        .execute()
        .get(10, TimeUnit.MINUTES);
```

**Returns:** `CompletableFuture<ExecutionResult>`

---

## dev.openan.workflow.engine.client

### WorkflowEngineClient

Primary interface for sending A2A messages to agents.

```java
public interface WorkflowEngineClient {
    // Send a message with optional context ID and preset metadata
    CompletableFuture<SendMessageResult> sendMessage(
            String agentName, String message,
            String contextId, Map<String, Object> metadata);

    // Convenience: no context ID, no metadata
    CompletableFuture<SendMessageResult> sendMessage(
            String agentName, String message);

    void setControlPoint(ControlPoint controlPoint);

    void setEventCallback(EventCallback callback);

    void close();
}
```

> One-shot pre-positioning (Authorization-T / Notification-T) lives on
> `ExtensionSender`, not here. See the `ExtensionSender` section below.

#### sendMessage

| Parameter   | Type                  | Description                                   |
|-------------|-----------------------|-----------------------------------------------|
| `agentName` | `String`              | Target agent name (must match AgentCard.name) |
| `message`   | `String`              | Full assembled message text                   |
| `contextId` | `String`              | Optional context ID (null = auto-generated)   |
| `metadata`  | `Map<String, Object>` | Optional preset metadata                      |

**Returns:** `CompletableFuture<SendMessageResult>` containing response text, task, metadata, and task state.

The engine internally handles before sending:

1. Task-T prompt generation (if AgentCard declares Task-T)
2. Negotiation-T metadata injection (for follow-up messages)
3. Auth header injection (from credentials or AuthProvider)
4. A2A-Extensions header (only extensions present in metadata)

After receiving:

1. Response text extraction from SSE events
2. Metadata extraction (task-level + artifact-level)
3. Negotiation-T auto-loop (if `INPUT_REQUIRED`)

### ExtensionSender

One-shot pre-positioning facade over the same `A2ATransport`. Sends Authorization-T / Notification-T (and any one-shot
extension) to agents before the workflow starts. Bypasses Task-T prompt generation and the Negotiation-T auto-loop, and
does not emit events through the global
`EventCallback` (the returned `CompletableFuture` is the callback).

```java
public interface ExtensionSender {
    CompletableFuture<SendMessageResult> sendExtensionMessage(
            String agentName, String instruction,
            String naturalLanguageInput, A2ATExtension extension);

    // Convenience: Authorization-T
    CompletableFuture<SendMessageResult> sendAuthorization(
            String agentName, String instruction, String naturalLanguageInput);

    // Convenience: Notification-T (long-lived SSE)
    CompletableFuture<SendMessageResult> sendNotification(
            String agentName, String instruction, String naturalLanguageInput);

    // Convenience: Notification-T (long-lived SSE + event callback)
    CompletableFuture<SendMessageResult> sendNotification(
            String agentName, String instruction,
            String naturalLanguageInput, Consumer<Map<String, Object>> eventCallback);
}
```

| Parameter              | Type            | Description                                     |
|------------------------|-----------------|-------------------------------------------------|
| `agentName`            | `String`        | Target agent name (must match `AgentCard.name`) |
| `instruction`          | `String`        | Short instruction text; becomes `parts[].text` in the A2A message body |
| `naturalLanguageInput` | `String`        | Natural language input passed to the A2A-T SDK to generate a structured extension prompt. The generated value is placed in the message `metadata` under the extension URI key (e.g. `https://.../Authorization-T/v1`). Falls back to this text as-is when SDK generation is unavailable |
| `extension`            | `A2ATExtension` | Extension enum (never hardcode URIs)            |
| `eventCallback`        | `Consumer<Map<String, Object>>` | Optional SSE event callback (`sendNotification` only). Recovery results pushed by the agent are received here in real time; the Map contains `agent`, `text`, `metadata`, `state`. Null drops subsequent events |

**Wire format**: The resulting A2A message sent to the agent has `parts[].text = instruction` and `metadata = { "<extension-URI>": "<SDK-generated structured prompt>" }`. For example, Authorization-T produces:

```json
{
  "parts": [{"text": "Authorize diagnosis operations"}],
  "metadata": {
    "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Authorization-T/v1": "<structured authorization policy>"
  }
}
```

### WorkflowEngineClientConfig

Builder-based configuration for the workflow engine client.

| Property                | Type                     | Default | Description                         |
|-------------------------|--------------------------|---------|-------------------------------------|
| `sslVerify`             | `boolean`                | `true`  | TLS certificate verification        |
| `caCertsPath`           | `String`                 | null    | Path to CA certs PEM file           |
| `sendTimeoutSeconds`    | `long`                   | `600`   | SSE stream timeout (10 min default) |
| `authProvider`          | `AuthProvider`           | null    | Custom auth provider                |
| `credentialsConfigPath` | `String`                 | null    | Path to credentials JSON            |
| `credentialsConfig`     | `Map`                    | null    | Inline credentials config           |
| `a2atEnvPath`           | `String`                 | null    | Path to `.env` file                 |
| `maxNegotiationRounds`  | `int`                    | `3`     | Max negotiation auto-loop rounds    |
| `customHandlers`        | `List<ExtensionHandler>` | null    | Custom extension handlers           |

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
        .sslVerify(false)
        .sendTimeoutSeconds(900)
        .a2atEnvPath(".env")
        .credentialsConfigPath("creds.json")
        .maxNegotiationRounds(5)
        .authProvider(myProvider)
        .build();
```

### AuthProvider

Custom authentication provider for non-standard auth mechanisms.

```java
public interface AuthProvider {
    void applyAuth(String agentName, AgentCard agentCard,
                   Map<String, String> headers);
}
```

Called for every message send. The `headers` map is mutable; add
`Authorization`, custom headers, etc. Runs before credentials-based auth.

### ExtensionHandler

Extension handler for custom A2A-T extensions.

```java
public interface ExtensionHandler {
    String extensionKeyword();

    CompletableFuture<Map<String, Object>> beforeSend(
            AgentCard agentCard, String messageText,
            Map<String, Object> metadata,
            A2ATClient a2atClient, ControlPoint controlPoint);

    CompletableFuture<SendMessageResult> afterReceive(
            AgentCard agentCard, SendMessageResult result,
            A2ATClient a2atClient, ControlPoint controlPoint,
            EventCallback eventCallback);
}
```

Built-in: Task-T and Negotiation-T are handled automatically. You can register custom handlers via customHandlers in the
config.

### A2AJavaClientRuntime

Runtime seam for A2A SDK message transport. Implement to customize HTTP transport behavior.

```java
public interface A2AJavaClientRuntime {
    Iterable<ClientEvent> sendMessage(
            AgentCard agentCard, MessageSendParams params,
            ClientCallContext callContext,
            Consumer<ClientEvent> eventSink,
            Consumer<String> logSink);

    void close();
}
```

A default implementation is provided. Implement this interface only if you need custom HTTP transport.

---

## dev.openan.workflow.engine.control

### ControlPoint

User-facing decision interface. Each method has a single responsibility.

```java
public interface ControlPoint {
    // Send a Task-T message to an agent. Just call sendMessage.
    CompletableFuture<TaskResponse> onTask(
            TaskRequest request, WorkflowEngineClient engineClient);

    // Self-loop step: handled locally, no A2A-T message to self.
    default CompletableFuture<TaskResponse> onSelfTask(TaskRequest request);

    // Conditional branch decision. Only decide which step to go to.
    CompletableFuture<RouteDecision> onRoute(
            String stepName, Map<String, Object> results,
            List<JumpCondition> conditions);

    // Provide clarification for negotiation. Default: generic text.
    default CompletableFuture<String> onNegotiation(
            String agentName, String negotiationText,
            Map<String, Object> receiveResult);
}
```

| Method            | When Called                                | Return                            |
|-------------------|--------------------------------------------|-----------------------------------|
| `onTask`          | A step dispatches a task to another agent  | `TaskResponse` (success + output) |
| `onSelfTask`      | A `SELF_LOOP` step runs locally (no A2A-T) | `TaskResponse` (success + output) |
| `onRoute`         | After step completes, before next step     | `RouteDecision` (nextStep)        |
| `onNegotiation`   | When agent returns `INPUT_REQUIRED`        | `String` clarification text       |

### DefaultControlPoint

Default implementation with sensible defaults:

- `onTask`: calls `sendMessage()`, returns success/output
- `onSelfTask`: echoes the task message back (override for local logic)
- `onRoute`: picks first non-terminal branch
- `onNegotiation`: returns generic clarification

Extend this class and override only the methods you need.

### EventCallback

```java
public class EventCallback {
    public void onEvent(String eventType, Map<String, Object> data) {
    }
}
```

Override to receive real-time execution events. Event types are defined in `EventType` constants.

### EventType

| Constant                 | Description                                        |
|--------------------------|----------------------------------------------------|
| `STEP_START`             | A workflow step began                              |
| `STEP_COMPLETE`          | A workflow step completed                          |
| `TASK_REQUEST`           | A task was dispatched to an agent                  |
| `TASK_RESPONSE`          | A task response was received                       |
| `AGENT_REQUEST`          | A message was sent to an agent                     |
| `AGENT_RESPONSE`         | A response was received from an agent              |
| `AGENT_STATUS_UPDATE`    | Agent SSE status update (SUBMITTED, WORKING, etc.) |
| `AGENT_ARTIFACT_UPDATE`  | Agent SSE artifact update                          |
| `AGENT_MESSAGE_EVENT`    | Agent SSE message event                            |
| `NEGOTIATION_REQUEST`    | Agent requested negotiation (INPUT_REQUIRED)       |
| `NEGOTIATION_RESOLVED`   | Clarification was sent to agent                    |
| `NEGOTIATION_FAILED`     | Negotiation could not be resolved                  |
| `AUTHORIZATION_REQUEST`  | Agent requested authorization                      |
| `AUTHORIZATION_RESOLVED` | Authorization decision was made                    |
| `NOTIFICATION`           | Notification received from agent                   |
| `ROUTE_DECISION`         | Route decision was made                            |
| `START`                  | Workflow execution started                         |
| `COMPLETE`               | Workflow execution completed successfully          |
| `ERROR`                  | Workflow execution failed                          |
| `CLOSE`                  | Engine client closed                               |

---

## dev.openan.workflow.engine.registry

### LoadPsop

Load and search PSOP workflows from the orchestration center.

#### load

```java
static Workflow load(String baseUrl, String psopId,
                     String accessToken, boolean sslVerify)

static Workflow load(String baseUrl, String psopId)
```

GET `/api/v1/orchestrate/psop/{psop_id}`. Returns the full workflow with steps, subtasks, and routing conditions.

#### search

```java
static List<WorkflowSearchResult> search(
        String baseUrl, String intent, int topN,
        String accessToken, boolean sslVerify)

static List<WorkflowSearchResult> search(
        String baseUrl, String intent)
```

POST `/api/v1/orchestrate/search`. Returns ranked workflow summaries matched by natural-language intent.

### RegistryClient

Fetch and register AgentCards from the Registry Center.

```java
new RegistryClient("https://127.0.0.1:5000",false)

List<Map<String, Object>> fetchAgentCards()

Map<String, Object> fetchAgentCard(String name)

Map<String, Object> fetchAgentCard(String name, String organization)

Map<String, Object> registerAgentCard(Map<String, Object> agentCard)
```

- `fetchAgentCards`: GET all cards from registry
- `fetchAgentCard`: GET a specific card by name (and optionally organization)
- `registerAgentCard`: POST a card to the registry

---

## dev.openan.workflow.engine.model

### Workflow

| Field         | Type                 | Description            |
|---------------|----------------------|------------------------|
| `id`          | `String`             | Workflow ID            |
| `name`        | `String`             | Workflow name          |
| `description` | `String`             | Description            |
| `steps`       | `List<WorkflowStep>` | Ordered workflow steps |

Static factory: `Workflow.fromMap(Map<String, Object>)` parses from orchestration center API response.

### WorkflowStep

| Field         | Type                  | Default       | Description                                              |
|---------------|-----------------------|---------------|----------------------------------------------------------|
| `name`        | `String`              | -             | Step name (unique within workflow)                       |
| `subtasks`    | `List<Task>`          | `List.of()`   | Subtasks dispatched in this step                         |
| `next`        | `List<JumpCondition>` | `List.of()`   | Conditional next steps                                   |
| `layer`       | `int`                 | `0`           | Context layer (0 = runtime intent only)                  |
| `contextFrom` | `List<String>`        | null          | Steps to inherit context from (`"*"` = all predecessors) |
| `stepType`    | `StepType`            | `ALL_SUCCESS` | Execution mode                                           |

### StepType

| Value         | Description                                                                                                            |
|---------------|------------------------------------------------------------------------------------------------------------------------|
| `ALL_SUCCESS` | All subtasks must succeed                                                                                              |
| `ANY_SUCCESS` | Any subtask success is sufficient                                                                                      |
| `SELF_LOOP`   | The workflow agent handles the task locally via `onSelfTask`; no A2A-T message is sent. Success follows `ALL_SUCCESS`. |

### Task

| Field         | Type     | Description                         |
|---------------|----------|-------------------------------------|
| `agent`       | `String` | Agent name (matches AgentCard.name) |
| `skill`       | `String` | Agent skill ID                      |
| `description` | `String` | Task description                    |

### JumpCondition

| Field       | Type     | Description                                        |
|-------------|----------|----------------------------------------------------|
| `step`      | `String` | Next step name (`"end"` for terminal)              |
| `condition` | `String` | Condition expression (`"success"`, `"fail"`, etc.) |

### TaskRequest

| Field          | Type     | Description               |
|----------------|----------|---------------------------|
| `agentName`    | `String` | Target agent              |
| `skill`        | `String` | Agent skill               |
| `message`      | `String` | Full message text         |
| `description`  | `String` | Task description          |
| `context`      | `String` | Context message           |
| `stepName`     | `String` | Source step name          |
| `subtaskIndex` | `int`    | Subtask index within step |

### TaskResponse

| Field      | Type      | Description                |
|------------|-----------|----------------------------|
| `success`  | `boolean` | Whether the task succeeded |
| `output`   | `String`  | Response text              |
| `error`    | `String`  | Error message (if failed)  |
| `metadata` | `Map`     | Response metadata          |

### SendMessageResult

| Field       | Type     | Description                                    |
|-------------|----------|------------------------------------------------|
| `text`      | `String` | Extracted response text                        |
| `task`      | `Task`   | SDK Task object                                |
| `metadata`  | `Map`    | Response metadata (merged task + artifact)     |
| `taskState` | `String` | Final task state (e.g. `TASK_STATE_COMPLETED`) |

### ExecutionResult

| Field         | Type               | Description                |
|---------------|--------------------|----------------------------|
| `success`     | `boolean`          | Whether workflow succeeded |
| `history`     | `List<Map>`        | Per-step execution history |
| `stepOutputs` | `Map<String, Map>` | Outputs keyed by step name |
| `error`       | `String`           | Error message (if failed)  |

### RouteDecision

| Field      | Type     | Description          |
|------------|----------|----------------------|
| `nextStep` | `String` | Next step to execute |
| `reason`   | `String` | Decision reason      |

### WorkflowSearchResult

| Field            | Type           | Description        |
|------------------|----------------|--------------------|
| `workflowId`     | `String`       | Workflow ID        |
| `workflowType`   | `String`       | Type               |
| `name`           | `String`       | Name               |
| `description`    | `String`       | Description        |
| `tags`           | `List<String>` | Tags               |
| `createdAt`      | `String`       | Creation timestamp |
| `score`          | `double`       | Relevance score    |
| `userIntent`     | `String`       | Matched intent     |
| `relatedPreflow` | `String`       | Related preflow    |
| `tasksSummary`   | `String`       | Task summary       |

---

## Extension URI Constants

| Extension       | URI                                                                                       |
|-----------------|-------------------------------------------------------------------------------------------|
| Task-T          | `https://projects.tmforum.org/a2aproject/telecommunication/extensions/Task-T/v1`          |
| Negotiation-T   | `https://projects.tmforum.org/a2aproject/telecommunication/extensions/NEGOTIATION-T`      |
| Authorization-T | `https://projects.tmforum.org/a2aproject/telecommunication/extensions/Authorization-T/v1` |
| Notification-T  | `https://projects.tmforum.org/a2aproject/telecommunication/extensions/Notification-T/v1`  |

> **Note:** `DATA-NEGOTIATION-T/v1` is **not** an A2A-T extension. It is an
> SDK-internal metadata key used by the A2A-T SDK negotiation module to carry
> structured context (`negotiationType`, `round`, `negotiationId`,
> `status`) alongside the Negotiation-T text. It is not declared on
> AgentCard, not handled by ExtensionHandler, and not advertised in the
> `A2A-Extensions` header.

---

## Thread Safety

- The engine client is thread-safe. Concurrent collections are used internally.
- `ControlPoint` implementations must be thread-safe if used from multiple workflow executions concurrently.
- `EventCallback.onEvent` is called from multiple threads (main + SSE worker threads). Use synchronization if needed.

## Error Handling

- Agent call failures throw `RuntimeException` wrapping the cause.
- Negotiation failures fall through after `maxNegotiationRounds`.
- Auth failures (401) are logged as `ERROR` and auth headers are not set; the request proceeds without auth.
- SSE stream errors after terminal events are logged at `DEBUG` level (expected behavior).
