package com.openan.a2at.engine.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class TaskRequest {
    private String agentName;
    private String skill;
    private String message;
    @Builder.Default private String description = "";
    private String context;
    private String stepName;
    @Builder.Default private int subtaskIndex = 0;
}
