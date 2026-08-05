/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the License); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an AS IS BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine;

import dev.openan.workflow.engine.client.WorkflowEngineClient;
import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.model.SendMessageResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Stub WorkflowEngineClient for testing. Records all sends and returns canned responses. No network
 * access.
 */
public class StubWorkflowEngineClient implements WorkflowEngineClient {

    private final List<SentMessage> sent = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> cannedResponses = new HashMap<>();
    private final List<String> agentNames = new ArrayList<>();
    private EventCallback eventCallback = new EventCallback();
    private ControlPoint controlPoint;
    private String defaultResponse = "stub-response";
    private String defaultTaskState = "COMPLETED";

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
            eventCallback.onEvent(
                    "agent_request",
                    Map.of(
                            "agent",
                            agentName,
                            "request",
                            message,
                            "metadata",
                            metadata != null ? metadata : Map.of()));
        }
        SendMessageResult result =
                SendMessageResult.builder()
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
    public void setControlPoint(ControlPoint controlPoint) {
        this.controlPoint = controlPoint;
    }

    @Override
    public void setEventCallback(EventCallback callback) {
        this.eventCallback = callback != null ? callback : new EventCallback();
    }

    @Override
    public void close() {}

    public List<SentMessage> getSentMessages() {
        return new ArrayList<>(sent);
    }

    public int getSentCount() {
        return sent.size();
    }

    public static final class SentMessage {
        public final String agentName;
        public final String message;
        public final String contextId;
        public final Map<String, Object> metadata;

        public SentMessage(
                String agentName, String message, String contextId, Map<String, Object> metadata) {
            this.agentName = agentName;
            this.message = message;
            this.contextId = contextId;
            this.metadata = metadata;
        }
    }
}
