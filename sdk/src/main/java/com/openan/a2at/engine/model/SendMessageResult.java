package com.openan.a2at.engine.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SendMessageResult {
    @Builder.Default
    private String text = "";
    private Object task;
    private Map<String, Object> metadata;
    @Builder.Default
    private String taskState = "";
}
