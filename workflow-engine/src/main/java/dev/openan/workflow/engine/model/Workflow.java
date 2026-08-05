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

package dev.openan.workflow.engine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Workflow {
    @Builder.Default private String id = "";
    @Builder.Default private String name = "";
    @Builder.Default private String description = "";
    @Builder.Default private List<WorkflowStep> steps = List.of();

    @SuppressWarnings("unchecked")
    public static Workflow fromMap(Map<String, Object> data) {
        Workflow wf = new Workflow();
        wf.setId((String) data.getOrDefault("id", ""));
        wf.setName((String) data.getOrDefault("name", ""));
        wf.setDescription((String) data.getOrDefault("description", ""));
        List<WorkflowStep> steps =
                parseSteps((List<Map<String, Object>>) data.getOrDefault("steps", List.of()));
        wf.setSteps(steps);
        return wf;
    }

    @SuppressWarnings("unchecked")
    private static List<WorkflowStep> parseSteps(List<Map<String, Object>> stepList) {
        List<WorkflowStep> steps = new ArrayList<>();
        for (Map<String, Object> s : stepList) {
            List<Task> subtasks =
                    parseSubtasks(
                            (List<Map<String, Object>>) s.getOrDefault("subtasks", List.of()));
            List<JumpCondition> nextList =
                    parseNextSteps((List<Map<String, Object>>) s.getOrDefault("next", List.of()));
            List<String> contextFrom = parseContextFrom(s.get("context_from"));
            int layer = s.get("layer") instanceof Number num ? num.intValue() : 0;
            String stValue =
                    (String) s.getOrDefault("step_type", s.getOrDefault("type", "AllSuccess"));
            steps.add(
                    WorkflowStep.builder()
                            .name((String) s.getOrDefault("name", ""))
                            .subtasks(subtasks)
                            .next(nextList)
                            .layer(layer)
                            .contextFrom(contextFrom)
                            .stepType(StepType.fromValue(stValue))
                            .build());
        }
        return steps;
    }

    @SuppressWarnings("unchecked")
    private static List<Task> parseSubtasks(List<Map<String, Object>> stList) {
        List<Task> subtasks = new ArrayList<>();
        for (Map<String, Object> t : stList) {
            subtasks.add(
                    Task.builder()
                            .agent((String) t.getOrDefault("agent", ""))
                            .skill((String) t.getOrDefault("skill", ""))
                            .description((String) t.getOrDefault("description", ""))
                            .build());
        }
        return subtasks;
    }

    @SuppressWarnings("unchecked")
    private static List<JumpCondition> parseNextSteps(List<Map<String, Object>> jcList) {
        List<JumpCondition> nextList = new ArrayList<>();
        for (Map<String, Object> jc : jcList) {
            nextList.add(
                    JumpCondition.builder()
                            .step((String) jc.getOrDefault("step", ""))
                            .condition((String) jc.getOrDefault("condition", ""))
                            .build());
        }
        return nextList;
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseContextFrom(Object cfRaw) {
        if (cfRaw instanceof List) return (List<String>) cfRaw;
        if (cfRaw instanceof String cfStr && !cfStr.isEmpty()) return List.of(cfStr);
        return null;
    }
}
