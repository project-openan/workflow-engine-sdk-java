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

package com.openan.a2at.engine.client;

import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.control.ControlPoint;
import com.openan.a2at.engine.control.EventType;
import com.openan.a2at.engine.control.ControlPoint;
import com.openan.a2at.engine.model.SendMessageResult;
import net.openan.a2at.sdk.client.A2ATClient;
import net.openan.a2at.sdk.client.model.PromptGenerationResult;
import net.openan.a2at.sdk.client.model.PromptGenerationFailure;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.SecurityScheme;
import org.a2aproject.sdk.spec.SecurityRequirement;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallInterceptor;
import org.a2aproject.sdk.client.transport.spi.interceptors.PayloadAndHeaders;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultWorkflowEngineClient implements WorkflowEngineClient, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DefaultWorkflowEngineClient.class);
    private final Map<String, AgentCard> cardMap = new ConcurrentHashMap<>();
    private final A2AJavaClientRuntime a2aClientRuntime;
    private final AgentAuthManager authManager;
    private final AuthProvider authProvider;
    private final ExtensionRegistry extensionRegistry;
    private final A2ATClient a2atClient;
    private final String contextId;
    private EventCallback eventCallback = new EventCallback();
    private ControlPoint controlPoint;
    private final int maxNegotiationRounds;

    public DefaultWorkflowEngineClient(List<AgentCard> agentCards, A2AJavaClientRuntime a2aClientRuntime,
                                       WorkflowEngineClientConfig config) {
        this.a2aClientRuntime = a2aClientRuntime != null ? a2aClientRuntime
                : new DefaultA2AJavaClientRuntime(config.isSslVerify(), config.getCaCertsPath(), config.getSendTimeoutSeconds(), config.getPreferredProtocol());
        this.contextId = UUID.randomUUID().toString();
        if (config.getCredentialsConfigPath() != null) {
            this.authManager = new AgentAuthManager(config.getCredentialsConfigPath());
        } else if (config.getCredentialsConfig() != null) {
            this.authManager = new AgentAuthManager(config.getCredentialsConfig());
        } else {
            this.authManager = new AgentAuthManager();
        }
        this.extensionRegistry = new ExtensionRegistry();
        if (config.getCustomHandlers() != null) {
            for (ExtensionHandler h : config.getCustomHandlers()) {
                extensionRegistry.register(h);
            }
        }
        EnvFileLoader.loadToSystemProperties(
                config.getA2atEnvPath() != null
                        ? java.nio.file.Path.of(config.getA2atEnvPath())
                        : null);
        this.a2atClient = initA2atClient(config.getA2atEnvPath());
        for (AgentCard card : agentCards) {
            if (!card.name().isEmpty()) {
                cardMap.put(card.name(), card);
            }
        }
        this.maxNegotiationRounds = config.getMaxNegotiationRounds();
        this.authProvider = config.getAuthProvider();
        log.info("[EngineClient] Initialized with {} agent(s), ssl_verify={}, a2at={}, maxNeg={}",
                cardMap.size(), config.isSslVerify(), a2atClient != null, maxNegotiationRounds);
    }

    public DefaultWorkflowEngineClient(List<AgentCard> agentCards, A2AJavaClientRuntime a2aClientRuntime) {
        this(agentCards, a2aClientRuntime,
                WorkflowEngineClientConfig.builder().sslVerify(false).build());
    }

    private A2ATClient initA2atClient(String a2atEnvPath) {
        if (a2atEnvPath == null || a2atEnvPath.isEmpty()) {
            return null;
        }
        try {
            return new A2ATClient(java.nio.file.Path.of(a2atEnvPath));
        } catch (Exception e) {
            log.warn("Failed to init A2ATClient: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void setEventCallback(EventCallback callback) {
        this.eventCallback = callback != null ? callback : new EventCallback();
    }

    @Override
    public void setControlPoint(ControlPoint controlPoint) {
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
        }
    }
    // --- send_message ---
    @Override
    public CompletableFuture<SendMessageResult> sendMessage(
            String agentName, String message, String contextId, Map<String, Object> metadata) {
        AgentCard agentCard = cardMap.get(agentName);
        if (agentCard == null) {
            log.error("[EngineClient] Agent not found: {}", agentName);
            return CompletableFuture.failedFuture(new RuntimeException("Agent not found: " + agentName));
        }
        log.info("[EngineClient] send_message to {}: {} chars", agentName, message.length());
        log.debug("[EngineClient] message content to {}: [{}]", agentName, message);
        return runBeforeSendHandlers(agentCard, message, metadata)
                .thenCompose(processedMetadata -> {
                    emit(EventType.AGENT_REQUEST, Map.of(
                            "agent", agentName,
                            "request", message,
                            "metadata", processedMetadata != null ? processedMetadata : Map.of()));
                    String ctx = contextId != null ? contextId : this.contextId;
                    return doSendViaA2ARuntime(agentCard, agentName, message, ctx, processedMetadata)
                            .thenCompose(result -> runAfterReceiveHandlers(agentCard, result))
                            .thenCompose(result -> autoNegotiate(agentCard, agentName, message, ctx, result, 1));
                });
    }

    /**
     * One-shot extension message for pre-positioning (Authorization-T,
     * Notification-T). The metadata value is generated by the A2A-T SDK
     * (LLM + prompt template) from the natural-language input. If the SDK
     * cannot generate (no matching scenario or unavailable), the input text
     * is used as-is. Bypasses Task-T prompt generation and Negotiation-T
     * auto-loop.
     */
    @Override
    public CompletableFuture<SendMessageResult> sendExtensionMessage(
            String agentName, String instruction, String naturalLanguageInput, A2ATExtension extension) {
        AgentCard agentCard = cardMap.get(agentName);
        if (agentCard == null) {
            log.error("[EngineClient] Agent not found: {}", agentName);
            return CompletableFuture.failedFuture(new RuntimeException("Agent not found: " + agentName));
        }
        // Generate the metadata value via the A2A-T SDK (LLM + prompt template).
        String metadataValue = generatePromptText(naturalLanguageInput);
        if (metadataValue == null || metadataValue.isEmpty()) {
            // Fallback: use the natural-language input directly
            metadataValue = naturalLanguageInput;
            log.info("[EngineClient] SDK prompt generation unavailable for {}, using input as metadata", agentName);
        }
        log.info("[EngineClient] sendExtensionMessage to {}: extension={}, metadataValue={} chars",
                agentName, extension.displayName(), metadataValue.length());
        log.debug("[EngineClient] Generated metadata value for {}: [{}]", agentName, metadataValue);
        Map<String, Object> metadata = Map.of(extension.uri(), metadataValue);
        return doSendViaA2ARuntime(agentCard, agentName, instruction, this.contextId, metadata)
                .thenApply(result -> {
                    log.info("[EngineClient] Extension response from {}: state={}", agentName, result.getTaskState());
                    return result;
                });
    }

    /**
     * Generate structured prompt text from natural-language input via the
     * A2A-T SDK's {@code generateTaskPrompt} API (LLM + scenario recognition
     * + template rendering). Returns null if the SDK is unavailable or
     * generation fails.
     */
    private String generatePromptText(String naturalLanguageInput) {
        if (a2atClient == null) {
            return null;
        }
        try {
            PromptGenerationResult result = a2atClient.generateTaskPrompt(naturalLanguageInput);
            if (result.success()) {
                return result.promptText();
            }
            PromptGenerationFailure f = result.failure();
            log.warn("[EngineClient] SDK prompt generation failed: code={}, stage={}, message={}",
                    f != null ? f.code() : "unknown",
                    f != null ? f.stage() : "unknown",
                    f != null ? f.message() : "unknown");
        } catch (Exception e) {
            log.warn("[EngineClient] SDK prompt generation error: {}", e.getMessage());
        }
        return null;
    }

    private CompletableFuture<Map<String, Object>> runBeforeSendHandlers(
            AgentCard agentCard, String message, Map<String, Object> presetMetadata) {
        Map<String, Object> metadata = presetMetadata != null
                ? new HashMap<>(presetMetadata) : new HashMap<>();
        List<String> extUris = extractExtensionUris(agentCard);
        List<ExtensionHandler> handlers = extensionRegistry.getHandlersForExtensions(extUris);
        log.debug("[EngineClient] beforeSend handlers for '{}': {}", agentCard.name(),
                handlers.stream().map(ExtensionHandler::extensionKeyword).toList());
        CompletableFuture<Map<String, Object>> future = CompletableFuture.completedFuture(metadata);
        for (ExtensionHandler handler : handlers) {
            future = future.thenCompose(m -> handler.beforeSend(agentCard, message, m, a2atClient, controlPoint));
        }
        return future;
    }

    private CompletableFuture<SendMessageResult> runAfterReceiveHandlers(AgentCard agentCard, SendMessageResult result) {
        List<String> extUris = extractExtensionUris(agentCard);
        List<ExtensionHandler> handlers = extensionRegistry.getHandlersForExtensions(extUris);
        log.debug("[EngineClient] afterReceive handlers for '{}': {}", agentCard.name(),
                handlers.stream().map(ExtensionHandler::extensionKeyword).toList());
        CompletableFuture<SendMessageResult> future = CompletableFuture.completedFuture(result);
        for (ExtensionHandler handler : handlers) {
            future = future.thenCompose(r -> handler.afterReceive(agentCard, r, a2atClient, controlPoint, eventCallback));
        }
        return future;
    }

    private List<String> extractExtensionUris(AgentCard agentCard) {
        List<String> uris = new ArrayList<>();
        assert agentCard.capabilities().extensions() != null;
        for (var ext : agentCard.capabilities().extensions()) {
            String uri = ext.uri();
            if (!uri.isEmpty()) uris.add(uri);
        }
        return uris;
    }

    // --- Core A2A send via SDK runtime ---
    protected CompletableFuture<SendMessageResult> doSendViaA2ARuntime(
            AgentCard agentCard, String agentName, String message,
            String contextId, Map<String, Object> metadata) {
        return CompletableFuture.supplyAsync(() -> {
            try {
               MessageSendParams params = buildMessageSendParams(message, contextId, metadata);
               ClientCallContext callContext = buildClientCallContext(agentCard, agentName, metadata);
               String endpoint = agentCard.supportedInterfaces().isEmpty() ? "?"
                       : agentCard.supportedInterfaces().get(0).url();
               ProtocolLogger.logRequest(agentName, endpoint, params, callContext.getHeaders());
               log.info("[EngineClient] Sending via A2A SDK to {}", agentName);
                Iterable<ClientEvent> events = a2aClientRuntime.sendMessage(
                        agentCard, params, callContext,
                        event -> forwardIntermediateEvent(event, agentName),
                        s -> log.info("[A2A] {}", s));
                String responseText = extractResponseText(events);
                String taskState = extractResponseTaskState(events);
                Map<String, Object> respMetadata = extractResponseMetadata(events);
                Task task = extractResponseTask(events);
                log.info("[EngineClient] Response from {}: {} chars, state={}", agentName, responseText.length(), taskState);
                log.debug("[EngineClient] Response text from {}: [{}]", agentName, responseText);
                if (!respMetadata.isEmpty()) {
                    log.info("[EngineClient] Response metadata keys for {}: {}", agentName, respMetadata.keySet());
                }
                return SendMessageResult.builder().text(responseText).task(task).metadata(respMetadata).taskState(taskState).build();
            } catch (Exception e) {
                log.error("[EngineClient] Failed to send message to {}: {}", agentName, e.getMessage(), e);
                throw new RuntimeException("Agent call failed: " + e.getMessage(), e);
            }
        });
    }

    private MessageSendParams buildMessageSendParams(String message, String contextId, Map<String, Object> metadata) {
        Message msg = Message.builder()
                .messageId(UUID.randomUUID().toString())
                .contextId(contextId)
                .role(Message.Role.ROLE_USER)
                .parts(new TextPart(message))
                .metadata(metadata != null ? metadata : Map.of())
                .build();
        return MessageSendParams.builder().message(msg).build();
    }

    private ClientCallContext buildClientCallContext(AgentCard agentCard, String agentName, Map<String, Object> messageMetadata) {
        Map<String, String> headers = new HashMap<>();
        if (authProvider != null) {
            authProvider.applyAuth(agentName, agentCard, headers);
        }
        applyAuthHeaders(agentCard, agentName, headers);
        List<ClientCallInterceptor> interceptors = authManager.buildInterceptors(agentCard, agentName);
        for (ClientCallInterceptor interceptor : interceptors) {
            if (interceptor instanceof ExtensionInterceptor extInterceptor) {
                try {
                    ClientCallContext interceptCtx = new ClientCallContext(new HashMap<>(), headers);
                    PayloadAndHeaders ph = extInterceptor.intercept("message/send", messageMetadata, headers, null, interceptCtx);
                    headers.putAll(ph.getHeaders());
                } catch (Exception e) {
                    log.warn("[EngineClient] Extension interceptor failed: {}", e.getMessage());
                }
            }
        }
        return new ClientCallContext(new HashMap<>(), headers);
    }
    // --- ClientEvent extraction ---
    // --- intermediate event forwarding ---
    private void forwardIntermediateEvent(ClientEvent event, String agentName) {
        if (event instanceof TaskUpdateEvent tue) {
           if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
               String state = sue.status().state().name();
               StringBuilder statusText = new StringBuilder();
               extractTextFromMessage(sue.status().message(), statusText);
               Map<String, Object> data = new HashMap<>();
               data.put("agent", agentName);
               data.put("state", state);
               data.put("is_final", sue.isFinal());
                if (!statusText.isEmpty()) data.put("text", statusText.toString());
               if (sue.metadata() != null && !sue.metadata().isEmpty()) data.put("metadata", sue.metadata());
               log.info("[EngineClient] Agent {} status update: {} (final={})", agentName, state, sue.isFinal());
               if (!statusText.isEmpty()) {
                   log.debug("[EngineClient] Agent {} status text: {}", agentName, statusText);
               }
               emit(EventType.AGENT_STATUS_UPDATE, data);
            } else if (tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent ae) {
                StringBuilder text = new StringBuilder();
                extractTextFromArtifact(ae.artifact(), text);
                Map<String, Object> data = new HashMap<>();
                data.put("agent", agentName);
                data.put("artifact_id", ae.artifact().artifactId());
                data.put("artifact_name", ae.artifact().name());
                data.put("append", ae.append());
                data.put("last_chunk", ae.lastChunk());
                if (!text.isEmpty()) data.put("text", text.toString());
                if (ae.metadata() != null && !ae.metadata().isEmpty()) data.put("metadata", ae.metadata());
                log.info("[EngineClient] Agent {} artifact update: {} ({})", agentName, ae.artifact().name(), ae.artifact().artifactId());
                emit(EventType.AGENT_ARTIFACT_UPDATE, data);
            }
        } else if (event instanceof MessageEvent me) {
            Message msg = me.getMessage();
            StringBuilder text = new StringBuilder();
            extractTextFromMessage(msg, text);
            Map<String, Object> data = new HashMap<>();
            data.put("agent", agentName);
            data.put("role", msg.role().name());
            if (!text.isEmpty()) data.put("text", text.toString());
            if (msg.metadata() != null && !msg.metadata().isEmpty()) {
                data.put("metadata", msg.metadata());
            }
            log.info("[EngineClient] Agent {} message event: {} chars", agentName, text.length());
            if (!text.isEmpty()) {
                log.debug("[EngineClient] Agent {} message text: {}", agentName, text);
            }
            emit(EventType.AGENT_MESSAGE_EVENT, data);
        }
    }

    private static String extractResponseText(Iterable<ClientEvent> events) {
        StringBuilder text = new StringBuilder();
        for (ClientEvent event : events) {
            if (event instanceof TaskEvent te) extractTextFromTask(te.getTask(), text);
            else if (event instanceof TaskUpdateEvent tue) {
                extractTextFromTask(tue.getTask(), text);
                if (tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent ae) extractTextFromArtifact(ae.artifact(), text);
            } else if (event instanceof MessageEvent me) extractTextFromMessage(me.getMessage(), text);
        }
        return text.toString();
    }

    private static void extractTextFromTask(Task task, StringBuilder sb) {
        if (task.artifacts() != null) for (Artifact a : task.artifacts()) extractTextFromArtifact(a, sb);
    }

    private static void extractTextFromArtifact(Artifact artifact, StringBuilder sb) {
        for (Part<?> part : artifact.parts()) if (part instanceof TextPart tp) sb.append(tp.text());
    }

    private static void extractTextFromMessage(Message message, StringBuilder sb) {
        // status().message() is legitimately null for terminal events emitted via complete()/fail()
        if (message == null) return;
        for (Part<?> part : message.parts()) if (part instanceof TextPart tp) sb.append(tp.text());
    }

    private static String extractResponseTaskState(Iterable<ClientEvent> events) {
        String state = "";
        for (ClientEvent event : events) {
            if (event instanceof TaskEvent te) state = te.getTask().status().state().name();
            else if (event instanceof TaskUpdateEvent tue) {
                if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) state = sue.status().state().name();
            }
        }
        return state;
    }

    private static Map<String, Object> extractResponseMetadata(Iterable<ClientEvent> events) {
        Map<String, Object> metadata = new HashMap<>();
        for (ClientEvent event : events) {
            if (event instanceof TaskEvent te) {
                mergeTaskMetadata(te.getTask(), metadata);
            } else if (event instanceof TaskUpdateEvent tue) {
                mergeTaskMetadata(tue.getTask(), metadata);
            }
        }
        return metadata;
    }

    /**
     * Merge task-level metadata AND each artifact's metadata into the result map.
     * Agents attach Authorization-T / Notification-T to artifact metadata, so
     * without this merge those extension payloads never reach the extension handlers.
     */
    private static void mergeTaskMetadata(Task task, Map<String, Object> metadata) {
        if (task == null) return;
        Map<String, Object> m = task.metadata();
        if (m != null && !m.isEmpty()) metadata.putAll(m);
        if (task.artifacts() != null) {
            for (Artifact a : task.artifacts()) {
                Map<String, Object> am = a.metadata();
                if (am != null && !am.isEmpty()) metadata.putAll(am);
            }
        }
    }

    private static Task extractResponseTask(Iterable<ClientEvent> events) {
        Task lastTask = null;
        for (ClientEvent event : events) {
            if (event instanceof TaskEvent te) lastTask = te.getTask();
            else if (event instanceof TaskUpdateEvent tue) lastTask = tue.getTask();
        }
        return lastTask;
    }

    // --- autoNegotiate ---
    private CompletableFuture<SendMessageResult> autoNegotiate(
            AgentCard agentCard, String agentName, String originalMessage,
            String contextId, SendMessageResult result, int round) {
        if (!isNegotiationNeeded(result) || round > maxNegotiationRounds) {
            emit(EventType.AGENT_RESPONSE, Map.of("agent", agentName, "response", result.getText()));
            return CompletableFuture.completedFuture(result);
        }
        Map<String, Object> negMeta = result.getMetadata() != null ? result.getMetadata() : new HashMap<>();
        String negText = negMeta.getOrDefault("negotiation_message", "").toString();
        log.info("[Negotiation] Round {} for '{}': {}", round, agentName, negText);
        log.debug("[Negotiation] Full metadata for '{}': {}", agentName, negMeta);
        emit(EventType.NEGOTIATION_REQUEST, Map.of("agent", agentName, "round", round, "concern", negText));
        CompletableFuture<String> clarFuture;
        if (controlPoint != null) {
            clarFuture = controlPoint.onNegotiation(agentName, negText, negMeta);
        } else {
            clarFuture = CompletableFuture.completedFuture("Please proceed with the original task using available information.");
        }
        return clarFuture.thenCompose(clarification -> {
            if (clarification == null || clarification.isEmpty()) {
                emit(EventType.NEGOTIATION_FAILED, Map.of("agent", agentName, "round", round, "reason", "no clarification"));
                emit(EventType.AGENT_RESPONSE, Map.of("agent", agentName, "response", result.getText()));
                return CompletableFuture.completedFuture(result);
            }
            log.info("[Negotiation] Clarification for '{}' round {}: {}", agentName, round, clarification);
            emit(EventType.NEGOTIATION_RESOLVED, Map.of("agent", agentName, "round", round, "clarification", clarification));
            String followUp = "[NEGOTIATION_RESOLUTION]\nThe engine has reviewed your negotiation request and provides the following clarification:\n\n" + clarification + "\n\n---\nOriginal Task:\n" + originalMessage + "\n\nPlease re-execute the task based on the clarification above.";
           // Carry the negotiation resolution as natural-language metadata
            // under the Negotiation-T URI key, per A2A-T protocol.
           Map<String, Object> followUpMeta = new HashMap<>();
            followUpMeta.put("https://projects.tmforum.org/a2aproject/telecommunication/extensions/NEGOTIATION-T",
                    "## \u6570\u636e\u8fd4\u56de\u786e\u8ba4\n" + clarification + "\n");
            return runBeforeSendHandlers(agentCard, followUp, followUpMeta)
                    .thenCompose(meta -> {
                        String ctx = contextId != null ? contextId : this.contextId;
                        return doSendViaA2ARuntime(agentCard, agentName, followUp, ctx, meta)
                                .thenCompose(r -> runAfterReceiveHandlers(agentCard, r))
                                .thenCompose(r -> autoNegotiate(agentCard, agentName, originalMessage, contextId, r, round + 1));
                    });
        });
    }

    private static boolean isNegotiationNeeded(SendMessageResult result) {
        return result.getTaskState() != null && result.getTaskState().contains("INPUT_REQUIRED");
    }
    // --- auth headers ---
    private void applyAuthHeaders(AgentCard agentCard, String agentName, Map<String, String> headerMap) {
        AgentCredentialService credSvc = authManager.getService(agentName);
        if (credSvc == null) return;
        Map<String, Map<String, Object>> schemeConfigs = authManager.getConfig(agentName);
        if (schemeConfigs == null) schemeConfigs = Map.of();
        Map<String, SecurityScheme> secSchemes = agentCard.securitySchemes();
        List<SecurityRequirement> secReqs = agentCard.securityRequirements();
        if (secSchemes == null || secSchemes.isEmpty() || secReqs == null || secReqs.isEmpty()) return;
        for (SecurityRequirement req : secReqs) {
            Map<String, List<String>> schemes = req.schemes();
            for (String schemeName : schemes.keySet()) {
                Map<String, Object> schemeCfg = schemeConfigs.getOrDefault(schemeName, Map.of());
                String credential = credSvc.getCredential(schemeName, null);
                if (credential == null) continue;
                String authHeader = (String) schemeCfg.get("auth_header");
                if (authHeader != null && !authHeader.isEmpty()) {
                    String prefix = (String) schemeCfg.getOrDefault("auth_header_prefix", "");
                    headerMap.put(authHeader, prefix + credential);
                    log.info("[Auth] Set header {} for agent {}", authHeader, agentName);
                } else {
                    headerMap.put("Authorization", "Bearer " + credential);
                    log.info("[Auth] Set Bearer header for agent {}", agentName);
                }
                String acceptHeader = (String) schemeCfg.get("accept_header");
                if (acceptHeader != null && !acceptHeader.isEmpty()) headerMap.put("Accept", acceptHeader);
                break;
            }
        }
    }

    // --- utility ---
    @Override
    public void close() {
        log.info("[EngineClient] Closing");
        try { a2aClientRuntime.close(); } catch (Exception ignored) {}
    }

    @Override
    public void updateAgentCards(List<AgentCard> agentCards) {
        cardMap.clear();
        for (AgentCard card : agentCards) {
            if (!card.name().isEmpty()) cardMap.put(card.name(), card);
        }
        log.info("[EngineClient] Updated agent cards: {} agent(s)", cardMap.size());
    }

    @Override
    public void registerHandler(ExtensionHandler handler) {
        extensionRegistry.register(handler);
    }
}
