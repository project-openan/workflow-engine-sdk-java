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

package com.openan.a2at.engine.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Workflow/WorkflowStep/Task/JumpCondition/StepType model parsing.
 */
class WorkflowModelTest {

    @Test
    void parsesLinearWorkflow() {
        Map<String, Object> data = Map.of(
                "id", "wf-1",
                "name", "test",
                "steps", List.of(
                        Map.of("name", "s1", "layer", 0,
                                "subtasks", List.of(Map.of("agent", "A", "skill", "diag", "description", "do A")),
                                "next", List.of(Map.of("step", "s2", "condition", ""))),
                        Map.of("name", "s2", "layer", 1,
                                "subtasks", List.of(Map.of("agent", "B", "description", "do B")))
                )
        );
        Workflow wf = Workflow.fromMap(data);
        assertEquals("wf-1", wf.getId());
        assertEquals("test", wf.getName());
        assertEquals(2, wf.getSteps().size());
        assertEquals("s1", wf.getSteps().get(0).getName());
        assertEquals(0, wf.getSteps().get(0).getLayer());
        assertEquals("A", wf.getSteps().get(0).getSubtasks().get(0).getAgent());
        assertEquals("diag", wf.getSteps().get(0).getSubtasks().get(0).getSkill());
        assertEquals("do A", wf.getSteps().get(0).getSubtasks().get(0).getDescription());
        assertEquals("s2", wf.getSteps().get(0).getNext().get(0).getStep());
        assertEquals(1, wf.getSteps().get(1).getLayer());
    }

    @Test
    void stepTypeCaseInsensitiveAllSuccess() {
        for (String v : List.of("AllSuccess", "ALLSUCCESS", "allsuccess", "ALL_SUCCESS", "all_success")) {
            Map<String, Object> data = Map.of("name", "t", "steps",
                    List.of(Map.of("name", "s", "step_type", v, "subtasks", List.of())));
            Workflow wf = Workflow.fromMap(data);
            assertEquals(StepType.ALL_SUCCESS, wf.getSteps().get(0).getStepType(),
                    "Failed for input: " + v);
        }
    }

    @Test
    void stepTypeCaseInsensitiveAnySuccess() {
        for (String v : List.of("AnySuccess", "ANYSUCCESS", "anysuccess", "ANY_SUCCESS", "any_success")) {
            Map<String, Object> data = Map.of("name", "t", "steps",
                    List.of(Map.of("name", "s", "step_type", v, "subtasks", List.of())));
            Workflow wf = Workflow.fromMap(data);
            assertEquals(StepType.ANY_SUCCESS, wf.getSteps().get(0).getStepType(),
                    "Failed for input: " + v);
        }
    }

    @Test
    void stepTypeViaTypeFieldFallback() {
        Map<String, Object> data = Map.of("name", "t", "steps",
                List.of(Map.of("name", "s", "type", "AnySuccess", "subtasks", List.of())));
        Workflow wf = Workflow.fromMap(data);
        assertEquals(StepType.ANY_SUCCESS, wf.getSteps().get(0).getStepType());
    }

    @Test
    void stepTypeDefaultsToAllSuccess() {
        Map<String, Object> data = Map.of("name", "t", "steps",
                List.of(Map.of("name", "s", "subtasks", List.of())));
        Workflow wf = Workflow.fromMap(data);
        assertEquals(StepType.ALL_SUCCESS, wf.getSteps().get(0).getStepType());
    }

    @Test
    void stepTypeBogusFallsBackToAllSuccess() {
        Map<String, Object> data = Map.of("name", "t", "steps",
                List.of(Map.of("name", "s", "step_type", "bogus", "subtasks", List.of())));
        Workflow wf = Workflow.fromMap(data);
        assertEquals(StepType.ALL_SUCCESS, wf.getSteps().get(0).getStepType());
    }

    @Test
    void contextFromSingleStringNormalizedToList() {
        Map<String, Object> data = Map.of("name", "t", "steps",
                List.of(Map.of("name", "s", "context_from", "prev_step", "subtasks", List.of())));
        Workflow wf = Workflow.fromMap(data);
        assertNotNull(wf.getSteps().get(0).getContextFrom());
        assertEquals(List.of("prev_step"), wf.getSteps().get(0).getContextFrom());
    }

    @Test
    void contextFromListPreserved() {
        Map<String, Object> data = Map.of("name", "t", "steps",
                List.of(Map.of("name", "s", "context_from", List.of("a", "b"), "subtasks", List.of())));
        Workflow wf = Workflow.fromMap(data);
        assertEquals(List.of("a", "b"), wf.getSteps().get(0).getContextFrom());
    }

    @Test
    void contextFromStarPreserved() {
        Map<String, Object> data = Map.of("name", "t", "steps",
                List.of(Map.of("name", "s", "context_from", List.of("*"), "subtasks", List.of())));
        Workflow wf = Workflow.fromMap(data);
        assertEquals(List.of("*"), wf.getSteps().get(0).getContextFrom());
    }

    @Test
    void taskStatusDefaultsToPending() {
        Task t = Task.builder().agent("A").description("do").build();
        assertEquals(TaskStatus.PENDING, t.getStatus());
    }

    @Test
    void jumpConditionDefaults() {
        JumpCondition jc = JumpCondition.builder().step("s2").build();
        assertEquals("s2", jc.getStep());
        assertEquals("", jc.getCondition());
    }

    @Test
    void emptyStepsAllowed() {
        Map<String, Object> data = Map.of("name", "empty", "steps", List.of());
        Workflow wf = Workflow.fromMap(data);
        assertTrue(wf.getSteps().isEmpty());
    }
}
