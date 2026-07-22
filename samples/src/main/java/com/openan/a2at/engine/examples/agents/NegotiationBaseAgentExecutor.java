package com.openan.a2at.engine.examples.agents;

import net.openan.a2at.sdk.client.A2ATClient;
import net.openan.a2at.sdk.negotiation.types.model.NegotiationType;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
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
public abstract class NegotiationBaseAgentExecutor implements AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(NegotiationBaseAgentExecutor.class);

    private volatile A2ATClient a2atClient;

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
        log.info("[{}] Received task: taskId={}, text={} chars, followUp={}",
                getClass().getSimpleName(), taskId, input.length(),
                NegotiationUtils.isFollowUpTask(input));

        emitter.submit(BaseAgentExecutor.buildStatusMessage(contextId, taskId, "Task received"));
        emitter.startWork(BaseAgentExecutor.buildStatusMessage(contextId, taskId, "Processing"));

        try {
            if (NegotiationUtils.isFollowUpTask(input)) {
                handleFollowUp(ctx, emitter, input);
            } else {
                handleNewTask(ctx, emitter, input);
            }
        } catch (Exception e) {
            log.error("[{}] execute failed: {}", getClass().getSimpleName(), e.getMessage(), e);
            emitter.fail(BaseAgentExecutor.buildStatusMessage(contextId, taskId, "Failed: " + e.getMessage()));
        }
    }

    /** New task: start negotiation and request input. Business runs on the follow-up. */
    private void handleNewTask(RequestContext ctx, AgentEmitter emitter, String input) {
        String taskId = ctx.getTaskId();
        String contextId = ctx.getContextId();
        Map<String, Object> neg = startNegotiation(input, taskId, contextId);
        Map<String, Object> contextData = neg != null && neg.get(NegotiationUtils.NEGOTIATION_CONTEXT_KEY) instanceof Map
                ? (Map<String, Object>) neg.get(NegotiationUtils.NEGOTIATION_CONTEXT_KEY) : fallbackContext();
        String negText = neg != null && neg.get(NegotiationUtils.NEGOTIATION_TEXT_KEY) instanceof String
                ? (String) neg.get(NegotiationUtils.NEGOTIATION_TEXT_KEY) : defaultNegotiationText();
        String concern = defaultNegotiationConcern();

        Map<String, Object> metadata = NegotiationUtils.negotiationResponseMetadata(contextData, negText, concern);
        // Carry the negotiation context as a task-status event so the client-side
        // NegotiationTHandler / autoNegotiate can parse it from task metadata.
        emitStatus(emitter, TaskState.TASK_STATE_INPUT_REQUIRED, contextId, taskId,
                "[NEGOTIATION_REQUEST] " + getClass().getSimpleName()
                        + " needs clarification before completing this task.", metadata);
        log.info("[{}] Requested negotiation (INPUT_REQUIRED)", getClass().getSimpleName());
    }

    /** Follow-up: clean markers, run business, complete with extension metadata. */
    private void handleFollowUp(RequestContext ctx, AgentEmitter emitter, String input) {
        String taskId = ctx.getTaskId();
        String contextId = ctx.getContextId();
        String cleanInput = NegotiationUtils.cleanupResolutionMarker(input);
        log.info("[{}] Follow-up received, re-executing business", getClass().getSimpleName());

        String response = executeBusiness(ctx, emitter, cleanInput);
        Map<String, Object> metadata = buildResponseMetadata(ctx, response);
        List<Part<?>> parts = List.of(new TextPart(response));
        emitter.addArtifact(parts, "result", getClass().getSimpleName() + " result", metadata, false, true);
        emitStatus(emitter, TaskState.TASK_STATE_COMPLETED, contextId, taskId,
                "Completed", metadata);
        emitter.complete(BaseAgentExecutor.buildStatusMessage(contextId, taskId, "Completed"));
        log.info("[{}] Task completed after negotiation", getClass().getSimpleName());
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
            return client.startNegotiation(NegotiationType.FULFILLMENT, input, facts);
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
                BaseAgentExecutor.buildStatusMessage(contextId, taskId, text), null);
        TaskStatusUpdateEvent event = TaskStatusUpdateEvent.builder()
                .taskId(taskId)
                .contextId(contextId)
                .status(status)
                .metadata(metadata)
                .build();
        emitter.emitEvent(event);
    }

    // ---- subclass extension points ----

    /** Run the agent's actual business logic; return the response text. May emit intermediate events. */
    protected abstract String executeBusiness(RequestContext ctx, AgentEmitter emitter, String input);

    /** Build the task metadata for the completed task (e.g. Authorization-T / Notification-T). */
    protected Map<String, Object> buildResponseMetadata(RequestContext ctx, String response) {
        return new LinkedHashMap<>();
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

    private static String extractText(Message message) {
        if (message == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Part<?> part : message.parts()) {
            if (part instanceof TextPart tp) sb.append(tp.text());
        }
        return sb.toString();
    }
}
