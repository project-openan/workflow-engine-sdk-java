/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the License); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an AS IS BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine.runner;

import static org.junit.jupiter.api.Assertions.*;

import dev.openan.workflow.engine.StubWorkflowEngineClient;
import dev.openan.workflow.engine.client.WorkflowEngineClient;
import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.control.EventType;

import dev.openan.workflow.engine.model.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Tests for ExecutePsop: end-to-end event flow, lifecycle, on_finish, on_event transformer, START
 * not duplicated, Builder usability.
 */
class ExecutePsopTest {

    private Task task(String agent, String desc) {
        return Task.builder().agent(agent).description(desc).build();
    }

    private ControlPoint autoCp() {
        return new ControlPoint() {
            @Override
            public CompletableFuture<TaskResponse> onTask(
                    TaskRequest request, WorkflowEngineClient engineClient) {
                return engineClient
                        .sendMessage(request.getAgentName(), request.getMessage())
                        .thenApply(
                                r ->
                                        TaskResponse.builder()
                                                .success(true)
                                                .output(r.getText())
                                                .build());
            }

            @Override
            public CompletableFuture<RouteDecision> onRoute(
                    String stepName, Map<String, Object> results, List<JumpCondition> conditions) {
                return CompletableFuture.completedFuture(
                        RouteDecision.builder()
                                .nextStep(conditions.get(0).getStep())
                                .reason("first")
                                .build());
            }
        };
    }

    private Workflow linearWorkflow() {
        WorkflowStep s1 =
                WorkflowStep.builder()
                        .name("s1")
                        .layer(0)
                        .subtasks(List.of(task("A", "do A")))
                        .next(List.of(JumpCondition.builder().step("s2").condition("").build()))
                        .build();
        WorkflowStep s2 =
                WorkflowStep.builder()
                        .name("s2")
                        .layer(1)
                        .subtasks(List.of(task("B", "do B")))
                        .build();
        return Workflow.builder().name("e2e").steps(List.of(s1, s2)).build();
    }

    @Test
    void fullLifecycleEventSequence() {
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B");
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        EventCallback cb =
                new EventCallback() {
                    @Override
                    public void onEvent(String type, Map<String, Object> data) {
                        events.add(type);
                    }
                };
        ExecutionResult result =
                ExecutePsop.execute(
                                linearWorkflow(),
                                List.of(),
                                autoCp(),
                                stub,
                                "intent",
                                "zh",
                                null,
                                null,
                                false,
                                null,
                                null,
                                cb,
                                (BiFunction<
                                                ExecutionResult,
                                                List<Map<String, Object>>,
                                                CompletableFuture<Void>>)
                                        null,
                                null)
                        .join();
        assertTrue(result.isSuccess());
        // Verify lifecycle bracket: start ... complete ... close
        assertEquals(EventType.START, events.get(0));
        assertEquals(EventType.CLOSE, events.get(events.size() - 1));
        int startCount = Collections.frequency(events, EventType.START);
        assertEquals(1, startCount, "START must be emitted exactly once (no duplicates)");
        assertTrue(events.contains(EventType.COMPLETE));
        assertTrue(events.contains(EventType.WORKFLOW_COMPLETE));
        int closeCount = Collections.frequency(events, EventType.CLOSE);
        assertEquals(1, closeCount);
        int completeIdx = events.indexOf(EventType.COMPLETE);
        int closeIdx = events.indexOf(EventType.CLOSE);
        assertTrue(completeIdx < closeIdx);
        int startIdx = events.indexOf(EventType.START);
        assertTrue(startIdx < completeIdx);
    }

    @Test
    void onFinishCalledWithResultAndEvents() {
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B");
        AtomicInteger finishCalls = new AtomicInteger(0);
        AtomicReference<ExecutionResult> capturedResult = new AtomicReference<>();
        BiFunction<ExecutionResult, List<Map<String, Object>>, CompletableFuture<Void>> onFinish =
                (r, e) -> {
                    finishCalls.incrementAndGet();
                    capturedResult.set(r);
                    assertTrue(e.size() > 0, "events should be collected");
                    return CompletableFuture.completedFuture(null);
                };
        ExecutionResult result =
                ExecutePsop.execute(
                                linearWorkflow(),
                                List.of(),
                                autoCp(),
                                stub,
                                "intent",
                                "zh",
                                null,
                                null,
                                false,
                                null,
                                null,
                                new EventCallback(),
                                onFinish,
                                null)
                        .join();
        assertEquals(1, finishCalls.get());
        assertTrue(capturedResult.get().isSuccess());
    }

    @Test
    void onEventTransformerCanInjectEvents() {
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B");
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        EventCallback cb =
                new EventCallback() {
                    @Override
                    public void onEvent(String type, Map<String, Object> data) {
                        events.add(type);
                    }
                };
        // Inject a "psop_update" before each step_start
        Function<Map<String, Object>, Object> onEvent =
                event -> {
                    if (EventType.STEP_START.equals(event.get("type"))) {
                        List<Map<String, Object>> injected = new ArrayList<>();
                        injected.add(Map.of("type", "psop_update", "data", Map.of("custom", true)));
                        injected.add(event);
                        return injected;
                    }
                    return event;
                };
        ExecutePsop.execute(
                        linearWorkflow(),
                        List.of(),
                        autoCp(),
                        stub,
                        "intent",
                        "zh",
                        null,
                        null,
                        false,
                        null,
                        null,
                        cb,
                        null,
                        onEvent)
                .join();
        assertTrue(events.contains("psop_update"));
        int psopIdx = events.indexOf("psop_update");
        int stepStartIdx = events.indexOf(EventType.STEP_START);
        assertTrue(psopIdx >= 0);
        // psop_update should come right before the first step_start
        assertEquals(stepStartIdx - 1, psopIdx);
    }

    @Test
    void onEventTransformerCanFilterEvents() {
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B");
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        EventCallback cb =
                new EventCallback() {
                    @Override
                    public void onEvent(String type, Map<String, Object> data) {
                        events.add(type);
                    }
                };
        // Filter out all task_request events
        Function<Map<String, Object>, Object> onEvent =
                event -> {
                    if (EventType.TASK_REQUEST.equals(event.get("type"))) {
                        return null;
                    }
                    return event;
                };
        ExecutePsop.execute(
                        linearWorkflow(),
                        List.of(),
                        autoCp(),
                        stub,
                        "intent",
                        "zh",
                        null,
                        null,
                        false,
                        null,
                        null,
                        cb,
                        null,
                        onEvent)
                .join();
        assertFalse(events.contains(EventType.TASK_REQUEST));
    }

    @Test
    void builderProducesSameResultAsStaticExecute() {
        StubWorkflowEngineClient stub1 = new StubWorkflowEngineClient("A", "B");
        StubWorkflowEngineClient stub2 = new StubWorkflowEngineClient("A", "B");
        ExecutionResult r1 =
                ExecutePsop.execute(
                                linearWorkflow(),
                                List.of(),
                                autoCp(),
                                stub1,
                                "intent",
                                "zh",
                                null,
                                null,
                                false,
                                null,
                                null,
                                new EventCallback(),
                                null,
                                null)
                        .join();
        ExecutionResult r2 =
                ExecutePsop.builder()
                        .psop(linearWorkflow())
                        .agentCards(List.of())
                        .controlPoint(autoCp())
                        .engineClient(stub2)
                        .runtimeIntent("intent")
                        .lang("zh")
                        .sslVerify(false)
                        .execute()
                        .join();
        assertEquals(r1.isSuccess(), r2.isSuccess());
        assertEquals(stub1.getSentCount(), stub2.getSentCount());
    }

    @Test
    void builderRequiresPsop() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ExecutePsop.builder().controlPoint(autoCp()).execute());
    }

    @Test
    void builderRequiresControlPoint() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ExecutePsop.builder().psop(linearWorkflow()).execute());
    }

    @Test
    void errorLifecycleOnTaskFailure() {
        StubWorkflowEngineClient stub = new StubWorkflowEngineClient("A", "B");
        ControlPoint failCp =
                new ControlPoint() {
                    @Override
                    public CompletableFuture<TaskResponse> onTask(
                            TaskRequest request, WorkflowEngineClient engineClient) {
                        if (request.getAgentName().equals("A")) {
                            return CompletableFuture.completedFuture(
                                    TaskResponse.builder().success(false).error("A broke").build());
                        }
                        return engineClient
                                .sendMessage(request.getAgentName(), request.getMessage())
                                .thenApply(
                                        r ->
                                                TaskResponse.builder()
                                                        .success(true)
                                                        .output(r.getText())
                                                        .build());
                    }

                    @Override
                    public CompletableFuture<RouteDecision> onRoute(
                            String stepName,
                            Map<String, Object> results,
                            List<JumpCondition> conditions) {
                        return CompletableFuture.completedFuture(
                                RouteDecision.builder()
                                        .nextStep(conditions.get(0).getStep())
                                        .build());
                    }
                };
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        EventCallback cb =
                new EventCallback() {
                    @Override
                    public void onEvent(String type, Map<String, Object> data) {
                        events.add(type);
                    }
                };
        ExecutionResult result =
                ExecutePsop.execute(
                                linearWorkflow(),
                                List.of(),
                                failCp,
                                stub,
                                "intent",
                                "zh",
                                null,
                                null,
                                false,
                                null,
                                null,
                                cb,
                                null,
                                null)
                        .join();
        assertFalse(result.isSuccess());
        assertEquals(EventType.START, events.get(0));
        assertEquals(EventType.CLOSE, events.get(events.size() - 1));
        assertTrue(events.contains(EventType.ERROR));
        assertFalse(events.contains(EventType.COMPLETE));
    }
}
