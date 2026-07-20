package com.openan.a2at.engine.core;

import com.openan.a2at.engine.model.Workflow;
import com.openan.a2at.engine.model.WorkflowStep;
import java.util.*;

/** Context assembly from upstream step outputs. Mirrors Python ContextBuilder. */
public class ContextBuilder {
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
                        if (jc.getStep().equals(current) && !s.getName().equals(current) && !ancestors.contains(s.getName())) {
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
                String text = taskEntry.getValue() instanceof String ? (String) taskEntry.getValue() : String.valueOf(taskEntry.getValue());
                parts.add("**Task**: " + taskEntry.getKey() + "\n**Output**: " + text + "\n\n");
            }
        }
        return String.join("\n", parts).trim();
    }

    public String buildTaskMessage(String taskDescription, String contextMessage, String lang) {
        String langHint = "";
        if ("en".equals(lang)) langHint = "\n\nPlease respond in English.";
        if ("zh".equals(lang)) langHint = "\n\n\u8bf7\u7528\u4e2d\u6587\u56de\u590d\u3002";
        if (contextMessage != null && !contextMessage.isEmpty()) {
            return contextMessage + "\n\n## Current Task\n" + taskDescription + langHint;
        }
        return taskDescription + langHint;
    }

    public Integer findStepIndex(String stepName) {
        return stepIndex.get(stepName);
    }
}
