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

import com.openan.a2at.engine.control.ControlPoint;
import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.model.SendMessageResult;

import org.a2aproject.sdk.spec.AgentCard;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Workflow-execution send facade over a shared {@link A2ATransport}.
 *
 * <p>Single responsibility: the workflow execution send path. This facade owns Task-T prompt
 * generation, the Negotiation-T auto-loop, the global {@link EventCallback}, and the ControlPoint /
 * ExtensionCallback wiring. All wire-level work (client runtime, auth, SSE event extraction)
 * delegates to the transport.
 *
 * <p>One-shot pre-positioning (Authorization-T / Notification-T) is a separate concern and lives on
 * {@link ExtensionSender}; callers that only need pre-positioning hold that lighter facade over the
 * same transport.
 *
 * <p>The single message type on this facade:
 *
 * <ul>
 *   <li>{@link #sendMessage} - streaming send used during workflow execution (invoked from {@link
 *       ControlPoint#onTask}). Runs through Task-T prompt generation, the Negotiation-T auto-loop,
 *       and the global {@link EventCallback}.
 * </ul>
 */
public interface WorkflowEngineClient {

    /**
     * Send a message to an agent via SSE streaming. Used during workflow execution. The engine
     * handles Task-T prompt generation, Negotiation-T auto-loop, auth, and extension header
     * injection automatically.
     *
     * @param agentName target agent name (must match AgentCard.name)
     * @param message full assembled message text
     * @param contextId optional context ID (null = auto-generated)
     * @param metadata optional preset metadata
     * @return future completing with response text, task, metadata, task state
     */
    CompletableFuture<SendMessageResult> sendMessage(
            String agentName, String message, String contextId, Map<String, Object> metadata);

    /** Convenience: no context ID, no preset metadata. */
    default CompletableFuture<SendMessageResult> sendMessage(String agentName, String message) {
        return sendMessage(agentName, message, null, null);
    }

    void setControlPoint(ControlPoint controlPoint);

    void setExtensionCallback(com.openan.a2at.engine.control.ExtensionCallback extensionCallback);

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
