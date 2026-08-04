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

package dev.openan.workflow.engine.core;

import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowStep;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Context assembly from upstream step outputs. Mirrors Python ContextBuilder. */
class ContextBuilder {
    private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);
    private final Workflow workflow;
    private final String runtimeIntent;
    private final Map<String, Integer> stepIndex;

    public ContextBuilder(Workflow workflow, String runtimeIntent) {
        this.workflow = workflow;
        this.runtimeIntent = runtimeIntent == null ? "" : runtimeIntent;
        this.stepIndex = new HashMap<>();
        for (int i = 0; i < workflow.getSteps().size(); i++) {
            stepIndex.put(workflow.getSteps().get(i).getName(), i);
        }
    }

    public List<String> getStepPredecessors(String stepName) {
        List<String> preds = new ArrayList<>();
        for (WorkflowStep s : workflow.getSteps()) {
            if (s.getNext() != null) {
                for (var jc : s.getNext()) {
                    if (jc.getStep().equals(stepName) && !s.getName().equals(stepName)) {
                        preds.add(s.getName());
                        break;
                    }
                }
            }
        }
        return preds;
    }

    public List<String> getAllPredecessors(String stepName) {
        Set<String> ancestors = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(stepName);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (WorkflowStep s : workflow.getSteps()) {
                if (s.getNext() != null) {
                    for (var jc : s.getNext()) {
                        if (jc.getStep().equals(current)
                                && !s.getName().equals(current)
                                && !ancestors.contains(s.getName())) {
                            ancestors.add(s.getName());
                            queue.add(s.getName());
                            break;
                        }
                    }
                }
            }
        }
        return new ArrayList<>(ancestors);
    }

    public String buildContext(WorkflowStep step, Map<String, Map<String, Object>> stepOutputs) {
        if (step.getLayer() <= 0) {
            log.info("[Context] Step {}: layer 0, using runtime intent only", step.getName());
            return runtimeIntent.isEmpty() ? "" : "## Runtime Context\n\n" + runtimeIntent;
        }
        List<String> parts = new ArrayList<>();
        if (!runtimeIntent.isEmpty()) {
            parts.add("## Runtime Context\n\n" + runtimeIntent);
        }
        parts.add("## Previous Step Execution Results\n");
        List<Map.Entry<String, Map<String, Object>>> refPairs = new ArrayList<>();
        if (step.getContextFrom() != null && step.getContextFrom().contains("*")) {
            for (String name : getAllPredecessors(step.getName())) {
                if (stepOutputs.containsKey(name)) {
                    refPairs.add(Map.entry(name, stepOutputs.get(name)));
                }
            }
        } else if (step.getContextFrom() != null && !step.getContextFrom().isEmpty()) {
            for (String name : step.getContextFrom()) {
                if (stepOutputs.containsKey(name)) {
                    refPairs.add(Map.entry(name, stepOutputs.get(name)));
                }
            }
        } else {
            for (String name : getStepPredecessors(step.getName())) {
                if (stepOutputs.containsKey(name)) {
                    refPairs.add(Map.entry(name, stepOutputs.get(name)));
                }
            }
        }
        for (var entry : refPairs) {
            parts.add("### " + entry.getKey() + " Results\n");
            for (var taskEntry : entry.getValue().entrySet()) {
                String text =
                        taskEntry.getValue() instanceof String
                                ? (String) taskEntry.getValue()
                                : String.valueOf(taskEntry.getValue());
                parts.add("**Task**: " + taskEntry.getKey() + "\n**Output**: " + text + "\n\n");
            }
        }
        String result = String.join("\n", parts).trim();
        log.info("[Context] Step {}: built context ({} chars)", step.getName(), result.length());
        return result;
    }

    public String buildTaskMessage(String taskDescription, String contextMessage, String lang) {
        String langHint = "";
        if ("en".equals(lang)) {
            langHint = "\n\nPlease respond in English.";
        }
        if ("zh".equals(lang)) {
            langHint = "\n\n请用中文回复。";
        }
        if (contextMessage != null && !contextMessage.isEmpty()) {
            return contextMessage + "\n\n## Current Task\n" + taskDescription + langHint;
        }
        return taskDescription + langHint;
    }

    public Integer findStepIndex(String stepName) {
        return stepIndex.get(stepName);
    }
}
