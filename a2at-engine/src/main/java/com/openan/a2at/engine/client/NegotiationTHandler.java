/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.openan.a2at.engine.client;

import com.openan.a2at.engine.model.SendMessageResult;
import com.openan.a2at.engine.control.ControlPoint;
import com.openan.a2at.engine.control.EventCallback;
import net.openan.a2at.sdk.client.A2ATClient;
import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Negotiation-T extension handler.
 *
 * <p>Mirrors the Python SDK's {@code NegotiationTHandler}. When an agent
 * returns INPUT_REQUIRED and supports Negotiation-T, this handler calls
 * the A2ATClient to extract the negotiation context and message.
 */
public class NegotiationTHandler implements ExtensionHandler {

    private static final Logger log = LoggerFactory.getLogger(NegotiationTHandler.class);

    @Override
    public String extensionKeyword() {
        return "Negotiation-T";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeSend(
            AgentCard agentCard,
            String messageText,
            Map<String, Object> metadata,
            A2ATClient a2atClient,
            ControlPoint controlPoint
    ) {
        return CompletableFuture.completedFuture(metadata);
    }

    @Override
    public CompletableFuture<SendMessageResult> afterReceive(
            AgentCard agentCard,
            SendMessageResult result,
            A2ATClient a2atClient,
            ControlPoint controlPoint,
            EventCallback eventCallback
    ) {
        // Only process if ALL three conditions are met:
        //   1. a2atClient available (for receive_negotiation API)
        //   2. Agent returned INPUT_REQUIRED (wants to negotiate)
        //   3. AgentCard declares Negotiation-T extension
        // Otherwise pass through unchanged.
        if (a2atClient == null
                || result.getTaskState() == null
                || !result.getTaskState().contains("INPUT_REQUIRED")
                || !supportsNegotiation(agentCard)) {
            return CompletableFuture.completedFuture(result);
        }
        Map<String, Object> metadata = result.getMetadata() != null
                ? new HashMap<>(result.getMetadata()) : new HashMap<>();
        try {
            // The negotiation context is nested under the DATA-NEGOTIATION-T key
            // in the task metadata, not at the top level. Extract it before
            // calling receiveNegotiation, which expects the context map directly.
            Map<String, Object> contextMap = extractNegotiationContext(metadata);
            if (contextMap == null) {
                contextMap = metadata;
            }
            Map<String, Object> receiveResult = a2atClient.receiveNegotiation(
                    result.getText(), contextMap);
            {
                Map<String, Object> rr = receiveResult;
                Boolean needResponse = (Boolean) rr.get("needResponse");
                if (Boolean.TRUE.equals(needResponse)) {
                    String negMsg = (String) rr.getOrDefault("message", "");
                    metadata.put("negotiation_message", negMsg);
                    metadata.put("negotiation_context", rr);
                    log.info("[Negotiation-T] Agent '{}' requested negotiation: {}", getAgentName(agentCard), negMsg);
                }
            }
        } catch (Exception e) {
            log.warn("[Negotiation-T] receiveNegotiation failed for '{}': {}, using fallback",
                    getAgentName(agentCard), e.getMessage());
            // Fallback: extract negotiation text directly from metadata
            String fallbackText = extractNegotiationText(metadata);
            if (fallbackText != null && !fallbackText.isEmpty()) {
                metadata.put("negotiation_message", fallbackText);
                log.info("[Negotiation-T] Agent '{}' requested negotiation (fallback): {}",
                        getAgentName(agentCard), fallbackText);
            }
        }
        result.setMetadata(metadata);
        return CompletableFuture.completedFuture(result);
    }


    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractNegotiationContext(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        for (var entry : metadata.entrySet()) {
            if (entry.getKey().contains("DATA-NEGOTIATION-T") && entry.getValue() instanceof Map) {
                return (Map<String, Object>) entry.getValue();
            }
        }
        return null;
    }


    private static String extractNegotiationText(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        for (var entry : metadata.entrySet()) {
            if (entry.getKey().contains("NEGOTIATION-T")
                    && !entry.getKey().contains("DATA-NEGOTIATION-T")
                    && entry.getValue() instanceof String) {
                return (String) entry.getValue();
            }
        }
        return null;
    }

    private static boolean supportsNegotiation(AgentCard agentCard) {
        if (agentCard.capabilities() == null) {
            return false;
        }
        for (var ext : agentCard.capabilities().extensions()) {
            String uri = ext.uri();
            if (uri != null && uri.contains("NEGOTIATION-T")) {
                return true;
            }
        }
        return false;
    }

    private static String getAgentName(AgentCard agentCard) {
        return agentCard.name();
    }
}
