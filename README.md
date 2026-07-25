# a2at-engine-java

Standalone workflow execution SDK for A2A-T multi-agent orchestration (Java).

This is the Java equivalent of the Python [a2at-engine](https://github.com/project-openan/workflow-exec-engine) SDK.
Feature parity is maintained across both SDKs.

## Architecture

Three layers, same as the Python version:

| Layer | Entry Point | What It Handles |
|-------|------------|-----------------|
| 2 (high) | `ExecutePsop.Builder` | Event stream, lifecycle, onFinish hook |
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
| `client/extension_interceptor.py` | `client/ExtensionInterceptor` | A2A-Extensions HTTP header injection (metadata-aware) |
| `client/extension_handlers.py` | `client/ExtensionRegistry` + handler classes | Task-T, Negotiation-T |
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

## Documentation

- [Integration Guide](docs/INTEGRATION_GUIDE.md) -- Setup, configuration, secondary development
- [API Reference](docs/API_REFERENCE.md) -- Public interface and class documentation
- [Developer Guide](DEVELOPER_GUIDE.md) -- Internal architecture and contribution guide

## Quick Start

```java
import com.openan.a2at.engine.*;
import com.openan.a2at.engine.client.*;
import com.openan.a2at.engine.control.*;
import com.openan.a2at.engine.model.*;
import com.openan.a2at.engine.runner.*;
import com.openan.a2at.engine.registry.*;
import java.util.concurrent.*;

// 1. Define or load a workflow (PSOP)
Workflow workflow = LoadPsop.load("https://127.0.0.1:5001", "psop-id", null, false);

// 2. Load AgentCards from registry or JSON files
RegistryClient registry = new RegistryClient("https://127.0.0.1:5001", false);
List<Map<String, Object>> cardMaps = registry.fetchAgentCards();

// 3. Implement ControlPoint (only onTask + onRoute required)
ControlPoint cp = new DefaultControlPoint() {
    @Override
    public CompletableFuture<TaskResponse> onTask(TaskRequest request, WorkflowEngineClient client) {
        return client.sendMessage(request.getAgentName(), request.getMessage())
                .thenApply(r -> TaskResponse.builder().success(true).output(r.getText()).build());
    }
};

// 4. Execute via Builder
ExecutionResult result = ExecutePsop.builder()
    .psop(workflow)
    .agentCards(agentCards)
    .controlPoint(cp)
    .runtimeIntent("Diagnose fault")
    .lang("zh")
    .a2atEnvPath(".env")
    .credentialsConfigPath("creds.json")
    .sslVerify(false)
    .onFinish((r, history) -> System.out.println("Done: " + r.isSuccess()))
    .execute()
    .get(10, TimeUnit.MINUTES);
```

## Package Structure

```text
src/main/java/com/openan/a2at/engine/
|-- model/          # Workflow, Task, JumpCondition, StepType, etc.
|-- control/        # ControlPoint, EventType, EventCallback
|-- core/           # WorkflowExecutor, ContextBuilder (package-private)
|-- client/         # DefaultWorkflowEngineClient + WorkflowEngineClient interface
|   |-- AgentAuthManager        # Interceptor builder from securitySchemes
|   |-- AgentCredentialService  # Bearer token login + cache
|   |-- AuthProvider            # Custom auth provider interface
|   |-- ExtensionInterceptor    # A2A-Extensions header injection (metadata-aware)
|   |-- ExtensionRegistry       # Task-T / Negotiation-T handler registry
|   |-- ExtensionHandler        # Extension handler interface (for custom extensions)
|   |-- TaskTHandler            # Task-T prompt generation (via a2a-t-sdk)
|   |-- NegotiationTHandler     # Negotiation-T receive/auto-loop
|   |-- ProtocolLogger          # Protocol-level request/response logging
|   |-- EnvFileLoader           # .env file to system properties bridge
|   |-- SslContextFactory       # Outbound TLS context (trust-all for dev)
|   `-- WorkflowEngineClientConfig  # Builder config: SSL + auth + A2A-T
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
