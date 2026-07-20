# a2at-engine-java

Standalone workflow execution SDK for A2A-T multi-agent orchestration (Java).

This is the Java equivalent of the Python [a2at-engine](https://github.com/project-openan/workflow-exec-engine) SDK.

## Architecture

Three layers, same as the Python version:

| Layer | Entry Point | What It Handles |
|-------|------------|-----------------|
| 2 (high) | `ExecutePsop.execute()` | Event stream, lifecycle, on_finish hook |
| 1 (mid) | `WorkflowExecutor` | DAG traversal, context assembly, ControlPoint dispatch |
| 0 (low) | `WorkflowEngineClient` | A2A send, auth, extensions |

## Dependencies

- `org.a2aproject.sdk:a2a-java-sdk-client` — A2A protocol (AgentCard, ClientEvent, MessageSendParams)
- `net.openan.a2at.sdk:a2a-t-client` — A2A-T extensions (A2ATClient, Task-T, Negotiation-T)
- Jackson, SLF4J, Lombok

## Quick Start

```java
import com.openan.a2at.engine.*;
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

// 4. Execute
ExecutePsop.execute(
    workflow, agentCards, cp, null,
    "Diagnose fault", "zh",
    a2aClientRuntime,  // from a2a-java-sdk
    new EventCallback(),  // event sink
    (result, events) -> { System.out.println("Done: " + result.isSuccess()); },  // on_finish
    null  // on_event transformer
).join();
```

## Package Structure

```
src/main/java/com/openan/a2at/engine/
├── model/          # Workflow, Task, JumpCondition, etc.
├── control/        # ControlPoint, EventType, EventCallback
├── core/           # WorkflowExecutor, ContextBuilder
├── client/         # WorkflowEngineClient (interface) + DefaultWorkflowEngineClient
├── runner/         # ExecutePsop (high-level runner)
└── registry/       # RegistryClient, LoadPsop
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
