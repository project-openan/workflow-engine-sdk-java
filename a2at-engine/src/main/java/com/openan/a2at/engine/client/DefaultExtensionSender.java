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

import com.openan.a2at.engine.model.SendMessageResult;

import net.openan.a2at.sdk.client.A2ATClient;
import net.openan.a2at.sdk.client.model.PromptGenerationFailure;
import net.openan.a2at.sdk.client.model.PromptGenerationResult;

import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Default {@link ExtensionSender} built on a shared {@link A2ATransport}.
 *
 * <p>Owns extension prompt-generation dispatch (Task-T via the A2A-T SDK; Negotiation-T /
 * Authorization-T / Notification-T reserved for future SDK support). All wire-level work delegates
 * to the transport.
 */
public record DefaultExtensionSender(A2ATransport transport)
        implements ExtensionSender, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DefaultExtensionSender.class);

    @Override
    public CompletableFuture<SendMessageResult> sendExtensionMessage(
            String agentName,
            String instruction,
            String naturalLanguageInput,
            A2ATExtension extension) {
        AgentCard agentCard = transport.getCard(agentName);
        if (agentCard == null) {
            log.error("[ExtensionSender] Agent not found: {}", agentName);
            return CompletableFuture.failedFuture(
                    new RuntimeException("Agent not found: " + agentName));
        }
        return CompletableFuture.supplyAsync(
                        () -> generateExtensionPrompt(extension, naturalLanguageInput))
                .thenCompose(
                        metadataValue -> {
                            String value = metadataValue;
                            if (value == null || value.isEmpty()) {
                                value = naturalLanguageInput;
                                log.info(
                                        "[ExtensionSender] SDK prompt generation unavailable for {} ({}), using input as metadata",
                                        agentName,
                                        extension.displayName());
                            }
                            log.info(
                                    "[ExtensionSender] sendExtensionMessage to {}: extension={}, metadataValue={} chars",
                                    agentName,
                                    extension.displayName(),
                                    value.length());
                            Map<String, Object> metadata = Map.of(extension.uri(), value);
                            if (extension == A2ATExtension.NOTIFICATION_T) {
                                return transport.sendNotificationStream(
                                        agentCard,
                                        agentName,
                                        instruction,
                                        transport.getContextId(),
                                        metadata,
                                        null);
                            }
                            return transport
                                    .send(
                                            agentCard,
                                            agentName,
                                            instruction,
                                            transport.getContextId(),
                                            metadata,
                                            null)
                                    .thenApply(
                                            result -> {
                                                log.info(
                                                        "[ExtensionSender] Extension response from {}: state={}",
                                                        agentName,
                                                        result.getTaskState());
                                                return result;
                                            });
                        });
    }

    @Override
    public CompletableFuture<SendMessageResult> sendNotification(
            String agentName,
            String instruction,
            String naturalLanguageInput,
            Consumer<Map<String, Object>> eventCallback) {
        AgentCard agentCard = transport.getCard(agentName);
        if (agentCard == null) {
            log.error("[ExtensionSender] Agent not found: {}", agentName);
            return CompletableFuture.failedFuture(
                    new RuntimeException("Agent not found: " + agentName));
        }
        return CompletableFuture.supplyAsync(
                        () ->
                                generateExtensionPrompt(
                                        A2ATExtension.NOTIFICATION_T, naturalLanguageInput))
                .thenCompose(
                        metadataValue -> {
                            String value = metadataValue;
                            if (value == null || value.isEmpty()) {
                                value = naturalLanguageInput;
                            }
                            Map<String, Object> metadata =
                                    Map.of(A2ATExtension.NOTIFICATION_T.uri(), value);
                            Consumer<ClientEvent> eventSink =
                                    eventCallback != null
                                            ? event ->
                                                    forwardNotificationEvent(
                                                            event, agentName, eventCallback)
                                            : null;
                            log.info(
                                    "[ExtensionSender] sendNotification to {}: metadataValue={} chars, callback={}",
                                    agentName,
                                    value.length(),
                                    eventCallback != null);
                            return transport.sendNotificationStream(
                                    agentCard,
                                    agentName,
                                    instruction,
                                    transport.getContextId(),
                                    metadata,
                                    eventSink);
                        });
    }

    private void forwardNotificationEvent(
            ClientEvent event, String agentName, Consumer<Map<String, Object>> callback) {
        Map<String, Object> data = new HashMap<>();
        data.put("agent", agentName);
        if (event instanceof TaskUpdateEvent tue) {
            if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
                data.put("state", sue.status().state().name());
                data.put("is_final", sue.isFinal());
                StringBuilder text = new StringBuilder();
                A2ATransport.extractTextFromMessage(sue.status().message(), text);
                if (!text.isEmpty()) data.put("text", text.toString());
                if (sue.metadata() != null && !sue.metadata().isEmpty())
                    data.put("metadata", sue.metadata());
            } else if (tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent ae) {
                StringBuilder text = new StringBuilder();
                for (Part<?> part : ae.artifact().parts()) {
                    if (part instanceof TextPart tp) text.append(tp.text());
                }
                data.put("artifact_name", ae.artifact().name());
                data.put("append", ae.append());
                if (!text.isEmpty()) data.put("text", text.toString());
                if (ae.metadata() != null && !ae.metadata().isEmpty())
                    data.put("metadata", ae.metadata());
            }
        } else if (event instanceof MessageEvent me) {
            Message msg = me.getMessage();
            StringBuilder text = new StringBuilder();
            A2ATransport.extractTextFromMessage(msg, text);
            data.put("role", msg.role().name());
            if (!text.isEmpty()) data.put("text", text.toString());
            if (msg.metadata() != null && !msg.metadata().isEmpty())
                data.put("metadata", msg.metadata());
        }
        if (!data.isEmpty()) {
            log.info("[ExtensionSender] Notification-T callback for {}: {} keys", agentName, data.keySet());
            callback.accept(data);
        }
    }

    // ------------------------------------------------------------------
    // Extension prompt generation dispatch
    // ------------------------------------------------------------------

    String generateExtensionPrompt(A2ATExtension extension, String naturalLanguageInput) {
        if (extension == A2ATExtension.TASK_T) {
            return generateTaskPromptText(naturalLanguageInput);
        }
        if (extension == A2ATExtension.NEGOTIATION_T) {
            return generateNegotiationPrompt(naturalLanguageInput);
        }
        if (extension == A2ATExtension.AUTHORIZATION_T) {
            return generateAuthorizationPrompt(naturalLanguageInput);
        }
        if (extension == A2ATExtension.NOTIFICATION_T) {
            return generateNotificationPrompt(naturalLanguageInput);
        }
        return null;
    }

    private String generateTaskPromptText(String naturalLanguageInput) {
        A2ATClient a2atClient = transport.getA2atClient();
        if (a2atClient == null) {
            return null;
        }
        try {
            PromptGenerationResult result = a2atClient.generateTaskPrompt(naturalLanguageInput);
            if (result.success()) {
                return result.promptText();
            }
            PromptGenerationFailure f = result.failure();
            log.warn(
                    "[ExtensionSender] SDK Task-T prompt generation failed: code={}, stage={}, message={}",
                    f != null ? f.code() : "unknown",
                    f != null ? f.stage() : "unknown",
                    f != null ? f.message() : "unknown");
        } catch (Exception e) {
            log.warn("[ExtensionSender] SDK Task-T prompt generation error: {}", e.getMessage());
        }
        return null;
    }

    private String generateNegotiationPrompt(String naturalLanguageInput) {
        if (transport.getA2atClient() == null) {
            return null;
        }
        return null;
    }

    private String generateAuthorizationPrompt(String naturalLanguageInput) {
        if (transport.getA2atClient() == null) {
            return null;
        }
        return null;
    }

    private String generateNotificationPrompt(String naturalLanguageInput) {
        if (transport.getA2atClient() == null) {
            return null;
        }
        return null;
    }

    @Override
    public void close() {
        // Transport is owned by the caller; do not close it here.
    }
}
