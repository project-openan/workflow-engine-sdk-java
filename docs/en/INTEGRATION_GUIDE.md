# A2A-T Workflow Execution Engine - Integration Guide

## 1. Overview

The A2A-T Workflow Execution Engine is a Java SDK for orchestrating multi-agent workflows using the A2A protocol with
A2A-T telecom extensions.

The engine handles all protocol mechanics automatically (message transport, SSE streaming, Task-T prompt generation,
Negotiation-T auto-loop, authentication, TLS). You focus on business decisions only.

## 2. Prerequisites

| Requirement | Version |
|-------------|---------|
| JDK         | 17+     |
| Maven       | 3.6+    |

## 3. Maven Dependency

```xml

<dependency>
    <groupId>dev.openan.workflow.sdk</groupId>
    <artifactId>workflow-engine</artifactId>
<version>1.0.0</version>
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
                        .stepType(StepType.SELF_LOOP)   // self-loop: workbench merges locally, no A2A-T to self
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
RegistryClient registry = new RegistryClient("https://127.0.0.1:5000", false);
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
    public CompletableFuture<TaskResponse> onSelfTask(TaskRequest request) {
        // SELF_LOOP step: handled locally, no engineClient, no A2A-T message.
        // request.getMessage() already carries upstream step results as context.
        String summary = summarizeLocally(request.getMessage());
        return CompletableFuture.completedFuture(
                TaskResponse.builder().success(true).output(summary).build());
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

| Method            | When Called                               | What You Do                                      |
|-------------------|-------------------------------------------|--------------------------------------------------|
| `onTask`          | A step dispatches a task to another agent | Call `engineClient.sendMessage()`, return result |
| `onSelfTask`      | A `SELF_LOOP` step runs locally           | Handle locally, return result (no A2A-T message) |
| `onRoute`         | After step completes, before next step    | Pick the next step from candidates               |
| `onNegotiation`   | Agent returns `INPUT_REQUIRED`            | Return clarification text                        |

`onNegotiation` defaults to a generic clarification. Override only what you need.

**Pre-positioning (Authorization-T / Notification-T)**: These are one-shot operations sent via `ExtensionSender` before the workflow starts. The send result is returned directly as a `SendMessageResult` -- no separate callback interface is needed.

**Self-loop steps (SelfLoop)**: When a step is the workflow-executing agent's own task (e.g. merging multiple agents'
diagnostic results), set `stepType` to `SELF_LOOP`. The engine calls `onSelfTask` locally instead of sending an A2A-T
message to the agent itself. `onSelfTask` takes no `engineClient` parameter — this enforces at the API level that
self-loop tasks never send A2A-T. Only steps targeting other agents go through `onTask` + A2A-T.

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

When `.env` is not configured, Task-T prompt generation is unavailable. All other features work normally.

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

### 5.2.1 Credential Encryption and Key Management

Passwords in the credentials file support encrypted storage to avoid plaintext exposure.

**Generate a key**

```bash
openssl rand -hex 32
```

Example output:

```
4f8a2b1c3d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b
```

Write the key to the `.env` file:

```
A2AT_CRED_KEY=4f8a2b1c3d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b
```

**Encrypt a password**

```bash
# Option 1: set env var first
set A2AT_CRED_KEY=4f8a2b1c3d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b
java -cp workflow-engine.jar dev.openan.workflow.engine.client.CredentialCrypto "Admin@123"

# Option 2: pass key as second argument
java -cp workflow-engine.jar dev.openan.workflow.engine.client.CredentialCrypto "Admin@123" 4f8a2b1c3d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b
```

Output:

```
enc:uHQcTeKZMVNRM9Ga:o5vm4weRozBXBs04phrLq7j7+/yRVyDsrw==
```

Paste the output into the `value` field of the credentials JSON.

**Rotating the key**

1. Generate a new key: `openssl rand -hex 32`
2. Update `A2AT_CRED_KEY` in `.env`
3. Re-encrypt all passwords: `java -cp workflow-engine.jar dev.openan.workflow.engine.client.CredentialCrypto "plaintext" new-key`
4. Update the `enc:...` results in the credentials JSON file

> The `.env` file should not be committed to version control. Add it to `.gitignore`.
### 5.3 Custom Authentication (AuthProvider)

When the AgentCard has no `securitySchemes`, or uses a non-standard auth mechanism, implement the `AuthProvider` interface. It has a single method:

```java
public interface AuthProvider {
    void applyAuth(String agentName, AgentCard agentCard, Map<String, String> headers);
}
```

`applyAuth` is called before every message send. The implementation adds auth headers to the mutable `headers` map.

**Scenario 1: Enterprise SSO / External Token Service**

```java
public class SsoAuthProvider implements AuthProvider {
    private final SsoClient ssoClient;

    public SsoAuthProvider(SsoClient ssoClient) {
        this.ssoClient = ssoClient;
    }

    @Override
    public void applyAuth(String agentName, AgentCard agentCard, Map<String, String> headers) {
        String token = ssoClient.getToken(agentName);
        headers.put("Authorization", "Bearer " + token);
    }
}

// Register
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
        .authProvider(new SsoAuthProvider(mySsoClient))
        .sslVerify(false)
        .a2atEnvPath(".env")
        .build();
```

**Scenario 2: AgentCard has no securitySchemes, but server requires auth**

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
        .authProvider((agentName, agentCard, headers) -> {
            headers.put("X-API-Key", "static-api-key-value");
        })
        .build();
```

**Scenario 3: Custom header name (non-standard Authorization)**

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
        .authProvider((agentName, agentCard, headers) -> {
            String token = refreshTokenIfNeeded(agentName);
            headers.put("X-Auth-Token", token);
            headers.put("X-Tenant-Id", "tenant-001");
        })
        .build();
```

**Notes:**

- `applyAuth` is called on every message send; implement token caching/refresh logic inside
- If both a credentials file and `AuthProvider` are configured, both run: `AuthProvider` first, credentials-based auth second
- On auth failure (e.g. token retrieval throws), the exception propagates to `send()` and the request is blocked
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
    "bearerAuth": {
      "type": "http",
      "scheme": "bearer"
    }
  },
  "securityRequirements": [
    {
      "schemes": {
        "bearerAuth": []
      }
    }
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

The engine handles four A2A-T extensions automatically. You do not need to deal with protocol details:

### Task-T (automatic)

When sending a message to an agent, the engine generates a structured task prompt and places it in the message metadata.
In `onTask`, you just call
`sendMessage()` -- prompt generation is transparent.

### Negotiation-T (automatic)

When an agent returns `INPUT_REQUIRED`, the engine extracts the negotiation text, calls your `onNegotiation()` for a
clarification, and sends it back. Auto-loops up to `maxNegotiationRounds` (default 3).

### Authorization-T (pre-positioning)

Before the workflow starts, send a whitelist authorization strategy to SPN agents. Pre-positioning uses the
`ExtensionSender` facade over the same transport, not the workflow client:

```java
ExtensionSender sender = new DefaultExtensionSender(transport);
sender.sendAuthorization(
    "SPN Domain Agent",
    "Authorization-T pre-positioning",
    "Task type: new authorization, operation: service recovery, ..."
);
```

`A2ATExtension.AUTHORIZATION_T` is used internally; never hardcode the URI. The SPN agent stores the strategy and
compares subsequent operations against the whitelist. Operations within the whitelist are executed; others are rejected.

### Notification-T (pre-positioning)

Before the workflow starts, subscribe to recovery result notifications:

```java
sender.sendNotification(
    "SPN Domain Agent",
            "Notification-T subscription",
            "Topic: service-recovery-execution-result, ..."
);
```

`A2ATExtension.NOTIFICATION_T` opens a long-lived SSE stream. To receive subsequent recovery results, pass a
`Consumer<Map<String, Object>>` callback as the fourth parameter:

```java
sender.sendNotification(
    "SPN Domain Agent",
            "Notification-T subscription",
            "Topic: service-recovery-execution-result, ...",
    event -> {
        // event contains agent, text, metadata, state
        Object text = event.get("text");
        if (text != null) {
            System.out.println("Recovery result: " + text);
        }
    }
);
```

Without a callback (null), subsequent events are dropped. The SPN agent reports recovery results through the
notification channel.

## 8. HTTPS Configuration

```java
// Dev: self-signed certs, skip verification
.sslVerify(false)

// Production: enable verification + custom CA certs
.sslVerify(true)
.caCertsPath("/path/to/ca-certs.pem")
```

## 9. Logging

The engine has a dedicated `PROTOCOL` logger that outputs full protocol-level request/response messages (headers +
body). Configure in `log4j2.properties`:

```properties
logger.PROTOCOL.name=PROTOCOL
logger.PROTOCOL.level=info
logger.PROTOCOL.additivity=false
logger.PROTOCOL.appenderRef=console
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

| Interface/Class                                        | Purpose                                                               |
|--------------------------------------------------------|-----------------------------------------------------------------------|
| `ExecutePsop.Builder`                                  | Workflow execution entry point                                        |
| `ControlPoint` / `DefaultControlPoint`                 | Business decisions (onTask, onSelfTask, onRoute, onNegotiation, etc.) |
| `WorkflowEngineClient` / `DefaultWorkflowEngineClient` | Workflow send (sendMessage, auth, extensions)                         |
| `ExtensionSender` / `DefaultExtensionSender`           | One-shot pre-positioning (sendAuthorization, sendNotification)        |
| `A2ATransport`                                         | Shared wire layer (httpx runtime, auth, SSE consumer)                 |
| `WorkflowEngineClientConfig`                           | Configuration (SSL, auth, A2A-T, negotiation rounds, custom handlers) |
| `AuthProvider`                                         | Custom authentication                                                 |
| `ExtensionHandler`                                     | Custom extension handler                                              |
| `EventCallback` / `EventType`                          | Event callback                                                        |
| `LoadPsop` / `RegistryClient`                          | Workflow loading / AgentCard fetching                                 |
| `Workflow` / `WorkflowStep` / `Task` / `JumpCondition` | Workflow definition                                                   |
| `ExecutionResult`                                      | Execution result                                                      |
| `SendMessageResult` / `TaskResponse`                   | Message/task response                                                 |
