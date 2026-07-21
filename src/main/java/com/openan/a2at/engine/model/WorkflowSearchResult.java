package com.openan.a2at.engine.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

/**
 * Summary of a PSOP workflow returned by the search endpoint.
 *
 * <p>Mirrors the Python orchestration center's {@code WorkflowSearchResult}.
 * Returned by {@link com.openan.a2at.engine.registry.LoadPsop#search}.
 * To get the full workflow with steps, take {@link #getWorkflowId()} and
 * call {@link com.openan.a2at.engine.registry.LoadPsop#load}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowSearchResult {
    private String workflowId;
    private String workflowType;
    private String name;
    private String description;
    @Builder.Default
    private List<String> tags = List.of();
    private String createdAt;
    @Builder.Default
    private double score = 1.0;
    private String userIntent;
    private String relatedPreflow;
    private String tasksSummary;
}
