package com.openan.a2at.engine.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.util.List;
import java.util.Map;

/** Execution status of a task within a workflow step. */
public enum TaskStatus {
    PENDING, RUNNING, SUCCESS, FAILED
}

/** Step execution type: all subtasks must succeed, or any one suffices. */
public enum StepType {
    ALL_SUCCESS("AllSuccess"),
    ANY_SUCCESS("AnySuccess");

    private final String value;
    StepType(String value) { this.value = value; }
    public String getValue() { return value; }

    public static StepType fromValue(String v) {
        if (v == null) return ALL_SUCCESS;
        for (StepType t : values()) {
            if (t.value.equalsIgnoreCase(v) || t.name().equalsIgnoreCase(v)) return t;
        }
        return ALL_SUCCESS;
    }
}

/** A single task assigned to an agent. */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Task {
    private String agent;
    private String skill = "";
    private String description = "";
    @Builder.Default
    private TaskStatus status = TaskStatus.PENDING;
}

/** A jump condition to the next step. */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class JumpCondition {
    private String step;
    private String condition = "";
}

/** A workflow step containing subtasks and next-step conditions. */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class WorkflowStep {
    private String name;
    @Builder.Default
    private List<Task> subtasks = List.of();
    @Builder.Default
    private List<JumpCondition> next = List.of();
    @Builder.Default
    private int layer = 0;
    private List<String> contextFrom;
    @Builder.Default
    private StepType stepType = StepType.ALL_SUCCESS;
}

/** A complete workflow (PSOP) with ordered steps. */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Workflow {
    private String id = "";
    private String name = "";
    private String description = "";
    @Builder.Default
    private List<WorkflowStep> steps = List.of();

    public static Workflow fromMap(Map<String, Object> data) {
        Workflow wf = new Workflow();
        wf.setId((String) data.getOrDefault("id", ""));
        wf.setName((String) data.getOrDefault("name", ""));
        wf.setDescription((String) data.getOrDefault("description", ""));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stepList = (List<Map<String, Object>>) data.getOrDefault("steps", List.of());
        List<WorkflowStep> steps = new java.util.ArrayList<>();
        for (Map<String, Object> s : stepList) {
            List<Task> subtasks = new java.util.ArrayList<>();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stList = (List<Map<String, Object>>) s.getOrDefault("subtasks", List.of());
            for (Map<String, Object> t : stList) {
                subtasks.add(Task.builder()
                        .agent((String) t.getOrDefault("agent", ""))
                        .skill((String) t.getOrDefault("skill", ""))
                        .description((String) t.getOrDefault("description", ""))
                        .build());
            }
            List<JumpCondition> nextList = new java.util.ArrayList<>();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> jcList = (List<Map<String, Object>>) s.getOrDefault("next", List.of());
            for (Map<String, Object> jc : jcList) {
                nextList.add(JumpCondition.builder()
                        .step((String) jc.getOrDefault("step", ""))
                        .condition((String) jc.getOrDefault("condition", ""))
                        .build());
            }
            String stValue = (String) s.getOrDefault("step_type", s.getOrDefault("type", "AllSuccess"));
            steps.add(WorkflowStep.builder()
                    .name((String) s.getOrDefault("name", ""))
                    .subtasks(subtasks)
                    .next(nextList)
                    .layer((Integer) s.getOrDefault("layer", 0))
                    .contextFrom((List<String>) s.get("context_from"))
                    .stepType(StepType.fromValue(stValue))
                    .build());
        }
        wf.setSteps(steps);
        return wf;
    }
}

/** Result of sending a message to an agent. */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class SendMessageResult {
    private String text = "";
    private Object task;
    private Map<String, Object> metadata;
    private String taskState = "";
}

/** Request passed to ControlPoint.onTask. */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class TaskRequest {
    private String agentName;
    private String skill;
    private String message;
    private String description = "";
    private String context;
    private String stepName;
    private int subtaskIndex;
}

/** Response from ControlPoint.onTask. */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class TaskResponse {
    private boolean success;
    private String output = "";
    private String error;
    private Map<String, Object> metadata;
}

/** Route decision from ControlPoint.onRoute. */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RouteDecision {
    private String nextStep;
    private String reason = "";
}

/** Final execution result. */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ExecutionResult {
    private boolean success;
    private List<Map<String, Object>> history;
    private Map<String, Map<String, Object>> stepOutputs;
    private String error;
}
