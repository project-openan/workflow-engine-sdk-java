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
 * on_task and on_route are required; on_authorization and on_notification
 * have default implementations (approve / no-op).
 */
public interface ControlPoint {

    /**
     * Called when a step needs to send a task to an agent.
     * request.message holds the full assembled message (context + task + lang hint).
     * Call engineClient.sendMessage(request.getAgentName(), request.getMessage()).
     */
    CompletableFuture<TaskResponse> onTask(TaskRequest request, com.openan.a2at.engine.client.WorkflowEngineClient engineClient);

    /**
     * Called at a conditional branch. User decides which branch to take.
     * conditions is List<JumpCondition>, each with getStep() and getCondition().
     */
    CompletableFuture<RouteDecision> onRoute(String stepName, Map<String, Object> results, List<JumpCondition> conditions);

    /**
     * Called when an agent requests authorization. Default: approve.
     */
    default CompletableFuture<Boolean> onAuthorization(String agentName, Map<String, Object> authRequest) {
        return CompletableFuture.completedFuture(true);
    }

    /**
     * Called when a notification is received. Default: no-op.
     */
    default CompletableFuture<Void> onNotification(String agentName, Map<String, Object> notification) {
        return CompletableFuture.completedFuture(null);
    }
}
