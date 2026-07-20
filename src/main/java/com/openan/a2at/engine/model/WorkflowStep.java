package com.openan.a2at.engine.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
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
