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

package com.openan.a2at.engine.control;

import com.openan.a2at.engine.model.TaskRequest;
import com.openan.a2at.engine.model.TaskResponse;
import com.openan.a2at.engine.model.RouteDecision;
import com.openan.a2at.engine.model.JumpCondition;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * User-facing decision interface.
 * Each method has a single responsibility.
 * SDK internally handles all A2A-T protocol mechanics (Task-T prompt generation,
 * Negotiation-T loop, Authorization-T injection, Notification-T subscription).
 * ControlPoint methods only make business decisions.
 */
public interface ControlPoint {

    /**
     * Send a Task-T message to an agent. Just call sendMessage.
    * SDK internally handles: Task-T prompt generation (beforeSend),
    * negotiation auto-loop (calls onNegotiation if INPUT_REQUIRED),
    * Authorization-T confirmation injection, Notification-T subscription.
     * Just call sendMessage - the SDK handles the rest.
    */
   CompletableFuture<TaskResponse> onTask(TaskRequest request, com.openan.a2at.engine.client.WorkflowEngineClient engineClient);

    /**
     * Conditional branch decision. Only decide which step to go to.
     * Do NOT send messages here.
     */
    CompletableFuture<RouteDecision> onRoute(String stepName, Map<String, Object> results, List<JumpCondition> conditions);

    /**
     * Authorization approval decision. Return true/false.
     * Do NOT send Authorization-T confirmation - SDK injects it automatically
     * into the next sendMessage via beforeSend. Default: auto-approve.
     */
    default CompletableFuture<Boolean> onAuthorization(String agentName, Map<String, Object> authRequest) {
        return CompletableFuture.completedFuture(true);
    }

    /**
     * Handle a received Notification-T. Do NOT send messages here. Default: no-op.
     */
    default CompletableFuture<Void> onNotification(String agentName, Map<String, Object> notification) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Provide supplementary data when an agent returns INPUT_REQUIRED
     * (Negotiation-T). Return the clarification text - the SDK internally
     * resends the follow-up message. Do NOT send messages here.
     * Default: returns a generic clarification.
     */
    default CompletableFuture<String> onNegotiation(String agentName, String negotiationText, Map<String, Object> receiveResult) {
        return CompletableFuture.completedFuture("Please proceed with the original task using available information.");
    }
}
