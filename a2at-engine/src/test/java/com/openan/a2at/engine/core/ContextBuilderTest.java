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

package com.openan.a2at.engine.core;

import com.openan.a2at.engine.model.JumpCondition;
import com.openan.a2at.engine.model.Task;
import com.openan.a2at.engine.model.Workflow;
import com.openan.a2at.engine.model.WorkflowStep;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContextBuilderTest {

    private WorkflowStep step(String name, int layer, List<String> next, List<String> contextFrom) {
        return WorkflowStep.builder()
                .name(name)
                .layer(layer)
                .next(next == null ? List.of() : next.stream()
                        .map(s -> JumpCondition.builder().step(s).build()).toList())
                .contextFrom(contextFrom)
                .subtasks(List.of(Task.builder().agent("A").description("t").build()))
                .build();
    }

    @Test
    void layerZeroReturnsRuntimeIntentOnly() {
        Workflow wf = Workflow.builder().name("w").steps(List.of(step("s1", 0, List.of("s2"), null))).build();
        ContextBuilder cb = new ContextBuilder(wf, "my intent");
        String ctx = cb.buildContext(wf.getSteps().get(0), new HashMap<>());
        assertEquals("## Runtime Context\n\nmy intent", ctx);
    }

    @Test
    void layerZeroNoIntentReturnsEmpty() {
        Workflow wf = Workflow.builder().name("w").steps(List.of(step("s1", 0, List.of("s2"), null))).build();
        ContextBuilder cb = new ContextBuilder(wf, "");
        String ctx = cb.buildContext(wf.getSteps().get(0), new HashMap<>());
        assertEquals("", ctx);
    }

    @Test
    void directPredecessorsProvideContext() {
        WorkflowStep s1 = step("s1", 0, List.of("s2"), null);
        WorkflowStep s2 = step("s2", 1, List.of(), null);
        Workflow wf = Workflow.builder().name("w").steps(List.of(s1, s2)).build();
        ContextBuilder cb = new ContextBuilder(wf, "intent");
        Map<String, Map<String, Object>> outputs = new HashMap<>();
        outputs.put("s1", Map.of("do A", "result-from-A"));
        String ctx = cb.buildContext(s2, outputs);
        assertTrue(ctx.contains("## Runtime Context"));
        assertTrue(ctx.contains("## Previous Step Execution Results"));
        assertTrue(ctx.contains("### s1 Results"));
        assertTrue(ctx.contains("result-from-A"));
    }

    @Test
    void contextFromExplicitListOverridesPredecessors() {
        WorkflowStep s1 = step("s1", 0, List.of("s3"), null);
        WorkflowStep s2 = step("s2", 0, List.of("s3"), null);
        WorkflowStep s3 = step("s3", 1, List.of(), List.of("s2"));
        Workflow wf = Workflow.builder().name("w").steps(List.of(s1, s2, s3)).build();
        ContextBuilder cb = new ContextBuilder(wf, "intent");
        Map<String, Map<String, Object>> outputs = new HashMap<>();
        outputs.put("s1", Map.of("t1", "out1"));
        outputs.put("s2", Map.of("t2", "out2"));
        String ctx = cb.buildContext(s3, outputs);
        assertTrue(ctx.contains("out2"));
        assertFalse(ctx.contains("out1"));
    }

    @Test
    void contextFromStarIncludesAllAncestors() {
        // DAG: s1 -> s2 -> s3, context_from=["*"] on s3 should include s1 and s2
        WorkflowStep s1 = step("s1", 0, List.of("s2"), null);
        WorkflowStep s2 = step("s2", 1, List.of("s3"), null);
        WorkflowStep s3 = step("s3", 1, List.of(), List.of("*"));
        Workflow wf = Workflow.builder().name("w").steps(List.of(s1, s2, s3)).build();
        ContextBuilder cb = new ContextBuilder(wf, "intent");
        Map<String, Map<String, Object>> outputs = new HashMap<>();
        outputs.put("s1", Map.of("t1", "out1"));
        outputs.put("s2", Map.of("t2", "out2"));
        String ctx = cb.buildContext(s3, outputs);
        assertTrue(ctx.contains("out1"));
        assertTrue(ctx.contains("out2"));
    }

    @Test
    void getStepPredecessorsFindsDirectParents() {
        WorkflowStep s1 = step("s1", 0, List.of("s3"), null);
        WorkflowStep s2 = step("s2", 0, List.of("s3"), null);
        WorkflowStep s3 = step("s3", 1, List.of(), null);
        Workflow wf = Workflow.builder().name("w").steps(List.of(s1, s2, s3)).build();
        ContextBuilder cb = new ContextBuilder(wf, "");
        List<String> preds = cb.getStepPredecessors("s3");
        assertEquals(2, preds.size());
        assertTrue(preds.contains("s1"));
        assertTrue(preds.contains("s2"));
    }

    @Test
    void getStepPredecessorsEmptyForRoot() {
        WorkflowStep s1 = step("s1", 0, List.of("s2"), null);
        WorkflowStep s2 = step("s2", 1, List.of(), null);
        Workflow wf = Workflow.builder().name("w").steps(List.of(s1, s2)).build();
        ContextBuilder cb = new ContextBuilder(wf, "");
        assertTrue(cb.getStepPredecessors("s1").isEmpty());
    }

    @Test
    void buildTaskMessageWithZhLang() {
        Workflow wf = Workflow.builder().name("w").steps(List.of(step("s", 0, List.of(), null))).build();
        ContextBuilder cb = new ContextBuilder(wf, "");
        String msg = cb.buildTaskMessage("do task", "context here", "zh");
        assertTrue(msg.contains("context here"));
        assertTrue(msg.contains("## Current Task"));
        assertTrue(msg.contains("do task"));
        assertTrue(msg.contains("请用中文回复"));
    }

    @Test
    void buildTaskMessageWithEnLang() {
        Workflow wf = Workflow.builder().name("w").steps(List.of(step("s", 0, List.of(), null))).build();
        ContextBuilder cb = new ContextBuilder(wf, "");
        String msg = cb.buildTaskMessage("do task", "context here", "en");
        assertTrue(msg.contains("Please respond in English"));
    }

    @Test
    void buildTaskMessageNoContext() {
        Workflow wf = Workflow.builder().name("w").steps(List.of(step("s", 0, List.of(), null))).build();
        ContextBuilder cb = new ContextBuilder(wf, "");
        String msg = cb.buildTaskMessage("just a task", "", "zh");
        assertTrue(msg.startsWith("just a task"));
        assertTrue(msg.contains("请用中文回复"));
        assertFalse(msg.contains("## Current Task"));
    }

    @Test
    void findStepIndexCorrect() {
        WorkflowStep s1 = step("s1", 0, null, null);
        WorkflowStep s2 = step("s2", 1, null, null);
        Workflow wf = Workflow.builder().name("w").steps(List.of(s1, s2)).build();
        ContextBuilder cb = new ContextBuilder(wf, "");
        assertEquals(0, cb.findStepIndex("s1"));
        assertEquals(1, cb.findStepIndex("s2"));
        assertNull(cb.findStepIndex("nonexistent"));
    }
}
