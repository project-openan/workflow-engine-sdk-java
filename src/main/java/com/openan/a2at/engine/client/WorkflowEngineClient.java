package com.openan.a2at.engine.client;

import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.model.SendMessageResult;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Communication client for sending A2A messages to remote agents.
 * Handles: AgentCard lookup, client creation, auth, A2A-T extensions,
 * streaming response handling, text extraction.
 *
 * The user calls sendMessage() from their ControlPoint implementation.
 */
public interface WorkflowEngineClient {

    /**
     * Send a message to an agent. Returns SendMessageResult with text, task, metadata, taskState.
     *
     * @param agentName  target agent name (must match AgentCard.name)
     * @param message    full assembled message text
     * @param contextId  optional context ID (null = auto-generated)
     * @param metadata   optional preset metadata (e.g., Task-T stub prompts)
     */
    CompletableFuture<SendMessageResult> sendMessage(String agentName, String message, String contextId, Map<String, Object> metadata);

    /** Convenience: no context ID, no preset metadata. */
    default CompletableFuture<SendMessageResult> sendMessage(String agentName, String message) {
        return sendMessage(agentName, message, null, null);
    }

    /**
     * Send a message with auto-negotiation support.
     * When agent returns INPUT_REQUIRED, uses A2A-T receive/continue state
     * machine, retries with the resolved clarification up to maxRounds.
     *
     * @param contextId           optional context ID (null = auto-generated)
     * @param negotiationResolver optional callback(agentName, negotiationText, receiveResult) -> clarification
     */
    CompletableFuture<SendMessageResult> sendMessageWithNegotiation(
            String agentName, String message, String contextId, int maxRounds,
            NegotiationResolver negotiationResolver);

    /** Convenience: no context ID. */
    default CompletableFuture<SendMessageResult> sendMessageWithNegotiation(
            String agentName, String message, int maxRounds,
            NegotiationResolver negotiationResolver) {
        return sendMessageWithNegotiation(agentName, message, null, maxRounds, negotiationResolver);
    }

    /** Functional interface for negotiation clarification. */
    @FunctionalInterface
    interface NegotiationResolver {
        CompletableFuture<String> resolve(String agentName, String negotiationText, Map<String, Object> receiveResult);
    }

    void setControlPoint(Object controlPoint);
    void setEventCallback(EventCallback callback);
    void close();
    java.util.List<String> getAgentNames();

    /** Update the agent card map (e.g. after fetching new cards). */
    default void updateAgentCards(java.util.List<?> agentCards) {
        // Default no-op; implementations override
    }

    /** Register a custom extension handler. */
    default void registerHandler(ExtensionHandler handler) {
        // Default no-op; implementations override
    }
}
