/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package com.openan.a2at.engine.client;

import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.control.ControlPoint;
import com.openan.a2at.engine.control.EventType;
import com.openan.a2at.engine.model.SendMessageResult;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Workflow-execution send facade built on a shared {@link A2ATransport}.
 *
 * <p>Single responsibility: the workflow execution send path. Owns the
 * Task-T/Negotiation-T extension handler chain, the Negotiation-T
 * auto-loop, the global EventCallback, and the ControlPoint/
 * ExtensionCallback wiring. All wire-level work (client runtime, auth,
 * SSE event extraction) delegates to the transport.
 *
 * <p>One-shot pre-positioning sends (Authorization-T / Notification-T)
 * are a separate concern and live on {@link DefaultExtensionSender}.
 */
public class DefaultWorkflowEngineClient implements WorkflowEngineClient, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DefaultWorkflowEngineClient.class);

    private final A2ATransport transport;
    private final ExtensionRegistry extensionRegistry;
    private final int maxNegotiationRounds;
    private EventCallback eventCallback = new EventCallback();
    private ControlPoint controlPoint;
    private com.openan.a2at.engine.control.ExtensionCallback extensionCallback;

    public DefaultWorkflowEngineClient(A2ATransport transport, int maxNegotiationRounds,
                                       List<ExtensionHandler> customHandlers) {
        this.transport = transport;
        this.extensionRegistry = new ExtensionRegistry();
        if (customHandlers != null) {
            for (ExtensionHandler h : customHandlers) {
                extensionRegistry.register(h);
            }
        }
        this.maxNegotiationRounds = maxNegotiationRounds;
        log.info("[EngineClient] Initialized over transport ({} agent(s)), maxNeg={}",
                transport.getAgentNames().size(), maxNegotiationRounds);
    }

    public DefaultWorkflowEngineClient(A2ATransport transport) {
        this(transport, 3, null);
    }

    // ------------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------------

    @Override
    public void setControlPoint(ControlPoint controlPoint) {
        this.controlPoint = controlPoint;
    }

    @Override
    public void setExtensionCallback(com.openan.a2at.engine.control.ExtensionCallback extensionCallback) {
        this.extensionCallback = extensionCallback;
    }

    @Override
    public void setEventCallback(EventCallback callback) {
        this.eventCallback = callback != null ? callback : new EventCallback();
    }

    @Override
    public void registerHandler(ExtensionHandler handler) {
        extensionRegistry.register(handler);
    }

    @Override
    public List<String> getAgentNames() {
        return transport.getAgentNames();
    }

    @Override
    public void updateAgentCards(List<AgentCard> agentCards) {
        transport.updateAgentCards(agentCards);
    }

    private void emit(String type, Map<String, Object> data) {
        eventCallback.onEvent(type, data);
    }

    // ------------------------------------------------------------------
    // Workflow send path
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<SendMessageResult> sendMessage(
            String agentName, String message, String contextId, Map<String, Object> metadata) {
        AgentCard agentCard = transport.getCard(agentName);
        if (agentCard == null) {
            log.error("[EngineClient] Agent not found: {}", agentName);
            return CompletableFuture.failedFuture(new RuntimeException("Agent not found: " + agentName));
        }
        log.info("[EngineClient] send_message to {}: {} chars", agentName, message.length());
        return runBeforeSendHandlers(agentCard, message, metadata)
                .thenCompose(processedMetadata -> {
                    emit(EventType.AGENT_REQUEST, Map.of(
                            "agent", agentName,
                            "request", message,
                            "metadata", processedMetadata != null ? processedMetadata : Map.of()));
                    String ctx = contextId != null ? contextId : transport.getContextId();
                    return transport.send(agentCard, agentName, message, ctx, processedMetadata,
                                    event -> forwardIntermediateEvent(event, agentName))
                            .thenCompose(result -> runAfterReceiveHandlers(agentCard, result))
                            .thenCompose(result -> autoNegotiate(agentCard, agentName, message, ctx, result, 1));
                });
    }

    // ------------------------------------------------------------------
    // Auto-negotiation
    // ------------------------------------------------------------------

    private CompletableFuture<SendMessageResult> autoNegotiate(
            AgentCard agentCard, String agentName, String originalMessage,
            String contextId, SendMessageResult result, int round) {
        if (!isNegotiationNeeded(result) || round > maxNegotiationRounds) {
            emit(EventType.AGENT_RESPONSE, Map.of("agent", agentName, "response", result.getText()));
            return CompletableFuture.completedFuture(result);
        }
        Map<String, Object> negMeta = result.getMetadata() != null ? result.getMetadata() : new HashMap<>();
        String negText = negMeta.getOrDefault("negotiation_message", "").toString();
        log.info("[Negotiation] Round {} for '{}': {}", round, agentName, negText);
        emit(EventType.NEGOTIATION_REQUEST, Map.of("agent", agentName, "round", round, "concern", negText));
        CompletableFuture<String> clarFuture;
        if (controlPoint != null) {
            clarFuture = controlPoint.onNegotiation(agentName, negText, negMeta);
        } else {
            clarFuture = CompletableFuture.completedFuture("Please proceed with the original task using available information.");
        }
        return clarFuture.thenCompose(clarification -> {
            if (clarification == null || clarification.isEmpty()) {
                emit(EventType.NEGOTIATION_FAILED, Map.of("agent", agentName, "round", round, "reason", "no clarification"));
                emit(EventType.AGENT_RESPONSE, Map.of("agent", agentName, "response", result.getText()));
                return CompletableFuture.completedFuture(result);
            }
            log.info("[Negotiation] Clarification for '{}' round {}: {}", agentName, round, clarification);
            emit(EventType.NEGOTIATION_RESOLVED, Map.of("agent", agentName, "round", round, "clarification", clarification));
            String followUp = "[NEGOTIATION_RESOLUTION]\nThe engine has reviewed your negotiation request and provides the following clarification:\n\n" + clarification + "\n\n---\nOriginal Task:\n" + originalMessage + "\n\nPlease re-execute the task based on the clarification above.";
            Map<String, Object> followUpMeta = new HashMap<>();
            followUpMeta.put("https://projects.tmforum.org/a2aproject/telecommunication/extensions/NEGOTIATION-T",
                    "## 数据返回确认\n" + clarification + "\n");
            return runBeforeSendHandlers(agentCard, followUp, followUpMeta)
                    .thenCompose(meta -> {
                        String ctx = contextId != null ? contextId : transport.getContextId();
                        return transport.send(agentCard, agentName, followUp, ctx, meta,
                                        event -> forwardIntermediateEvent(event, agentName))
                                .thenCompose(r -> runAfterReceiveHandlers(agentCard, r))
                                .thenCompose(r -> autoNegotiate(agentCard, agentName, originalMessage, contextId, r, round + 1));
                    });
        });
    }

    private static boolean isNegotiationNeeded(SendMessageResult result) {
        return result.getTaskState() != null && result.getTaskState().contains("INPUT_REQUIRED");
    }

    // ------------------------------------------------------------------
    // Extension handler chain
    // ------------------------------------------------------------------

    private CompletableFuture<Map<String, Object>> runBeforeSendHandlers(
            AgentCard agentCard, String message, Map<String, Object> presetMetadata) {
        Map<String, Object> metadata = presetMetadata != null ? new HashMap<>(presetMetadata) : new HashMap<>();
        List<String> extUris = A2ATransport.extractExtensionUris(agentCard);
        List<ExtensionHandler> handlers = extensionRegistry.getHandlersForExtensions(extUris);
        CompletableFuture<Map<String, Object>> future = CompletableFuture.completedFuture(metadata);
        for (ExtensionHandler handler : handlers) {
            future = future.thenCompose(m -> handler.beforeSend(agentCard, message, m, transport.getA2atClient(), controlPoint));
        }
        return future;
    }

    private CompletableFuture<SendMessageResult> runAfterReceiveHandlers(AgentCard agentCard, SendMessageResult result) {
        List<String> extUris = A2ATransport.extractExtensionUris(agentCard);
        List<ExtensionHandler> handlers = extensionRegistry.getHandlersForExtensions(extUris);
        CompletableFuture<SendMessageResult> future = CompletableFuture.completedFuture(result);
        for (ExtensionHandler handler : handlers) {
            future = future.thenCompose(r -> handler.afterReceive(agentCard, r, transport.getA2atClient(), controlPoint, extensionCallback, eventCallback));
        }
        return future;
    }

    // ------------------------------------------------------------------
    // Intermediate event forwarding
    // ------------------------------------------------------------------

    private void forwardIntermediateEvent(ClientEvent event, String agentName) {
        if (event instanceof TaskUpdateEvent tue) {
            if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
                String state = sue.status().state().name();
                StringBuilder statusText = new StringBuilder();
                A2ATransport.extractTextFromMessage(sue.status().message(), statusText);
                Map<String, Object> data = new HashMap<>();
                data.put("agent", agentName);
                data.put("state", state);
                data.put("is_final", sue.isFinal());
                if (!statusText.isEmpty()) data.put("text", statusText.toString());
                if (sue.metadata() != null && !sue.metadata().isEmpty()) data.put("metadata", sue.metadata());
                log.info("[EngineClient] Agent {} status update: {} (final={})", agentName, state, sue.isFinal());
                emit(EventType.AGENT_STATUS_UPDATE, data);
            } else if (tue.getUpdateEvent() instanceof org.a2aproject.sdk.spec.TaskArtifactUpdateEvent ae) {
                StringBuilder text = new StringBuilder();
                A2ATransport.extractTextFromArtifact(ae.artifact(), text);
                Map<String, Object> data = new HashMap<>();
                data.put("agent", agentName);
                data.put("artifact_id", ae.artifact().artifactId());
                data.put("artifact_name", ae.artifact().name());
                data.put("append", ae.append());
                data.put("last_chunk", ae.lastChunk());
                if (!text.isEmpty()) data.put("text", text.toString());
                if (ae.metadata() != null && !ae.metadata().isEmpty()) data.put("metadata", ae.metadata());
                log.info("[EngineClient] Agent {} artifact update: {} ({})", agentName, ae.artifact().name(), ae.artifact().artifactId());
                emit(EventType.AGENT_ARTIFACT_UPDATE, data);
            }
        } else if (event instanceof MessageEvent me) {
            Message msg = me.getMessage();
            StringBuilder text = new StringBuilder();
            A2ATransport.extractTextFromMessage(msg, text);
            Map<String, Object> data = new HashMap<>();
            data.put("agent", agentName);
            data.put("role", msg.role().name());
            if (!text.isEmpty()) data.put("text", text.toString());
            if (msg.metadata() != null && !msg.metadata().isEmpty()) {
                data.put("metadata", msg.metadata());
            }
            log.info("[EngineClient] Agent {} message event: {} chars", agentName, text.length());
            emit(EventType.AGENT_MESSAGE_EVENT, data);
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public void close() {
        // Transport is owned by the caller; do not close it here.
    }
}