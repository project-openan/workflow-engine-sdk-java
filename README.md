# a2at-engine-java

Standalone workflow execution SDK for A2A-T multi-agent orchestration (Java).

This is the Java equivalent of the Python [a2at-engine](https://github.com/project-openan/workflow-exec-engine) SDK.
Feature parity is maintained across both SDKs.

## Architecture

Three layers, same as the Python version:

| Layer | Entry Point | What It Handles |
|-------|------------|-----------------|
| 2 (high) | `ExecutePsop.execute()` | Event stream, lifecycle, on_finish hook |
| 1 (mid) | `WorkflowExecutor` | DAG traversal, context assembly, ControlPoint dispatch |
| 0 (low) | `WorkflowEngineClient` | A2A send, auth, extensions, SSL, SSE normalization |

## Feature Parity with Python SDK

All Python SDK modules have Java equivalents:

| Python module | Java equivalent | Purpose |
|---|---|---|
| `client/engine_client.py` | `client/DefaultWorkflowEngineClient` | A2A message send, streaming, text extraction |
| `client/agentcard_normalizer.py` | `client/AgentCardNormalizer` | OpenAPI -> structured security scheme normalization |
| `client/ssl_context.py` | `client/SslContextFactory` | Outbound TLS context with CA trust store |
| `client/credential_service.py` | `client/AgentCredentialService` | Bearer token login + TTL cache |
| `client/auth_manager.py` | `client/AgentAuthManager` | Interceptor builder from AgentCard securitySchemes |
| `client/extension_interceptor.py` | `client/ExtensionInterceptor` | A2A-Extensions HTTP header injection |
| `client/extension_handlers.py` | `client/ExtensionRegistry` + 4 handler classes | Task-T, Negotiation-T, Authorization-T, Notification-T |
| `client/sse_normalization.py` | `client/SseNormalization` | Non-standard SSE response coercion |
| `control/control_points.py` | `control/ControlPoint` + `EventCallback` | User decision interface |
| `core/executor.py` | `core/WorkflowExecutor` | DAG traversal + step execution |
| `core/context_builder.py` | `core/ContextBuilder` | Upstream context assembly |
| `registry/registry_client.py` | `registry/RegistryClient` + `LoadPsop` | AgentCard + PSOP fetch |
| `runner.py` | `runner/ExecutePsop` | High-level runner with event stream |

## Dependencies

- `org.a2aproject.sdk:a2a-java-sdk-client` -- A2A protocol (AgentCard, Client, MessageSendParams)
- `net.openan.a2at.sdk:a2a-t-client` -- A2A-T extensions (A2ATClient, Task-T, Negotiation-T)
- Jackson, SLF4J, Lombok

## Quick Start

```java
import com.openan.a2at.engine.*;
import com.openan.a2at.engine.client.*;
import com.openan.a2at.engine.control.*;
import com.openan.a2at.engine.model.*;
import com.openan.a2at.engine.runner.*;
import com.openan.a2at.engine.registry.*;
import java.util.concurrent.*;

// 1. Get AgentCards from registry
RegistryClient registry = new RegistryClient("https://127.0.0.1:5000", false);
List<Map<String, Object>> agentCards = registry.fetchAgentCards();

// 2. Load workflow
Workflow workflow = LoadPsop.load("https://127.0.0.1:5001", "psop-id", "token", false);

// 3. Implement ControlPoint (only on_task + on_route required)
ControlPoint cp = new ControlPoint() {
    @Override
    public CompletableFuture<TaskResponse> onTask(TaskRequest request, WorkflowEngineClient client) {
        return client.sendMessage(request.getAgentName(), request.getMessage())
                .thenApply(result -> TaskResponse.builder().success(true).output(result.getText()).build());
    }
    @Override
    public CompletableFuture<RouteDecision> onRoute(String step, Map<String, Object> results, List<JumpCondition> conditions) {
        return CompletableFuture.completedFuture(RouteDecision.builder().nextStep(conditions.get(0).getStep()).build());
    }
};

// 4. Execute with full config (SSL, auth, A2A-T)
ExecutePsop.execute(
    workflow, agentCards, cp, null,
    "Diagnose fault", "zh",
    ".env",                              // a2atEnvPath (for Task-T prompt generation)
    "etc/conf/agent_credentials.json",   // credentialsConfigPath
    false,                               // sslVerify
    null,                                // caCertsPath
    null,                                // a2aClientRuntime (null = raw HTTP fallback)
    new EventCallback(),
    (result, events) -> { System.out.println("Done: " + result.isSuccess()); },
    null
).join();
```

## Package Structure

```text
src/main/java/com/openan/a2at/engine/
|-- model/          # Workflow, Task, JumpCondition, StepType, etc.
|-- control/        # ControlPoint, EventType, EventCallback
|-- core/           # WorkflowExecutor, ContextBuilder
|-- client/         # DefaultWorkflowEngineClient + WorkflowEngineClient interface
|   |-- AgentCardNormalizer     # OpenAPI -> structured security scheme
|   |-- SslContextFactory       # Outbound TLS context
|   |-- AgentCredentialService  # Bearer token login + cache
|   |-- AgentAuthManager         # Interceptor builder from securitySchemes
|   |-- CustomAuthInterceptor   # Non-Bearer header support
|   |-- ExtensionInterceptor    # A2A-Extensions header injection
|   |-- ExtensionRegistry       # Task-T / Negotiation-T / Auth-T / Notification-T
|   |-- ExtensionHandler        # Extension handler interface
|   |-- TaskTHandler            # Task-T prompt generation
|   |-- NegotiationTHandler     # Negotiation-T receive/continue
|   |-- AuthorizationTHandler   # Authorization-T user decision
|   |-- NotificationTHandler    # Notification-T push handling
|   |-- SseNormalization        # Non-standard SSE response coercion
|   `-- WorkflowEngineClientConfig  # SSL + auth + A2A-T config builder
|-- runner/         # ExecutePsop (high-level runner)
`-- registry/       # RegistryClient, LoadPsop
```

## Maven

```xml
<dependency>
    <groupId>com.openan.a2at</groupId>
    <artifactId>a2at-engine-java</artifactId>
    <version>0.3.0</version>
</dependency>
```

## License

Apache License 2.0
