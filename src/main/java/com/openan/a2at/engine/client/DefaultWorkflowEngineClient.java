package com.openan.a2at.engine.client;

import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.control.EventType;
import com.openan.a2at.engine.model.SendMessageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * Default implementation of WorkflowEngineClient.
 *
 * This implementation wraps the a2a-java-sdk client runtime to send messages
 * to remote A2A agents. It handles:
 * - AgentCard lookup
 * - A2A message sending via the Java SDK's transport layer
 * - Response text extraction
 * - Event emission (agent_request / agent_response)
 *
 * The actual A2A transport is delegated to the a2a-java-sdk
 * (org.a2aproject.sdk) via an injected A2AClientRuntime.
 */
public class DefaultWorkflowEngineClient implements WorkflowEngineClient {
    private static final Logger log = LoggerFactory.getLogger(DefaultWorkflowEngineClient.class);

    private final Map<String, Object> cardMap = new ConcurrentHashMap<>();
    private final Object a2aClientRuntime; // org.a2aproject.sdk client runtime
    private EventCallback eventCallback = new EventCallback();
    private Object controlPoint;
    private final String contextId;

    /**
     * @param agentCards      list of AgentCard objects (protobuf or Map)
     * @param a2aClientRuntime  the A2A client runtime from a2a-java-sdk
     *                          (implements the transport: REST, JSON-RPC, etc.)
     */
    public DefaultWorkflowEngineClient(List<Object> agentCards, Object a2aClientRuntime) {
        this.a2aClientRuntime = a2aClientRuntime;
        this.contextId = UUID.randomUUID().toString();
        for (Object card : agentCards) {
            String name = extractName(card);
            if (name != null) {
                cardMap.put(name, card);
            }
        }
        log.info("[EngineClient] Initialized with {} agent(s)", cardMap.size());
    }

    private String extractName(Object card) {
        if (card instanceof Map) {
            Object name = ((Map<String, Object>) card).get("name");
            return name != null ? name.toString() : null;
        }
        try {
            return (String) card.getClass().getMethod("getName").invoke(card);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void setEventCallback(EventCallback callback) {
        this.eventCallback = callback != null ? callback : new EventCallback();
    }

    @Override
    public void setControlPoint(Object controlPoint) {
        this.controlPoint = controlPoint;
    }

    @Override
    public List<String> getAgentNames() {
        return new ArrayList<>(cardMap.keySet());
    }

    private void emit(String type, Map<String, Object> data) {
        try {
            eventCallback.onEvent(type, data);
        } catch (Exception ignored) {}
    }

    @Override
    public CompletableFuture<SendMessageResult> sendMessage(String agentName, String message, String contextId, Map<String, Object> metadata) {
        Object agentCard = cardMap.get(agentName);
        if (agentCard == null) {
            log.error("[EngineClient] Agent not found: {}", agentName);
            return CompletableFuture.failedFuture(new RuntimeException("Agent not found: " + agentName));
        }
        log.info("[EngineClient] send_message to {}: {} chars", agentName, message.length());

        emit(EventType.AGENT_REQUEST, Map.of("agent", agentName, "request", message, "metadata", metadata != null ? metadata : Map.of()));

        // Delegate to the A2A client runtime.
        // The actual implementation calls a2aClientRuntime.sendMessage(agentCard, request, context, logSink)
        // and iterates over ClientEvent objects to extract response text.
        // This is where the a2a-java-sdk transport is used.
        return doSendViaA2ARuntime(agentCard, agentName, message, contextId != null ? contextId : this.contextId, metadata)
                .thenApply(result -> {
                    emit(EventType.AGENT_RESPONSE, Map.of("agent", agentName, "response", result.getText()));
                    return result;
                });
    }

    /**
     * Core A2A send via the a2a-java-sdk runtime.
     *
     * This method bridges to the Java A2A SDK's transport layer.
     * The a2aClientRuntime is expected to provide:
     *   Iterable<ClientEvent> sendMessage(agentCard, MessageSendParams, ClientCallContext, Consumer<String>)
     *
     * The implementation iterates over ClientEvent objects, extracts text from
     * task artifacts / message parts, and builds a SendMessageResult.
     */
    @SuppressWarnings("unchecked")
    protected CompletableFuture<SendMessageResult> doSendViaA2ARuntime(
            Object agentCard, String agentName, String message, String contextId, Map<String, Object> metadata) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Use reflection to call the A2A runtime generically.
                // In production, replace with direct typed calls to
                // org.a2aproject.sdk.client API.
                var createClientMethod = a2aClientRuntime.getClass().getMethod(
                        "createStreamingClient", String.class);
                var sendMethod = a2aClientRuntime.getClass().getMethod(
                        "sendMessage",
                        Map.class, // agentCard as Map
                        Object.class, // MessageSendParams
                        Object.class, // ClientCallContext
                        java.util.function.Consumer.class); // logSink

                // Resolve agent base URL
                String baseUrl = resolveAgentUrl(agentCard);
                Object streamingClient = createClientMethod.invoke(a2aClientRuntime, baseUrl);

                // Build MessageSendParams (from a2a-java-sdk)
                Object sendParams = buildMessageSendParams(message, contextId, metadata);

                // Build ClientCallContext
                Object callContext = buildCallContext(agentCard, metadata);

                // Send and iterate events
                Iterable<Object> events = (Iterable<Object>) sendMethod.invoke(
                        a2aClientRuntime, toMap(agentCard), sendParams, callContext,
                        (java.util.function.Consumer<String>) s -> log.info("[A2A] {}", s));

                String responseText = null;
                Object lastTask = null;
                Map<String, Object> lastMetadata = new HashMap<>();
                String taskState = "";

                for (Object event : events) {
                    // Extract text from ClientEvent
                    // ClientEvent has getTask() / getMessage() / getStatusUpdate() etc.
                    String text = extractEventText(event);
                    if (text != null && !text.isEmpty()) {
                        responseText = (responseText != null ? responseText : "") + text;
                    }
                    Map<String, Object> eventMeta = extractEventMetadata(event);
                    if (eventMeta != null && !eventMeta.isEmpty()) {
                        lastMetadata = eventMeta;
                    }
                    Object eventTask = extractEventTask(event);
                    if (eventTask != null) {
                        lastTask = eventTask;
                    }
                }

                if (responseText == null && lastTask != null) {
                    responseText = String.valueOf(lastTask);
                }

                return SendMessageResult.builder()
                        .text(responseText != null ? responseText : "")
                        .task(lastTask)
                        .metadata(lastMetadata)
                        .taskState(taskState)
                        .build();
            } catch (Exception e) {
                log.error("[EngineClient] Failed to send message to {}: {}", agentName, e.getMessage(), e);
                throw new RuntimeException("Agent call failed: " + e.getMessage(), e);
            }
        });
    }

    private String resolveAgentUrl(Object agentCard) {
        if (agentCard instanceof Map) {
            Map<String, Object> card = (Map<String, Object>) agentCard;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> interfaces = (List<Map<String, Object>>) card.get("supportedInterfaces");
            if (interfaces != null && !interfaces.isEmpty()) {
                return (String) interfaces.get(0).get("url");
            }
        }
        try {
            Object interfaces = agentCard.getClass().getMethod("getSupportedInterfaces").invoke(agentCard);
            if (interfaces instanceof List && !((List<?>) interfaces).isEmpty()) {
                Object first = ((List<?>) interfaces).get(0);
                return (String) first.getClass().getMethod("getUrl").invoke(first);
            }
        } catch (Exception ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object card) {
        if (card instanceof Map) return (Map<String, Object>) card;
        return Map.of();
    }

    private Object buildMessageSendParams(String message, String contextId, Map<String, Object> metadata) {
        // In production, use org.a2aproject.sdk.spec.MessageSendParams builder.
        // For now, return a simple Map that the runtime can interpret.
        Map<String, Object> params = new HashMap<>();
        params.put("message", message);
        params.put("contextId", contextId);
        if (metadata != null) params.put("metadata", metadata);
        return params;
    }

    private Object buildCallContext(Object agentCard, Map<String, Object> metadata) {
        // In production, use org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext.
        return null;
    }

    private String extractEventText(Object event) {
        try {
            // Try getTask() -> getArtifacts() -> getParts() -> getText()
            Object task = event.getClass().getMethod("getTask").invoke(event);
            if (task != null) {
                Object artifacts = task.getClass().getMethod("getArtifacts").invoke(task);
                if (artifacts instanceof List) {
                    for (Object artifact : (List<?>) artifacts) {
                        Object parts = artifact.getClass().getMethod("getParts").invoke(artifact);
                        if (parts instanceof List) {
                            for (Object part : (List<?>) parts) {
                                try {
                                    String text = (String) part.getClass().getMethod("getText").invoke(part);
                                    if (text != null && !text.isEmpty()) return text;
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        try {
            Object msg = event.getClass().getMethod("getMessage").invoke(event);
            if (msg != null) {
                Object parts = msg.getClass().getMethod("getParts").invoke(msg);
                if (parts instanceof List) {
                    for (Object part : (List<?>) parts) {
                        String text = (String) part.getClass().getMethod("getText").invoke(part);
                        if (text != null && !text.isEmpty()) return text;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Map<String, Object> extractEventMetadata(Object event) {
        try {
            Object task = event.getClass().getMethod("getTask").invoke(event);
            if (task != null) {
                Object metadata = task.getClass().getMethod("getMetadata").invoke(task);
                if (metadata instanceof Map) return (Map<String, Object>) metadata;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Object extractEventTask(Object event) {
        try {
            return event.getClass().getMethod("getTask").invoke(event);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public CompletableFuture<SendMessageResult> sendMessageWithNegotiation(
            String agentName, String message, int maxRounds,
            NegotiationResolver negotiationResolver) {
        return sendMessage(agentName, message, null, null)
                .thenCompose(result -> {
                    if (result.getTaskState() != null && result.getTaskState().contains("INPUT_REQUIRED") && maxRounds > 0) {
                        return resolveNegotiation(agentName, message, result, 1, maxRounds, negotiationResolver);
                    }
                    return CompletableFuture.completedFuture(result);
                });
    }

    private CompletableFuture<SendMessageResult> resolveNegotiation(
            String agentName, String originalMessage, SendMessageResult result,
            int round, int maxRounds, NegotiationResolver resolver) {
        Map<String, Object> negContext = result.getMetadata() != null ?
                (Map<String, Object>) result.getMetadata().get("negotiation_context") : null;
        String negMsg = result.getMetadata() != null ?
                (String) result.getMetadata().getOrDefault("negotiation_message", "") : "";

        emit(EventType.NEGOTIATION_REQUEST, Map.of("agent", agentName, "round", round, "concern", negMsg.substring(0, Math.min(200, negMsg.length()))));

        if (negContext == null) {
            if (!negMsg.isEmpty() && resolver != null) {
                return resolver.resolve(agentName, negMsg, null)
                        .thenCompose(clarification -> {
                            if (clarification != null && !clarification.isEmpty()) {
                                emit(EventType.NEGOTIATION_RESOLVED, Map.of("agent", agentName, "round", round, "clarification", clarification.substring(0, Math.min(200, clarification.length()))));
                                String followUp = "[NEGOTIATION_RESOLUTION]\nThe engine has reviewed your negotiation request and provides the following clarification:\n\n" + clarification + "\n\n---\nOriginal Task:\n" + originalMessage + "\n\nPlease re-execute the task based on the clarification above.";
                                return sendMessage(agentName, followUp, null, null);
                            }
                            emit(EventType.NEGOTIATION_FAILED, Map.of("agent", agentName, "round", round, "reason", "no clarification from resolver"));
                            String followUp = "Original task: " + originalMessage + "\n\nClarification needed:\n" + negMsg;
                            return sendMessage(agentName, followUp, null, null);
                        });
            }
            return CompletableFuture.completedFuture(result);
        }
        // A2AT negotiation path
        if (resolver != null) {
            return resolver.resolve(agentName, negMsg, null)
                    .thenCompose(clarification -> {
                        if (clarification == null || clarification.isEmpty()) {
                            emit(EventType.NEGOTIATION_FAILED, Map.of("agent", agentName, "round", round, "reason", "resolver returned empty"));
                            return CompletableFuture.completedFuture(result);
                        }
                        emit(EventType.NEGOTIATION_RESOLVED, Map.of("agent", agentName, "round", round, "clarification", clarification.substring(0, Math.min(200, clarification.length()))));
                        String followUp = "[NEGOTIATION_RESOLUTION]\n" + clarification + "\n\n---\nOriginal Task:\n" + originalMessage + "\n\nPlease re-execute the task based on the clarification above.";
                        return sendMessage(agentName, followUp, null, null);
                    });
        }
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public void close() {
        log.info("[EngineClient] Closing");
        // Close the a2a-java-sdk client resources if needed
    }
}
