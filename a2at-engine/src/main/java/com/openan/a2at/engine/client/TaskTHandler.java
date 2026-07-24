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
import net.openan.a2at.sdk.client.model.PromptGenerationResult;
import org.a2aproject.sdk.spec.AgentCard;
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
    public CompletableFuture<Map<String, Object>> beforeSend(
            AgentCard agentCard,
            String messageText,
            Map<String, Object> metadata,
            A2ATClient a2atClient,
            ControlPoint controlPoint
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
            PromptGenerationResult promptResult = a2atClient.generateTaskPrompt(messageText);
            if (promptResult.success()) {
                String promptText = promptResult.promptText();
                if (promptText != null && !promptText.isEmpty()) {
                    result.put(taskTUri, promptText);
                    log.info("[Task-T] Generated prompt for '{}': {} chars", getAgentName(agentCard), promptText.length());
                    log.debug("[Task-T] Prompt content: [{}]", promptText);
                }
            }
        } catch (Exception e) {
            log.warn("[Task-T] Failed: {}", e.getMessage());
        }
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<SendMessageResult> afterReceive(
            AgentCard agentCard,
            SendMessageResult result,
            A2ATClient a2atClient,
            ControlPoint controlPoint,
            EventCallback eventCallback
    ) {
        return CompletableFuture.completedFuture(result);
    }

    private static String findTaskTUri(AgentCard agentCard) {
        if (agentCard.capabilities() == null) {
            return null;
        }
        for (var ext : agentCard.capabilities().extensions()) {
            String uri = ext.uri();
            if (uri != null && uri.contains("Task-T")) {
                return uri;
            }
        }
        return null;
    }

    private static String getAgentName(AgentCard agentCard) {
        return agentCard.name();
    }
}
