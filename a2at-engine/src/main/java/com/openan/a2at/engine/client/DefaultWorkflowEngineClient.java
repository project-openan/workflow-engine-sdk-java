package com.openan.a2at.engine.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.control.EventType;
import com.openan.a2at.engine.control.ControlPoint;
import com.openan.a2at.engine.model.SendMessageResult;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallInterceptor;
import org.a2aproject.sdk.client.transport.spi.interceptors.PayloadAndHeaders;
import org.a2aproject.sdk.spec.Artifact;
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
    private static final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Object> cardMap = new ConcurrentHashMap<>();
    private final A2AJavaClientRuntime a2aClientRuntime;
    private final AgentAuthManager authManager;
    private final ExtensionRegistry extensionRegistry;
    private final Object a2atClient;
    private final boolean sslVerify;
    private final String contextId;
    private EventCallback eventCallback = new EventCallback();
    private Object controlPoint;
    private final int maxNegotiationRounds;

    public DefaultWorkflowEngineClient(List<?> agentCards, A2AJavaClientRuntime a2aClientRuntime,
                                       WorkflowEngineClientConfig config) {
        this.a2aClientRuntime = a2aClientRuntime != null ? a2aClientRuntime
                : new DefaultA2AJavaClientRuntime(config.isSslVerify(), config.getCaCertsPath());
        this.contextId = UUID.randomUUID().toString();
        this.sslVerify = config.isSslVerify();
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
        this.a2atClient = initA2atClient(config.getA2atEnvPath());
        for (Object card : agentCards) {
            String name = extractName(card);
            if (name != null) {
                cardMap.put(name, card);
            }
        }
        this.maxNegotiationRounds = config.getMaxNegotiationRounds();
        log.info("[EngineClient] Initialized with {} agent(s), ssl_verify={}, a2at={}, maxNeg={}",
                cardMap.size(), config.isSslVerify(), a2atClient != null, maxNegotiationRounds);
    }

    public DefaultWorkflowEngineClient(List<?> agentCards, A2AJavaClientRuntime a2aClientRuntime) {
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
        }
    }
    // --- send_message ---
    @Override
    public CompletableFuture<SendMessageResult> sendMessage(
            String agentName, String message, String contextId, Map<String, Object> metadata) {
        Object agentCard = cardMap.get(agentName);
        if (agentCard == null) {
            log.error("[EngineClient] Agent not found: {}", agentName);
            return CompletableFuture.failedFuture(new RuntimeException("Agent not found: " + agentName));
        }
        log.info("[EngineClient] send_message to {}: {} chars", agentName, message.length());
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
        if (caps == null) return uris;
        List<Map<String, Object>> extensions = (List<Map<String, Object>>) caps.get("extensions");
        if (extensions == null) return uris;
        for (Map<String, Object> ext : extensions) {
            Object uri = ext.get("uri");
            if (uri != null && !uri.toString().isEmpty()) uris.add(uri.toString());
        }
        return uris;
    }
    // --- Core A2A send via SDK runtime ---
    protected CompletableFuture<SendMessageResult> doSendViaA2ARuntime(
            Object agentCard, String agentName, String message,
            String contextId, Map<String, Object> metadata) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> cardAsMap = toMap(agentCard);
                MessageSendParams params = buildMessageSendParams(message, contextId, metadata);
                ClientCallContext callContext = buildClientCallContext(cardAsMap, agentName, metadata);
                log.info("[EngineClient] Sending via A2A SDK to {}", agentName);
                Iterable<ClientEvent> events = a2aClientRuntime.sendMessage(
                        cardAsMap, params, callContext, s -> log.info("[A2A] {}", s));
                String responseText = extractResponseText(events);
                String taskState = extractResponseTaskState(events);
                Map<String, Object> respMetadata = extractResponseMetadata(events);
                Object task = extractResponseTask(events);
                log.info("[EngineClient] Response from {}: {} chars, state={}", agentName, responseText.length(), taskState);
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
                .role(Message.Role.ROLE_AGENT)
                .parts(new TextPart(message))
                .metadata(metadata != null ? metadata : Map.of())
                .build();
        return MessageSendParams.builder().message(msg).build();
    }
    @SuppressWarnings("unchecked")
    private ClientCallContext buildClientCallContext(Map<String, Object> cardAsMap, String agentName, Map<String, Object> messageMetadata) {
        Map<String, String> headers = new HashMap<>();
        applyAuthHeaders(cardAsMap, agentName, headers);
        List<ClientCallInterceptor> interceptors = authManager.buildInterceptors(cardAsMap, agentName);
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
        if (artifact.parts() != null) for (Part<?> part : artifact.parts()) if (part instanceof TextPart tp) sb.append(tp.text());
    }
    private static void extractTextFromMessage(Message message, StringBuilder sb) {
        if (message.parts() != null) for (Part<?> part : message.parts()) if (part instanceof TextPart tp) sb.append(tp.text());
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
                Map<String, Object> m = te.getTask().metadata();
                if (m != null && !m.isEmpty()) metadata = m;
            } else if (event instanceof TaskUpdateEvent tue) {
                Map<String, Object> m = tue.getTask().metadata();
                if (m != null && !m.isEmpty()) metadata = m;
            }
        }
        return metadata;
    }
    private static Object extractResponseTask(Iterable<ClientEvent> events) {
        Object lastTask = null;
        for (ClientEvent event : events) {
            if (event instanceof TaskEvent te) lastTask = te.getTask();
            else if (event instanceof TaskUpdateEvent tue) lastTask = tue.getTask();
        }
        return lastTask;
    }
    // --- autoNegotiate ---
    private CompletableFuture<SendMessageResult> autoNegotiate(
            Object agentCard, String agentName, String originalMessage,
            String contextId, SendMessageResult result, int round) {
        if (!isNegotiationNeeded(result) || round > maxNegotiationRounds) {
            emit(EventType.AGENT_RESPONSE, Map.of("agent", agentName, "response", result.getText()));
            return CompletableFuture.completedFuture(result);
        }
        Map<String, Object> negMeta = result.getMetadata() != null ? result.getMetadata() : new HashMap<>();
        String negText = negMeta.getOrDefault("negotiation_message", "").toString();
        log.info("[Negotiation] Round {} for '{}': {}", round, agentName, negText);
        emit(EventType.NEGOTIATION_REQUEST, Map.of("agent", agentName, "round", round, "concern", negText));
        CompletableFuture<String> clarFuture;
        if (controlPoint instanceof ControlPoint cp) {
            clarFuture = cp.onNegotiation(agentName, negText, negMeta);
        } else {
            clarFuture = CompletableFuture.completedFuture("Please proceed with the original task using available information.");
        }
        return clarFuture.thenCompose(clarification -> {
            if (clarification == null || clarification.isEmpty()) {
                emit(EventType.NEGOTIATION_FAILED, Map.of("agent", agentName, "round", round, "reason", "no clarification"));
                emit(EventType.AGENT_RESPONSE, Map.of("agent", agentName, "response", result.getText()));
                return CompletableFuture.completedFuture(result);
            }
            emit(EventType.NEGOTIATION_RESOLVED, Map.of("agent", agentName, "round", round, "clarification", clarification));
            String followUp = "[NEGOTIATION_RESOLUTION]\nThe engine has reviewed your negotiation request and provides the following clarification:\n\n" + clarification + "\n\n---\nOriginal Task:\n" + originalMessage + "\n\nPlease re-execute the task based on the clarification above.";
            return runBeforeSendHandlers(agentCard, followUp, null)
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
    @SuppressWarnings("unchecked")
    private void applyAuthHeaders(Map<String, Object> cardAsMap, String agentName, Map<String, String> headerMap) {
        AgentCredentialService credSvc = authManager.getService(agentName);
        if (credSvc == null) return;
        Map<String, Map<String, Object>> schemeConfigs = authManager.getConfig(agentName);
        if (schemeConfigs == null) schemeConfigs = Map.of();
        Map<String, Object> secSchemes = (Map<String, Object>) cardAsMap.get("securitySchemes");
        Object secReqsObj = cardAsMap.get("securityRequirements");
        List<Map<String, Object>> secReqs = secReqsObj instanceof List ? (List<Map<String, Object>>) secReqsObj : List.of();
        if (secSchemes == null || secSchemes.isEmpty() || secReqs.isEmpty()) return;
        for (Map<String, Object> req : secReqs) {
            Object schemes = req.get("schemes");
            if (schemes instanceof Map) {
                for (String schemeName : ((Map<String, Object>) schemes).keySet()) {
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
    }
    // --- utility ---
    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object card) {
        if (card instanceof Map) return (Map<String, Object>) card;
        try {
            return mapper.convertValue(card, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
    @Override
    public void close() {
        log.info("[EngineClient] Closing");
        try { a2aClientRuntime.close(); } catch (Exception ignored) {}
    }
    @Override
    public void updateAgentCards(List<?> agentCards) {
        cardMap.clear();
        for (Object card : agentCards) {
            String name = extractName(card);
            if (name != null) cardMap.put(name, card);
        }
        log.info("[EngineClient] Updated agent cards: {} agent(s)", cardMap.size());
    }
    @Override
    public void registerHandler(ExtensionHandler handler) {
        extensionRegistry.register(handler);
    }
    public Object getA2atClient() { return a2atClient; }
    public static Map<String, Object> normalizeAgentDict(Map<String, Object> agentDict) {
        return AgentCardNormalizer.normalize(agentDict);
    }
}
