package com.openan.a2at.engine.control;

import com.openan.a2at.engine.client.WorkflowEngineClient;
import com.openan.a2at.engine.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Default ControlPoint implementation with common auto-send behavior.
 *
 * <p>Users who don't need custom business logic can use this directly:
 * <pre>{@code
 * ExecutePsop.builder()
 *     .psop(workflow)
 *     .agentCards(cards)
 *     .controlPoint(new DefaultControlPoint())
 *     .execute();
 * }</pre>
 *
 * <p>Override individual methods to customize specific decision points:
 * <pre>{@code
 * new DefaultControlPoint() {
 *     @Override
 *     public CompletableFuture<Boolean> onAuthorization(String agent, Map<String, Object> req) {
 *         return showAuthDialogAndWait(agent, req);  // custom human authorization
 *     }
 * }
 * }</pre>
 */
public class DefaultControlPoint implements ControlPoint {
    private static final Logger log = LoggerFactory.getLogger(DefaultControlPoint.class);

    private final int maxNegotiationRounds;

    /** Create with default negotiation max rounds (3). */
    public DefaultControlPoint() {
        this(3);
    }

    /**
     * @param maxNegotiationRounds max negotiation rounds for sendMessageWithNegotiation
     */
    public DefaultControlPoint(int maxNegotiationRounds) {
        this.maxNegotiationRounds = maxNegotiationRounds;
    }

    /**
     * Auto-send task to agent. Uses sendMessageWithNegotiation to handle
     * Negotiation-T automatically (no-op if agent doesn't negotiate).
     * Returns success if response has non-empty text and not INPUT_REQUIRED.
     */
    @Override
    public CompletableFuture<TaskResponse> onTask(
            TaskRequest request, WorkflowEngineClient engineClient) {
        log.info("[DefaultCP] onTask: agent={}, step={}", request.getAgentName(), request.getStepName());
        return engineClient.sendMessageWithNegotiation(
                request.getAgentName(), request.getMessage(),
                maxNegotiationRounds, this::resolveNegotiation
        ).thenApply(r -> {
            boolean success = r.getText() != null && !r.getText().isEmpty()
                    && (r.getTaskState() == null || !r.getTaskState().contains("INPUT_REQUIRED"));
            log.info("[DefaultCP] Response from {}: {} chars, success={}",
                    request.getAgentName(),
                    r.getText() != null ? r.getText().length() : 0, success);
            return TaskResponse.builder()
                    .success(success)
                    .output(r.getText())
                    .build();
        }).exceptionally(e -> {
            log.error("[DefaultCP] Task failed for {}: {}", request.getAgentName(), e.getMessage());
            return TaskResponse.builder()
                    .success(false)
                    .error("Agent call failed: " + e.getMessage())
                    .build();
        });
    }

    /**
     * Default negotiation resolver: returns a generic clarification.
     * Override to provide context-specific supplementary data.
     */
    public CompletableFuture<String> resolveNegotiation(
            String agentName, String negotiationText,
            Map<String, Object> receiveResult) {
        log.info("[DefaultCP] Negotiation from {}: {}",
                agentName,
                negotiationText != null ? negotiationText : "(empty)");
        return CompletableFuture.completedFuture(
                "Please proceed with the original task using available information.");
    }

    /**
     * Default routing: pick the first non-terminal branch.
     * Skips "end", "retry", "endNode". If all are terminal, picks first.
     */
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

    /**
     * Default authorization: auto-approve.
     * Override to implement human-in-the-loop authorization.
     */
    @Override
    public CompletableFuture<Boolean> onAuthorization(
            String agentName, Map<String, Object> authRequest) {
        log.info("[DefaultCP] onAuthorization: agent={}, auto-approving", agentName);
        return CompletableFuture.completedFuture(true);
    }

    /**
     * Default notification: log and no-op.
     * Override to persist, forward to upstream, or trigger UI updates.
     */
    @Override
    public CompletableFuture<Void> onNotification(
            String agentName, Map<String, Object> notification) {
        log.info("[DefaultCP] onNotification: agent={}, data={}", agentName, notification);
        return CompletableFuture.completedFuture(null);
    }
}
