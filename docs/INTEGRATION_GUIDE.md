# A2A-T Workflow Execution Engine - Integration Guide

## 1. Overview

The A2A-T Workflow Execution Engine (artifact: `a2at-engine`) is a Java SDK
that orchestrates multi-agent workflows using the A2A protocol with A2A-T
telecommunication extensions. It sits between your application and the
underlying `a2a-java-sdk` + `a2a-t-sdk-java`, handling:

- A2A message send/receive via SSE streaming
- Task-T structured prompt generation (LLM + template, via a2a-t-sdk)
- Negotiation-T automatic negotiation loop
- Authorization-T pre-positioning (whitelist strategy)
- Notification-T pre-positioning (result subscription)
- AgentCard resolution, auth token management, HTTPS/TLS
- PSOP workflow execution with step routing and context assembly

The engine is designed for **secondary development**: you implement
business decisions via `ControlPoint`, and the engine handles all protocol
mechanics.

## 2. Prerequisites

| Requirement | Version |
|---|---|
| JDK | 17+ |
| Maven | 3.6+ |
| a2a-java-sdk | 1.0.0.Beta1 |
| a2a-t-sdk-java | 1.0.0 |
| Jackson | 2.20.1 |
| SLF4J + Log4j2 | 2.0.17 / 2.24.3 |

## 3. Maven Dependency

```xml
<dependency>
    <groupId>com.openan.a2at</groupId>
    <artifactId>a2at-engine</artifactId>
    <version>0.3.0</version>
</dependency>
```

If you build from source:

```bash
mvn -o clean install -DskipTests
```

## 4. Quick Start

### 4.1 Define a Workflow (PSOP)

```java
Workflow workflow = Workflow.builder()
    .id("my-workflow")
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
RegistryClient registry = new RegistryClient(
    "https://127.0.0.1:5001", false);
List<Map<String, Object>> cards = registry.fetchAgentCards();
```

### 4.3 Implement ControlPoint

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
        // Your routing logic
        return CompletableFuture.completedFuture(
            RouteDecision.builder()
                .nextStep(conditions.get(0).getStep())
                .build());
    }

    @Override
    public CompletableFuture<String> onNegotiation(
            String agentName, String negotiationText,
            Map<String, Object> receiveResult) {
        // Provide clarification text
        return CompletableFuture.completedFuture(
            "Please proceed with available information.");
    }
}
```

### 4.4 Execute

```java
CompletableFuture<ExecutionResult> future = ExecutePsop.builder()
    .psop(workflow)
    .agentCards(List.of(card1, card2))
    .controlPoint(new MyControlPoint())
    .runtimeIntent("SPN cross-city fault diagnosis")
    .lang("zh")
    .a2atEnvPath(".env")           // A2A-T SDK config
    .credentialsConfigPath("credentials.json")
    .sslVerify(false)              // false for self-signed certs
    .onFinish((result, history) -> {
        System.out.println("Success: " + result.isSuccess());
    })
    .execute();

ExecutionResult result = future.get(10, TimeUnit.MINUTES);
```

## 5. Configuration

### 5.1 .env File (A2A-T SDK)

The `.env` file configures the A2A-T SDK's LLM and prompt runtime. The
engine loads it via `EnvFileLoader` and sets entries as system properties.

```ini
# Language
A2AT_LANGUAGE=zh-CN

# LLM (OpenAI-compatible providers)
A2AT_LLM_PROVIDER=openai
A2AT_LLM_MODEL=deepseek-v4-flash
A2AT_LLM_API_KEY=sk-xxxxxxxxxxxxxxxx
A2AT_LLM_BASE_URL=https://api.deepseek.com
A2AT_LLM_MAX_TOKENS=2000
A2AT_LLM_TEMPERATURE=0
A2AT_LLM_TIMEOUT_SECONDS=60

# Prompt
A2AT_PROMPT_SOURCE_TYPE=classpath
A2AT_PROMPT_COMPLIANCE_ENABLED=false

# Credential encryption key (32-byte hex)
A2AT_CRED_KEY=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
```

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

- Encrypted passwords use `enc:<iv>:<ciphertext>` format (AES-GCM)
- The key is read from `A2AT_CRED_KEY` (env var or system property)
- Plaintext values (no `enc:` prefix) are also accepted
- Tokens are cached with TTL and refreshed automatically

### 5.3 Custom Auth Provider

When AgentCard has no securitySchemes or uses a non-standard auth mechanism:

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
    .authProvider((agentName, agentCard, headers) -> {
        String token = mySsoClient.getToken(agentName);
        headers.put("Authorization", "Bearer " + token);
        headers.put("X-Custom-Header", "custom-value");
    })
    .sslVerify(false)
    .a2atEnvPath(".env")
    .build();
```

The `AuthProvider` is called for every message send. If both a credentials
file and `AuthProvider` are configured, both run (custom provider first,
credentials-based auth second).

## 6. AgentCard Definition

AgentCards declare extensions via the `capabilities.extensions` array:

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

Extension URIs must match the a2a-t-sdk definitions exactly.

## 7. A2A-T Extension Integration

The engine handles four A2A-T extensions:

### Task-T

**Automatic.** Before sending a message to an agent whose AgentCard declares
Task-T, the engine calls `a2atClient.generateTaskPrompt()` to produce a
structured prompt. The prompt text is placed in `message.metadata` under the
Task-T URI key, and the `A2A-Extensions` header advertises `Task-T/v1`.

No user code required.

### Negotiation-T

**Automatic.** When an agent responds with `INPUT_REQUIRED` state, the
engine extracts the negotiation text from the response metadata and calls
`ControlPoint.onNegotiation()` for the user's clarification. The
clarification is sent back as a follow-up message with the Negotiation-T
metadata. This auto-loops up to `maxNegotiationRounds` (default 3).

Override `onNegotiation()` in your `ControlPoint` to provide
business-specific clarifications.

### Authorization-T

**Pre-positioning.** Before the workflow starts, the workbench agent
sends a whitelist authorization strategy to each SPN agent via
`sendExtensionMessage()`. The SPN agent stores it and compares
subsequent operations against the whitelist.

```java
engineClient.sendExtensionMessage(
    "SPN Domain Agent",
    "Authorization-T pre-positioning",
    "Task type: new authorization, operation: service recovery, ...",
    "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Authorization-T/v1"
);
```

### Notification-T

**Pre-positioning.** Before the workflow starts, the workbench agent
subscribes to result notifications from each SPN agent via
`sendExtensionMessage()`. The SPN agent reports recovery results back
through the notification channel.

```java
engineClient.sendExtensionMessage(
    "SPN Domain Agent",
    "Notification-T subscription",
    "Topic: service-recovery-execution-result, ...",
    "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Notification-T/v1"
);
```

## 8. HTTPS/TLS Configuration

```java
// Disable verification (dev/test with self-signed certs)
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
    .sslVerify(false)
    .build();

// Enable verification with custom CA certs
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
    .sslVerify(true)
    .caCertsPath("/path/to/ca-certs.pem")
    .build();
```

When `sslVerify=false`, a trust-all SSL context is used. This is suitable
for development only.

## 9. Logging

The engine uses SLF4J with a dedicated `PROTOCOL` logger for protocol-level
message dumps. Configure in `log4j2.properties`:

```properties
# Protocol-level request/response dumps
logger.PROTOCOL.name = PROTOCOL
logger.PROTOCOL.level = info
logger.PROTOCOL.additivity = false
logger.PROTOCOL.appenderRef = console

# Engine client
logger.engine.name = com.openan.a2at.engine.client
logger.engine.level = debug

# Protocol logger appender
appender.console.type = Console
appender.console.name = console
appender.console.layout.type = PatternLayout
appender.console.layout.pattern = %d{HH:mm:ss.SSS} %-5level [%t] %logger{20} - %msg{nolookups}%n
```

Set `logger.PROTOCOL.level = debug` to see full request/response bodies
including headers.

## 10. Custom Extension Handlers

To extend the engine with custom A2A-T extensions:

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
        // Add custom metadata before sending
        metadata.put("https://example.com/extensions/My-Extension/v1",
            "custom value");
        return CompletableFuture.completedFuture(metadata);
    }

    @Override
    public CompletableFuture<SendMessageResult> afterReceive(
            AgentCard agentCard, SendMessageResult result,
            A2ATClient a2atClient, ControlPoint controlPoint,
            EventCallback eventCallback) {
        // Process response metadata
        return CompletableFuture.completedFuture(result);
    }
}
```

Register via config:

```java
WorkflowEngineClientConfig config = WorkflowEngineClientConfig.builder()
    .customHandlers(List.of(new MyExtensionHandler()))
    .build();
```

## 11. Protocol Message Format

The engine follows the A2A-T protocol. Request messages have:

- `parts[].text`: Short natural-language message
- `metadata[extension-uri]`: Structured extension content
- `A2A-Extensions` header: Comma-separated list of active extension URIs
- `Authorization` header: Bearer token (when auth is configured)

Response messages (from agents) should place:

- Short summary in `artifact.parts[].text`
- Full extension content in `artifact.metadata[extension-uri]`
- Negotiation text in `status.metadata[NEGOTIATION-T]`
- Negotiation context in `status.metadata[DATA-NEGOTIATION-T/v1]`

## 12. Event Callback

Subscribe to execution events for real-time monitoring:

```java
EventCallback callback = new EventCallback() {
    @Override
    public void onEvent(String eventType, Map<String, Object> data) {
        switch (eventType) {
        case EventType.STEP_START:
            System.out.println("Step started: " + data.get("step"));
            break;
        case EventType.AGENT_STATUS_UPDATE:
            System.out.println("Agent " + data.get("agent")
                + " state: " + data.get("state"));
            break;
        case EventType.NEGOTIATION_REQUEST:
            System.out.println("Negotiation from " + data.get("agent"));
            break;
        case EventType.COMPLETE:
            System.out.println("Workflow complete");
            break;
        }
    }
};

ExecutePsop.builder()
    .eventCallback(callback)
    // ...
```

Event types are defined in `EventType` constants.

## 13. Workflow Loading from Orchestration Center

Load PSOP workflows from a remote orchestration center:

```java
// Search by intent
List<WorkflowSearchResult> results = LoadPsop.search(
    "https://127.0.0.1:5001",
    "SPN cross-city fault diagnosis",
    5,      // topN
    null,   // access token
    false   // ssl verify
);

// Load full workflow by ID
Workflow workflow = LoadPsop.load(
    "https://127.0.0.1:5001",
    results.get(0).getWorkflowId(),
    null,   // access token
    false   // ssl verify
);
```

## 14. Architecture

```
com.openan.a2at.engine
  +-- client          A2A message transport, auth, extensions
  +-- control         User decision points (ControlPoint, events)
  +-- core            Internal workflow execution (package-private)
  +-- model           Data models (Workflow, Task, results)
  +-- registry        PSOP loading + AgentCard registry
  +-- runner          ExecutePsop (entry point)
```

**Public API surface** (user-facing):
- `WorkflowEngineClient` / `WorkflowEngineClientConfig`
- `ControlPoint` / `DefaultControlPoint`
- `ExecutePsop.Builder`
- `AuthProvider`
- `ExtensionHandler`
- `LoadPsop` / `RegistryClient`
- `EventCallback` / `EventType`
- Model classes (`Workflow`, `Task`, `ExecutionResult`, etc.)

**Internal** (package-private, not for direct use):
- `DefaultWorkflowEngineClient`, `DefaultA2AJavaClientRuntime`
- `TaskTHandler`, `NegotiationTHandler`, `ExtensionInterceptor`
- `AgentAuthManager`, `AgentCredentialService`, `CredentialCrypto`
- `ContextBuilder`, `WorkflowExecutor`
- `ProtocolLogger`, `SslContextFactory`, `EnvFileLoader`
