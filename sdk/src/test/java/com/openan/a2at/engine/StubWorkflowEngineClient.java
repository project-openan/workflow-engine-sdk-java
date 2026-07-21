package com.openan.a2at.engine;

import com.openan.a2at.engine.client.WorkflowEngineClient;
import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.model.SendMessageResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Stub WorkflowEngineClient for testing. Records all sends and returns
 * canned responses. No network access.
 */
public class StubWorkflowEngineClient implements WorkflowEngineClient {

    public static final class SentMessage {
        public final String agentName;
        public final String message;
        public final String contextId;
        public final Map<String, Object> metadata;

        public SentMessage(String agentName, String message, String contextId, Map<String, Object> metadata) {
            this.agentName = agentName;
            this.message = message;
            this.contextId = contextId;
            this.metadata = metadata;
        }
    }

    private final List<SentMessage> sent = Collections.synchronizedList(new ArrayList<>());
    private EventCallback eventCallback = new EventCallback();
    private Object controlPoint;
    private final Map<String, String> cannedResponses = new HashMap<>();
    private String defaultResponse = "stub-response";
    private String defaultTaskState = "COMPLETED";
    private final List<String> agentNames = new ArrayList<>();

    public StubWorkflowEngineClient(String... agentNames) {
        this.agentNames.addAll(List.of(agentNames));
    }

    public StubWorkflowEngineClient withResponse(String agentName, String text) {
        cannedResponses.put(agentName, text);
        return this;
    }

    public StubWorkflowEngineClient withDefaultResponse(String text) {
        this.defaultResponse = text;
        return this;
    }

    public StubWorkflowEngineClient withDefaultTaskState(String state) {
        this.defaultTaskState = state;
        return this;
    }

    @Override
    public CompletableFuture<SendMessageResult> sendMessage(
            String agentName, String message, String contextId, Map<String, Object> metadata) {
        sent.add(new SentMessage(agentName, message, contextId, metadata));
        String text = cannedResponses.getOrDefault(agentName, defaultResponse);
        if (eventCallback != null) {
            eventCallback.onEvent("agent_request", Map.of("agent", agentName, "request", message, "metadata", metadata != null ? metadata : Map.of()));
        }
        SendMessageResult result = SendMessageResult.builder()
                .text(text)
                .taskState(defaultTaskState)
                .metadata(new HashMap<>())
                .build();
        if (eventCallback != null) {
            eventCallback.onEvent("agent_response", Map.of("agent", agentName, "response", text));
        }
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public void setControlPoint(Object controlPoint) {
        this.controlPoint = controlPoint;
    }

    @Override
    public void setEventCallback(EventCallback callback) {
        this.eventCallback = callback != null ? callback : new EventCallback();
    }

    @Override
    public void close() {
    }

    @Override
    public List<String> getAgentNames() {
        return new ArrayList<>(agentNames);
    }

    public List<SentMessage> getSentMessages() {
        return new ArrayList<>(sent);
    }

    public int getSentCount() {
        return sent.size();
    }
}

