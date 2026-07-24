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

import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.http.JdkA2AHttpClient;
import org.a2aproject.sdk.client.transport.rest.RestTransport;
import org.a2aproject.sdk.client.transport.rest.RestTransportConfig;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.A2AClientException;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Default implementation of {@link A2AJavaClientRuntime} that uses the
 * a2a-java SDK's {@link Client} with {@link RestTransport}.
 *
 * <p>This replaces the engine's previous hand-written HTTP fallback
 * ({@code sendViaRawHttp}) with the SDK's built-in transport, SSE
 * parsing, and error handling. The runtime creates a new {@link Client}
 * per message send (matching the pattern in the a2a-t-sdk-java sample).
 *
 * <p>SSL handling: when {@code sslVerify=false}, a trust-all SSL context
 * is created and hostname verification is disabled globally via the
 * {@code jdk.internal.httpclient.disableHostnameVerification} system
 * property (must be set before any {@code HttpClient} is created).
 */
public class DefaultA2AJavaClientRuntime implements A2AJavaClientRuntime {

    private static final Logger log = LoggerFactory.getLogger(DefaultA2AJavaClientRuntime.class);
    private static final long SEND_TIMEOUT_SECONDS = 120;

    private final boolean sslVerify;
    private final String caCertsPath;

    /**
     * Create a runtime with the given SSL configuration.
     *
     * @param sslVerify   whether to verify server TLS certificates
     * @param caCertsPath optional path to a PEM CA trust store (null = use default)
     */
    public DefaultA2AJavaClientRuntime(boolean sslVerify, String caCertsPath) {
        this.sslVerify = sslVerify;
        this.caCertsPath = caCertsPath;
        if (!sslVerify) {
            // Must be set before any HttpClient is created: the JDK caches
            // this property at class-load time.
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        }
        log.info("[A2ARuntime] Initialized: sslVerify={}, caCerts={}", sslVerify, caCertsPath);
    }

    /**
     * Simplified constructor without CA trust store (SSL verify defaults to false).
     */
    public DefaultA2AJavaClientRuntime() {
        this(false, null);
    }

    @Override
    public Iterable<ClientEvent> sendMessage(
            AgentCard agentCard,
            org.a2aproject.sdk.spec.MessageSendParams params,
            ClientCallContext callContext,
            Consumer<ClientEvent> eventSink,
            Consumer<String> logSink) {
        String agentUrl = "?";
        if (agentCard.supportedInterfaces() != null && !agentCard.supportedInterfaces().isEmpty()) {
            agentUrl = agentCard.supportedInterfaces().get(0).url();
        }
        RestTransportConfig transportConfig = createTransportConfig();
        Client client;
        try {
            client = Client.builder(agentCard)
                    .withTransport(RestTransport.class, transportConfig)
                    .build();
        } catch (A2AClientException e) {
            log.error("[A2ARuntime] Failed to create client for '{}' ({}): {}", agentCard.name(), agentUrl, e.getMessage(), e);
            throw new RuntimeException("Failed to create a2a-java client for "
                    + agentCard.name() + ": " + e.getMessage(), e);
        }
        log.info("[A2ARuntime] sendMessage: agent='{}', url={}, messageId={}",
                agentCard.name(), agentUrl,
                params.message() != null ? params.message().messageId() : "?");

        List<ClientEvent> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicReference<ClientEvent> lastEventRef = new AtomicReference<>();

        try {
            if (logSink != null) {
                logSink.accept("[A2A] Sending message to " + agentCard.name());
            }
            client.sendMessage(
                    params,
                    List.of((event, card) -> {
                        events.add(event);
                        lastEventRef.set(event);
                        logEvent(agentCard.name(), event);
                        if (eventSink != null) {
                            try {
                                eventSink.accept(event);
                            } catch (Exception e) {
                                log.warn("[A2ARuntime] eventSink callback failed for {} (event_class={}): {}",
                                        agentCard.name(), event.getClass().getSimpleName(), e.getMessage(), e);
                            }
                        }
                        if (isTerminal(event)) {
                            log.info("[A2ARuntime] Terminal event for '{}': {}",
                                    agentCard.name(), describeTerminalEvent(event));
                            done.countDown();
                        }
                    }),
                    error -> {
                        // If the terminal event was already received, this is a benign
                        // connection closure during teardown (client.close() or server
                        // shutdown). Don't set errorRef so the completed task isn't
                        // marked as failed, and log at DEBUG instead of ERROR.
                        if (done.getCount() == 0) {
                            log.debug("[A2ARuntime] Connection closed after terminal event for '{}': {}",
                                    agentCard.name(), error.getMessage());
                        } else {
                            errorRef.set(error);
                            log.error("[A2ARuntime] Error callback for '{}': {}", agentCard.name(), error.getMessage(), error);
                            done.countDown();
                        }
                    },
                    callContext);
        } catch (A2AClientException e) {
            client.close();
            log.error("[A2ARuntime] message:send exception for '{}': {}", agentCard.name(), e.getMessage(), e);
            throw new RuntimeException("A2A message:send failed for "
                    + agentCard.name() + ": " + e.getMessage(), e);
        }

        try {
            if (!done.await(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                ClientEvent last = lastEventRef.get();
                log.error("[A2ARuntime] TIMEOUT for '{}' after {}s: received {} event(s), last event_class={}",
                        agentCard.name(), SEND_TIMEOUT_SECONDS, events.size(),
                        last != null ? last.getClass().getSimpleName() : "none");
                client.close();
                throw new RuntimeException("A2A message:send timed out for " + agentCard.name());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            client.close();
            log.warn("[A2ARuntime] Interrupted while waiting for '{}'", agentCard.name());
            throw new RuntimeException("A2A message:send interrupted for " + agentCard.name(), e);
        }

        client.close();

        if (errorRef.get() != null) {
            throw new RuntimeException("A2A message:send failed for "
                    + agentCard.name() + ": " + errorRef.get().getMessage(), errorRef.get());
        }
        log.info("[A2ARuntime] Completed for '{}': {} event(s)", agentCard.name(), events.size());
        return events;
    }

    @Override
    public void close() {
        log.info("[A2ARuntime] Closed");
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private RestTransportConfig createTransportConfig() {
        if (sslVerify) {
            return new RestTransportConfig();
        }
        SSLContext trustAllCtx = SslContextFactory.createTrustAll();
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(60))
                .sslContext(trustAllCtx)
                .build();
        return new RestTransportConfig(new JdkA2AHttpClient(httpClient));
    }

    private static boolean isTerminal(ClientEvent event) {
        if (event instanceof TaskEvent taskEvent) {
            return isTerminal(taskEvent.getTask().status().state());
        }
        if (event instanceof TaskUpdateEvent taskUpdateEvent) {
            if (taskUpdateEvent.getUpdateEvent() instanceof TaskStatusUpdateEvent statusUpdate) {
                return isTerminal(statusUpdate.status().state());
            }
            // Artifact updates are not terminal
            if (taskUpdateEvent.getUpdateEvent() instanceof TaskArtifactUpdateEvent) {
                return false;
            }
        }
        return false;
    }

    private static boolean isTerminal(TaskState state) {
        return state == TaskState.TASK_STATE_COMPLETED
                || state == TaskState.TASK_STATE_FAILED
                || state == TaskState.TASK_STATE_CANCELED
                || state == TaskState.TASK_STATE_REJECTED
                || state == TaskState.TASK_STATE_INPUT_REQUIRED
                || state == TaskState.TASK_STATE_AUTH_REQUIRED;
    }

    // ------------------------------------------------------------------
    // Diagnostic logging helpers
    // ------------------------------------------------------------------

    private static void logEvent(String agentName, ClientEvent event) {
        if (event instanceof TaskEvent te) {
            TaskStatus st = te.getTask() != null ? te.getTask().status() : null;
            log.info("[A2ARuntime] Event[Task] agent='{}', state={}, final={}",
                    agentName,
                    st != null ? st.state() : "?",
                    st != null && st.state() != null && isTerminal(st.state()));
        } else if (event instanceof TaskUpdateEvent tue) {
            if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
                TaskStatus st = sue.status();
                log.info("[A2ARuntime] Event[StatusUpdate] agent='{}', state={}, final={}",
                        agentName,
                        st != null ? st.state() : "?",
                        sue.isFinal());
            } else if (tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent ae) {
                log.info("[A2ARuntime] Event[ArtifactUpdate] agent='{}', name={}, append={}, lastChunk={}",
                        agentName,
                        ae.artifact() != null ? ae.artifact().name() : "?",
                        ae.append(), ae.lastChunk());
            }
        } else if (event instanceof MessageEvent me) {
            log.info("[A2ARuntime] Event[Message] agent='{}', role={}, parts={}",
                    agentName,
                    me.getMessage() != null && me.getMessage().role() != null ? me.getMessage().role() : "?",
                    me.getMessage() != null && me.getMessage().parts() != null ? me.getMessage().parts().size() : 0);
        } else {
            log.debug("[A2ARuntime] Event[{}] agent='{}'", event.getClass().getSimpleName(), agentName);
        }
    }

    private static String describeTerminalEvent(ClientEvent event) {
        if (event instanceof TaskEvent te) {
            return te.getTask() != null && te.getTask().status() != null
                    ? te.getTask().status().state().name() : "?";
        }
        if (event instanceof TaskUpdateEvent tue
                && tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
            return sue.status() != null && sue.status().state() != null
                    ? sue.status().state().name() : "?";
        }
        return event.getClass().getSimpleName();
    }
}
