# A2A-T Workflow Execution Engine - Integration Guide

## 1. Overview

The A2A-T Workflow Execution Engine is a Java SDK for orchestrating
multi-agent workflows using the A2A protocol with A2A-T telecom extensions.

The engine handles all protocol mechanics automatically (message transport,
SSE streaming, Task-T prompt generation, Negotiation-T auto-loop,
authentication, TLS). You focus on business decisions only.

## 2. Prerequisites

| Requirement | Version |
|---|---|
| JDK | 17+ |
| Maven | 3.6+ |

## 3. Maven Dependency

```xml
<dependency>
    <groupId>com.openan.a2at</groupId>
    <artifactId>a2at-engine</artifactId>
    <version>0.3.0</version>
</dependency>
```

## 4. Quick Start

Four steps: define workflow -> load AgentCard -> implement ControlPoint -> execute.

### 4.1 Define a Workflow

```java
Workflow workflow = Workflow.builder()
    .name("Fault Diagnosis")
    .steps(List.of(
        WorkflowStep.builder()
            .name("diagnose")
            .subtasks(List.of(
                Task.builder()
                    .agent("SPN Domain Agent")
                    .skill("diagnosis")
                    .description("Diagnose fault")
                    .build()))
            .next(List.of(
                JumpCondition.builder()
                    .step("merge")
                    .condition("success")
                    .build()))
            .layer(0)
            .build(),
        WorkflowStep.builder()
            .name("merge")
            .subtasks(List.of(
                Task.builder()
                    .agent("Transport Workbench Agent")
                    .skill("aggregate")
                    .description("Merge results")
                    .build()))
            .next(List.of(
                JumpCondition.builder()
                    .step("end")
                    .condition("success")
                    .build()))
            .layer(1)
            .contextFrom(List.of("*"))
            .build()
    ))
    .build();
```

### 4.2 Load AgentCards

```java
// Option A: From JSON files
ObjectMapper mapper = new ObjectMapper()
    .registerModule(new AgentCardJacksonModule());
AgentCard card = mapper.readValue(
    new File("agentcard/my_agent.json"), AgentCard.class);

// Option B: From Registry Center
RegistryClient registry = new RegistryClient("https://127.0.0.1:5001", false);
List<Map<String, Object>> cards = registry.fetchAgentCards();
```

### 4.3 Implement ControlPoint

Extend `DefaultControlPoint` and override the methods you need:

```java
public class MyControlPoint extends DefaultControlPoint {
    @Override
    public CompletableFuture<TaskResponse> onTask(
            TaskRequest request, WorkflowEngineClient engineClient) {
        return engineClient.sendMessage(
                request.getAgentName(), request.getMessage())
            .thenApply(r -> TaskResponse.builder()
                .success(r.getText() != null && !r.getText().isEmpty())
                .output(r.getText())
                .build());
    }

    @Override
    public CompletableFuture<RouteDecision> onRoute(
            String stepName, Map<String, Object> results,
            List<JumpCondition> conditions) {
        return CompletableFuture.completedFuture(
            RouteDecision.builder()
                .nextStep(conditions.get(0).getStep())
                .build());
    }

    @Override
    public CompletableFuture<String> onNegotiation(
            String agentName, String negotiationText,
            Map<String, Object> receiveResult) {
        return CompletableFuture.completedFuture(
            "Please proceed with available information.");
    }
}
```

| Method | When Called | What You Do |
|---|---|---|
| `onTask` | Each workflow step dispatches a task | Call `engineClient.sendMessage()`, return result |
| `onRoute` | After step completes, before next step | Pick the next step from candidates |
| `onNegotiation` | Agent returns `INPUT_REQUIRED` | Return clarification text |
| `onAuthorization` | Agent requests authorization (optional) | Return true/false |
| `onNotification` | Agent pushes notification (optional) | Handle notification |

`onAuthorization` and `onNotification` have default implementations
(auto-approve / no-op). `onNegotiation` defaults to a generic clarification.
Override only what you need.

### 4.4 Execute

```java
ExecutionResult result = ExecutePsop.builder()
    .psop(workflow)
    .agentCards(List.of(card1, card2))
    .controlPoint(new MyControlPoint())
    .runtimeIntent("SPN cross-city fault diagnosis")
    .lang("zh")
    .a2atEnvPath(".env")
    .credentialsConfigPath("credentials.json")
    .sslVerify(false)
    .onFinish((r, history) -> {
        System.out.println("Result: " + r.isSuccess());
    })
    .execute()
    .get(10, TimeUnit.MINUTES);
```

Required: `psop`, `controlPoint`. All other config items have defaults.

## 5. Configuration

### 5.1 .env File

Configures the LLM and prompt runtime:

```ini
A2AT_LANGUAGE=zh-CN
A2AT_LLM_PROVIDER=openai
A2AT_LLM_MODEL=deepseek-v4-flash
A2AT_LLM_API_KEY=sk-xxxxxxxxxxxxxxxx
A2AT_LLM_BASE_URL=https://api.deepseek.com
A2AT_LLM_MAX_TOKENS=2000
A2AT_LLM_TEMPERATURE=0
A2AT_LLM_TIMEOUT_SECONDS=60
A2AT_CRED_KEY=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
```

When `.env` is not configured, Task-T prompt generation is unavailable.
All other features work normally.

### 5.2 Credentials File

For agents requiring authentication, provide a JSON credentials file:

```json
{
  "SPN Domain Agent": {
    "bearerAuth": {
      "login_url": "https://127.0.0.1:26335/rest/plat/smapp/v1/oauth/token",
      "method": "PUT",
      "request_fields": {
        "grantType": "password",
        "userName": "admin",
        "value": "enc:<base64-iv>:<base64-ciphertext>",
        "ipaddr": "*"
      },
      "token_field": "accessSession",
      "token_ttl": 3600
    }
  }
}
```

- Encrypted passwords use `enc:<iv>:<ciphertext>` format, key from `A2AT_CRED_KEY`
- Plaintext values (no `enc:` prefix) are also accepted
- Tokens are cached and refreshed automatically

### 5.3 Custom Authentication

When AgentCard has no securitySchemes or uses a non-standard auth mechanism,
implement `AuthProvider`:

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
    .authProvider((agentName, agentCard, headers) -> {
        headers.put("Authorization", "Bearer " + mySsoClient.getToken(agentName));
    })
    .sslVerify(false)
    .a2atEnvPath(".env")
    .build();
```

Called for every message send. If both a credentials file and `AuthProvider`
are configured, both take effect.

## 6. AgentCard Definition

AgentCards declare extensions via `capabilities.extensions`:

```json
{
  "name": "SPN Domain Agent",
  "capabilities": {
    "streaming": true,
    "extensions": [
      {
        "uri": "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Task-T/v1",
        "description": "Structured task prompt",
        "required": false
      },
      {
        "uri": "https://projects.tmforum.org/a2aproject/telecommunication/extensions/NEGOTIATION-T",
        "description": "Negotiation text exchange",
        "required": false
      },
      {
        "uri": "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Authorization-T/v1",
        "description": "Authorization whitelist",
        "required": false
      },
      {
        "uri": "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Notification-T/v1",
        "description": "Result notification subscription",
        "required": false
      }
    ]
  },
  "securitySchemes": {
    "bearerAuth": { "type": "http", "scheme": "bearer" }
  },
  "securityRequirements": [
    { "schemes": { "bearerAuth": [] } }
  ],
  "supportedInterfaces": [
    {
      "protocolBinding": "HTTP+JSON",
      "protocolVersion": "1.0",
      "url": "https://127.0.0.1:26335/a2a/json"
    }
  ]
}
```

Extension URIs must match the A2A-T definitions exactly.

## 7. A2A-T Extensions

The engine handles four A2A-T extensions automatically. You do not need
to deal with protocol details:

### Task-T (automatic)

When sending a message to an agent, the engine generates a structured task
prompt and places it in the message metadata. In `onTask`, you just call
`sendMessage()` -- prompt generation is transparent.

### Negotiation-T (automatic)

When an agent returns `INPUT_REQUIRED`, the engine extracts the negotiation
text, calls your `onNegotiation()` for a clarification, and sends it back.
Auto-loops up to `maxNegotiationRounds` (default 3).

### Authorization-T (pre-positioning)

Before the workflow starts, send a whitelist authorization strategy to
SPN agents:

```java
engineClient.sendExtensionMessage(
    "SPN Domain Agent",
    "Authorization-T pre-positioning",
    "Task type: new authorization, operation: service recovery, ...",
    "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Authorization-T/v1"
);
```

The SPN agent stores the strategy and compares subsequent operations against
the whitelist. Operations within the whitelist are executed; others are rejected.

### Notification-T (pre-positioning)

Before the workflow starts, subscribe to recovery result notifications:

```java
engineClient.sendExtensionMessage(
    "SPN Domain Agent",
    "Notification-T subscription",
    "Topic: service-recovery-execution-result, ...",
    "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Notification-T/v1"
);
```

The SPN agent reports recovery results through the notification channel.

## 8. HTTPS Configuration

```java
// Dev: self-signed certs, skip verification
.sslVerify(false)

// Production: enable verification + custom CA certs
.sslVerify(true).caCertsPath("/path/to/ca-certs.pem")
```

## 9. Logging

The engine has a dedicated `PROTOCOL` logger that outputs full protocol-level
request/response messages (headers + body). Configure in `log4j2.properties`:

```properties
logger.PROTOCOL.name = PROTOCOL
logger.PROTOCOL.level = info
logger.PROTOCOL.additivity = false
logger.PROTOCOL.appenderRef = console
```

Set to `debug` to see full message bodies.

## 10. Event Callback

Subscribe to execution events for real-time monitoring:

```java
EventCallback callback = new EventCallback() {
    @Override
    public void onEvent(String eventType, Map<String, Object> data) {
        switch (eventType) {
        case EventType.STEP_START -> System.out.println("Step started: " + data.get("step"));
        case EventType.AGENT_STATUS_UPDATE -> System.out.println(
            data.get("agent") + " state: " + data.get("state"));
        case EventType.NEGOTIATION_REQUEST -> System.out.println(
            "Negotiation from " + data.get("agent"));
        case EventType.COMPLETE -> System.out.println("Workflow complete");
        }
    }
};

ExecutePsop.builder()
    .eventCallback(callback)
    // ...
```

Common event types: `STEP_START`, `STEP_COMPLETE`, `AGENT_REQUEST`,
`AGENT_RESPONSE`, `NEGOTIATION_REQUEST`, `NEGOTIATION_RESOLVED`,
`COMPLETE`, `ERROR`.

## 11. Load Workflows from Orchestration Center

```java
// Search by intent
List<WorkflowSearchResult> results = LoadPsop.search(
    "https://127.0.0.1:5001", "SPN cross-city fault diagnosis", 5, null, false);

// Load full workflow by ID
Workflow workflow = LoadPsop.load(
    "https://127.0.0.1:5001", results.get(0).getWorkflowId(), null, false);
```

## 12. Custom Extensions

To add a new A2A-T extension, implement `ExtensionHandler`:

```java
public class MyExtensionHandler implements ExtensionHandler {
    @Override
    public String extensionKeyword() {
        return "My-Extension";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeSend(
            AgentCard agentCard, String messageText,
            Map<String, Object> metadata,
            A2ATClient a2atClient, ControlPoint controlPoint) {
        metadata.put("https://example.com/extensions/My-Extension/v1", "value");
        return CompletableFuture.completedFuture(metadata);
    }

    @Override
    public CompletableFuture<SendMessageResult> afterReceive(
            AgentCard agentCard, SendMessageResult result,
            A2ATClient a2atClient, ControlPoint controlPoint,
            EventCallback eventCallback) {
        return CompletableFuture.completedFuture(result);
    }
}
```

Register via config:

```java
WorkflowEngineClientConfig.builder()
    .customHandlers(List.of(new MyExtensionHandler()))
    .build();
```

## 13. Interface Reference

| Interface/Class | Purpose |
|---|---|
| `ExecutePsop.Builder` | Workflow execution entry point |
| `ControlPoint` / `DefaultControlPoint` | Business decisions (onTask, onRoute, onNegotiation, etc.) |
| `WorkflowEngineClient` | Send messages to agents (sendMessage, sendExtensionMessage) |
| `WorkflowEngineClientConfig` | Configuration (SSL, auth, A2A-T, negotiation rounds, custom handlers) |
| `AuthProvider` | Custom authentication |
| `ExtensionHandler` | Custom extension handler |
| `EventCallback` / `EventType` | Event callback |
| `LoadPsop` / `RegistryClient` | Workflow loading / AgentCard fetching |
| `Workflow` / `WorkflowStep` / `Task` / `JumpCondition` | Workflow definition |
| `ExecutionResult` | Execution result |
| `SendMessageResult` / `TaskResponse` | Message/task response |
