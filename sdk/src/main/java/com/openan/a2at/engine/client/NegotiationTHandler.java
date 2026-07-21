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
            Map<String, Object> agentCard,
            String messageText,
            Map<String, Object> metadata,
            Object a2atClient,
            Object controlPoint
    ) {
        return CompletableFuture.completedFuture(metadata);
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<SendMessageResult> afterReceive(
            Map<String, Object> agentCard,
            SendMessageResult result,
            Object a2atClient,
            Object controlPoint,
            Object eventCallback
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
            // Call a2atClient.receiveNegotiation(message, context) via reflection
            Object receiveResult = a2atClient.getClass()
                    .getMethod("receiveNegotiation", String.class, Map.class)
                    .invoke(a2atClient, result.getText(), metadata);
            if (receiveResult instanceof Map) {
                Map<String, Object> rr = (Map<String, Object>) receiveResult;
                Boolean needResponse = (Boolean) rr.get("needResponse");
                if (Boolean.TRUE.equals(needResponse)) {
                    String negMsg = (String) rr.getOrDefault("message", "");
                    metadata.put("negotiation_message", negMsg);
                    metadata.put("negotiation_context", rr);
                    log.info("[Negotiation-T] Agent '{}' requested negotiation", getAgentName(agentCard));
                }
            }
        } catch (Exception e) {
            log.warn("[Negotiation-T] Failed: {}", e.getMessage());
        }
        result.setMetadata(metadata);
        return CompletableFuture.completedFuture(result);
    }

    @SuppressWarnings("unchecked")
    private static boolean supportsNegotiation(Map<String, Object> agentCard) {
        Map<String, Object> caps = (Map<String, Object>) agentCard.get("capabilities");
        if (caps == null) {
            return false;
        }
        List<Map<String, Object>> extensions = (List<Map<String, Object>>) caps.get("extensions");
        if (extensions == null) {
            return false;
        }
        for (Map<String, Object> ext : extensions) {
            String uri = (String) ext.get("uri");
            if (uri != null && uri.contains("NEGOTIATION-T")) {
                return true;
            }
        }
        return false;
    }

    private static String getAgentName(Map<String, Object> agentCard) {
        Object name = agentCard.get("name");
        return name != null ? name.toString() : "?";
    }
}
