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

package dev.openan.workflow.engine.client;

import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.control.ExtensionCallback;
import dev.openan.workflow.engine.model.SendMessageResult;

import net.openan.a2at.sdk.client.A2ATClient;

import org.a2aproject.sdk.spec.AgentCard;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Extension handler for A2A-T extensions (SDK-internal).
 *
 * <p>The in-workflow handler chain registers Task-T and Negotiation-T. Task-T generates the
 * structured task prompt on send; Negotiation-T extracts the negotiation context on receive and
 * feeds the auto-loop.
 *
 * <p>Authorization-T / Notification-T are pre-positioning operations (one-shot, via {@link
 * ExtensionSender#sendExtensionMessage}), so they are not part of this in-workflow chain. Reactive
 * hooks for agent-pushed Authorization-T / Notification-T data, when handlers for those types are
 * registered manually, delegate to {@link ExtensionCallback}.
 */
public interface ExtensionHandler {

    /** The extension keyword (e.g. "Task-T", "Negotiation-T"). */
    String extensionKeyword();

    /**
     * Called before sending a message to an agent. May modify the metadata (e.g. inject Task-T
     * prompt).
     *
     * @param agentCard the agent's card as a map
     * @param messageText the message being sent
     * @param metadata current metadata (mutable)
     * @param a2atClient optional A2ATClient (may be null)
     * @param controlPoint optional ControlPoint (may be null)
     * @return updated metadata
     */
    CompletableFuture<Map<String, Object>> beforeSend(
            AgentCard agentCard,
            String messageText,
            Map<String, Object> metadata,
            A2ATClient a2atClient,
            ControlPoint controlPoint);

    /**
     * Called after receiving a response from an agent. May inspect/modify the result (e.g. handle
     * negotiation, authorization).
     *
     * @param agentCard the agent's card as a map
     * @param result the received result
     * @param a2atClient optional A2ATClient (may be null)
     * @param controlPoint optional ControlPoint (may be null)
     * @param extensionCallback optional ExtensionCallback (may be null)
     * @param eventCallback optional event callback (may be null)
     * @return updated result
     */
    CompletableFuture<SendMessageResult> afterReceive(
            AgentCard agentCard,
            SendMessageResult result,
            A2ATClient a2atClient,
            ControlPoint controlPoint,
            ExtensionCallback extensionCallback,
            EventCallback eventCallback);
}
