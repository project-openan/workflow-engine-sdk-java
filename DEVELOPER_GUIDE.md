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

```java
// Get AgentCards
RegistryClient registry = new RegistryClient("https://127.0.0.1:5000", false);
List<Map<String, Object>> agentCards = registry.fetchAgentCards();

// Load workflow
Workflow workflow = LoadPsop.load("https://127.0.0.1:5001", "psop-id", "token", false);

// Create A2A client runtime (from a2a-java-sdk)
Object a2aRuntime = ...; // your A2AJavaClientRuntime instance

// Execute with event collection + persistence hook
CompletableFuture<ExecutionResult> future = ExecutePsop.execute(
    workflow,
    agentCards,
    new MyControlPoint(),
    null,                    // engineClient (null = auto-create)
    "Diagnose SPN fault",    // runtimeIntent
    "zh",                    // lang
    a2aRuntime,              // A2A client runtime
    new EventCallback() {    // event callback
        @Override
        public void onEvent(String type, Map<String, Object> data) {
            System.out.println("[" + type + "] " + data);
        }
    },
    (result, events) -> {    // on_finish: persist result
        if (result.isSuccess()) {
            saveToDatabase(result.getHistory(), result.getStepOutputs());
        }
    },
    null                     // on_event transformer (null = pass through)
);

ExecutionResult result = future.join();
```

## 5. Event Types

| Event | When | Key Data |
|-------|------|----------|
| `start` | Workflow begins | `workflow`, `steps` |
| `step_start` | Step begins | `step` |
| `agent_request` | Message sent to agent | `agent`, `request`, `metadata` |
| `agent_response` | Response from agent | `agent`, `response` |
| `task_status_changed` | Task status updated | `step`, `subtask_index`, `agent`, `status` |
| `route_decision` | Branch chosen | `step`, `next`, `reason` |
| `negotiation_request` | Agent needs clarification | `agent`, `round`, `concern` |
| `negotiation_resolved` | Clarification provided | `agent`, `round`, `clarification` |
| `complete` | Workflow succeeded | `history`, `step_outputs` |
| `error` | Workflow failed | `error`, `history` |
| `close` | Cleanup done | (empty) |

Compare with `EventType.STEP_START` etc.

## 6. Mid-Level (Layer 1: WorkflowExecutor)

```java
try (var client = new DefaultWorkflowEngineClient(agentCards, a2aRuntime)) {
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

## 7. Agent Authentication

When AgentCards declare `securitySchemes`, the SDK attaches auth headers.
Configure via JSON file:

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

Pass to the SDK via the `a2aClientRuntime` which handles auth interceptor setup.

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
            intent, "zh", a2aRuntime,
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

## 9. Checklist

1. Add Maven dependencies
2. Implement `ControlPoint` (on_task + on_route)
3. Get AgentCards (from registry or custom source)
4. Load Workflow (via `LoadPsop` or build your own)
5. Create A2A client runtime (from a2a-java-sdk)
6. Call `ExecutePsop.execute()`
7. Handle events + on_finish persistence
