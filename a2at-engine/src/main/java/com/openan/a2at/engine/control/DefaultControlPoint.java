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

import com.openan.a2at.engine.client.WorkflowEngineClient;
import com.openan.a2at.engine.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Default ControlPoint with single-responsibility methods.
 * SDK internally handles: Negotiation-T auto-loop, Authorization-T
 * confirmation injection, Notification-T subscription.
 */
public class DefaultControlPoint implements ControlPoint {
    private static final Logger log = LoggerFactory.getLogger(DefaultControlPoint.class);

    @Override
    public CompletableFuture<TaskResponse> onTask(
            TaskRequest request, WorkflowEngineClient engineClient) {
        log.info("[DefaultCP] onTask: agent={}, step={}", request.getAgentName(), request.getStepName());
        return engineClient.sendMessage(request.getAgentName(), request.getMessage())
                .thenApply(r -> {
                    boolean success = r.getText() != null && !r.getText().isEmpty();
                    log.info("[DefaultCP] Response from {}: {} chars, success={}",
                            request.getAgentName(),
                            r.getText() != null ? r.getText().length() : 0, success);
                    return TaskResponse.builder().success(success).output(r.getText()).build();
                })
                .exceptionally(e -> {
                    log.error("[DefaultCP] Task failed for {}: {}", request.getAgentName(), e.getMessage());
                    return TaskResponse.builder().success(false).error("Agent call failed: " + e.getMessage()).build();
                });
    }

    @Override
    public CompletableFuture<RouteDecision> onRoute(
            String stepName, Map<String, Object> results,
            List<JumpCondition> conditions) {
        String nextStep = conditions.get(0).getStep();
        for (JumpCondition jc : conditions) {
            String step = jc.getStep();
            if (!"end".equals(step) && !"retry".equals(step) && !"endNode".equals(step)) {
                nextStep = step;
                break;
            }
        }
        log.info("[DefaultCP] onRoute: {} -> {}", stepName, nextStep);
        return CompletableFuture.completedFuture(
                RouteDecision.builder().nextStep(nextStep).reason("default: first non-terminal branch").build());
    }

    @Override
    public CompletableFuture<Boolean> onAuthorization(
            String agentName, Map<String, Object> authRequest) {
        log.info("[DefaultCP] onAuthorization: agent={}, auto-approving", agentName);
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Void> onNotification(
            String agentName, Map<String, Object> notification) {
        log.info("[DefaultCP] onNotification: agent={}, data={}", agentName, notification);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<String> onNegotiation(
            String agentName, String negotiationText,
            Map<String, Object> receiveResult) {
        log.info("[DefaultCP] onNegotiation: agent={}, concern={}", agentName, negotiationText);
        return CompletableFuture.completedFuture(
                "Please proceed with the original task using available information.");
    }
}
