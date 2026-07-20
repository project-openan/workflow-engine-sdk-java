package com.openan.a2at.engine.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Workflow {
    @Builder.Default
    private String id = "";
    @Builder.Default
    private String name = "";
    @Builder.Default
    private String description = "";
    @Builder.Default
    private List<WorkflowStep> steps = List.of();

    @SuppressWarnings("unchecked")
    public static Workflow fromMap(Map<String, Object> data) {
        Workflow wf = new Workflow();
        wf.setId((String) data.getOrDefault("id", ""));
        wf.setName((String) data.getOrDefault("name", ""));
        wf.setDescription((String) data.getOrDefault("description", ""));
        List<Map<String, Object>> stepList = (List<Map<String, Object>>) data.getOrDefault("steps", List.of());
        List<WorkflowStep> steps = new ArrayList<>();
        for (Map<String, Object> s : stepList) {
            List<Task> subtasks = new ArrayList<>();
            List<Map<String, Object>> stList = (List<Map<String, Object>>) s.getOrDefault("subtasks", List.of());
            for (Map<String, Object> t : stList) {
                subtasks.add(Task.builder()
                        .agent((String) t.getOrDefault("agent", ""))
                        .skill((String) t.getOrDefault("skill", ""))
                        .description((String) t.getOrDefault("description", ""))
                        .build());
            }
            List<JumpCondition> nextList = new ArrayList<>();
            List<Map<String, Object>> jcList = (List<Map<String, Object>>) s.getOrDefault("next", List.of());
            for (Map<String, Object> jc : jcList) {
                nextList.add(JumpCondition.builder()
                        .step((String) jc.getOrDefault("step", ""))
                        .condition((String) jc.getOrDefault("condition", ""))
                        .build());
            }
           String stValue = (String) s.getOrDefault("step_type", s.getOrDefault("type", "AllSuccess"));
            // Handle context_from: may be a single string instead of a list
            List<String> contextFrom = null;
            Object cfRaw = s.get("context_from");
            if (cfRaw instanceof List) {
                contextFrom = (List<String>) cfRaw;
            } else if (cfRaw instanceof String cfStr && !cfStr.isEmpty()) {
                contextFrom = List.of(cfStr);
            }
            // Handle layer: may be Integer or Number
            int layer = 0;
            Object layerRaw = s.get("layer");
            if (layerRaw instanceof Number num) {
                layer = num.intValue();
            }
           steps.add(WorkflowStep.builder()
                   .name((String) s.getOrDefault("name", ""))
                   .subtasks(subtasks)
                   .next(nextList)
                    .layer(layer)
                    .contextFrom(contextFrom)
                   .stepType(StepType.fromValue(stValue))
                   .build());
        }
        wf.setSteps(steps);
        return wf;
    }
}
