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
import org.a2aproject.sdk.client.http.JdkA2AHttpClient;
import org.a2aproject.sdk.client.transport.rest.RestTransport;
import org.a2aproject.sdk.client.transport.rest.RestTransportConfig;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.A2AClientException;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.http.HttpClient;
import java.security.cert.X509Certificate;
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
            Map<String, Object> agentCardMap,
            org.a2aproject.sdk.spec.MessageSendParams params,
            ClientCallContext callContext,
            Consumer<ClientEvent> eventSink,
            Consumer<String> logSink) {
        AgentCard agentCard = AgentCardMapper.toSdkAgentCard(agentCardMap);
        RestTransportConfig transportConfig = createTransportConfig();
        Client client;
        try {
            client = Client.builder(agentCard)
                    .withTransport(RestTransport.class, transportConfig)
                    .build();
        } catch (A2AClientException e) {
            throw new RuntimeException("Failed to create a2a-java client for "
                    + agentCard.name() + ": " + e.getMessage(), e);
        }

        List<ClientEvent> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        try {
            if (logSink != null) {
                logSink.accept("[A2A] Sending message to " + agentCard.name());
            }
            client.sendMessage(
                    params,
                    List.of((event, card) -> {
                        events.add(event);
                        if (eventSink != null) {
                            try {
                                eventSink.accept(event);
                            } catch (Exception e) {
                                log.warn("[A2ARuntime] eventSink callback failed: {}", e.getMessage());
                            }
                        }
                        if (isTerminal(event)) {
                            done.countDown();
                        }
                    }),
                    error -> {
                        errorRef.set(error);
                        done.countDown();
                    },
                    callContext);
        } catch (A2AClientException e) {
            client.close();
            throw new RuntimeException("A2A message:send failed for "
                    + agentCard.name() + ": " + e.getMessage(), e);
        }

        try {
            if (!done.await(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                client.close();
                throw new RuntimeException("A2A message:send timed out for " + agentCard.name());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            client.close();
            throw new RuntimeException("A2A message:send interrupted for " + agentCard.name(), e);
        }

        client.close();

        if (errorRef.get() != null) {
            throw new RuntimeException("A2A message:send failed for "
                    + agentCard.name() + ": " + errorRef.get().getMessage(), errorRef.get());
        }
        if (logSink != null) {
            logSink.accept("[A2A] Received " + events.size() + " event(s) from " + agentCard.name());
        }
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
        // Trust-all SSL context for self-signed certs
        try {
            SSLContext trustAllCtx = SSLContext.getInstance("TLS");
            trustAllCtx.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, null);
            HttpClient httpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(60))
                    .sslContext(trustAllCtx)
                    .build();
            return new RestTransportConfig(new JdkA2AHttpClient(httpClient));
        } catch (Exception e) {
            log.warn("[A2ARuntime] Failed to create trust-all SSL context, using default: {}", e.getMessage());
            return new RestTransportConfig();
        }
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
}
