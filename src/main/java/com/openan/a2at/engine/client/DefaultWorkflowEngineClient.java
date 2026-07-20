/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.openan.a2at.engine.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.control.EventType;
import com.openan.a2at.engine.model.SendMessageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of WorkflowEngineClient.
 *
 * <p>Mirrors the Python SDK's {@code WorkflowEngineClient}. Handles:
 * AgentCard lookup, A2A message sending, auth interceptors,
 * A2A-T extensions (Task-T, Negotiation-T, Authorization-T, Notification-T),
 * SSE response normalization, streaming response handling, text extraction,
 * and event emission.
 *
 * <p>When an {@code a2aClientRuntime} is provided, delegates to it via
 * reflection. Otherwise, falls back to raw HTTP JSON-RPC POST.
 */
public class DefaultWorkflowEngineClient implements WorkflowEngineClient, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DefaultWorkflowEngineClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, Object> cardMap = new ConcurrentHashMap<>();
    private final Object a2aClientRuntime;
    private final AgentAuthManager authManager;
    private final ExtensionRegistry extensionRegistry;
    private final Object a2atClient;
    private final String contextId;
    private EventCallback eventCallback = new EventCallback();
    private Object controlPoint;

    /**
     * Full constructor with configuration.
     *
     * @param agentCards        list of AgentCard objects (Map or typed)
     * @param a2aClientRuntime  the A2A client runtime (optional, may be null)
     * @param config            configuration for SSL, auth, A2A-T
     */
    public DefaultWorkflowEngineClient(List<?> agentCards, Object a2aClientRuntime,
                                       WorkflowEngineClientConfig config) {
        this.a2aClientRuntime = a2aClientRuntime;
        this.contextId = UUID.randomUUID().toString();
        this.authManager = config.getCredentialsConfigPath() != null
                ? new AgentAuthManager(config.getCredentialsConfigPath())
                : new AgentAuthManager();
        this.extensionRegistry = new ExtensionRegistry();
        if (config.getCustomHandlers() != null) {
            for (ExtensionHandler h : config.getCustomHandlers()) {
                extensionRegistry.register(h);
            }
        }
        this.a2atClient = initA2atClient(config.getA2atEnvPath());
        for (Object card : agentCards) {
            String name = extractName(card);
            if (name != null) {
                cardMap.put(name, card);
            }
        }
        log.info("[EngineClient] Initialized with {} agent(s), ssl_verify={}, a2at={}",
                cardMap.size(), config.isSslVerify(), a2atClient != null);
    }

    /**
     * Legacy constructor without config (SSL verify defaults to false, no auth/A2AT).
     *
     * @param agentCards        list of AgentCard objects
     * @param a2aClientRuntime  the A2A client runtime (optional)
     */
    public DefaultWorkflowEngineClient(List<?> agentCards, Object a2aClientRuntime) {
        this(agentCards, a2aClientRuntime,
                WorkflowEngineClientConfig.builder().sslVerify(false).build());
    }

    private Object initA2atClient(String a2atEnvPath) {
        if (a2atEnvPath == null || a2atEnvPath.isEmpty()) {
            return null;
        }
        try {
            Class<?> a2atClientClass = Class.forName("net.openan.a2at.sdk.client.A2ATClient");
            java.nio.file.Path envPath = java.nio.file.Path.of(a2atEnvPath);
            return a2atClientClass.getConstructor(java.nio.file.Path.class).newInstance(envPath);
        } catch (Exception e) {
            log.warn("Failed to init A2ATClient: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
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
        } catch (Exception ignored) {
            // Event callback failures are non-fatal
        }
    }

    // ------------------------------------------------------------------
    // send_message
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<SendMessageResult> sendMessage(
            String agentName, String message, String contextId, Map<String, Object> metadata) {
        Object agentCard = cardMap.get(agentName);
        if (agentCard == null) {
            log.error("[EngineClient] Agent not found: {}", agentName);
            return CompletableFuture.failedFuture(new RuntimeException("Agent not found: " + agentName));
        }
        log.info("[EngineClient] send_message to {}: {} chars", agentName, message.length());

        // Run before_send extension handlers (e.g. Task-T prompt generation)
        return runBeforeSendHandlers(agentCard, message, metadata)
                .thenCompose(processedMetadata -> {
                    emit(EventType.AGENT_REQUEST, Map.of(
                            "agent", agentName,
                            "request", message,
                            "metadata", processedMetadata != null ? processedMetadata : Map.of()));
                    String ctx = contextId != null ? contextId : this.contextId;
                    return doSendViaA2ARuntime(agentCard, agentName, message, ctx, processedMetadata)
                            .thenCompose(result -> runAfterReceiveHandlers(agentCard, result))
                            .thenApply(result -> {
                                emit(EventType.AGENT_RESPONSE,
                                        Map.of("agent", agentName, "response", result.getText()));
                                return result;
                            });
                });
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Map<String, Object>> runBeforeSendHandlers(
            Object agentCard, String message, Map<String, Object> presetMetadata) {
        Map<String, Object> metadata = presetMetadata != null
                ? new HashMap<>(presetMetadata) : new HashMap<>();
        Map<String, Object> cardAsMap = toMap(agentCard);
        List<String> extUris = extractExtensionUris(cardAsMap);
        List<ExtensionHandler> handlers = extensionRegistry.getHandlersForExtensions(extUris);
        CompletableFuture<Map<String, Object>> future = CompletableFuture.completedFuture(metadata);
        for (ExtensionHandler handler : handlers) {
            future = future.thenCompose(m -> handler.beforeSend(cardAsMap, message, m, a2atClient, controlPoint));
        }
        return future;
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<SendMessageResult> runAfterReceiveHandlers(Object agentCard, SendMessageResult result) {
        Map<String, Object> cardAsMap = toMap(agentCard);
        List<String> extUris = extractExtensionUris(cardAsMap);
        List<ExtensionHandler> handlers = extensionRegistry.getHandlersForExtensions(extUris);
        CompletableFuture<SendMessageResult> future = CompletableFuture.completedFuture(result);
        for (ExtensionHandler handler : handlers) {
            future = future.thenCompose(r -> handler.afterReceive(cardAsMap, r, a2atClient, controlPoint, eventCallback));
        }
        return future;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractExtensionUris(Map<String, Object> agentCard) {
        List<String> uris = new ArrayList<>();
        Map<String, Object> caps = (Map<String, Object>) agentCard.get("capabilities");
        if (caps == null) {
            return uris;
        }
        List<Map<String, Object>> extensions = (List<Map<String, Object>>) caps.get("extensions");
        if (extensions == null) {
            return uris;
        }
        for (Map<String, Object> ext : extensions) {
            Object uri = ext.get("uri");
            if (uri != null && !uri.toString().isEmpty()) {
                uris.add(uri.toString());
            }
        }
        return uris;
    }

    // ------------------------------------------------------------------
    // Core A2A send
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    protected CompletableFuture<SendMessageResult> doSendViaA2ARuntime(
            Object agentCard, String agentName, String message,
            String contextId, Map<String, Object> metadata) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (a2aClientRuntime == null) {
                    return sendViaRawHttp(agentCard, agentName, message, contextId, metadata);
                }
                // Delegate to the a2a-java-sdk runtime via reflection
                var createClientMethod = a2aClientRuntime.getClass().getMethod(
                        "createStreamingClient", String.class);
                var sendMethod = a2aClientRuntime.getClass().getMethod(
                        "sendMessage",
                        Map.class, Object.class, Object.class,
                        java.util.function.Consumer.class);
                String baseUrl = resolveAgentUrl(agentCard);
                createClientMethod.invoke(a2aClientRuntime, baseUrl);
                Object sendParams = buildMessageSendParams(message, contextId, metadata);
                Object callContext = buildCallContext(agentCard, metadata);
                Iterable<Object> events = (Iterable<Object>) sendMethod.invoke(
                        a2aClientRuntime, toMap(agentCard), sendParams, callContext,
                        (java.util.function.Consumer<String>) s -> log.info("[A2A] {}", s));
                String responseText = null;
                Object lastTask = null;
                Map<String, Object> lastMetadata = new HashMap<>();
                String taskState = "";
                for (Object event : events) {
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

    /**
     * Fallback: send a raw HTTP JSON-RPC POST when no a2a-java-sdk runtime is provided.
     * Applies auth interceptors to build headers, normalizes SSE responses.
     */
    @SuppressWarnings("unchecked")
    private SendMessageResult sendViaRawHttp(
            Object agentCard, String agentName, String message,
            String contextId, Map<String, Object> metadata) throws Exception {
        String url = resolveAgentUrl(agentCard);
        if (url == null) {
            throw new RuntimeException("Cannot resolve agent URL for: " + agentName);
        }
        log.info("[EngineClient] Raw HTTP POST to {}", url);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(60))
                .build();

        // Build JSON-RPC message/send request body
        Map<String, Object> requestBody = new HashMap<>();
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "user");
        List<Map<String, Object>> parts = new ArrayList<>();
        Map<String, Object> textPart = new HashMap<>();
        textPart.put("type", "text");
        textPart.put("text", message);
        parts.add(textPart);
        msg.put("parts", parts);
        msg.put("contextId", contextId);
        if (metadata != null && !metadata.isEmpty()) {
            msg.put("metadata", metadata);
        }
        requestBody.put("jsonrpc", "2.0");
        requestBody.put("method", "message/send");
        requestBody.put("params", Map.of("message", msg));
        requestBody.put("id", UUID.randomUUID().toString());

        String jsonBody = mapper.writeValueAsString(requestBody);
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(60));

        // Apply auth + extension interceptors to build headers
        Map<String, Object> cardAsMap = toMap(agentCard);
        List<org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallInterceptor> interceptors =
                authManager.buildInterceptors(cardAsMap, agentName);
        Map<String, String> headerMap = new HashMap<>();
        for (org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallInterceptor interceptor : interceptors) {
            try {
                org.a2aproject.sdk.client.transport.spi.interceptors.PayloadAndHeaders ph =
                        interceptor.intercept("message/send", requestBody, headerMap, null,
                                new org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext(
                                        new HashMap<>(), new HashMap<>()));
                headerMap.putAll(ph.getHeaders());
            } catch (Exception e) {
                log.warn("[EngineClient] Interceptor failed: {}", e.getMessage());
            }
        }
        for (Map.Entry<String, String> h : headerMap.entrySet()) {
            reqBuilder.header(h.getKey(), h.getValue());
        }

        HttpResponse<String> resp = client.send(reqBuilder.build(),
                HttpResponse.BodyHandlers.ofString());
        Map<String, Object> respData = mapper.readValue(resp.body(), Map.class);
        respData = SseNormalization.normalize(respData);

        String responseText = "";
        Map<String, Object> respMetadata = new HashMap<>();
        String taskState = "";
        Object result = respData.get("result");
        if (result == null) {
            // Response might be a bare task
            result = respData.get("task");
        }
        if (result instanceof Map) {
            Map<String, Object> resultMap = (Map<String, Object>) result;
            Object status = resultMap.get("status");
            if (status instanceof Map) {
                Object state = ((Map<String, Object>) status).get("state");
                if (state instanceof String) {
                    taskState = (String) state;
                }
            }
            Object meta = resultMap.get("metadata");
            if (meta instanceof Map) {
                respMetadata = (Map<String, Object>) meta;
            }
            responseText = extractTextFromResultMap(resultMap);
        }
        // Fallback: extract text from metadata
        if (responseText.isEmpty() && respMetadata != null) {
            for (Object val : respMetadata.values()) {
                if (val instanceof String s && s.length() > 20) {
                    responseText = s;
                    break;
                }
            }
        }
        return SendMessageResult.builder()
                .text(responseText)
                .metadata(respMetadata)
                .taskState(taskState)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static String extractTextFromResultMap(Map<String, Object> resultMap) {
        StringBuilder text = new StringBuilder();
        // Extract from artifacts
        Object artifacts = resultMap.get("artifacts");
        if (artifacts instanceof List) {
            for (Object art : (List<?>) artifacts) {
                if (art instanceof Map) {
                    Object artParts = ((Map<String, Object>) art).get("parts");
                    if (artParts instanceof List) {
                        for (Object p : (List<?>) artParts) {
                            if (p instanceof Map) {
                                Object t = ((Map<String, Object>) p).get("text");
                                if (t instanceof String) {
                                    text.append(t);
                                }
                            }
                        }
                    }
                }
            }
        }
        return text.toString();
    }

    // ------------------------------------------------------------------
    // Utility
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private String resolveAgentUrl(Object agentCard) {
        if (agentCard instanceof Map) {
            Map<String, Object> card = (Map<String, Object>) agentCard;
            List<Map<String, Object>> interfaces =
                    (List<Map<String, Object>>) card.get("supportedInterfaces");
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
        } catch (Exception ignored) {
            // AgentCard shape not reflectable; URL resolution skipped
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object card) {
        if (card instanceof Map) {
            return (Map<String, Object>) card;
        }
        try {
            return mapper.convertValue(card, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Object buildMessageSendParams(String message, String contextId, Map<String, Object> metadata) {
        Map<String, Object> params = new HashMap<>();
        params.put("message", message);
        params.put("contextId", contextId);
        if (metadata != null) {
            params.put("metadata", metadata);
        }
        return params;
    }

    private Object buildCallContext(Object agentCard, Map<String, Object> metadata) {
        return null;
    }

    private String extractEventText(Object event) {
        try {
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
                                    if (text != null && !text.isEmpty()) {
                                        return text;
                                    }
                                } catch (Exception ignored) {
                                    // Part shape mismatch, skip
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Task path extraction failed, try message path
        }
        try {
            Object msg = event.getClass().getMethod("getMessage").invoke(event);
            if (msg != null) {
                Object parts = msg.getClass().getMethod("getParts").invoke(msg);
                if (parts instanceof List) {
                    for (Object part : (List<?>) parts) {
                        String text = (String) part.getClass().getMethod("getText").invoke(part);
                        if (text != null && !text.isEmpty()) {
                            return text;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Message path extraction failed
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractEventMetadata(Object event) {
        try {
            Object task = event.getClass().getMethod("getTask").invoke(event);
            if (task != null) {
                Object metadata = task.getClass().getMethod("getMetadata").invoke(task);
                if (metadata instanceof Map) {
                    return (Map<String, Object>) metadata;
                }
            }
        } catch (Exception ignored) {
            // Metadata not reflectable
        }
        return null;
    }

    private Object extractEventTask(Object event) {
        try {
            return event.getClass().getMethod("getTask").invoke(event);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Negotiation
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<SendMessageResult> sendMessageWithNegotiation(
            String agentName, String message, int maxRounds,
            NegotiationResolver negotiationResolver) {
        return sendMessage(agentName, message, null, null)
                .thenCompose(result -> {
                    if (result.getTaskState() != null
                            && result.getTaskState().contains("INPUT_REQUIRED")
                            && maxRounds > 0) {
                        return resolveNegotiation(agentName, message, result, 1, maxRounds, negotiationResolver);
                    }
                    return CompletableFuture.completedFuture(result);
                });
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<SendMessageResult> resolveNegotiation(
            String agentName, String originalMessage, SendMessageResult result,
            int round, int maxRounds, NegotiationResolver resolver) {
        Map<String, Object> negContext = result.getMetadata() != null
                ? (Map<String, Object>) result.getMetadata().get("negotiation_context") : null;
        String negMsg = result.getMetadata() != null
                ? (String) result.getMetadata().getOrDefault("negotiation_message", "") : "";

        emit(EventType.NEGOTIATION_REQUEST, Map.of(
                "agent", agentName, "round", round,
                "concern", negMsg.substring(0, Math.min(200, negMsg.length()))));

        if (negContext == null) {
            if (!negMsg.isEmpty() && resolver != null) {
                return resolver.resolve(agentName, negMsg, null)
                        .thenCompose(clarification -> {
                            if (clarification != null && !clarification.isEmpty()) {
                                emit(EventType.NEGOTIATION_RESOLVED, Map.of(
                                        "agent", agentName, "round", round,
                                        "clarification", clarification.substring(0, Math.min(200, clarification.length()))));
                                String followUp = "[NEGOTIATION_RESOLUTION]\n"
                                        + "The engine has reviewed your negotiation request and provides "
                                        + "the following clarification:\n\n" + clarification
                                        + "\n\n---\nOriginal Task:\n" + originalMessage
                                        + "\n\nPlease re-execute the task based on the clarification above.";
                                return sendMessage(agentName, followUp, null, null);
                            }
                            emit(EventType.NEGOTIATION_FAILED, Map.of(
                                    "agent", agentName, "round", round,
                                    "reason", "no clarification from resolver"));
                            String followUp = "Original task: " + originalMessage
                                    + "\n\nClarification needed:\n" + negMsg;
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
                            emit(EventType.NEGOTIATION_FAILED, Map.of(
                                    "agent", agentName, "round", round,
                                    "reason", "resolver returned empty"));
                            return CompletableFuture.completedFuture(result);
                        }
                        emit(EventType.NEGOTIATION_RESOLVED, Map.of(
                                "agent", agentName, "round", round,
                                "clarification", clarification.substring(0, Math.min(200, clarification.length()))));
                        String followUp = "[NEGOTIATION_RESOLUTION]\n" + clarification
                                + "\n\n---\nOriginal Task:\n" + originalMessage
                                + "\n\nPlease re-execute the task based on the clarification above.";
                        return sendMessage(agentName, followUp, null, null);
                    });
        }
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public void close() {
        log.info("[EngineClient] Closing");
    }

    @Override
    public void updateAgentCards(List<?> agentCards) {
        cardMap.clear();
        for (Object card : agentCards) {
            String name = extractName(card);
            if (name != null) {
                cardMap.put(name, card);
            }
        }
        log.info("[EngineClient] Updated agent cards: {} agent(s)", cardMap.size());
    }

    @Override
    public void registerHandler(ExtensionHandler handler) {
        extensionRegistry.register(handler);
    }
}
