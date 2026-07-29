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

import io.grpc.ManagedChannelBuilder;

import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.JdkA2AHttpClient;
import org.a2aproject.sdk.client.transport.grpc.GrpcTransport;
import org.a2aproject.sdk.client.transport.grpc.GrpcTransportConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig;
import org.a2aproject.sdk.client.transport.rest.RestTransport;
import org.a2aproject.sdk.client.transport.rest.RestTransportConfig;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.A2AClientException;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.net.ssl.SSLContext;

/**
 * Default implementation of {@link A2AJavaClientRuntime} that uses the a2a-java SDK's {@link
 * Client} with {@link RestTransport}.
 *
 * <p>This replaces the engine's previous hand-written HTTP fallback ({@code sendViaRawHttp}) with
 * the SDK's built-in transport, SSE parsing, and error handling. The runtime creates a new {@link
 * Client} per message send (matching the pattern in the a2a-t-sdk-java sample).
 *
 * <p>SSL handling: when {@code sslVerify=false}, a trust-all SSL context is created and hostname
 * verification is disabled globally via the {@code
 * jdk.internal.httpclient.disableHostnameVerification} system property (must be set before any
 * {@code HttpClient} is created).
 */
public class DefaultA2AJavaClientRuntime implements A2AJavaClientRuntime {

    private static final Logger log = LoggerFactory.getLogger(DefaultA2AJavaClientRuntime.class);

    private final boolean sslVerify;
    private final String caCertsPath;
    private final long sendTimeoutSeconds;
    private final String preferredProtocol;
    private final java.util.concurrent.ExecutorService httpClientExecutor =
            java.util.concurrent.Executors.newCachedThreadPool(
                    r -> {
                        Thread t = new Thread(r, "a2a-client");
                        t.setDaemon(true);
                        return t;
                    });

    /**
     * Create a runtime with the given SSL configuration.
     *
     * @param sslVerify whether to verify server TLS certificates
     * @param caCertsPath optional path to a PEM CA trust store (null = use default)
     * @param sendTimeoutSeconds SSE response wait timeout in seconds
     */
    public DefaultA2AJavaClientRuntime(
            boolean sslVerify,
            String caCertsPath,
            long sendTimeoutSeconds,
            String preferredProtocol) {
        this.sslVerify = sslVerify;
        this.caCertsPath = caCertsPath;
        this.sendTimeoutSeconds = sendTimeoutSeconds;
        this.preferredProtocol = preferredProtocol;
        if (!sslVerify) {
            // Must be set before any HttpClient is created: the JDK caches
            // this property at class-load time.
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        }
        log.info(
                "[A2ARuntime] Initialized: sslVerify={}, caCerts={}, timeout={}s",
                sslVerify,
                caCertsPath,
                sendTimeoutSeconds);
    }

    /** Simplified constructor without CA trust store (SSL verify defaults to false). */
    public DefaultA2AJavaClientRuntime() {
        this(false, null, 600L, null);
    }

    private static String extractAgentUrl(AgentCard agentCard) {
        if (!agentCard.supportedInterfaces().isEmpty()) {
            return agentCard.supportedInterfaces().get(0).url();
        }
        return "?";
    }

    private static void onEvent(
            String agentName,
            ClientEvent event,
            List<ClientEvent> events,
            AtomicReference<ClientEvent> lastEventRef,
            Consumer<ClientEvent> eventSink,
            CountDownLatch done) {
        events.add(event);
        lastEventRef.set(event);
        logEvent(agentName, event);
        ProtocolLogger.logResponseEvent(agentName, event);
        if (eventSink != null) {
            try {
                eventSink.accept(event);
            } catch (Exception e) {
                log.warn(
                        "[A2ARuntime] eventSink callback failed for {} (event_class={}): {}",
                        agentName,
                        event.getClass().getSimpleName(),
                        e.getMessage(),
                        e);
            }
        }
        if (isTerminal(event)) {
            log.info(
                    "[A2ARuntime] Terminal event for '{}': {}",
                    agentName,
                    describeTerminalEvent(event));
            done.countDown();
        }
    }

    private static void onError(
            String agentName,
            Throwable error,
            CountDownLatch done,
            AtomicReference<Throwable> errorRef) {
        if (done.getCount() == 0) {
            log.debug(
                    "[A2ARuntime] Connection closed after terminal event for '{}': {}",
                    agentName,
                    error.getMessage());
        } else {
            String msg = error.getMessage() != null ? error.getMessage() : "";
            boolean connectionClosed =
                    msg.contains("connection closed locally")
                            || msg.contains("chunked transfer encoding, state: READING_LENGTH");
            if (connectionClosed) {
                log.debug("[A2ARuntime] Connection closed for '{}': {}", agentName, msg);
            } else {
                errorRef.set(error);
                log.error(
                        "[A2ARuntime] Error callback for '{}': {}",
                        agentName,
                        error.getMessage(),
                        error);
            }
            done.countDown();
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

    private static void logEvent(String agentName, ClientEvent event) {
        if (event instanceof TaskEvent te) {
            TaskStatus st = te.getTask().status();
            log.info(
                    "[A2ARuntime] Event[Task] agent='{}', state={}, final={}",
                    agentName,
                    st.state(),
                    isTerminal(st.state()));
        } else if (event instanceof TaskUpdateEvent tue) {
            if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
                TaskStatus st = sue.status();
                log.info(
                        "[A2ARuntime] Event[StatusUpdate] agent='{}', state={}, final={}",
                        agentName,
                        st.state(),
                        sue.isFinal());
            } else if (tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent ae) {
                log.info(
                        "[A2ARuntime] Event[ArtifactUpdate] agent='{}', name={}, append={}, lastChunk={}",
                        agentName,
                        ae.artifact().name(),
                        ae.append(),
                        ae.lastChunk());
            }
        } else if (event instanceof MessageEvent me) {
            log.info(
                    "[A2ARuntime] Event[Message] agent='{}', role={}, parts={}",
                    agentName,
                    me.getMessage().role(),
                    me.getMessage().parts().size());
        } else {
            log.debug(
                    "[A2ARuntime] Event[{}] agent='{}'",
                    event.getClass().getSimpleName(),
                    agentName);
        }
    }

    private static String describeTerminalEvent(ClientEvent event) {
        if (event instanceof TaskEvent te) {
            return te.getTask().status().state().name();
        }
        if (event instanceof TaskUpdateEvent tue
                && tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
            return sue.status().state().name();
        }
        return event.getClass().getSimpleName();
    }

    @Override
    public Iterable<ClientEvent> sendMessage(
            AgentCard agentCard,
            org.a2aproject.sdk.spec.MessageSendParams params,
            ClientCallContext callContext,
            Consumer<ClientEvent> eventSink,
            Consumer<String> logSink) {
        String agentUrl = extractAgentUrl(agentCard);
        Client client = createClient(agentCard, agentUrl);
        List<ClientEvent> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicReference<ClientEvent> lastEventRef = new AtomicReference<>();

        if (logSink != null) logSink.accept("[A2A] Sending message to " + agentCard.name());

        try {
            client.sendMessage(
                    params,
                    List.of(
                            (event, card) ->
                                    onEvent(
                                            agentCard.name(),
                                            event,
                                            events,
                                            lastEventRef,
                                            eventSink,
                                            done)),
                    error -> onError(agentCard.name(), error, done, errorRef),
                    callContext);
        } catch (A2AClientException e) {
            client.close();
            throw new RuntimeException(
                    "A2A message:send failed for " + agentCard.name() + ": " + e.getMessage(), e);
        }

        awaitCompletion(agentCard.name(), done, events, lastEventRef, client);
        client.close();

        if (errorRef.get() != null) {
            throw new RuntimeException(
                    "A2A message:send failed for "
                            + agentCard.name()
                            + ": "
                            + errorRef.get().getMessage(),
                    errorRef.get());
        }
        log.info("[A2ARuntime] Completed for '{}': {} event(s)", agentCard.name(), events.size());
        return events;
    }

    private Client createClient(AgentCard agentCard, String agentUrl) {
        AgentInterface selected = selectInterface(agentCard);
        String protocolBinding = selected.protocolBinding();
        A2AHttpClient httpClient = createHttpClient();
        try {
            Client client = buildClientWithTransport(agentCard, protocolBinding, httpClient);
            log.info(
                    "[A2ARuntime] Transport: {} for '{}' ({})",
                    protocolBinding,
                    agentCard.name(),
                    selected.url());
            return client;
        } catch (A2AClientException e) {
            log.error(
                    "[A2ARuntime] Failed to create client for '{}' ({}): {}",
                    agentCard.name(),
                    agentUrl,
                    e.getMessage(),
                    e);
            throw new RuntimeException(
                    "Failed to create a2a-java client for "
                            + agentCard.name()
                            + ": "
                            + e.getMessage(),
                    e);
        }
    }

    /** Select the best AgentInterface based on preferredProtocol or first available. */
    private AgentInterface selectInterface(AgentCard agentCard) {
        List<AgentInterface> interfaces = agentCard.supportedInterfaces();
        if (interfaces == null || interfaces.isEmpty()) {
            throw new RuntimeException("AgentCard has no supportedInterfaces: " + agentCard.name());
        }
        if (preferredProtocol != null && !preferredProtocol.isBlank()) {
            for (AgentInterface iface : interfaces) {
                if (preferredProtocol.equalsIgnoreCase(iface.protocolBinding())) {
                    log.info(
                            "[A2ARuntime] Selected preferred protocol {} for '{}'",
                            preferredProtocol,
                            agentCard.name());
                    return iface;
                }
            }
            log.warn(
                    "[A2ARuntime] Preferred protocol '{}' not in supportedInterfaces for '{}', using first available: {}",
                    preferredProtocol,
                    agentCard.name(),
                    interfaces.get(0).protocolBinding());
        }
        return interfaces.get(0);
    }

    /**
     * Build the client with the transport matching the protocol binding.
     *
     * <p>HTTP+JSON and JSONRPC use A2AHttpClient for SSL configuration. GRPC uses
     * GrpcTransportConfig with a custom Channel factory. When sslVerify=false, gRPC uses plaintext
     * (HTTP/2 without TLS). For custom gRPC CA certs, provide a custom A2AJavaClientRuntime.
     */
    private Client buildClientWithTransport(
            AgentCard agentCard, String protocolBinding, A2AHttpClient httpClient)
            throws A2AClientException {
        if ("JSONRPC".equalsIgnoreCase(protocolBinding)) {
            return Client.builder(agentCard)
                    .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig(httpClient))
                    .build();
        }
        if ("GRPC".equalsIgnoreCase(protocolBinding)) {
            GrpcTransportConfig grpcConfig = new GrpcTransportConfig(url -> createGrpcChannel(url));
            return Client.builder(agentCard).withTransport(GrpcTransport.class, grpcConfig).build();
        }
        return Client.builder(agentCard)
                .withTransport(RestTransport.class, new RestTransportConfig(httpClient))
                .build();
    }

    private void awaitCompletion(
            String agentName,
            CountDownLatch done,
            List<ClientEvent> events,
            AtomicReference<ClientEvent> lastEventRef,
            Client client) {
        try {
            if (!done.await(sendTimeoutSeconds, TimeUnit.SECONDS)) {
                ClientEvent last = lastEventRef.get();
                log.error(
                        "[A2ARuntime] TIMEOUT for '{}' after {}s: received {} event(s), last event_class={}",
                        agentName,
                        sendTimeoutSeconds,
                        events.size(),
                        last != null ? last.getClass().getSimpleName() : "none");
                client.close();
                throw new RuntimeException("A2A message:send timed out for " + agentName);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            client.close();
            throw new RuntimeException("A2A message:send interrupted for " + agentName, e);
        }
    }

    @Override
    public void close() {
        log.info("[A2ARuntime] Closed");
    }

    /**
     * Create a gRPC channel with SSL settings matching the engine config.
     *
     * <p>When sslVerify=false, uses plaintext (HTTP/2 without TLS). When sslVerify=true, uses the
     * default TLS trust store. For custom CA certs with gRPC, add grpc-netty-shaded to classpath
     * and override this method via a custom A2AJavaClientRuntime.
     */
    private io.grpc.Channel createGrpcChannel(String url) {
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forTarget(url);
        if (!sslVerify) {
            builder.usePlaintext();
        }
        return builder.build();
    }

    private A2AHttpClient createHttpClient() {
        if (this.sslVerify) {
            return new JdkA2AHttpClient();
        }
        SSLContext trustAllCtx = SslContextFactory.createTrustAll();
        HttpClient httpClient =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(60))
                        .sslContext(trustAllCtx)
                        .executor(httpClientExecutor)
                        .build();
        return new JdkA2AHttpClient(httpClient);
    }
}
