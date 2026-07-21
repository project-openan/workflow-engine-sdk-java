package com.openan.a2at.engine.examples;

import com.openan.a2at.engine.client.WorkflowEngineClient;
import com.openan.a2at.engine.control.ControlPoint;
import com.openan.a2at.engine.control.DefaultControlPoint;
import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.control.EventType;
import com.openan.a2at.engine.model.ExecutionResult;
import com.openan.a2at.engine.model.JumpCondition;
import com.openan.a2at.engine.model.RouteDecision;
import com.openan.a2at.engine.model.TaskRequest;
import com.openan.a2at.engine.model.TaskResponse;
import com.openan.a2at.engine.model.Workflow;
import com.openan.a2at.engine.runner.ExecutePsop;
import com.openan.a2at.engine.registry.RegistryClient;
import com.openan.a2at.engine.registry.LoadPsop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * End-to-end demo: fetch AgentCards, load a workflow, implement ControlPoint,
 * execute via ExecutePsop, print events.
 *
 * Prerequisites:
 * 1. Registry center running at https://127.0.0.1:5000
 * 2. Orchestration center running at https://127.0.0.1:5001
 * 3. a2a-java-sdk client runtime configured
 *
 * Usage:
 *   java -cp ... com.openan.a2at.engine.examples.ExecutePsopDemo
 */
public class ExecutePsopDemo {
    private static final Logger log = LoggerFactory.getLogger(ExecutePsopDemo.class);

    public static void main(String[] args) {
        try {
            new ExecutePsopDemo().run(args);
        } catch (Exception e) {
            log.error("Demo failed", e);
            System.exit(1);
        }
    }

    public void run(String[] args) throws Exception {
        // --- Config (parse from args or use defaults) ---
        String registryUrl = args.length > 0 ? args[0] : "https://127.0.0.1:5000";
        String orchUrl = args.length > 1 ? args[1] : "https://127.0.0.1:5001";
        String psopId = args.length > 2 ? args[2] : "psop_cross_city_fault";
        String accessToken = args.length > 3 ? args[3] : null;
        boolean sslVerify = false; // dev: self-signed certs

        log.info("=== a2at-engine Demo ===");
        log.info("Registry: {}", registryUrl);
        log.info("Orchestration: {}", orchUrl);
        log.info("PSOP ID: {}", psopId);

        // 1. Fetch AgentCards from registry
        log.info("--- Step 1: Fetch AgentCards ---");
        RegistryClient registry = new RegistryClient(registryUrl, sslVerify);
        List<Map<String, Object>> agentCards = registry.fetchAgentCards();
        log.info("Got {} agent card(s): {}", agentCards.size(),
                agentCards.stream().map(c -> c.get("name")).toList());

        // 2. Load workflow from orchestration center
        log.info("--- Step 2: Load Workflow ---");
        Workflow workflow = LoadPsop.load(orchUrl, psopId, accessToken, sslVerify);
        log.info("Workflow: {} ({} steps)", workflow.getName(), workflow.getSteps().size());

        // 3. Implement ControlPoint
        log.info("--- Step 3: Implement ControlPoint ---");
        // Use DefaultControlPoint: auto-send, first-branch routing, auto-approve auth
        // Override methods to customize: new DefaultControlPoint() { @Override ... }
        ControlPoint controlPoint = new DefaultControlPoint();

        // 4. Create A2A client runtime
        // In production, use the a2a-java-sdk to create a real runtime:
        //   A2AJavaClientRuntime runtime = new DefaultSampleClientRuntime(...);
        // For this demo, pass null (the SDK will auto-create a basic client)
        Object a2aRuntime = null;

        // 5. Execute
        log.info("--- Step 4: Execute ---");
        EventCallback eventCallback = new EventCallback() {
            @Override
            public void onEvent(String type, Map<String, Object> data) {
                switch (type) {
                    case EventType.START -> log.info("  [START] workflow={}", data.get("workflow"));
                    case EventType.STEP_START -> log.info("  -> Step: {}", data.get("step"));
                    case EventType.TASK_REQUEST -> log.info("     Agent: {}", data.get("agent"));
                    case EventType.TASK_RESPONSE -> {
                        String output = String.valueOf(data.getOrDefault("output", ""));
                        log.info("     Response: {}", output.substring(0, Math.min(80, output.length())));
                    }
                    case EventType.ROUTE_DECISION -> log.info("     Route: {} -> {}", data.get("step"), data.get("next"));
                    case EventType.STEP_COMPLETE -> log.info("  <- Step done: {}", data.get("step"));
                    case EventType.COMPLETE -> log.info("  [COMPLETE] tasks={}",
                            ((List<?>) data.getOrDefault("history", List.of())).size());
                    case EventType.ERROR -> log.error("  [ERROR] {}", data.get("error"));
                    case EventType.CLOSE -> log.info("  [CLOSE]");
                    default -> {} // negotiation, auth, notification events
                }
            }
        };

        // on_finish: print summary
        var onFinishRef = new Object() {
            java.util.function.BiFunction<ExecutionResult, List<Map<String, Object>>, java.util.concurrent.CompletableFuture<Void>> fn =
                (result, events) -> {
                    log.info("--- on_finish ---");
                    log.info("Success: {}", result.isSuccess());
                    log.info("History: {} task(s)", result.getHistory() != null ? result.getHistory().size() : 0);
                    log.info("Events: {} total", events.size());
                    if (result.getError() != null) {
                        log.info("Error: {}", result.getError());
                    }
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                };
        };

        log.info("Starting execution...");
        ExecutionResult result = ExecutePsop.execute(
                workflow,
                agentCards,
                controlPoint,
                null,              // engineClient (null = auto-create)
                "Diagnose SPN cross-city fault",
                "zh",
                null,              // a2atEnvPath (null = no Task-T prompt generation)
                null,              // credentialsConfigPath (null = no agent auth)
                sslVerify,         // sslVerify
                null,              // caCertsPath
                a2aRuntime,        // A2A client runtime
                eventCallback,     // event sink
                onFinishRef.fn,   // on_finish persistence hook
                null               // on_event transformer
        ).join();

        log.info("=== Demo finished: success={} ===", result.isSuccess());
    }

    /**
     * Demo ControlPoint: auto-send to agents, pick first branch on route.
     */
    static class DemoControlPoint implements ControlPoint {

        @Override
        public CompletableFuture<TaskResponse> onTask(
                TaskRequest request, WorkflowEngineClient engineClient) {
            log.info("  [on_task] agent={}, task={}", request.getAgentName(),
                    request.getDescription().substring(0,
                            Math.min(60, request.getDescription().length())));

            return engineClient.sendMessage(request.getAgentName(), request.getMessage())
                    .thenApply(result -> {
                        log.info("  [on_task] response from {}: {} chars",
                                request.getAgentName(),
                                result.getText() != null ? result.getText().length() : 0);
                        return TaskResponse.builder()
                                .success(true)
                                .output(result.getText())
                                .build();
                    })
                    .exceptionally(e -> {
                        log.error("  [on_task] failed: {}", e.getMessage());
                        return TaskResponse.builder()
                                .success(false)
                                .error("Agent call failed: " + e.getMessage())
                                .build();
                    });
        }

        @Override
        public CompletableFuture<RouteDecision> onRoute(
                String stepName, Map<String, Object> results,
                List<JumpCondition> conditions) {
            // Demo: pick the first available branch
            // In production: use your own LLM or business logic
            String nextStep = conditions.get(0).getStep();
            log.info("  [on_route] step={}, picking first branch: {}", stepName, nextStep);
            return CompletableFuture.completedFuture(
                    RouteDecision.builder()
                            .nextStep(nextStep)
                            .reason("demo: picked first branch")
                            .build());
        }

        @Override
        public CompletableFuture<Boolean> onAuthorization(
                String agentName, Map<String, Object> authRequest) {
            log.info("  [on_authorization] agent={}, auto-approving", agentName);
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Void> onNotification(
                String agentName, Map<String, Object> notification) {
            log.info("  [on_notification] agent={}: {}", agentName, notification);
            return CompletableFuture.completedFuture(null);
        }
    }
}
