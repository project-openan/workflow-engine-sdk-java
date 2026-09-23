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

package dev.openan.workflow.engine.client;

import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.ReceivedMessage;
import dev.openan.workflow.engine.model.SendMessageResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared A2A communication base (client runtime + auth + card map + SSE event extraction). This is
 * the low-level layer over which two single-responsibility facades sit:
 *
 * <ul>
 *   <li>{@link DefaultWorkflowEngineClient} -- workflow execution path (task dispatch, negotiation
 *       correlation and reply validation, event callback, control point).
 *   <li>{@link DefaultExtensionSender} -- sending host-generated Authorization-T requests and
 *       observing Notification-T subscriptions.
 * </ul>
 *
 * Neither facade duplicates transport code; both delegate here.
 */
public class A2ATransport implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(A2ATransport.class);
  private final A2AJavaClientRuntime a2aClientRuntime;
  private final AgentAuthManager authManager;
  private final AuthProvider authProvider;
  private final String contextId;
  private final ClientCallContextFactory clientCallContextFactory;
  private final ExecutorService asyncExecutor;
  private final ScheduledExecutorService timeoutScheduler;
  private final long notificationAckTimeoutSeconds;
  private final long sendTimeoutSeconds;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final java.util.Set<NotificationSubscription> notificationSubscriptions =
      ConcurrentHashMap.newKeySet();
  private volatile Map<String, AgentCard> cardMap = Map.of();

  public A2ATransport(
      List<AgentCard> agentCards,
      A2AJavaClientRuntime a2aClientRuntime,
      WorkflowEngineClientConfig config) {
    config = java.util.Objects.requireNonNull(config, "config");
    Map<String, AgentCard> validatedCards = validateAgentCards(agentCards);
    this.a2aClientRuntime =
        a2aClientRuntime != null
            ? a2aClientRuntime
            : new DefaultA2AJavaClientRuntime(
                config.isSslVerify(),
                config.getCaCertsPath(),
                config.getClientCertPath(),
                config.getClientKeyPath(),
                config.getClientKeyPassword(),
                config.getCrlPath(),
                config.getSendTimeoutSeconds(),
                config.getPreferredProtocol(),
                config.getSendExecutorCoreSize(),
                config.getSendExecutorMaxSize(),
                config.getSendExecutorQueueCapacity());
    this.contextId = UUID.randomUUID().toString();
    this.notificationAckTimeoutSeconds = config.getNotificationAckTimeoutSeconds();
    this.sendTimeoutSeconds = config.getSendTimeoutSeconds();
    this.asyncExecutor =
        new ThreadPoolExecutor(
            config.getSendExecutorCoreSize(),
            config.getSendExecutorMaxSize(),
            60L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(config.getSendExecutorQueueCapacity()),
            r -> {
              Thread t = new Thread(r, "engine-send");
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());
    this.timeoutScheduler =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "transport-timeouts");
              thread.setDaemon(true);
              return thread;
            });
    CredentialHttpTransport credentialHttpTransport = null;
    String credentialEncryptionKey = config.getCredentialEncryptionKey();
    if (config.getCredentialsConfigPath() != null || config.getCredentialsConfig() != null) {
      credentialHttpTransport =
          CredentialHttpTransport.create(
              config.isSslVerify(),
              config.getCaCertsPath(),
              config.getClientCertPath(),
              config.getClientKeyPath(),
              config.getClientKeyPassword(),
              config.getCrlPath(),
              Duration.ofSeconds(30));
    }
    if (config.getCredentialsConfigPath() != null) {
      this.authManager =
          new AgentAuthManager(
              config.getCredentialsConfigPath(), credentialHttpTransport, credentialEncryptionKey);
    } else if (config.getCredentialsConfig() != null) {
      this.authManager =
          new AgentAuthManager(
              config.getCredentialsConfig(), credentialHttpTransport, credentialEncryptionKey);
    } else {
      this.authManager = new AgentAuthManager();
    }
    this.authProvider = config.getAuthProvider();
    this.clientCallContextFactory =
        new ClientCallContextFactory(
            new AuthProviderHeaderContributor(authProvider),
            new CredentialHeaderContributor(authManager, authProvider));
    cardMap = java.util.Collections.unmodifiableMap(validatedCards);
    log.info("[Transport] Initialized with {} agent(s)", cardMap.size());
  }

  public static String extractResponseText(Iterable<ClientEvent> events) {
    // A2A clients commonly expose the same artifact three times: first as an artifact update,
    // then in the Task snapshot attached to a status update, and finally in the terminal Task.
    // Treat artifact updates as the authoritative stream and use snapshots/messages only as
    // fallbacks. Blindly concatenating every representation corrupts workflow input.
    Map<String, StringBuilder> streamedArtifacts = new LinkedHashMap<>();
    Map<String, String> snapshotArtifacts = new LinkedHashMap<>();
    Set<String> fallbackMessages = new LinkedHashSet<>();
    for (ClientEvent event : events) {
      if (event instanceof TaskEvent te) {
        captureTaskArtifacts(te.getTask(), snapshotArtifacts);
      } else if (event instanceof TaskUpdateEvent tue) {
        captureTaskArtifacts(tue.getTask(), snapshotArtifacts);
        if (tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent ae) {
          String artifactId = artifactKey(ae.artifact(), streamedArtifacts.size());
          String chunk = artifactText(ae.artifact());
          if (Boolean.TRUE.equals(ae.append())) {
            streamedArtifacts
                .computeIfAbsent(artifactId, ignored -> new StringBuilder())
                .append(chunk);
          } else {
            streamedArtifacts.put(artifactId, new StringBuilder(chunk));
          }
        }
        if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue
            && sue.status().message() != null) {
          addFallbackMessage(sue.status().message(), fallbackMessages);
        }
      } else if (event instanceof MessageEvent me) {
        addFallbackMessage(me.getMessage(), fallbackMessages);
      }
    }
    if (!streamedArtifacts.isEmpty()) {
      return concatenate(streamedArtifacts.values());
    }
    if (!snapshotArtifacts.isEmpty()) {
      return concatenate(snapshotArtifacts.values());
    }
    return String.join("", fallbackMessages);
  }

  private static void captureTaskArtifacts(Task task, Map<String, String> artifacts) {
    if (task == null || task.artifacts() == null) return;
    for (Artifact artifact : task.artifacts()) {
      artifacts.put(artifactKey(artifact, artifacts.size()), artifactText(artifact));
    }
  }

  private static String artifactKey(Artifact artifact, int fallbackIndex) {
    if (artifact.artifactId() != null && !artifact.artifactId().isBlank()) {
      return artifact.artifactId();
    }
    if (artifact.name() != null && !artifact.name().isBlank()) {
      return artifact.name();
    }
    return "artifact-" + fallbackIndex;
  }

  private static String artifactText(Artifact artifact) {
    StringBuilder text = new StringBuilder();
    extractTextFromArtifact(artifact, text);
    return text.toString();
  }

  private static void addFallbackMessage(Message message, Set<String> messages) {
    StringBuilder text = new StringBuilder();
    extractTextFromMessage(message, text);
    if (!text.isEmpty()) messages.add(text.toString());
  }

  private static String concatenate(Iterable<?> values) {
    StringBuilder text = new StringBuilder();
    for (Object value : values) text.append(value);
    return text.toString();
  }

  // ------------------------------------------------------------------
  // Accessors
  // ------------------------------------------------------------------

  public static void extractTextFromTask(Task task, StringBuilder sb) {
    if (task.artifacts() != null)
      for (Artifact a : task.artifacts()) extractTextFromArtifact(a, sb);
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
        if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue)
          state = sue.status().state().name();
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
        if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
          Map<String, Object> em = sue.metadata();
          if (em != null && !em.isEmpty()) metadata.putAll(em);
          // A status can carry its own Message (e.g. a Negotiation-T propose riding on
          // an INPUT_REQUIRED status); merge that message's metadata too.
          if (sue.status().message() != null) {
            Map<String, Object> sm = sue.status().message().metadata();
            if (sm != null && !sm.isEmpty()) metadata.putAll(sm);
          }
        }
      } else if (event instanceof MessageEvent me) {
        Map<String, Object> mm = me.getMessage().metadata();
        if (mm != null && !mm.isEmpty()) metadata.putAll(mm);
      }
    }
    return metadata;
  }

  // ------------------------------------------------------------------
  // Core send via SDK runtime
  // ------------------------------------------------------------------

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

  // ------------------------------------------------------------------
  // Build helpers
  // ------------------------------------------------------------------

  public static List<String> extractExtensionUris(AgentCard agentCard) {
    List<String> uris = new ArrayList<>();
    if (agentCard == null || agentCard.capabilities() == null) {
      return uris;
    }
    var extensions = agentCard.capabilities().extensions();
    if (extensions == null) {
      return uris;
    }
    for (var ext : extensions) {
      if (ext == null) continue;
      String uri = ext.uri();
      if (uri != null && !uri.isBlank()) uris.add(uri);
    }
    return uris;
  }

  private static Map<String, AgentCard> validateAgentCards(List<AgentCard> agentCards) {
    java.util.Objects.requireNonNull(agentCards, "agentCards");
    Map<String, AgentCard> validated = new java.util.LinkedHashMap<>();
    for (AgentCard card : agentCards) {
      java.util.Objects.requireNonNull(card, "agentCard");
      if (card.name() == null || card.name().isBlank()) {
        throw new IllegalArgumentException("AgentCard name must not be blank");
      }
      if (card.capabilities() == null) {
        throw new IllegalArgumentException("AgentCard capabilities are required: " + card.name());
      }
      if (card.supportedInterfaces() == null || card.supportedInterfaces().isEmpty()) {
        throw new IllegalArgumentException(
            "AgentCard supportedInterfaces are required: " + card.name());
      }
      if (validated.putIfAbsent(card.name(), card) != null) {
        throw new IllegalArgumentException("Duplicate AgentCard name: " + card.name());
      }
    }
    return validated;
  }

  private static SendMessageResult eventResult(ClientEvent event) {
    List<ClientEvent> eventList = List.of(event);
    return SendMessageResult.builder()
        .text(extractResponseText(eventList))
        .task(extractResponseTask(eventList))
        .metadata(extractResponseMetadata(eventList))
        .taskState(extractResponseTaskState(eventList))
        .receivedMessages(ProtocolResponses.assemble(eventList))
        .build();
  }

  private static boolean isFailureState(String state) {
    if (state == null) return false;
    return state.contains("FAILED")
        || state.contains("CANCELED")
        || state.contains("CANCELLED")
        || state.contains("REJECTED");
  }

  static boolean isAcknowledgementState(String state) {
    return state != null && !state.isBlank();
  }

  private static Map<String, String> taskTrace(String agentName, String taskId) {
    Map<String, String> trace = new HashMap<>(WireLog.context());
    trace.put("agent", agentName);
    trace.put("remoteTaskId", taskId);
    trace.putIfAbsent("channel", "task");
    return trace;
  }

  private static String taskState(Task task) {
    return task != null && task.status() != null && task.status().state() != null
        ? task.status().state().name()
        : null;
  }

  /** Returns the loaded AgentCard for the given agent name. */
  public AgentCard getCard(String agentName) {
    return cardMap.get(agentName);
  }

  /** Time budget shared by sending and business interaction callbacks. */
  public long sendTimeoutSeconds() {
    return sendTimeoutSeconds;
  }

  public String getContextId() {
    return contextId;
  }

  /**
   * Send a message and collect the streaming events. The optional {@code eventSink} is invoked for
   * each intermediate event (status updates, artifact updates, messages); the workflow facade wires
   * it to its event callback, the one-shot sender passes {@code null}.
   */
  public CompletableFuture<SendMessageResult> send(
      AgentCard agentCard,
      String agentName,
      MessageContent content,
      String contextId,
      String taskId,
      Consumer<ClientEvent> eventSink) {
    return send(agentCard, agentName, content, contextId, taskId, eventSink, () -> true);
  }

  CompletableFuture<SendMessageResult> send(
      AgentCard agentCard,
      String agentName,
      MessageContent content,
      String contextId,
      String taskId,
      Consumer<ClientEvent> eventSink,
      java.util.function.BooleanSupplier active) {
    if (closed.get()) {
      return CompletableFuture.failedFuture(new IllegalStateException("A2A transport is closed"));
    }
    Map<String, String> trace = new HashMap<>(WireLog.context());
    trace.put("agent", agentName);
    trace.put("contextId", contextId);
    if (taskId != null) trace.put("remoteTaskId", taskId);
    trace.put(
        "channel",
        content.extensions().contains(A2ATExtension.AUTHORIZATION_T.uri())
            ? "authorization"
            : "task");
    return CompletableFuture.supplyAsync(
        () ->
            WireLog.call(
                trace,
                () -> {
                  try {
                    if (!active.getAsBoolean())
                      throw new java.util.concurrent.CancellationException("Late send ignored");
                    MessageSendParams params = buildMessageSendParams(content, contextId, taskId);
                    ClientCallContext callContext =
                        buildContentCallContext(agentCard, agentName, content);
                    if (!active.getAsBoolean())
                      throw new java.util.concurrent.CancellationException(
                          "Late authenticated send ignored");
                    String endpoint =
                        agentCard.supportedInterfaces().isEmpty()
                            ? "?"
                            : agentCard.supportedInterfaces().get(0).url();
                    ProtocolLogger.logRequest(
                        agentName, endpoint, params, callContext.getHeaders());
                    log.info("[Transport] Sending via A2A SDK to {}", agentName);
                    Iterable<ClientEvent> events =
                        a2aClientRuntime.sendMessage(
                            agentCard,
                            params,
                            callContext,
                            eventSink,
                            s -> log.info("[A2A] {}", s));
                    String responseText = extractResponseText(events);
                    String taskState = extractResponseTaskState(events);
                    Map<String, Object> respMetadata = extractResponseMetadata(events);
                    Task task = extractResponseTask(events);
                    log.info(
                        "[Transport] Response from {}: {} chars, state={}",
                        agentName,
                        responseText.length(),
                        taskState);
                    return SendMessageResult.builder()
                        .text(responseText)
                        .task(task)
                        .metadata(respMetadata)
                        .taskState(taskState)
                        .receivedMessages(ProtocolResponses.assemble(events))
                        .build();
                  } catch (Exception e) {
                    RemoteA2AErrorException remoteError = RemoteA2AErrorException.findIn(e);
                    if (remoteError != null) {
                      log.warn(
                          "[Transport] A2A_ERROR agent={}, contextId={}, httpStatus={}, status={}, reason={}, message={}",
                          agentName,
                          contextId,
                          remoteError.getHttpStatus(),
                          remoteError.getStatus(),
                          remoteError.getReason(),
                          remoteError.getMessage().replace("\r", "\\r").replace("\n", "\\n"));
                    } else {
                      log.error(
                          "[Transport] Failed to send message to {}: {}",
                          agentName,
                          e.getMessage(),
                          e);
                    }
                    if (e instanceof SecurityException securityException) {
                      throw securityException;
                    }
                    throw new RuntimeException("Agent call failed: " + e.getMessage(), e);
                  }
                }),
        asyncExecutor);
  }

  /** Release resources retained by a conversation-scoped runtime. */
  public void closeConversation(AgentCard agentCard, String contextId) {
    if (a2aClientRuntime instanceof ConversationScopedA2AJavaClientRuntime scopedRuntime) {
      try {
        scopedRuntime.closeConversation(agentCard, contextId);
      } catch (RuntimeException e) {
        log.warn(
            "[Transport] CONVERSATION_CLOSE_FAILED contextId={}, agent={}, message={}",
            contextId,
            agentCard != null ? agentCard.name() : "?",
            e.getMessage(),
            e);
      }
    }
  }

  /**
   * Long-lived SSE stream for Notification-T subscription. Opens a daemon thread that keeps the SSE
   * response stream open. The eventSink callback processes events in real-time (subscribed ack +
   * later recovery results). The returned future completes on the first event carrying a concrete
   * task state; an artifact alone is application data, not a protocol acknowledgement.
   */
  public NotificationSubscription openNotificationStream(
      AgentCard agentCard,
      String agentName,
      MessageContent content,
      String contextId,
      java.util.function.BiConsumer<NotificationSubscription, ReceivedMessage> eventSink) {
    if (closed.get()) {
      throw new IllegalStateException("A2A transport is closed");
    }
    AtomicReference<Thread> streamThreadRef = new AtomicReference<>();
    NotificationSubscription subscription =
        new NotificationSubscription(
            agentName,
            contextId,
            () -> {
              closeConversation(agentCard, contextId);
              Thread running = streamThreadRef.get();
              if (running != null) {
                running.interrupt();
              }
            });
    notificationSubscriptions.add(subscription);
    Map<String, String> trace = new HashMap<>(WireLog.context());
    trace.put("agent", agentName);
    trace.put("contextId", contextId);
    trace.put("channel", "notification");
    ProtocolResponses.Accumulator responses = new ProtocolResponses.Accumulator();
    Thread streamThread =
        new Thread(
            () ->
                WireLog.inContext(
                    trace,
                    () -> {
                      try {
                        MessageSendParams params = buildMessageSendParams(content, contextId, null);
                        ClientCallContext callContext =
                            buildContentCallContext(agentCard, agentName, content);
                        callContext
                            .getState()
                            .put(
                                A2AJavaClientRuntime.CHANNEL_STATE_KEY,
                                A2AJavaClientRuntime.NOTIFICATION_CHANNEL);
                        callContext
                            .getState()
                            .put(
                                A2AJavaClientRuntime.TRANSPORT_ACTIVITY_STATE_KEY,
                                (Runnable) subscription::recordActivity);
                        String endpoint =
                            agentCard.supportedInterfaces().isEmpty()
                                ? "?"
                                : agentCard.supportedInterfaces().get(0).url();
                        ProtocolLogger.logRequest(
                            agentName, endpoint, params, callContext.getHeaders());
                        log.info(
                            "[Transport] Opening Notification-T long-lived stream to {}",
                            agentName);
                        a2aClientRuntime.sendMessage(
                            agentCard,
                            params,
                            callContext,
                            event -> {
                              log.info(
                                  "[Transport] Notification-T event from {}: {}",
                                  agentName,
                                  event.getClass().getSimpleName());
                              subscription.recordEvent();
                              if (eventSink != null) {
                                for (ReceivedMessage received :
                                    responses.acceptIncrementally(event)) {
                                  eventSink.accept(subscription, received);
                                }
                              }
                              SendMessageResult acknowledgement = eventResult(event);
                              if (isFailureState(acknowledgement.getTaskState())) {
                                subscription.failAcknowledgement(
                                    new IllegalStateException(
                                        "Notification-T subscription rejected by '"
                                            + agentName
                                            + "': state="
                                            + acknowledgement.getTaskState()
                                            + ", response="
                                            + acknowledgement.getText()));
                              } else if (isAcknowledgementState(acknowledgement.getTaskState())) {
                                subscription.acknowledge(acknowledgement);
                              }
                            },
                            s -> log.info("[A2A] {}", s));
                        log.info("[Transport] Notification-T stream closed for {}", agentName);
                        if (!subscription.acknowledgement().isDone()) {
                          subscription.failAcknowledgement(
                              new IllegalStateException("Stream closed before acknowledgement"));
                        }
                        subscription.completeStream();
                      } catch (Exception e) {
                        boolean connectionClosed =
                            !subscription.isActive() || TransportFailures.isExpectedLocalClose(e);
                        if (connectionClosed) {
                          log.info("[Transport] Notification-T stream closed for {}", agentName);
                        } else {
                          log.error(
                              "[Transport] Notification-T stream error for {}: {}",
                              agentName,
                              e.getMessage(),
                              e);
                        }
                        if (!subscription.acknowledgement().isDone()) {
                          subscription.failAcknowledgement(e);
                        }
                        if (!connectionClosed && subscription.isActive()) {
                          subscription.failStream(e);
                        } else {
                          subscription.completeStream();
                        }
                      } finally {
                        closeConversation(agentCard, contextId);
                        notificationSubscriptions.remove(subscription);
                        subscription.markStreamTerminated();
                      }
                    }),
            "notif-t-" + agentName);
    streamThread.setDaemon(true);
    streamThreadRef.set(streamThread);
    streamThread.start();
    ScheduledFuture<?> acknowledgementTimeout =
        timeoutScheduler.schedule(
            () -> {
              if (subscription.isActive() && !subscription.acknowledgement().isDone()) {
                log.warn(
                    "[Transport] Notification-T subscription: no acknowledgement in {}s; closing unconfirmed stream",
                    notificationAckTimeoutSeconds);
                subscription.failAcknowledgement(
                    new java.util.concurrent.TimeoutException(
                        "No subscription acknowledgement received"));
                subscription.close();
              }
            },
            notificationAckTimeoutSeconds,
            TimeUnit.SECONDS);
    subscription
        .acknowledgement()
        .whenComplete((ignored, error) -> acknowledgementTimeout.cancel(false));
    subscription
        .completion()
        .whenComplete((ignored, error) -> acknowledgementTimeout.cancel(false));
    return subscription;
  }

  private MessageSendParams buildMessageSendParams(
      MessageContent content, String contextId, String taskId) {
    Message msg =
        Message.builder()
            .messageId(UUID.randomUUID().toString())
            .contextId(contextId)
            .taskId(taskId)
            .role(Message.Role.ROLE_USER)
            .parts(content.parts())
            .metadata(content.metadata())
            .extensions(new ArrayList<>(content.extensions()))
            .build();
    return MessageSendParams.builder().message(msg).build();
  }

  // ------------------------------------------------------------------
  // Lifecycle
  // ------------------------------------------------------------------

  private ClientCallContext buildContentCallContext(
      AgentCard card, String agentName, MessageContent content) {
    ClientCallContext context = buildClientCallContext(card, agentName, content.metadata());
    Map<String, String> headers = new HashMap<>(context.getHeaders());
    Set<String> extensions = new LinkedHashSet<>(content.extensions());
    String existingKey =
        headers.keySet().stream()
            .filter(key -> key.equalsIgnoreCase("A2A-Extensions"))
            .findFirst()
            .orElse(null);
    if (existingKey != null) {
      for (String value : headers.remove(existingKey).split(",")) {
        if (!value.isBlank()) extensions.add(value.trim());
      }
    }
    if (card.capabilities() != null && card.capabilities().extensions() != null) {
      for (var extension : card.capabilities().extensions()) {
        if (extension.required() && !extensions.contains(extension.uri())) {
          throw new IllegalArgumentException(
              "Required extension not activated: " + extension.uri());
        }
      }
    }
    if (!extensions.isEmpty()) headers.put("A2A-Extensions", String.join(",", extensions));
    return new ClientCallContext(new HashMap<>(), headers);
  }

  private ClientCallContext buildClientCallContext(
      AgentCard agentCard, String agentName, Map<String, Object> messageMetadata) {
    return clientCallContextFactory.create(agentCard, agentName, messageMetadata);
  }

  public CompletableFuture<SendMessageResult> getTask(
      AgentCard agentCard, String agentName, String taskId) {
    if (closed.get()) {
      return CompletableFuture.failedFuture(new IllegalStateException("A2A transport is closed"));
    }
    Map<String, String> trace = taskTrace(agentName, taskId);
    return CompletableFuture.supplyAsync(
        () ->
            WireLog.call(
                trace,
                () -> {
                  try {
                    ClientCallContext ctx = buildClientCallContext(agentCard, agentName, Map.of());
                    Task task = a2aClientRuntime.getTask(agentCard, taskId, ctx);
                    StringBuilder text = new StringBuilder();
                    extractTextFromTask(task, text);
                    Map<String, Object> metadata = new HashMap<>();
                    mergeTaskMetadata(task, metadata);
                    return SendMessageResult.builder()
                        .text(text.toString())
                        .task(task)
                        .metadata(metadata)
                        .taskState(taskState(task))
                        .receivedMessages(ProtocolResponses.assemble(List.of(new TaskEvent(task))))
                        .build();
                  } catch (Exception e) {
                    throw new RuntimeException(
                        "getTask failed for " + agentName + ": " + e.getMessage(), e);
                  }
                }),
        asyncExecutor);
  }

  public CompletableFuture<ListTasksResult> listTasks(
      AgentCard agentCard, String agentName, ListTasksParams params) {
    if (closed.get()) {
      return CompletableFuture.failedFuture(new IllegalStateException("A2A transport is closed"));
    }
    Map<String, String> trace = new HashMap<>(WireLog.context());
    trace.put("agent", agentName);
    trace.put("operation", "listTasks");
    return CompletableFuture.supplyAsync(
        () ->
            WireLog.call(
                trace,
                () -> {
                  try {
                    ClientCallContext ctx = buildClientCallContext(agentCard, agentName, Map.of());
                    return a2aClientRuntime.listTasks(agentCard, params, ctx);
                  } catch (Exception e) {
                    throw new RuntimeException(
                        "listTasks failed for " + agentName + ": " + e.getMessage(), e);
                  }
                }),
        asyncExecutor);
  }

  public CompletableFuture<SendMessageResult> cancelTask(
      AgentCard agentCard, String agentName, String taskId) {
    if (closed.get()) {
      return CompletableFuture.failedFuture(new IllegalStateException("A2A transport is closed"));
    }
    Map<String, String> trace = taskTrace(agentName, taskId);
    return CompletableFuture.supplyAsync(
        () ->
            WireLog.call(
                trace,
                () -> {
                  try {
                    ClientCallContext ctx = buildClientCallContext(agentCard, agentName, Map.of());
                    Task task = a2aClientRuntime.cancelTask(agentCard, taskId, ctx);
                    StringBuilder text = new StringBuilder();
                    extractTextFromTask(task, text);
                    Map<String, Object> metadata = new HashMap<>();
                    mergeTaskMetadata(task, metadata);
                    return SendMessageResult.builder()
                        .text(text.toString())
                        .task(task)
                        .metadata(metadata)
                        .taskState(taskState(task))
                        .receivedMessages(ProtocolResponses.assemble(List.of(new TaskEvent(task))))
                        .build();
                  } catch (Exception e) {
                    throw new RuntimeException(
                        "cancelTask failed for " + agentName + ": " + e.getMessage(), e);
                  }
                }),
        asyncExecutor);
  }

  public CompletableFuture<SendMessageResult> subscribeToTask(
      AgentCard agentCard, String agentName, String taskId, Consumer<ClientEvent> eventSink) {
    if (closed.get()) {
      return CompletableFuture.failedFuture(new IllegalStateException("A2A transport is closed"));
    }
    ClientCallContext ctx = buildClientCallContext(agentCard, agentName, Map.of());
    return WireLog.call(
        taskTrace(agentName, taskId),
        () ->
            a2aClientRuntime.subscribeToTask(
                agentCard,
                taskId,
                ctx,
                event -> {
                  if (eventSink != null) {
                    try {
                      eventSink.accept(event);
                    } catch (RuntimeException callbackError) {
                      log.warn(
                          "[Transport] Task subscription callback failed for {}: {}",
                          agentName,
                          callbackError.getMessage(),
                          callbackError);
                    }
                  }
                }));
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    log.info("[Transport] Closing");
    List<NotificationSubscription> subscriptions = new ArrayList<>(notificationSubscriptions);
    for (NotificationSubscription subscription : subscriptions) {
      subscription.close();
    }
    awaitNotificationShutdown(subscriptions);
    notificationSubscriptions.clear();
    timeoutScheduler.shutdownNow();
    asyncExecutor.shutdown();
    try {
      if (!asyncExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
        asyncExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      asyncExecutor.shutdownNow();
      Thread.currentThread().interrupt();
    }
    try {
      a2aClientRuntime.close();
    } catch (Exception e) {
      log.warn("[Transport] Runtime close failed: {}", e.getMessage(), e);
    }
  }

  private void awaitNotificationShutdown(List<NotificationSubscription> subscriptions) {
    if (subscriptions.isEmpty()) {
      return;
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    int terminated = 0;
    for (NotificationSubscription subscription : subscriptions) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        break;
      }
      try {
        subscription.streamTermination().get(remaining, TimeUnit.NANOSECONDS);
        terminated++;
      } catch (java.util.concurrent.TimeoutException e) {
        break;
      } catch (java.util.concurrent.ExecutionException e) {
        terminated++;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    if (terminated == subscriptions.size()) {
      log.info("[Transport] NOTIFICATION_SHUTDOWN_DONE subscriptions={}", terminated);
    } else {
      log.warn(
          "[Transport] NOTIFICATION_SHUTDOWN_TIMEOUT terminated={}, total={}, timeoutSeconds=2",
          terminated,
          subscriptions.size());
    }
  }
}
