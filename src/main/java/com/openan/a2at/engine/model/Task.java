package com.openan.a2at.engine.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Task {
    private String agent;
    @Builder.Default private String skill = "";
    @Builder.Default private String description = "";
    @Builder.Default private TaskStatus status = TaskStatus.PENDING;
}
