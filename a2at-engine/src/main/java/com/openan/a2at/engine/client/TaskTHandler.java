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
 * Task-T extension handler.
 *
 * <p>Mirrors the Python SDK's {@code TaskTHandler}. When an AgentCard
 * declares the Task-T extension, this handler calls the A2ATClient to
 * generate a structured task prompt and injects it into the message metadata.
 */
public class TaskTHandler implements ExtensionHandler {

    private static final Logger log = LoggerFactory.getLogger(TaskTHandler.class);

    @Override
    public String extensionKeyword() {
        return "Task-T";
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> beforeSend(
            Map<String, Object> agentCard,
            String messageText,
            Map<String, Object> metadata,
            Object a2atClient,
            Object controlPoint
    ) {
        if (a2atClient == null) {
            return CompletableFuture.completedFuture(metadata);
        }
        // Skip Task-T prompt generation for negotiation follow-up tasks
        if (messageText != null && messageText.contains("[NEGOTIATION_RESOLUTION]")) {
            log.info("[Task-T] Skipping prompt generation for negotiation follow-up");
            return CompletableFuture.completedFuture(metadata);
        }
        String taskTUri = findTaskTUri(agentCard);
        if (taskTUri == null) {
            return CompletableFuture.completedFuture(metadata);
        }
        // Skip if caller already pre-set the Task-T prompt in metadata
        Map<String, Object> result = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        if (result.containsKey(taskTUri)) {
            log.info("[Task-T] Metadata already preset, skipping generation");
            return CompletableFuture.completedFuture(result);
        }
        try {
            // Call a2atClient.generateTaskPrompt(messageText) via reflection
            Object promptResult = a2atClient.getClass()
                    .getMethod("generateTaskPrompt", Object.class)
                    .invoke(a2atClient, messageText);
            // Extract prompt text from PromptGenerationResult
            Boolean success = (Boolean) promptResult.getClass().getMethod("success").invoke(promptResult);
            if (Boolean.TRUE.equals(success)) {
                String promptText = (String) promptResult.getClass()
                        .getMethod("promptText").invoke(promptResult);
                if (promptText != null && !promptText.isEmpty()) {
                    result.put(taskTUri, promptText);
                    log.info("[Task-T] Generated prompt for '{}'", getAgentName(agentCard));
                }
            }
        } catch (Exception e) {
            log.warn("[Task-T] Failed: {}", e.getMessage());
        }
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<SendMessageResult> afterReceive(
            Map<String, Object> agentCard,
            SendMessageResult result,
            Object a2atClient,
            Object controlPoint,
            Object eventCallback
    ) {
        return CompletableFuture.completedFuture(result);
    }

    @SuppressWarnings("unchecked")
    private static String findTaskTUri(Map<String, Object> agentCard) {
        Map<String, Object> caps = (Map<String, Object>) agentCard.get("capabilities");
        if (caps == null) {
            return null;
        }
        List<Map<String, Object>> extensions = (List<Map<String, Object>>) caps.get("extensions");
        if (extensions == null) {
            return null;
        }
        for (Map<String, Object> ext : extensions) {
            String uri = (String) ext.get("uri");
            if (uri != null && uri.contains("Task-T")) {
                return uri;
            }
        }
        return null;
    }

    private static String getAgentName(Map<String, Object> agentCard) {
        Object name = agentCard.get("name");
        return name != null ? name.toString() : "?";
    }
}
