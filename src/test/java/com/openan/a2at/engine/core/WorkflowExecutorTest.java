package com.openan.a2at.engine.core;

import com.openan.a2at.engine.StubWorkflowEngineClient;
import com.openan.a2at.engine.client.WorkflowEngineClient;
import com.openan.a2at.engine.control.ControlPoint;
import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.control.EventType;
import com.openan.a2at.engine.model.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WorkflowExecutor: DAG traversal, parallel subtasks,
 * ANY_SUCCESS, conditional routing, failure propagation, events.
 */
class WorkflowExecutorTest {

    private final List<String> events = Collections.synchronizedList(new ArrayList<>());

    private EventCallback recordingCallback() {
        events.clear();
        return new EventCallback() {
            @Override
            public void onEvent(String type, Map<String, Object> data) {
                events.add(type);
            }
        };
    }

    private Task task(String agent, String desc) {
        return Task.builder().agent(agent).description(desc).build();
    }

    private JumpCondition jump(String step, String cond) {
        return JumpCondition.builder().step(step).condition(cond).build();
    }

    /** ControlPoint that auto-sends to the stub client and picks the first branch. */
    private ControlPoint autoCp() {
        return new ControlPoint() {
            @Override
            public CompletableFuture<TaskResponse> onTask(TaskRequest request, WorkflowEngineClient engineClient) {
                return engineClient.sendMessage(request.getAgentName(), request.getMessage())
                        .thenApply(r -> TaskResponse.builder().success(true).output(r.getText()).build());
            }

            @Override
            public CompletableFuture<RouteDecision> onRoute(String stepName, Map<String, Object> results, List<JumpCondition> conditions) {
                return CompletableFuture.completedFuture(RouteDecision.builder()
                        .nextStep(conditions.get(0).getStep()).reason("first").build());
            }
        };
    }

    @Test
    void linearWorkflowTwoSteps() {
        WorkflowStep s1 = WorkflowStep.builder().name("s1").layer(0)
                .subtasks(List.of(task("A", "do A")))
                .next(List.of(jump("s2", "")))
                .build();
        WorkflowStep s2 = WorkflowStep.builder().name("s2").layer(1)
                .subtasks(List.of(task("B", "do B")))
                .build();
        Workflow wf = Workflow.builder().name("linear").steps(List.of(s1, s2)).build();
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B");
        WorkflowExecutor exec = new WorkflowExecutor(wf, autoCp(), stub, recordingCallback(), "intent", "zh");

        ExecutionResult result = exec.run().join();

        assertTrue(result.isSuccess());
        assertEquals(2, exec.getHistory().size());
        assertEquals(2, stub.getSentCount());
        assertTrue(events.contains(EventType.STEP_START));
        assertTrue(events.contains(EventType.STEP_COMPLETE));
        assertTrue(events.contains(EventType.WORKFLOW_COMPLETE));
        assertFalse(events.contains(EventType.START));
        assertFalse(events.contains(EventType.COMPLETE));
    }

    @Test
    void parallelFanOutAllUnconditionalNext() {
        // s1 -> s2 (unconditional) and s3 (unconditional)
        WorkflowStep s1 = WorkflowStep.builder().name("s1").layer(0)
                .subtasks(List.of(task("A", "do A")))
                .next(List.of(jump("s2", ""), jump("s3", "")))
                .build();
        WorkflowStep s2 = WorkflowStep.builder().name("s2").layer(1)
                .subtasks(List.of(task("B", "do B")))
                .build();
        WorkflowStep s3 = WorkflowStep.builder().name("s3").layer(1)
                .subtasks(List.of(task("C", "do C")))
                .build();
        Workflow wf = Workflow.builder().name("fanout").steps(List.of(s1, s2, s3)).build();
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B", "C");
        WorkflowExecutor exec = new WorkflowExecutor(wf, autoCp(), stub, recordingCallback(), "", "zh");

        ExecutionResult result = exec.run().join();

        assertTrue(result.isSuccess());
        assertEquals(3, exec.getHistory().size());
        assertEquals(3, stub.getSentCount());
        Set<String> agents = new HashSet<>();
        for (var sm : stub.getSentMessages()) {
            agents.add(sm.agentName);
        }
        assertEquals(Set.of("A", "B", "C"), agents);
    }

    @Test
    void conditionalRouteOnRouteCalled() {
        WorkflowStep s1 = WorkflowStep.builder().name("s1").layer(0)
                .subtasks(List.of(task("A", "do A")))
                .next(List.of(jump("s2", "A ok"), jump("s3", "A fail")))
                .build();
        WorkflowStep s2 = WorkflowStep.builder().name("s2").layer(1)
                .subtasks(List.of(task("B", "do B")))
                .build();
        WorkflowStep s3 = WorkflowStep.builder().name("s3").layer(1)
                .subtasks(List.of(task("C", "do C")))
                .build();
        Workflow wf = Workflow.builder().name("cond").steps(List.of(s1, s2, s3)).build();
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B", "C");
        // Route to s3 (the second branch)
        ControlPoint cp = new ControlPoint() {
            @Override
            public CompletableFuture<TaskResponse> onTask(TaskRequest req, WorkflowEngineClient ec) {
                return ec.sendMessage(req.getAgentName(), req.getMessage())
                        .thenApply(r -> TaskResponse.builder().success(true).output(r.getText()).build());
            }

            @Override
            public CompletableFuture<RouteDecision> onRoute(String stepName, Map<String, Object> results, List<JumpCondition> conditions) {
                return CompletableFuture.completedFuture(RouteDecision.builder()
                        .nextStep("s3").reason("chose s3").build());
            }
        };
        WorkflowExecutor exec = new WorkflowExecutor(wf, cp, stub, recordingCallback(), "", "zh");

        ExecutionResult result = exec.run().join();

        assertTrue(result.isSuccess());
        // s1 -> s3 only (s2 skipped)
        assertEquals(2, stub.getSentCount());
        assertTrue(events.contains(EventType.ROUTE_DECISION));
        assertEquals("C", stub.getSentMessages().get(1).agentName);
    }

    @Test
    void onRouteInvalidStepEndsWorkflow() {
        WorkflowStep s1 = WorkflowStep.builder().name("s1").layer(0)
                .subtasks(List.of(task("A", "do A")))
                .next(List.of(jump("s2", "cond")))
                .build();
        WorkflowStep s2 = WorkflowStep.builder().name("s2").layer(1)
                .subtasks(List.of(task("B", "do B")))
                .build();
        Workflow wf = Workflow.builder().name("invalid").steps(List.of(s1, s2)).build();
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B");
        ControlPoint cp = new ControlPoint() {
            @Override
            public CompletableFuture<TaskResponse> onTask(TaskRequest req, WorkflowEngineClient ec) {
                return ec.sendMessage(req.getAgentName(), req.getMessage())
                        .thenApply(r -> TaskResponse.builder().success(true).output(r.getText()).build());
            }

            @Override
            public CompletableFuture<RouteDecision> onRoute(String stepName, Map<String, Object> results, List<JumpCondition> conditions) {
                return CompletableFuture.completedFuture(RouteDecision.builder()
                        .nextStep("nonexistent").reason("bad").build());
            }
        };
        WorkflowExecutor exec = new WorkflowExecutor(wf, cp, stub, recordingCallback(), "", "zh");

        ExecutionResult result = exec.run().join();

        // s1 executes, then invalid route ends workflow (s2 never runs)
        assertTrue(result.isSuccess());
        assertEquals(1, stub.getSentCount());
    }

    @Test
    void taskFailurePropagatesAndStopsWorkflow() {
        WorkflowStep s1 = WorkflowStep.builder().name("s1").layer(0)
                .subtasks(List.of(task("A", "do A")))
                .next(List.of(jump("s2", "")))
                .build();
        WorkflowStep s2 = WorkflowStep.builder().name("s2").layer(1)
                .subtasks(List.of(task("B", "do B")))
                .build();
        Workflow wf = Workflow.builder().name("fail").steps(List.of(s1, s2)).build();
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B");
        ControlPoint cp = new ControlPoint() {
            @Override
            public CompletableFuture<TaskResponse> onTask(TaskRequest req, WorkflowEngineClient ec) {
                if (req.getAgentName().equals("A")) {
                    return CompletableFuture.completedFuture(TaskResponse.builder()
                            .success(false).error("A failed").build());
                }
                return ec.sendMessage(req.getAgentName(), req.getMessage())
                        .thenApply(r -> TaskResponse.builder().success(true).output(r.getText()).build());
            }

            @Override
            public CompletableFuture<RouteDecision> onRoute(String stepName, Map<String, Object> results, List<JumpCondition> conditions) {
                return CompletableFuture.completedFuture(RouteDecision.builder()
                        .nextStep(conditions.get(0).getStep()).build());
            }
        };
        WorkflowExecutor exec = new WorkflowExecutor(wf, cp, stub, recordingCallback(), "", "zh");

        ExecutionResult result = exec.run().join();

        assertFalse(result.isSuccess());
        assertNotNull(result.getError());
        // Agent A fails directly (no send), agent B must never be reached.
        boolean bSent = stub.getSentMessages().stream()
                .anyMatch(m -> m.agentName.equals("B"));
        assertFalse(bSent, "Agent B must not be reached after A fails");
        assertTrue(events.contains(EventType.ERROR));
    }

    @Test
    void anySuccessReturnsOnFirstSuccess() {
        // s1 has 3 subtasks, ANY_SUCCESS: first success cancels the rest.
        Task t1 = task("A", "do A");
        Task t2 = Task.builder().agent("B").description("do B").build();
        Task t3 = Task.builder().agent("C").description("do C").build();
        WorkflowStep s1 = WorkflowStep.builder().name("s1").layer(0)
                .stepType(StepType.ANY_SUCCESS)
                .subtasks(List.of(t1, t2, t3))
                .next(List.of())
                .build();
        Workflow wf = Workflow.builder().name("any").steps(List.of(s1)).build();
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B", "C");
        // All succeed; the step should complete as soon as the first returns.
        WorkflowExecutor exec = new WorkflowExecutor(wf, autoCp(), stub, recordingCallback(), "", "zh");

        ExecutionResult result = exec.run().join();

        assertTrue(result.isSuccess());
        // At least one task was sent (could be all 3 racing, but >= 1)
        assertTrue(stub.getSentCount() >= 1);
    }

    @Test
    void anySuccessAllFailReturnsFailure() {
        Task t1 = task("A", "do A");
        Task t2 = task("B", "do B");
        WorkflowStep s1 = WorkflowStep.builder().name("s1").layer(0)
                .stepType(StepType.ANY_SUCCESS)
                .subtasks(List.of(t1, t2))
                .next(List.of())
                .build();
        Workflow wf = Workflow.builder().name("any-fail").steps(List.of(s1)).build();
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B");
        ControlPoint cp = new ControlPoint() {
            @Override
            public CompletableFuture<TaskResponse> onTask(TaskRequest req, WorkflowEngineClient ec) {
                return CompletableFuture.completedFuture(TaskResponse.builder()
                        .success(false).error("failed").build());
            }

            @Override
            public CompletableFuture<RouteDecision> onRoute(String stepName, Map<String, Object> results, List<JumpCondition> conditions) {
                return CompletableFuture.completedFuture(RouteDecision.builder().nextStep(conditions.get(0).getStep()).build());
            }
        };
        WorkflowExecutor exec = new WorkflowExecutor(wf, cp, stub, recordingCallback(), "", "zh");

        ExecutionResult result = exec.run().join();

        assertFalse(result.isSuccess());
    }

    @Test
    void eventSequenceForLinearWorkflow() {
        WorkflowStep s1 = WorkflowStep.builder().name("s1").layer(0)
                .subtasks(List.of(task("A", "do A")))
                .next(List.of())
                .build();
        Workflow wf = Workflow.builder().name("seq").steps(List.of(s1)).build();
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A");
        WorkflowExecutor exec = new WorkflowExecutor(wf, autoCp(), stub, recordingCallback(), "", "zh");

        exec.run().join();

        // Executor emits: step_start, task_request, task_status_changed,
        // task_response, step_complete, workflow_complete (NO start/complete/close)
        int startIdx = events.indexOf(EventType.STEP_START);
        int taskReqIdx = events.indexOf(EventType.TASK_REQUEST);
        int taskStatusIdx = events.indexOf(EventType.TASK_STATUS_CHANGED);
        int taskRespIdx = events.indexOf(EventType.TASK_RESPONSE);
        int stepCompleteIdx = events.indexOf(EventType.STEP_COMPLETE);
        int wfCompleteIdx = events.indexOf(EventType.WORKFLOW_COMPLETE);

        assertNotEquals(-1, startIdx);
        assertTrue(startIdx < taskReqIdx);
        assertTrue(taskReqIdx < taskStatusIdx);
        assertTrue(taskStatusIdx < taskRespIdx);
        assertTrue(taskRespIdx < stepCompleteIdx);
        assertTrue(stepCompleteIdx < wfCompleteIdx);
        assertFalse(events.contains(EventType.START), "Executor must not emit START (runner's job)");
    }

    @Test
    void runtimeIntentPassedToContext() {
        WorkflowStep s1 = WorkflowStep.builder().name("s1").layer(0)
                .subtasks(List.of(task("A", "do A")))
                .next(List.of())
                .build();
        Workflow wf = Workflow.builder().name("intent").steps(List.of(s1)).build();
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A");

        List<String> messages = Collections.synchronizedList(new ArrayList<>());
        ControlPoint cp = new ControlPoint() {
            @Override
            public CompletableFuture<TaskResponse> onTask(TaskRequest req, WorkflowEngineClient ec) {
                messages.add(req.getMessage());
                return CompletableFuture.completedFuture(TaskResponse.builder().success(true).output("ok").build());
            }

            @Override
            public CompletableFuture<RouteDecision> onRoute(String stepName, Map<String, Object> results, List<JumpCondition> conditions) {
                return CompletableFuture.completedFuture(RouteDecision.builder().nextStep(conditions.get(0).getStep()).build());
            }
        };
        WorkflowExecutor exec = new WorkflowExecutor(wf, cp, stub, new EventCallback(), "my intent", "zh");
        exec.run().join();

        assertFalse(messages.isEmpty());
        assertTrue(messages.get(0).contains("my intent"));
    }

    @Test
    void noSubtasksStepSucceeds() {
        WorkflowStep s1 = WorkflowStep.builder().name("s1").layer(0)
                .subtasks(List.of())
                .next(List.of())
                .build();
        Workflow wf = Workflow.builder().name("empty-step").steps(List.of(s1)).build();
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient();
        WorkflowExecutor exec = new WorkflowExecutor(wf, autoCp(), stub, recordingCallback(), "", "zh");
        ExecutionResult result = exec.run().join();
        assertTrue(result.isSuccess());
        assertEquals(0, stub.getSentCount());
    }
}
