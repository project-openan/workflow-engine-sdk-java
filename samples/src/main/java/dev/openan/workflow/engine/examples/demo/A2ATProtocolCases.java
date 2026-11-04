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
package dev.openan.workflow.engine.examples.demo;

import dev.openan.workflow.engine.client.A2ATExtension;
import dev.openan.workflow.engine.client.A2ATransport;
import dev.openan.workflow.engine.client.DefaultExtensionSender;
import dev.openan.workflow.engine.client.DefaultWorkflowEngineClient;
import dev.openan.workflow.engine.client.NotificationSubscription;
import dev.openan.workflow.engine.client.WorkflowEngineClientConfig;
import dev.openan.workflow.engine.examples.agents.SpnDomainAgentCity1Executor;
import dev.openan.workflow.engine.examples.server.OmcAgentLauncher;
import dev.openan.workflow.engine.examples.util.EnvResolver;
import dev.openan.workflow.engine.model.SendMessageResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reproduces the 11 protocol verification cases from the A2A-T spec extraction document. Run with
 * --case=7.1 to select individual cases, or --case=all for all.
 */
public class A2ATProtocolCases {
  private static final Logger log = LoggerFactory.getLogger(A2ATProtocolCases.class);

  private static final String NEGOTIATION_T_URI = A2ATExtension.NEGOTIATION_T.uri();

  private static final String AGENT_NAME = "SPN Domain Agent City1";
  private static final long STARTUP_WAIT = 3;

  private final OmcAgentLauncher omc = new OmcAgentLauncher();
  private final AtomicReference<String> lastTaskId = new AtomicReference<>();
  private final AtomicReference<String> notificationTaskId = new AtomicReference<>();
  private final AtomicBoolean recoveryPlanReceived = new AtomicBoolean();
  private final AtomicBoolean recoveryNotificationReceived = new AtomicBoolean();
  private final AtomicInteger negotiationRequests = new AtomicInteger();
  private final CountDownLatch reconnectEvent = new CountDownLatch(1);
  private A2ATransport taskTransport;
  private A2ATransport authorizationTransport;
  private A2ATransport notificationTransport;
  private DefaultWorkflowEngineClient client;
  private net.openan.a2at.sdk.client.A2ATClient contentClient;
  private DefaultWorkflowEngineClient notificationControlClient;
  private DefaultExtensionSender authorizationSender;
  private DefaultExtensionSender notificationSender;
  private NotificationSubscription notificationSubscription;
  private CompletableFuture<SendMessageResult> reconnectedSubscription;

  public static void main(String[] args) throws Exception {
    String caseId = "all";
    for (String arg : args) {
      if (arg.startsWith("--case=")) caseId = arg.substring("--case=".length());
    }
    new A2ATProtocolCases().run(caseId);
  }

  private static void requireExtensionResponse(
      SendMessageResult result, String extensionUri, String... expectedFragments) {
    Map<String, Object> metadata = result != null ? result.getMetadata() : null;
    Object value = metadata != null ? metadata.get(extensionUri) : null;
    String response = value instanceof String text ? text : "";
    for (String fragment : expectedFragments) {
      if (!response.contains(fragment)) {
        throw new IllegalStateException(
            "Protocol response metadata for "
                + extensionUri
                + " is missing '"
                + fragment
                + "': "
                + response);
      }
    }
  }

  public void run(String caseId) throws Exception {
    log.info("[ProtocolCases] START case={}", caseId);
    setup();
    try {
      if ("all".equalsIgnoreCase(caseId)) {
        case_0();
        case_7_1();
        case_7_2();
        case_7_3();
        case_7_4();
        case_7_5();
        case_7_6();
        case_7_7();
        case_7_8();
        case_7_9();
        case_7_11();
        case_7_10();
      } else {
        switch (caseId) {
          case "0" -> case_0();
          case "7.1" -> case_7_1();
          case "7.2" -> case_7_2();
          case "7.3" -> case_7_3();
          case "7.4" -> case_7_4();
          case "7.5" -> case_7_5();
          case "7.6" -> case_7_6();
          case "7.7" -> case_7_7();
          case "7.8" -> case_7_8();
          case "7.9" -> case_7_9();
          case "7.10" -> case_7_10();
          case "7.11" -> case_7_11();
          default -> log.warn("[ProtocolCases] Unknown case: {}", caseId);
        }
      }
    } finally {
      teardown();
    }
    log.info("[ProtocolCases] DONE");
  }

  private void setup() throws Exception {
    omc.startFromResource(
        "agentcard/spn_domain_agent_city1.json", new SpnDomainAgentCity1Executor());
    TimeUnit.SECONDS.sleep(STARTUP_WAIT);
    log.info("[ProtocolCases] OMC agent {} started on https://127.0.0.1:26335", AGENT_NAME);

    List<org.a2aproject.sdk.spec.AgentCard> cards =
        List.of(OmcAgentLauncher.cardFromResource("agentcard/spn_domain_agent_city1.json"));
    WorkflowEngineClientConfig config =
        WorkflowEngineClientConfig.builder()
            .sslVerify(false)
            .credentialsConfigPath(OmcAgentLauncher.resourcePath("spn_agent_credentials.json"))
            .build();
    // Task-T, Authorization-T and Notification-T deliberately use independent transport,
    // runtime and context instances. Notification-T owns the only long-lived stream.
    taskTransport = new A2ATransport(cards, null, config);
    authorizationTransport = new A2ATransport(cards, null, config);
    notificationTransport = new A2ATransport(cards, null, config);
    client = new DefaultWorkflowEngineClient(taskTransport);
    contentClient =
        dev.openan.workflow.engine.examples.util.A2ATInitialization.create(
            () ->
                new net.openan.a2at.sdk.client.A2ATClient(
                    java.nio.file.Path.of(EnvResolver.resolveEnvPath())));
    client.setControlPoint(
        new dev.openan.workflow.engine.control.DefaultControlPoint(
            new dev.openan.workflow.engine.examples.negotiation.NegotiationStrategy(
                EnvResolver.resolveEnvPath())));
    client.setEventCallback(
        new dev.openan.workflow.engine.control.EventCallback() {
          @Override
          public void onEvent(String type, Map<String, Object> data) {
            if (dev.openan.workflow.engine.control.EventType.NEGOTIATION_REQUEST.equals(type)) {
              negotiationRequests.incrementAndGet();
            }
          }
        });
    notificationControlClient = new DefaultWorkflowEngineClient(notificationTransport);
    authorizationSender = DefaultExtensionSender.nonOwning(authorizationTransport);
    notificationSender = DefaultExtensionSender.nonOwning(notificationTransport);
  }

  private void teardown() {
    if (notificationSubscription != null) notificationSubscription.close();
    if (client != null) client.close();
    if (notificationControlClient != null) notificationControlClient.close();
    if (taskTransport != null) taskTransport.close();
    if (authorizationTransport != null) authorizationTransport.close();
    if (notificationTransport != null) notificationTransport.close();
    omc.close();
  }

  /** Case 0: identify the standard template used by the Task-T cases. */
  private void case_0() {
    log.info(
        "[Case 0] Task-T template: {}",
        net.openan.a2at.sdk.core.model.StandardTemplates.PRIVATE_LINE_COMPLAINT.uri());
  }

  private void case_7_1() throws Exception {
    log.info("[Case 7.1] Create diagnosis task (Task-T, fromData track)");
    SendMessageResult result =
        sendDiagnosis(
                dev.openan.workflow.engine.client.A2atMessages.from(
                    contentClient.generateTaskPromptFromDataWithSchema(
                        SpnCasePrompts.privateLineComplaintData(),
                        SpnCasePrompts.privateLineComplaintSchema(),
                        net.openan.a2at.sdk.core.model.StandardTemplates.PRIVATE_LINE_COMPLAINT
                            .uri()),
                    List.of(new org.a2aproject.sdk.spec.TextPart(SpnCasePrompts.TASK_TEXT))))
            .join();
    log.info(
        "[Case 7.1] state={}, textLen={}",
        result.getTaskState(),
        result.getText() != null ? result.getText().length() : 0);
    if (result.getTask() != null) {
      lastTaskId.set(result.getTask().id());
      log.info("[Case 7.1] taskId={}", result.getTask().id());
    }
  }

  private CompletableFuture<SendMessageResult> sendDiagnosis(
      dev.openan.workflow.engine.model.MessageContent content) {
    // Fault-injection cases omit/mutate the transmitted field; the host keeps authoritative input
    // to demonstrate a legitimate correction, never a hardcoded engine negotiation fallback.
    var request =
        dev.openan.workflow.engine.model.TaskRequest.builder()
            .agentName(AGENT_NAME)
            .executionId(java.util.UUID.randomUUID().toString())
            .taskId(java.util.UUID.randomUUID().toString())
            .stepName("diagnosis_city1")
            .instruction(SpnCasePrompts.TASK_TEXT)
            .input(
                dev.openan.workflow.engine.model.BusinessInput.data(
                    SpnCasePrompts.privateLineComplaintData()))
            .build();
    var strategy =
        new dev.openan.workflow.engine.examples.negotiation.NegotiationStrategy(
            EnvResolver.resolveEnvPath());
    return client.sendTask(
        AGENT_NAME,
        content,
        negotiation ->
            strategy.resolve(
                new dev.openan.workflow.engine.model.NegotiationRequest(
                    request,
                    negotiation.originalSubmission(),
                    negotiation.received(),
                    negotiation.previousExchanges(),
                    negotiation.remainingWait())));
  }

  private void case_7_2() throws Exception {
    String taskId = lastTaskId.get();
    if (taskId == null) {
      log.info("[Case 7.2] No task, running 7.1 first");
      case_7_1();
      taskId = lastTaskId.get();
    }
    log.info("[Case 7.2] Query task: {}", taskId);
    SendMessageResult result = client.getTask(AGENT_NAME, taskId).join();
    log.info(
        "[Case 7.2] state={}, artifacts={}",
        result.getTaskState(),
        result.getTask() != null && result.getTask().artifacts() != null
            ? result.getTask().artifacts().size()
            : 0);
  }

  private void case_7_3() {
    log.info("[Case 7.3] Negotiation - missing required parameter");
    int before = negotiationRequests.get();
    Map<String, Object> metadata =
        SpnCasePrompts.taskTMetadata(SpnCasePrompts.privateLineComplaintPromptBlankObject());
    SendMessageResult result =
        sendDiagnosis(
                new dev.openan.workflow.engine.model.MessageContent(
                    List.of(
                        new org.a2aproject.sdk.spec.TextPart(SpnCasePrompts.TASK_TEXT + "(参数缺失)")),
                    metadata,
                    java.util.Set.of(A2ATExtension.TASK_T.uri()))
                    .withExtension(A2ATExtension.NEGOTIATION_T.uri()))
            .join();
    requireCompletedNegotiation("7.3", result, before);
    log.info(
        "[Case 7.3] state={}, metaKeys={}",
        result.getTaskState(),
        result.getMetadata() != null ? result.getMetadata().keySet() : "none");
  }

  private void case_7_4() {
    log.info("[Case 7.4] Negotiation - semantic error (Task-T fromData + Negotiation-T)");
    int before = negotiationRequests.get();
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.putAll(
        contentClient
            .generateTaskPromptFromDataWithSchema(
                SpnCasePrompts.privateLineComplaintDataUnknownPort(),
                SpnCasePrompts.privateLineComplaintSchema(),
                net.openan.a2at.sdk.core.model.StandardTemplates.PRIVATE_LINE_COMPLAINT.uri())
            .buildMetadataContent());
    SendMessageResult result =
        sendDiagnosis(
                new dev.openan.workflow.engine.model.MessageContent(
                    List.of(
                        new org.a2aproject.sdk.spec.TextPart(SpnCasePrompts.TASK_TEXT + "(语义错误)")),
                    metadata,
                    java.util.Set.of(A2ATExtension.TASK_T.uri()))
                    .withExtension(A2ATExtension.NEGOTIATION_T.uri()))
            .join();
    requireCompletedNegotiation("7.4", result, before);
    log.info(
        "[Case 7.4] state={}, metaKeys={}",
        result.getTaskState(),
        result.getMetadata() != null ? result.getMetadata().keySet() : "none");
  }

  private void requireCompletedNegotiation(
      String caseId, SendMessageResult result, int requestCountBefore) {
    if (result == null
        || result.getTaskState() == null
        || !result.getTaskState().endsWith("COMPLETED")
        || negotiationRequests.get() <= requestCountBefore) {
      throw new IllegalStateException(
          "Case "
              + caseId
              + " did not complete a Negotiation-T round: state="
              + (result != null ? result.getTaskState() : "null")
              + ", negotiationRequests="
              + negotiationRequests.get());
    }
  }

  private void case_7_5() {
    log.info("[Case 7.5] Add authorization (Authorization-T)");
    SendMessageResult result =
        authorizationSender
            .sendAuthorization(
                AGENT_NAME,
                dev.openan.workflow.engine.client.A2atMessages.from(
                    contentClient.generateAuthPromptFromDataWithSchema(
                        SpnCasePrompts.addAuthorizationData(),
                        SpnCasePrompts.authorizationSchema(),
                        net.openan.a2at.sdk.core.model.StandardTemplates
                            .AUTHORIZATION_POLICY_MANAGEMENT
                            .uri()),
                    List.of(new org.a2aproject.sdk.spec.TextPart("新增动网操作授权"))))
            .join();
    requireExtensionResponse(
        result,
        A2ATExtension.AUTHORIZATION_T.uri(),
        "授权操作执行结果：成功",
        "7d8c7b00-3c8c-4f8e-9b1e-9b17b6a3e5c3");
    log.info("[Case 7.5] state={}", result.getTaskState());
  }

  private void case_7_6() {
    log.info("[Case 7.6] Delete authorization (Authorization-T)");
    SendMessageResult result =
        authorizationSender
            .sendAuthorization(
                AGENT_NAME,
                dev.openan.workflow.engine.client.A2atMessages.from(
                    contentClient.generateAuthPromptFromDataWithSchema(
                        Map.of(
                            "授权策略的操作类型",
                            "删除授权策略",
                            "动网操作的授权策略列表",
                            "1. 策略标识是7d8c7b00-3c8c-4f8e-9b1e-9b17b6a3e5c3"),
                        SpnCasePrompts.authorizationSchema(),
                        net.openan.a2at.sdk.core.model.StandardTemplates
                            .AUTHORIZATION_POLICY_MANAGEMENT
                            .uri()),
                    List.of(new org.a2aproject.sdk.spec.TextPart("删除动网操作的授权"))))
            .join();
    requireExtensionResponse(result, A2ATExtension.AUTHORIZATION_T.uri(), "授权操作执行结果：成功");
    log.info("[Case 7.6] state={}", result.getTaskState());
  }

  private void case_7_7() {
    log.info("[Case 7.7] Query authorization (Authorization-T)");
    SendMessageResult result =
        authorizationSender
            .sendAuthorization(
                AGENT_NAME,
                dev.openan.workflow.engine.client.A2atMessages.from(
                    contentClient.generateAuthPromptFromDataWithSchema(
                        Map.of("授权策略的操作类型", "查询授权策略", "动网操作的授权策略列表", ""),
                        SpnCasePrompts.authorizationSchema(),
                        net.openan.a2at.sdk.core.model.StandardTemplates
                            .AUTHORIZATION_POLICY_MANAGEMENT
                            .uri()),
                    List.of(new org.a2aproject.sdk.spec.TextPart("查询业务抢通操作授权信息"))))
            .join();
    requireExtensionResponse(result, A2ATExtension.AUTHORIZATION_T.uri(), "授权操作执行结果：成功", "无匹配授权策略");
    log.info("[Case 7.7] state={}", result.getTaskState());
  }

  private void case_7_8() {
    log.info("[Case 7.8] Subscribe notification (Notification-T)");
    recoveryPlanReceived.set(false);
    recoveryNotificationReceived.set(false);
    notificationSubscription =
        notificationSender.openNotification(
            AGENT_NAME,
            dev.openan.workflow.engine.client.A2atMessages.from(
                contentClient.generateNotificationPromptFromDataWithSchema(
                    SpnCasePrompts.subscribeServiceRecoveryData(),
                    SpnCasePrompts.serviceRecoverySchema(),
                    net.openan.a2at.sdk.core.model.StandardTemplates.SERVICE_RECOVERY.uri()),
                List.of(new org.a2aproject.sdk.spec.TextPart("订阅业务抢通事件"))),
            (handle, received) -> {
              if (received.artifacts().stream()
                  .anyMatch(artifact -> "recovery-plan".equals(artifact.name())))
                recoveryPlanReceived.set(true);
              if (received.artifacts().stream()
                  .anyMatch(artifact -> "recovery-result".equals(artifact.name())))
                recoveryNotificationReceived.set(true);
            });
    SendMessageResult result = notificationSubscription.acknowledgement().join();
    requireExtensionResponse(result, A2ATExtension.NOTIFICATION_T.uri(), "订阅结果：成功");
    if (result.getTask() != null) {
      notificationTaskId.set(result.getTask().id());
    }
    log.info("[Case 7.8] ack state={}", result.getTaskState());
  }

  private void case_7_9() throws Exception {
    log.info("[Case 7.9] Notification report (Notification-T)");
    if (notificationTaskId.get() == null) {
      log.info("[Case 7.9] No notification task, running 7.8 first");
      case_7_8();
    }
    // Case 7.6 deliberately removed the policy. Re-add it before diagnosis so the documented
    // plan -> whitelist match -> automatic execution -> result lifecycle can complete.
    case_7_5();
    case_7_1();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while ((!recoveryPlanReceived.get() || !recoveryNotificationReceived.get())
        && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(100);
    }
    if (!recoveryPlanReceived.get() || !recoveryNotificationReceived.get()) {
      throw new IllegalStateException(
          "Case 7.9 expected both recovery-plan and recovery-result events; plan="
              + recoveryPlanReceived.get()
              + ", result="
              + recoveryNotificationReceived.get());
    }
    log.info("[Case 7.9] Recovery plan and automatic-execution result received");
  }

  private void case_7_10() throws Exception {
    if (notificationTaskId.get() == null) {
      log.info("[Case 7.10] No notification task, running 7.8 first");
      case_7_8();
    }
    String taskId = notificationTaskId.getAndSet(null);
    log.info("[Case 7.10] Cancel subscription: {}", taskId);
    SendMessageResult result = notificationControlClient.cancelTask(AGENT_NAME, taskId).join();
    if (reconnectedSubscription != null) {
      SendMessageResult terminal = reconnectedSubscription.get(5, TimeUnit.SECONDS);
      if (terminal.getTaskState() == null || !terminal.getTaskState().contains("CANCELED")) {
        throw new IllegalStateException(
            "Case 7.10 expected reconnected stream to end as CANCELED, state="
                + terminal.getTaskState());
      }
      reconnectedSubscription = null;
    }
    if (notificationSubscription != null) {
      notificationSubscription.close();
      notificationSubscription = null;
    }
    log.info("[Case 7.10] state={}", result.getTaskState());
  }

  private void case_7_11() throws Exception {
    String taskId = notificationTaskId.get();
    if (taskId == null) {
      log.info("[Case 7.11] No notification task, running 7.8 first");
      case_7_8();
      taskId = notificationTaskId.get();
    }
    if (notificationSubscription != null) {
      notificationSubscription.close();
      notificationSubscription = null;
    }
    log.info("[Case 7.11] Reconnect: {}", taskId);
    reconnectedSubscription =
        notificationControlClient.subscribeToTask(
            AGENT_NAME,
            taskId,
            data -> {
              reconnectEvent.countDown();
              log.info("[Case 7.11] callback: keys={}", data.keySet());
            });
    if (!reconnectEvent.await(5, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Case 7.11 reconnect produced no protocol event");
    }
    if (reconnectedSubscription.isDone()) {
      throw new IllegalStateException(
          "Case 7.11 reconnect stream ended before explicit cancellation");
    }
    log.info("[Case 7.11] reconnected stream is active");
  }
}
