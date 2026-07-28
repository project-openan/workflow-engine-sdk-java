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
import net.openan.a2at.sdk.client.model.PromptGenerationResult;
import net.openan.a2at.sdk.client.model.PromptGenerationFailure;
import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Default {@link ExtensionSender} built on a shared {@link A2ATransport}.
 *
 * <p>Owns extension prompt-generation dispatch (Task-T via the A2A-T SDK;
 * Negotiation-T / Authorization-T / Notification-T reserved for future SDK
 * support). All wire-level work delegates to the transport.
 */
public class DefaultExtensionSender implements ExtensionSender, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DefaultExtensionSender.class);

    private final A2ATransport transport;

    public DefaultExtensionSender(A2ATransport transport) {
        this.transport = transport;
    }

    public A2ATransport getTransport() {
        return transport;
    }

    @Override
    public CompletableFuture<SendMessageResult> sendExtensionMessage(
            String agentName, String instruction,
            String naturalLanguageInput, A2ATExtension extension) {
        AgentCard agentCard = transport.getCard(agentName);
        if (agentCard == null) {
            log.error("[ExtensionSender] Agent not found: {}", agentName);
            return CompletableFuture.failedFuture(new RuntimeException("Agent not found: " + agentName));
        }
        String metadataValue = generateExtensionPrompt(extension, naturalLanguageInput);
        if (metadataValue == null || metadataValue.isEmpty()) {
            metadataValue = naturalLanguageInput;
            log.info("[ExtensionSender] SDK prompt generation unavailable for {} ({}), using input as metadata",
                    agentName, extension.displayName());
        }
        log.info("[ExtensionSender] sendExtensionMessage to {}: extension={}, metadataValue={} chars",
                agentName, extension.displayName(), metadataValue.length());
        Map<String, Object> metadata = Map.of(extension.uri(), metadataValue);
        if (extension == A2ATExtension.NOTIFICATION_T) {
            return transport.sendNotificationStream(agentCard, agentName, instruction, transport.getContextId(), metadata, null);
        }
        return transport.send(agentCard, agentName, instruction, transport.getContextId(), metadata, null)
                .thenApply(result -> {
                    log.info("[ExtensionSender] Extension response from {}: state={}", agentName, result.getTaskState());
                    return result;
                });
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
            log.warn("[ExtensionSender] SDK Task-T prompt generation failed: code={}, stage={}, message={}",
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