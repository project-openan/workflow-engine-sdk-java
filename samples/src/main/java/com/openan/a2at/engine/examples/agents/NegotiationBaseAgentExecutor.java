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

package com.openan.a2at.engine.examples.agents;

import net.openan.a2at.sdk.client.A2ATClient;
import net.openan.a2at.sdk.negotiation.types.model.NegotiationType;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Server-side negotiation base, mirroring the Python reference
 * (orchestration-center/samples/agents/negotiation_base_agent.py).
 *
 * <p>Every agent that declares the Negotiation-T extension MUST be able to
 * receive and reply to negotiation messages. This base implements that
 * capability: on a new task it starts a fulfillment negotiation and replies
 * with INPUT_REQUIRED carrying the negotiation context in task metadata; on a
 * follow-up ([NEGOTIATION_RESOLUTION]) it re-executes the business and completes.
 *
 * <p>Negotiation triggers on every new task (deterministic), so the demo
 * exercises the full Negotiation-T round-trip each run. When the A2A-T .env is
 * absent or LLM is disabled, the negotiation context falls back to a minimal
 * in-process context and the business text falls back to the subclass default.
 */
public abstract class NegotiationBaseAgentExecutor extends BaseAgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(NegotiationBaseAgentExecutor.class);

    private volatile A2ATClient a2atClient;

    // Pre-positioned extensions (Authorization-T / Notification-T) are handled
    // by a dedicated handler, keeping this class focused on Negotiation-T.
    private final PrePositionedExtensionHandler prePositionedHandler = new PrePositionedExtensionHandler();

    private final BlockingQueue<String> notificationQueue = new LinkedBlockingQueue<>();

    /** The pre-positioned Authorization-T whitelist policy text, or null. */
    protected final String getAuthorizationPolicy() { return prePositionedHandler.getAuthorizationPolicy(); }

    /** The pre-positioned Notification-T subscription text, or null. */
    protected final String getNotificationSubscription() { return prePositionedHandler.getNotificationSubscription(); }

    protected void pushNotificationResult(String result) {
        notificationQueue.offer(result);
    }

    /** Resolve the A2A-T .env path; null disables A2ATClient (negotiation still works with fallback context). */
    protected abstract String resolveEnvPath();

    private A2ATClient a2at() {
        if (a2atClient != null) {
            return a2atClient;
        }
        if (Boolean.getBoolean("a2at.llm.disabled")) {
            return null;
        }
        String env = resolveEnvPath();
        if (env == null || env.isBlank()) {
            return null;
        }
        try {
            a2atClient = new A2ATClient(Path.of(env));
            log.info("[{}] A2ATClient ready for negotiation", getClass().getSimpleName());
            return a2atClient;
        } catch (Exception e) {
            log.warn("[{}] A2ATClient init failed, negotiation will use fallback context: {}",
                    getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    @Override
    public void execute(RequestContext ctx, AgentEmitter emitter) throws A2AError {
        String taskId = ctx.getTaskId();
        String contextId = ctx.getContextId();
        String input = extractText(ctx.getMessage());
        log.info("[{}] Received task: taskId={}, text={} chars, followUp={}, prePositioned={}",
                getClass().getSimpleName(), taskId, input.length(),
                NegotiationUtils.isFollowUpTask(input),
               PrePositionedExtensionHandler.detect(ctx) != null);
        try {
            String prePositionedExt = PrePositionedExtensionHandler.detect(ctx);
            if (prePositionedExt != null) {
                if (prePositionedExt.contains("Notification")) {
                    handleNotificationSubscription(ctx, emitter);
                } else {
                    prePositionedHandler.handle(ctx, emitter, prePositionedExt,
                            getClass().getSimpleName());
                }
            } else if (NegotiationUtils.isFollowUpTask(input)) {
                handleFollowUp(ctx, emitter, input);
            } else {
                handleNewTask(ctx, emitter, input);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("[{}] Notification-T stream interrupted (shutdown)", getClass().getSimpleName());
        } catch (Exception e) {
            log.error("[{}] execute failed: {}", getClass().getSimpleName(), e.getMessage(), e);
            emitter.fail(buildStatusMessage(contextId, taskId, "Failed: " + e.getMessage()));
        }
    }




    /**
     * Handle a Notification-T subscription: send "subscribed" ack, then block
     * on a queue to keep the SSE stream open. Recovery results pushed via
     * pushNotificationResult are forwarded through this stream with the
     * Notification-T URI in artifact metadata. The stream stays open until
     * the client disconnects or the agent is shut down.
     */
    private void handleNotificationSubscription(RequestContext ctx, AgentEmitter emitter)
            throws InterruptedException {
        String taskId = ctx.getTaskId();
        String contextId = ctx.getContextId();
        String agentTag = getClass().getSimpleName();
        String notifUri = "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Notification-T/v1";
        log.info("[{}] Notification-T subscription received, keeping stream open", agentTag);
        List<Part<?>> ackParts = List.of(new TextPart("Subscribed to recovery results"));
        emitter.addArtifact(ackParts, "subscription", agentTag + " subscription", Map.of(), false, true);
        emitStatus(emitter, TaskState.TASK_STATE_WORKING, contextId, taskId,
                "Notification-T subscribed, waiting for recovery results", Map.of());
        while (!Thread.currentThread().isInterrupted()) {
            String result = notificationQueue.poll(30, TimeUnit.SECONDS);
            if (result == null) {
                continue;
            }
            log.info("[{}] Pushing recovery result via Notification-T stream", agentTag);
            Map<String, Object> notifMeta = new LinkedHashMap<>();
            notifMeta.put(notifUri, result);
            List<Part<?>> resultParts = List.of(new TextPart(result));
            emitter.addArtifact(resultParts, "recovery-result", "recovery-result", notifMeta, false, true);
        }
    }
    /** New task: start negotiation and request input. Business runs on the follow-up. */
    private void handleNewTask(RequestContext ctx, AgentEmitter emitter, String input) {
        if (needsNegotiation(input)) {
            requestNegotiation(ctx, emitter, input);
        } else {
            log.info("[{}] Parameters sufficient, skipping negotiation", getClass().getSimpleName());
            runBusinessAndComplete(ctx, emitter, input);
        }
    }

    /** Follow-up: clean markers, run business, complete with extension metadata. */
    private void handleFollowUp(RequestContext ctx, AgentEmitter emitter, String input) {
        String taskId = ctx.getTaskId();
        String contextId = ctx.getContextId();
        String cleanInput = NegotiationUtils.cleanupResolutionMarker(input);
        log.info("[{}] Follow-up received, re-executing business", getClass().getSimpleName());
        runBusinessAndComplete(ctx, emitter, cleanInput);
    }

    /** Send INPUT_REQUIRED to start a Negotiation-T round. */
    @SuppressWarnings("unchecked")
    private void requestNegotiation(RequestContext ctx, AgentEmitter emitter, String input) {
        String taskId = ctx.getTaskId();
        String contextId = ctx.getContextId();
        Map<String, Object> neg = startNegotiation(input, taskId, contextId);
        Map<String, Object> contextData = neg != null && neg.get(NegotiationUtils.NEGOTIATION_CONTEXT_KEY) instanceof Map
                ? (Map<String, Object>) neg.get(NegotiationUtils.NEGOTIATION_CONTEXT_KEY) : fallbackContext();
        String negText = neg != null && neg.get(NegotiationUtils.NEGOTIATION_TEXT_KEY) instanceof String
                ? (String) neg.get(NegotiationUtils.NEGOTIATION_TEXT_KEY) : defaultNegotiationText();
        String concern = defaultNegotiationConcern();
        Map<String, Object> metadata = NegotiationUtils.negotiationResponseMetadata(contextData, negText, concern);
        emitStatus(emitter, TaskState.TASK_STATE_INPUT_REQUIRED, contextId, taskId,
                "[NEGOTIATION_REQUEST] " + getClass().getSimpleName()
                        + " needs clarification before completing this task.", metadata);
        log.info("[{}] Requested negotiation (INPUT_REQUIRED)", getClass().getSimpleName());
    }

    /** Run business logic, emit artifact, and complete the task. */
    private void runBusinessAndComplete(RequestContext ctx, AgentEmitter emitter, String input) {
        String taskId = ctx.getTaskId();
        String contextId = ctx.getContextId();
       String response = executeBusiness(ctx, emitter, input);
       Map<String, Object> metadata = buildResponseMetadata(ctx, response);
        List<Part<?>> parts = List.of(new TextPart(response));
       emitter.addArtifact(parts, "result", buildArtifactName(), metadata, false, true);
        emitStatus(emitter, TaskState.TASK_STATE_COMPLETED, contextId, taskId,
                "Completed", metadata);
        emitter.complete(buildStatusMessage(contextId, taskId, "Completed"));
        log.info("[{}] Task completed", getClass().getSimpleName());
    }

    /** Start a fulfillment negotiation via A2ATClient, or return a fallback payload. */
    @SuppressWarnings("unchecked")
   private Map<String, Object> startNegotiation(String input, String taskId, String contextId) {
       A2ATClient client = a2at();
       if (client == null) {
           return fallbackNegotiationPayload(input, taskId, contextId);
       }
       try {
           Map<String, Object> facts = new LinkedHashMap<>();
           facts.put("agent", getClass().getSimpleName());
           if (taskId != null) facts.put("task_id", taskId);
           if (contextId != null) facts.put("context_id", contextId);
            // contentText should be the short negotiation request text, not
            // the full task input. The full input is passed as a fact so the
            // negotiation context retains the original task context.
            facts.put("input", input);
            return client.startNegotiation(NegotiationType.FULFILLMENT, defaultNegotiationText(), facts);
       } catch (Exception e) {
            log.warn("[{}] startNegotiation failed, using fallback: {}", getClass().getSimpleName(), e.getMessage());
            return fallbackNegotiationPayload(input, taskId, contextId);
        }
    }

    private Map<String, Object> fallbackContext() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("negotiationType", "fulfillment");
        ctx.put("negotiationId", UUID.randomUUID().toString());
        ctx.put("round", 1);
        ctx.put("status", "in-progress");
        ctx.put("extra", Map.of());
        return ctx;
    }

    private Map<String, Object> fallbackNegotiationPayload(String input, String taskId, String contextId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(NegotiationUtils.NEGOTIATION_CONTEXT_KEY, fallbackContext());
        payload.put(NegotiationUtils.NEGOTIATION_TEXT_KEY, defaultNegotiationText());
        return payload;
    }

    private void emitStatus(AgentEmitter emitter, TaskState state, String contextId, String taskId,
                            String text, Map<String, Object> metadata) {
        TaskStatus status = new TaskStatus(state,
                buildStatusMessage(contextId, taskId, text), null);
        TaskStatusUpdateEvent event = TaskStatusUpdateEvent.builder()
                .taskId(taskId)
                .contextId(contextId)
                .status(status)
                .metadata(metadata)
                .build();
        emitter.emitEvent(event);
    }

    // ---- subclass extension points ----

    /**
     * Whether the incoming task parameters are incomplete/wrong and require
     * a Negotiation-T round before proceeding. Default: false (parameters
     * are sufficient, skip negotiation and run business directly).
     * Override to return true when the agent needs clarification.
     */
    protected boolean needsNegotiation(String input) {
        return false;
    }

    /** Run the agent's actual business logic; return the response text. May emit intermediate events. */
    protected abstract String executeBusiness(RequestContext ctx, AgentEmitter emitter, String input);

    /** Build the task metadata for the completed task (e.g. Authorization-T / Notification-T). */
    protected Map<String, Object> buildResponseMetadata(RequestContext ctx, String response) {
        return new LinkedHashMap<>();
    }

    /** Short human-readable summary for the artifact parts. */
    protected String buildResultSummary() {
        return "业务处理结果";
    }

    /** Artifact display name. Default: subclass simple name + " result". */
    protected String buildArtifactName() {
        return getClass().getSimpleName() + " result";
    }

    /** Default negotiation text shown when A2ATClient is unavailable. */
    protected String defaultNegotiationText() {
        return "Please confirm the task parameters so I can proceed.";
    }

    /** Default negotiation concern. */
    protected String defaultNegotiationConcern() {
        return "needs clarification";
    }

    @Override
    public void cancel(RequestContext ctx, AgentEmitter emitter) throws A2AError {
        emitter.cancel();
    }
}
