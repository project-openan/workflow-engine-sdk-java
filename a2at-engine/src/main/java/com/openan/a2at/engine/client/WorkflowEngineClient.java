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

package com.openan.a2at.engine.client;

import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.control.ControlPoint;
import com.openan.a2at.engine.model.SendMessageResult;
import org.a2aproject.sdk.spec.AgentCard;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Communication client for sending A2A messages to remote agents.
 * Handles: AgentCard lookup, client creation, auth, A2A-T extensions,
 * streaming response handling, text extraction.
 *
 * The user calls sendMessage() from their ControlPoint implementation.
 */
public interface WorkflowEngineClient {

    /**
     * Send a message to an agent. Returns SendMessageResult with text, task, metadata, taskState.
     *
     * @param agentName  target agent name (must match AgentCard.name)
     * @param message    full assembled message text
     * @param contextId  optional context ID (null = auto-generated)
     * @param metadata   optional preset metadata (e.g., Task-T stub prompts)
     */
    CompletableFuture<SendMessageResult> sendMessage(String agentName, String message, String contextId, Map<String, Object> metadata);

    /** Convenience: no context ID, no preset metadata. */
    default CompletableFuture<SendMessageResult> sendMessage(String agentName, String message) {
        return sendMessage(agentName, message, null, null);
    }

    /**
     * Send a one-shot extension message (e.g. Authorization-T pre-positioning,
     * Notification-T subscription) to an agent. The metadata value is generated
     * by the A2A-T SDK (LLM + prompt template) from the natural-language input.
     * If the SDK cannot generate (no matching scenario or unavailable), the
     * natural-language input is used as-is. Bypasses Task-T prompt generation
     * and Negotiation-T auto-loop.
     *
     * @param agentName           target agent name
     * @param instruction         short instruction text (becomes message parts)
     * @param naturalLanguageInput natural-language input for SDK prompt generation
     * @param extensionUri        full extension URI (becomes metadata key + A2A-Extensions header)
     */
    default CompletableFuture<SendMessageResult> sendExtensionMessage(
            String agentName, String instruction, String naturalLanguageInput, String extensionUri) {
        // Default: use the natural-language input directly as metadata value
        return sendMessage(agentName, instruction, null, Map.of(extensionUri, naturalLanguageInput));
    }

    void setControlPoint(ControlPoint controlPoint);
    void setEventCallback(EventCallback callback);
    void close();
    java.util.List<String> getAgentNames();

    /** Update the agent card map (e.g. after fetching new cards). */
    default void updateAgentCards(java.util.List<AgentCard> agentCards) {
        // Default no-op; implementations override
    }

    /** Register a custom extension handler. */
    default void registerHandler(ExtensionHandler handler) {
        // Default no-op; implementations override
    }
}
