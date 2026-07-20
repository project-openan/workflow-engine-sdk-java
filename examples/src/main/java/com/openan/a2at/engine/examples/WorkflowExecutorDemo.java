package com.openan.a2at.engine.examples;

import com.openan.a2at.engine.client.DefaultWorkflowEngineClient;
import com.openan.a2at.engine.client.WorkflowEngineClientConfig;
import com.openan.a2at.engine.control.ControlPoint;
import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.control.EventType;
import com.openan.a2at.engine.core.WorkflowExecutor;
import com.openan.a2at.engine.model.ExecutionResult;
import com.openan.a2at.engine.model.JumpCondition;
import com.openan.a2at.engine.model.RouteDecision;
import com.openan.a2at.engine.model.TaskRequest;
import com.openan.a2at.engine.model.TaskResponse;
import com.openan.a2at.engine.model.Workflow;
import com.openan.a2at.engine.registry.RegistryClient;
import com.openan.a2at.engine.registry.LoadPsop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Mid-level demo: use WorkflowExecutor directly (Layer 1).
 * More control than ExecutePsop, but you manage lifecycle yourself.
 */
public class WorkflowExecutorDemo {
    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutorDemo.class);

    public static void main(String[] args) throws Exception {
        String registryUrl = "https://127.0.0.1:5000";
        String orchUrl = "https://127.0.0.1:5001";

        // 1. Get AgentCards
        RegistryClient registry = new RegistryClient(registryUrl, false);
        List<Map<String, Object>> agentCards = registry.fetchAgentCards();

        // 2. Load workflow
        Workflow workflow = LoadPsop.load(orchUrl, "psop_cross_city_fault", null, false);

        // 3. Create engine client with config (use try-with-resources for cleanup)
        try (var client = new DefaultWorkflowEngineClient(
                (List) agentCards, null,
                WorkflowEngineClientConfig.builder()
                        .sslVerify(false)
                        .a2atEnvPath(".env")
                        .build())) {
            // 4. Create executor with event callback
            EventCallback callback = new EventCallback() {
                @Override
                public void onEvent(String type, Map<String, Object> data) {
                    log.info("[{}] {}", type, data);
                }
            };

            WorkflowExecutor executor = new WorkflowExecutor(
                    workflow,
                    new DemoControlPoint(),
                    client,
                    callback,
                    "Diagnose SPN fault",
                    "zh");

            // 5. Run
            ExecutionResult result = executor.run().join();

            log.info("Result: success={}, history={} tasks, error={}",
                    result.isSuccess(),
                    result.getHistory() != null ? result.getHistory().size() : 0,
                    result.getError());
        }

        // Note: try-with-resources requires AutoCloseable on DefaultWorkflowEngineClient.
        // If not available, call client.close() manually in a finally block.
    }

    static class DemoControlPoint implements ControlPoint {
        @Override
        public CompletableFuture<TaskResponse> onTask(
                TaskRequest request, com.openan.a2at.engine.client.WorkflowEngineClient engineClient) {
            return engineClient.sendMessage(request.getAgentName(), request.getMessage())
                    .thenApply(r -> TaskResponse.builder().success(true).output(r.getText()).build())
                    .exceptionally(e -> TaskResponse.builder().success(false).error(e.getMessage()).build());
        }

        @Override
        public CompletableFuture<RouteDecision> onRoute(
                String stepName, Map<String, Object> results, List<JumpCondition> conditions) {
            return CompletableFuture.completedFuture(
                    RouteDecision.builder().nextStep(conditions.get(0).getStep()).build());
        }
    }
}
