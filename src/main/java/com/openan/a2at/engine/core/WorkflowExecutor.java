package com.openan.a2at.engine.core;

import com.openan.a2at.engine.control.ControlPoint;
import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.control.EventType;
import com.openan.a2at.engine.model.ExecutionResult;
import com.openan.a2at.engine.model.JumpCondition;
import com.openan.a2at.engine.model.StepType;
import com.openan.a2at.engine.model.Task;
import com.openan.a2at.engine.model.TaskRequest;
import com.openan.a2at.engine.model.TaskStatus;
import com.openan.a2at.engine.model.Workflow;
import com.openan.a2at.engine.model.WorkflowStep;
import com.openan.a2at.engine.client.WorkflowEngineClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Main entry point. Traverses DAG, calls ControlPoint at decision points.
 * Mirrors Python WorkflowExecutor.
 */
public class WorkflowExecutor {
    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutor.class);

    private final Workflow workflow;
    private final ControlPoint controlPoint;
    private final WorkflowEngineClient engineClient;
    private final EventCallback eventCallback;
    private final ContextBuilder contextBuilder;
    private final String lang;
    private final Map<String, Map<String, Object>> stepOutputs = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> executionHistory = Collections.synchronizedList(new ArrayList<>());

    public WorkflowExecutor(Workflow workflow, ControlPoint controlPoint,
                            WorkflowEngineClient engineClient,
                            EventCallback eventCallback,
                            String runtimeIntent, String lang) {
        this.workflow = workflow;
        this.controlPoint = controlPoint;
        this.engineClient = engineClient;
        this.eventCallback = eventCallback != null ? eventCallback : new EventCallback();
        this.contextBuilder = new ContextBuilder(workflow, runtimeIntent);
        this.lang = lang != null ? lang : "zh";
        try {
            this.engineClient.setEventCallback(this.eventCallback);
        } catch (Exception ignored) {
            // Engine client may not support event callback injection
        }
    }

    /**
     * Current step outputs (mutable, updated during execution).
     * Mirrors Python SDK's {@code current_step_outputs} property.
     */
    public Map<String, Map<String, Object>> getCurrentStepOutputs() {
        return new HashMap<>(stepOutputs);
    }

    /**
     * Execution history (mutable, updated during execution).
     * Mirrors Python SDK's {@code history} property.
     */
    public List<Map<String, Object>> getHistory() {
        return new ArrayList<>(executionHistory);
    }

    private void emit(String type, Map<String, Object> data) {
        try {
            eventCallback.onEvent(type, data);
        } catch (Exception e) {
            log.warn("Event callback error: {}", e.getMessage());
        }
    }

    public CompletableFuture<ExecutionResult> run() {
        emit(EventType.START, Map.of("workflow", workflow.getName(), "steps", workflow.getSteps().size()));
        log.info("[Executor] Starting workflow: {} ({} steps)", workflow.getName(), workflow.getSteps().size());

        Deque<Integer> pending = new ArrayDeque<>();
        for (int i = 0; i < workflow.getSteps().size(); i++) {
            var s = workflow.getSteps().get(i);
            if (s.getLayer() == 0 && contextBuilder.getStepPredecessors(s.getName()).isEmpty()) {
                pending.add(i);
            }
        }
        Set<Integer> executed = new HashSet<>();
        boolean[] failed = {false};

        Map<Integer, Integer> deferCount = new HashMap<>();
        return executeSteps(pending, executed, failed, deferCount)
                .thenApply(v -> {
                    emit(EventType.WORKFLOW_COMPLETE, Map.of());
                    log.info("[Executor] Workflow completed: {}, {} task(s) executed", workflow.getName(), executionHistory.size());
                    return ExecutionResult.builder()
                            .success(!failed[0])
                            .history(new ArrayList<>(executionHistory))
                            .stepOutputs(new HashMap<>(stepOutputs))
                            .error(failed[0] ? "Step execution failed" : null)
                            .build();
                });
    }

    private CompletableFuture<Void> executeSteps(
            Deque<Integer> pending, Set<Integer> executed,
            boolean[] failed, Map<Integer, Integer> deferCount) {
        if (pending.isEmpty() || failed[0]) {
            return CompletableFuture.completedFuture(null);
        }
        int idx = pending.pollFirst();
        if (idx >= workflow.getSteps().size() || executed.contains(idx)) {
            return executeSteps(pending, executed, failed, deferCount);
        }
        var step = workflow.getSteps().get(idx);
        var preds = contextBuilder.getStepPredecessors(step.getName());
        if (!preds.stream().allMatch(stepOutputs::containsKey)) {
            int dc = deferCount.getOrDefault(idx, 0) + 1;
            if (dc > workflow.getSteps().size()) {
                log.warn("Step {} deferred too many times, skipping", step.getName());
                executed.add(idx);
                return executeSteps(pending, executed, failed, deferCount);
            }
            deferCount.put(idx, dc);
            pending.addLast(idx);
            return CompletableFuture.completedFuture(null)
                    .thenComposeAsync(v -> executeSteps(pending, executed, failed, deferCount));
        }
        executed.add(idx);
        emit(EventType.STEP_START, Map.of("step", step.getName()));
        log.info("--- Executing step: {} ---", step.getName());

        return executeSubtasks(step)
                .thenCompose(result -> {
                    stepOutputs.put(step.getName(), result.results());
                    if (!result.success()) {
                        log.error("Step {} failed, stopping.", step.getName());
                        emit(EventType.ERROR, Map.of("step", step.getName(), "results", result.results()));
                        failed[0] = true;
                        return CompletableFuture.completedFuture(null);
                    }
                    emit(EventType.STEP_COMPLETE, Map.of("step", step.getName(), "results", result.results()));
                    return determineNextSteps(step, result.results())
                            .thenAccept(nextIndices -> {
                                for (int i = nextIndices.size() - 1; i >= 0; i--) {
                                    int nxt = nextIndices.get(i);
                                    if (!executed.contains(nxt) && !pending.contains(nxt)) {
                                        pending.addFirst(nxt);
                                    }
                                }
                            });
                })
                .thenCompose(v -> executeSteps(pending, executed, failed, deferCount));
    }

    private record StepResult(String taskDesc, Object output, boolean success, Map<String, Object> results) {}

    private CompletableFuture<StepResult> executeSubtasks(WorkflowStep step) {
        String contextMessage = contextBuilder.buildContext(step, stepOutputs);
        List<CompletableFuture<StepResult>> futures = new ArrayList<>();

        for (int i = 0; i < step.getSubtasks().size(); i++) {
            final int subtaskIndex = i;
            final var task = step.getSubtasks().get(i);
            String taskMessage = contextBuilder.buildTaskMessage(task.getDescription(), contextMessage, lang);
            var request = TaskRequest.builder()
                    .agentName(task.getAgent())
                    .skill(task.getSkill())
                    .message(taskMessage)
                    .description(task.getDescription())
                    .context(contextMessage)
                    .stepName(step.getName())
                    .subtaskIndex(subtaskIndex)
                    .build();
            emit(EventType.TASK_REQUEST, Map.of("step", step.getName(), "agent", task.getAgent(), "task", task.getDescription()));
            log.info("[Executor] Dispatching task to agent {}: {}", task.getAgent(), task.getDescription().substring(0, Math.min(80, task.getDescription().length())));

            futures.add(controlPoint.onTask(request, engineClient).thenApply(response -> {
                task.setStatus(response.isSuccess() ? TaskStatus.SUCCESS : TaskStatus.FAILED);
                emit(EventType.TASK_STATUS_CHANGED, Map.of("step", step.getName(), "subtask_index", subtaskIndex, "agent", task.getAgent(), "status", task.getStatus().getValue()));
                String status = response.isSuccess() ? "success" : "failed";
                log.info("[Executor] Task {} -> {}: {}", task.getDescription().substring(0, Math.min(60, task.getDescription().length())), task.getAgent(), status);
                executionHistory.add(Map.of("step", step.getName(), "task", task.getDescription(), "agent", task.getAgent(), "status", status, "output", response.isSuccess() ? response.getOutput() : (response.getError() != null ? response.getError() : "")));
                emit(EventType.TASK_RESPONSE, Map.of("step", step.getName(), "agent", task.getAgent(), "task", task.getDescription(), "output", response.isSuccess() ? response.getOutput() : (response.getError() != null ? response.getError() : "")));
                return new StepResult(task.getDescription(), response.isSuccess() ? response.getOutput() : response.getError(), response.isSuccess(), null);
            }).exceptionally(e -> {
                task.setStatus(TaskStatus.FAILED);
                emit(EventType.TASK_STATUS_CHANGED, Map.of("step", step.getName(), "subtask_index", subtaskIndex, "agent", task.getAgent(), "status", TaskStatus.FAILED.getValue()));
                log.error("[Executor] Task {} -> {}: exception: {}", task.getDescription(), task.getAgent(), e.getMessage());
                executionHistory.add(Map.of("step", step.getName(), "task", task.getDescription(), "agent", task.getAgent(), "status", "failed", "output", e.getMessage()));
                return new StepResult(task.getDescription(), e.getMessage(), false, null);
            }));
        }

        if (step.getStepType() == StepType.ANY_SUCCESS) {
            return CompletableFuture.anyOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(obj -> {
                        @SuppressWarnings("unchecked")
                        var firstResult = (StepResult) obj;
                        Map<String, Object> results = new HashMap<>();
                       for (var f : futures) {
                            if (!f.isDone()) {
                                f.cancel(true);
                            }
                            if (f.isDone() && !f.isCompletedExceptionally()) {
                                try {
                                    var r = f.join();
                                    results.put(r.taskDesc(), r.output());
                                } catch (Exception ignored) {
                                    // Cancellation race, skip
                                }
                            }
                        }
                        return new StepResult(null, null, true, results);
                    });
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    Map<String, Object> results = new HashMap<>();
                    boolean anyFailed = false;
                   for (var f : futures) {
                       var r = f.join();
                       results.put(r.taskDesc(), r.output());
                        if (!r.success()) {
                            anyFailed = true;
                        }
                    }
                    return new StepResult(null, null, !anyFailed, results);
                });
    }

    private CompletableFuture<List<Integer>> determineNextSteps(WorkflowStep step, Map<String, Object> stepResult) {
        if (step.getNext() == null || step.getNext().isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        if (step.getNext().stream().allMatch(jc -> jc.getCondition() == null || jc.getCondition().isEmpty())) {
            List<Integer> indices = new ArrayList<>();
            for (var jc : step.getNext()) {
                if (jc.getStep().equals("end") || jc.getStep().equals("retry") || jc.getStep().equals("endNode")) {
                    continue;
                }
                Integer idx = contextBuilder.findStepIndex(jc.getStep());
                if (idx != null) {
                    indices.add(idx);
                }
            }
            return CompletableFuture.completedFuture(indices);
        }
        return controlPoint.onRoute(step.getName(), stepResult, step.getNext())
                .thenApply(decision -> {
                    log.info("Route for '{}': {} ({})", step.getName(), decision.getNextStep(), decision.getReason());
                    emit(EventType.ROUTE_DECISION, Map.of("step", step.getName(), "next", decision.getNextStep(), "reason", decision.getReason()));
                    Integer idx = contextBuilder.findStepIndex(decision.getNextStep());
                    if (idx == null) {
                        List<String> allowed = step.getNext().stream().map(JumpCondition::getStep).collect(Collectors.toList());
                        log.warn("on_route returned '{}' for step '{}', not in allowed next {}; workflow will end.", decision.getNextStep(), step.getName(), allowed);
                        return List.<Integer>of();
                    }
                    return List.of(idx);
                });
    }
}
