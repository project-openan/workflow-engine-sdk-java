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

import com.openan.a2at.engine.model.JumpCondition;
import com.openan.a2at.engine.model.RouteDecision;
import com.openan.a2at.engine.model.TaskRequest;
import com.openan.a2at.engine.model.TaskResponse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Workflow-control decision interface.
 *
 * <p>Each method drives the workflow forward and is called by the {@link
 * com.openan.a2at.engine.core.WorkflowExecutor} (onTask / onSelfTask / onRoute) or the client
 * auto-negotiate loop (onNegotiation). Reactive hooks for agent-pushed A2A-T data live on {@link
 * ExtensionCallback} instead. ControlPoint methods make business decisions; the SDK owns all A2A-T
 * protocol mechanics.
 */
public interface ControlPoint {

    /**
     * Send a task to an agent. Call {@code engineClient.sendMessage(...)}.
     *
     * <p>The SDK handles Task-T prompt generation, the Negotiation-T auto-loop (calling {@link
     * #onNegotiation} on INPUT_REQUIRED), auth, and extension header injection. Just send the
     * message.
     */
    CompletableFuture<TaskResponse> onTask(
            TaskRequest request, com.openan.a2at.engine.client.WorkflowEngineClient engineClient);

    /**
     * Handle a self-loop task locally. Called when a workflow step is marked SELF_LOOP: the agent
     * executing the workflow processes the task itself, WITHOUT sending an A2A-T message to itself.
     * Only steps that dispatch to OTHER agents go through {@link #onTask} and the A2A-T protocol.
     *
     * <p>No {@code engineClient} is passed on purpose: self-loop tasks must not send A2A-T
     * messages. Implement this to handle local aggregation, merge, or any business logic the
     * workflow-executing agent owns.
     *
     * <p>Default: echoes the task message back as the output.
     */
    default CompletableFuture<TaskResponse> onSelfTask(TaskRequest request) {
        return CompletableFuture.completedFuture(
                TaskResponse.builder().success(true).output(request.getMessage()).build());
    }

    /**
     * Branch decision at a conditional step. Return the next step to take. Only decide which
     * branch; do not send messages here.
     */
    CompletableFuture<RouteDecision> onRoute(
            String stepName, Map<String, Object> results, List<JumpCondition> conditions);

    /**
     * Provide supplementary data when an agent returns INPUT_REQUIRED (Negotiation-T). Return the
     * clarification text - the SDK internally resends the follow-up message. Do NOT send messages
     * here. Default: returns a generic clarification.
     */
    default CompletableFuture<String> onNegotiation(
            String agentName, String negotiationText, Map<String, Object> receiveResult) {
        return CompletableFuture.completedFuture(
                "Please proceed with the original task using available information.");
    }
}
