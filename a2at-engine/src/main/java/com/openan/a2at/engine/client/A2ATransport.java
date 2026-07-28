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

package com.openan.a2at.engine.client;

import com.openan.a2at.engine.model.SendMessageResult;
import net.openan.a2at.sdk.client.A2ATClient;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallInterceptor;
import org.a2aproject.sdk.client.transport.spi.interceptors.PayloadAndHeaders;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.SecurityRequirement;
import org.a2aproject.sdk.spec.SecurityScheme;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Shared A2A communication base (client runtime + auth + card map +
 * SSE event extraction). This is the low-level layer over which two
 * single-responsibility facades sit:
 *
 * <ul>
 *   <li>{@link DefaultWorkflowEngineClient} -- workflow execution path
 *       (Task-T prompt generation, Negotiation-T auto-loop, extension
 *       handlers, event callback, control point).</li>
 *   <li>{@link DefaultExtensionSender} -- one-shot pre-positioning
 *       sends (Authorization-T / Notification-T).</li>
 * </ul>
 *
 * Neither facade duplicates transport code; both delegate here.
 */
public class A2ATransport implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(A2ATransport.class);

    private final Map<String, AgentCard> cardMap = new ConcurrentHashMap<>();
    private final A2AJavaClientRuntime a2aClientRuntime;
    private final AgentAuthManager authManager;
    private final AuthProvider authProvider;
    private final A2ATClient a2atClient;
    private final String contextId;
    private final ExecutorService asyncExecutor =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "engine-send");
                t.setDaemon(true);
                return t;
            });

    public A2ATransport(List<AgentCard> agentCards, A2AJavaClientRuntime a2aClientRuntime,
                        WorkflowEngineClientConfig config) {
        this.a2aClientRuntime = a2aClientRuntime != null ? a2aClientRuntime
                : new DefaultA2AJavaClientRuntime(config.isSslVerify(), config.getCaCertsPath(),
                        config.getSendTimeoutSeconds(), config.getPreferredProtocol());
        this.contextId = UUID.randomUUID().toString();
        if (config.getCredentialsConfigPath() != null) {
            this.authManager = new AgentAuthManager(config.getCredentialsConfigPath());
        } else if (config.getCredentialsConfig() != null) {
            this.authManager = new AgentAuthManager(config.getCredentialsConfig());
        } else {
            this.authManager = new AgentAuthManager();
        }
        this.a2atClient = initA2atClient(config.getA2atEnvPath());
        this.authProvider = config.getAuthProvider();
        for (AgentCard card : agentCards) {
            if (!card.name().isEmpty()) {
                cardMap.put(card.name(), card);
            }
        }
        log.info("[Transport] Initialized with {} agent(s), a2at={}",
                cardMap.size(), a2atClient != null);
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

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public AgentCard getCard(String agentName) {
        return cardMap.get(agentName);
    }

    public List<String> getAgentNames() {
        return new ArrayList<>(cardMap.keySet());
    }

    public A2ATClient getA2atClient() {
        return a2atClient;
    }

    public String getContextId() {
        return contextId;
    }

    public void updateAgentCards(List<AgentCard> agentCards) {
        cardMap.clear();
        for (AgentCard card : agentCards) {
            if (!card.name().isEmpty()) {
                cardMap.put(card.name(), card);
            }
        }
        log.info("[Transport] Updated agent cards: {} agent(s)", cardMap.size());
    }

    // ------------------------------------------------------------------
    // Core send via SDK runtime
    // ------------------------------------------------------------------

    /**
     * Send a message and collect the streaming events. The optional
     * {@code eventSink} is invoked for each intermediate event (status
     * updates, artifact updates, messages); the workflow facade wires it
     * to its event callback, the one-shot sender passes {@code null}.
     */
    public CompletableFuture<SendMessageResult> send(
            AgentCard agentCard, String agentName, String message,
            String contextId, Map<String, Object> metadata,
            Consumer<ClientEvent> eventSink) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                MessageSendParams params = buildMessageSendParams(message, contextId, metadata);
                ClientCallContext callContext = buildClientCallContext(agentCard, agentName, metadata);
                String endpoint = agentCard.supportedInterfaces().isEmpty() ? "?"
                        : agentCard.supportedInterfaces().get(0).url();
                ProtocolLogger.logRequest(agentName, endpoint, params, callContext.getHeaders());
                log.info("[Transport] Sending via A2A SDK to {}", agentName);
                Iterable<ClientEvent> events = a2aClientRuntime.sendMessage(
                        agentCard, params, callContext,
                        eventSink,
                        s -> log.info("[A2A] {}", s));
                String responseText = extractResponseText(events);
                String taskState = extractResponseTaskState(events);
                Map<String, Object> respMetadata = extractResponseMetadata(events);
                Task task = extractResponseTask(events);
                log.info("[Transport] Response from {}: {} chars, state={}", agentName, responseText.length(), taskState);
                return SendMessageResult.builder().text(responseText).task(task).metadata(respMetadata).taskState(taskState).build();
            } catch (Exception e) {
                log.error("[Transport] Failed to send message to {}: {}", agentName, e.getMessage(), e);
                throw new RuntimeException("Agent call failed: " + e.getMessage(), e);
            }
        }, asyncExecutor);
    }

    /**
     * Long-lived SSE stream for Notification-T subscription. Opens a daemon
     * thread that keeps the SSE response stream open. The eventSink callback
     * processes events in real-time (subscribed ack + later recovery results).
     * The returned future completes on the first event (subscription confirmed).
     */
    public CompletableFuture<SendMessageResult> sendNotificationStream(
            AgentCard agentCard, String agentName, String message,
            String contextId, Map<String, Object> metadata,
            Consumer<ClientEvent> eventSink) {
        CompletableFuture<SendMessageResult> future = new CompletableFuture<>();
        Thread streamThread = new Thread(() -> {
            try {
                MessageSendParams params = buildMessageSendParams(message, contextId, metadata);
                ClientCallContext callContext = buildClientCallContext(agentCard, agentName, metadata);
                String endpoint = agentCard.supportedInterfaces().isEmpty() ? "?"
                        : agentCard.supportedInterfaces().get(0).url();
                ProtocolLogger.logRequest(agentName, endpoint, params, callContext.getHeaders());
                log.info("[Transport] Opening Notification-T long-lived stream to {}", agentName);
                a2aClientRuntime.sendMessage(
                        agentCard, params, callContext,
                        event -> {
                            log.info("[Transport] Notification-T event from {}: {}", agentName, event.getClass().getSimpleName());
                            if (eventSink != null) {
                                eventSink.accept(event);
                            }
                            if (!future.isDone()) {
                                future.complete(SendMessageResult.builder()
                                        .text("Subscribed")
                                        .taskState("TASK_STATE_WORKING")
                                        .build());
                            }
                        },
                        s -> log.info("[A2A] {}", s));
                log.info("[Transport] Notification-T stream closed for {}", agentName);
                if (!future.isDone()) {
                    future.complete(SendMessageResult.builder()
                            .text("Stream closed")
                            .taskState("TASK_STATE_COMPLETED")
                            .build());
                }
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                boolean connectionClosed = msg.contains("connection closed locally")
                        || msg.contains("chunked transfer encoding, state: READING_LENGTH");
                if (connectionClosed) {
                    log.info("[Transport] Notification-T stream closed for {}", agentName);
                } else {
                    log.error("[Transport] Notification-T stream error for {}: {}", agentName, e.getMessage(), e);
                }
                if (!future.isDone()) {
                    future.completeExceptionally(e);
                }
            }
        }, "notif-t-" + agentName);
        streamThread.setDaemon(true);
        streamThread.start();
        return future.orTimeout(5, TimeUnit.SECONDS)
                .exceptionally(e -> {
                    log.warn("[Transport] Notification-T subscription: no event in 5s, assuming active (stream stays open)");
                    return SendMessageResult.builder()
                            .text("Subscribed (no-ack)")
                            .taskState("TASK_STATE_WORKING")
                            .build();
                });
    }

    // ------------------------------------------------------------------
    // Build helpers
    // ------------------------------------------------------------------

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
                    log.warn("[Transport] Extension interceptor failed: {}", e.getMessage());
                }
            }
        }
        return new ClientCallContext(new HashMap<>(), headers);
    }

    // ------------------------------------------------------------------
    // ClientEvent extraction (static helpers, shared by facades)
    // ------------------------------------------------------------------

    public static String extractResponseText(Iterable<ClientEvent> events) {
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

    public static void extractTextFromTask(Task task, StringBuilder sb) {
        if (task.artifacts() != null) for (Artifact a : task.artifacts()) extractTextFromArtifact(a, sb);
    }

    public static void extractTextFromArtifact(Artifact artifact, StringBuilder sb) {
        for (Part<?> part : artifact.parts()) if (part instanceof TextPart tp) sb.append(tp.text());
    }

    public static void extractTextFromMessage(Message message, StringBuilder sb) {
        if (message == null) return;
        for (Part<?> part : message.parts()) if (part instanceof TextPart tp) sb.append(tp.text());
    }

    public static String extractResponseTaskState(Iterable<ClientEvent> events) {
        String state = "";
        for (ClientEvent event : events) {
            if (event instanceof TaskEvent te) state = te.getTask().status().state().name();
            else if (event instanceof TaskUpdateEvent tue) {
                if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) state = sue.status().state().name();
            }
        }
        return state;
    }

    public static Map<String, Object> extractResponseMetadata(Iterable<ClientEvent> events) {
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

    public static void mergeTaskMetadata(Task task, Map<String, Object> metadata) {
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

    public static Task extractResponseTask(Iterable<ClientEvent> events) {
        Task lastTask = null;
        for (ClientEvent event : events) {
            if (event instanceof TaskEvent te) lastTask = te.getTask();
            else if (event instanceof TaskUpdateEvent tue) lastTask = tue.getTask();
        }
        return lastTask;
    }

    public static List<String> extractExtensionUris(AgentCard agentCard) {
        List<String> uris = new ArrayList<>();
        assert agentCard.capabilities().extensions() != null;
        for (var ext : agentCard.capabilities().extensions()) {
            String uri = ext.uri();
            if (!uri.isEmpty()) uris.add(uri);
        }
        return uris;
    }

    // ------------------------------------------------------------------
    // Auth headers
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public void close() {
        log.info("[Transport] Closing");
        try { a2aClientRuntime.close(); } catch (Exception ignored) {}
    }
}