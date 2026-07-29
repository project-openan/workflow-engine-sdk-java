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
package com.openan.a2at.engine.examples.agents;

import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Handles pre-positioned Authorization-T / Notification-T messages on the agent server side.
 *
 * <p>This is a <b>separate concern</b> from Negotiation-T. Pre-positioned extensions are sent by
 * the orchestrator <i>before</i> the workflow starts (via {@code
 * WorkflowEngineClient.sendExtensionMessage}) to establish whitelists and subscriptions. When an
 * agent receives one, it must:
 *
 * <ol>
 *   <li>Store the payload for later use during business execution
 *   <li>Acknowledge receipt with a short artifact
 *   <li>Complete the task immediately (no negotiation, no business logic)
 * </ol>
 *
 * <p>Extracted from {@code NegotiationBaseAgentExecutor} so that the latter only carries
 * Negotiation-T responsibility.
 */
public class PrePositionedExtensionHandler {
    private static final Logger log = LoggerFactory.getLogger(PrePositionedExtensionHandler.class);
    private volatile String authorizationPolicy;
    private volatile String notificationSubscription;

    /** The pre-positioned Authorization-T whitelist policy text, or null. */
    public String getAuthorizationPolicy() {
        return authorizationPolicy;
    }

    /** The pre-positioned Notification-T subscription text, or null. */
    public String getNotificationSubscription() {
        return notificationSubscription;
    }

    /**
     * Detect whether the incoming message carries an Authorization-T or Notification-T extension in
     * its metadata. Returns the extension keyword ("Authorization-T" or "Notification-T"), or null
     * for a normal task.
     */
    public static String detect(RequestContext ctx) {
        Message msg = ctx.getMessage();
        if (msg == null || msg.metadata() == null) {
            return null;
        }
        for (String key : msg.metadata().keySet()) {
            if (key.contains("Authorization-T")) {
                return "Authorization-T";
            }
            if (key.contains("Notification-T")) {
                return "Notification-T";
            }
        }
        return null;
    }

    /**
     * Handle a pre-positioned message: store the payload, emit an ACK artifact, and complete the
     * task.
     *
     * @param agentTag short agent class name for logging
     */
    public void handle(
            RequestContext ctx, AgentEmitter emitter, String extKeyword, String agentTag) {
        String taskId = ctx.getTaskId();
        String contextId = ctx.getContextId();
        String payloadText =
                ctx.getMessage().metadata().entrySet().stream()
                        .filter(e -> e.getKey().contains(extKeyword))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .map(v -> v instanceof String s ? s : String.valueOf(v))
                        .orElse("");
        if (extKeyword.contains("Authorization")) {
            authorizationPolicy = payloadText;
        } else if (extKeyword.contains("Notification")) {
            notificationSubscription = payloadText;
        }
        log.info(
                "[{}] Pre-positioned {} received, payload length={}",
                agentTag,
                extKeyword,
                payloadText.length());
        String ackText = extKeyword + " pre-positioning acknowledged";
        List<Part<?>> parts = List.of(new TextPart(ackText));
        emitter.addArtifact(parts, "result", agentTag + " ack", Map.of(), false, true);
        emitStatus(
                emitter,
                TaskState.TASK_STATE_COMPLETED,
                contextId,
                taskId,
                extKeyword + " pre-positioned successfully",
                Map.of());
        emitter.complete(BaseAgentExecutor.buildStatusMessage(contextId, taskId, "Completed"));
        log.info("[{}] {} pre-positioning completed", agentTag, extKeyword);
    }

    private static void emitStatus(
            AgentEmitter emitter,
            TaskState state,
            String contextId,
            String taskId,
            String text,
            Map<String, Object> metadata) {
        TaskStatus status =
                new TaskStatus(
                        state, BaseAgentExecutor.buildStatusMessage(contextId, taskId, text), null);
        TaskStatusUpdateEvent event =
                TaskStatusUpdateEvent.builder()
                        .taskId(taskId)
                        .contextId(contextId)
                        .status(status)
                        .metadata(metadata)
                        .build();
        emitter.emitEvent(event);
    }
}
