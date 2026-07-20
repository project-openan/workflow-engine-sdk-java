package com.openan.a2at.engine.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionResult {
    private boolean success;
    private List<Map<String, Object>> history;
    private Map<String, Map<String, Object>> stepOutputs;
    private String error;
}
