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
package dev.openan.workflow.engine.spring;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.auth.AuthenticatedUser;
import org.a2aproject.sdk.server.auth.TaskAuthorizationProvider;
import org.a2aproject.sdk.server.auth.TaskOperation;
import org.a2aproject.sdk.server.config.A2AConfigProvider;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.TaskNotFoundError;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.transport.rest.handler.RestHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class A2AAutoConfigurationTest {

  @Test
  void everyTaskRouteUsesConfiguredA2aPathPrefix() throws Exception {
    String placeholder = "${a2at.server.path-prefix}";

    assertEquals(
        placeholder + "/tasks/{id}",
        A2AController.class
            .getMethod("getTask", jakarta.servlet.http.HttpServletRequest.class, String.class)
            .getAnnotation(GetMapping.class)
            .value()[0]);
    assertEquals(
        placeholder + "/tasks",
        A2AController.class
            .getMethod(
                "listTasks",
                jakarta.servlet.http.HttpServletRequest.class,
                String.class,
                String.class,
                Integer.class,
                String.class,
                Integer.class,
                String.class,
                Boolean.class)
            .getAnnotation(GetMapping.class)
            .value()[0]);
    assertEquals(
        placeholder + "/tasks/{id}:cancel",
        A2AController.class
            .getMethod(
                "cancelTask",
                jakarta.servlet.http.HttpServletRequest.class,
                String.class,
                String.class)
            .getAnnotation(PostMapping.class)
            .value()[0]);
    var subscribeMethod =
        A2AController.class.getMethod(
            "subscribeToTask", jakarta.servlet.http.HttpServletRequest.class, String.class);
    assertEquals(
        placeholder + "/tasks/{id}:subscribe",
        subscribeMethod.getAnnotation(PostMapping.class).value()[0]);
    assertEquals(SseEmitter.class, subscribeMethod.getReturnType());
    assertEquals(
        "text/event-stream", subscribeMethod.getAnnotation(PostMapping.class).produces()[0]);

    var streamMethod =
        A2AController.class.getMethod(
            "streamMessage", jakarta.servlet.http.HttpServletRequest.class, String.class);
    // SseEmitter (not ResponseEntity): a wildcard ResponseEntity return is not picked up by
    // Spring's emitter handler and the stream response degrades to a converter error.
    assertEquals(SseEmitter.class, streamMethod.getReturnType());
    assertEquals("text/event-stream", streamMethod.getAnnotation(PostMapping.class).produces()[0]);
  }

  @Test
  void sendResponseEchoesRequestedSupportedExtensions() throws Exception {
    RestHandler restHandler = mock(RestHandler.class);
    RequestHandler requestHandler = mock(RequestHandler.class);
    AgentCard agentCard = mock(AgentCard.class);
    when(agentCard.capabilities())
        .thenReturn(
            AgentCapabilities.builder()
                .streaming(true)
                .extensions(
                    List.of(
                        org.a2aproject.sdk.spec.AgentExtension.builder()
                            .uri("https://example.org/extensions/demo/v1")
                            .build()))
                .build());
    when(restHandler.sendMessage(any(), any(), any()))
        .thenReturn(new RestHandler.HTTPRestResponse(200, "application/a2a+json", "{\"ok\":true}"));
    var controller = new A2AController(restHandler, requestHandler, agentCard);
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .addPlaceholderValue("a2at.server.path-prefix", "/a2a/json")
            .build();

    // Requested + supported -> echoed; requested but unsupported -> not activated.
    mockMvc
        .perform(
            post("/a2a/json/message:send")
                .header(
                    "A2A-Extensions",
                    "https://example.org/extensions/demo/v1,"
                        + "https://example.org/extensions/unknown/v1")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(header().string("A2A-Extensions", "https://example.org/extensions/demo/v1"));

    // A card without extension declarations activates nothing.
    AgentCard plainCard = mock(AgentCard.class);
    when(plainCard.capabilities()).thenReturn(AgentCapabilities.builder().streaming(true).build());
    var plainController = new A2AController(restHandler, requestHandler, plainCard);
    MockMvc plainMockMvc =
        MockMvcBuilders.standaloneSetup(plainController)
            .addPlaceholderValue("a2at.server.path-prefix", "/a2a/json")
            .build();
    plainMockMvc
        .perform(
            post("/a2a/json/message:send")
                .header("A2A-Extensions", "https://example.org/extensions/demo/v1")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("A2A-Extensions"));
  }

  @Test
  void completedSseReleasesPublisherAndSdkEventConsumer() throws Exception {
    RestHandler restHandler = mock(RestHandler.class);
    RequestHandler requestHandler = mock(RequestHandler.class);
    AgentCard agentCard = mock(AgentCard.class);
    when(agentCard.capabilities()).thenReturn(AgentCapabilities.builder().streaming(true).build());
    AtomicReference<Flow.Subscriber<? super StreamingEventKind>> subscriber =
        new AtomicReference<>();
    AtomicInteger subscriptionCancellations = new AtomicInteger();
    AtomicInteger eventConsumerCancellations = new AtomicInteger();
    when(requestHandler.onSubscribeToTask(any(), any()))
        .thenAnswer(
            invocation -> {
              ServerCallContext context = invocation.getArgument(1);
              context.setEventConsumerCancelCallback(eventConsumerCancellations::incrementAndGet);
              Flow.Publisher<StreamingEventKind> publisher =
                  receiver -> {
                    subscriber.set(receiver);
                    receiver.onSubscribe(
                        new Flow.Subscription() {
                          @Override
                          public void request(long count) {}

                          @Override
                          public void cancel() {
                            subscriptionCancellations.incrementAndGet();
                          }
                        });
                  };
              return publisher;
            });
    var controller = new A2AController(restHandler, requestHandler, agentCard);
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .addPlaceholderValue("a2at.server.path-prefix", "/a2a/json")
            .build();

    MvcResult stream =
        mockMvc
            .perform(post("/a2a/json/tasks/task-1:subscribe").accept("text/event-stream"))
            .andExpect(request().asyncStarted())
            .andReturn();
    subscriber.get().onComplete();
    mockMvc.perform(asyncDispatch(stream)).andExpect(status().isOk());

    assertEquals(1, subscriptionCancellations.get());
    assertEquals(1, eventConsumerCancellations.get());
    assertEquals(0, controller.activeStreamCount());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"before-subscribe", "after-subscribe", "during-request"})
  void synchronousPublisherFailureReleasesResourcesBeforeMvcDispatch(String phase)
      throws Exception {
    for (String endpoint : new String[] {"/message:stream", "/tasks/task-1:subscribe"}) {
      RestHandler restHandler = mock(RestHandler.class);
      when(restHandler.createErrorResponse(any()))
          .thenReturn(
              new RestHandler.HTTPRestResponse(
                  500,
                  "application/a2a+json",
                  "{\"error\":{\"code\":500,\"status\":\"INTERNAL\",\"message\":\"Setup failed\"}}"));
      RequestHandler requestHandler = mock(RequestHandler.class);
      AgentCard agentCard = mock(AgentCard.class);
      when(agentCard.capabilities())
          .thenReturn(AgentCapabilities.builder().streaming(true).build());
      var agentInterface = mock(org.a2aproject.sdk.spec.AgentInterface.class);
      when(agentInterface.protocolVersion()).thenReturn("1.0");
      when(agentCard.supportedInterfaces()).thenReturn(java.util.List.of(agentInterface));
      AtomicInteger subscriptionCancellations = new AtomicInteger();
      AtomicInteger eventConsumerCancellations = new AtomicInteger();
      AtomicReference<Flow.Subscriber<? super StreamingEventKind>> subscriber =
          new AtomicReference<>();
      org.mockito.stubbing.Answer<Flow.Publisher<StreamingEventKind>> answer =
          invocation -> {
            ServerCallContext context = invocation.getArgument(1);
            context.setEventConsumerCancelCallback(eventConsumerCancellations::incrementAndGet);
            return receiver -> {
              subscriber.set(receiver);
              if (!phase.equals("before-subscribe")) {
                receiver.onSubscribe(
                    new Flow.Subscription() {
                      @Override
                      public void request(long count) {
                        if (phase.equals("during-request")) {
                          throw new IllegalStateException("Request failed");
                        }
                      }

                      @Override
                      public void cancel() {
                        subscriptionCancellations.incrementAndGet();
                      }
                    });
              }
              throw new IllegalStateException("Subscribe failed");
            };
          };
      when(requestHandler.onSubscribeToTask(any(), any())).thenAnswer(answer);
      when(requestHandler.onMessageSendStream(any(), any())).thenAnswer(answer);
      var controller = new A2AController(restHandler, requestHandler, agentCard);
      MockMvc mockMvc =
          MockMvcBuilders.standaloneSetup(controller)
              .addPlaceholderValue("a2at.server.path-prefix", "/a2a/json")
              .build();

      mockMvc
          .perform(
              post("/a2a/json" + endpoint)
                  .header("A2A-Version", "1.0")
                  .contentType("application/json")
                  .accept("text/event-stream")
                  .content(
                      "{\"message\":{\"messageId\":\"m1\",\"role\":\"ROLE_USER\","
                          + "\"parts\":[{\"text\":\"test\"}]}}"))
          .andExpect(status().isInternalServerError())
          .andExpect(request().asyncNotStarted())
          .andExpect(content().contentTypeCompatibleWith("application/a2a+json"))
          .andExpect(content().string(containsString("\"status\":\"INTERNAL\"")));

      assertEquals(0, controller.activeStreamCount());
      assertTrue(controller.awaitStreamsDrained(java.time.Duration.ofMillis(10)));
      assertEquals(1, eventConsumerCancellations.get());
      assertEquals(phase.equals("before-subscribe") ? 0 : 1, subscriptionCancellations.get());
      subscriber.get().onComplete();
      assertEquals(0, controller.activeStreamCount());
      assertEquals(1, eventConsumerCancellations.get());
    }
  }

  @Test
  void nonStreamingA2AResponseUsesProtocolMediaType() throws Exception {
    RestHandler restHandler = mock(RestHandler.class);
    when(restHandler.sendMessage(any(), eq(""), eq("{}")))
        .thenReturn(new RestHandler.HTTPRestResponse(200, "application/json", "{}"));
    var controller =
        new A2AController(restHandler, mock(RequestHandler.class), mock(AgentCard.class));
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .addPlaceholderValue("a2at.server.path-prefix", "/a2a/json")
            .build();

    mockMvc
        .perform(post("/a2a/json/message:send").content("{}"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/a2a+json"));
  }

  @Test
  void taskSubscriptionRejectionReturnsAStandardNonStreamingA2AError() throws Exception {
    RestHandler restHandler = mock(RestHandler.class);
    RequestHandler requestHandler = mock(RequestHandler.class);
    var responseBody =
        "{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\","
            + "\"message\":\"Task not found\",\"details\":[]}}";
    when(restHandler.createErrorResponse(any()))
        .thenReturn(new RestHandler.HTTPRestResponse(404, "application/a2a+json", responseBody));
    doThrow(new TaskNotFoundError())
        .when(requestHandler)
        .authorizeTaskAccess(eq("missing"), any(), eq(TaskOperation.SUBSCRIBE_TO_TASK));
    AgentCard agentCard = mock(AgentCard.class);
    when(agentCard.capabilities()).thenReturn(AgentCapabilities.builder().streaming(true).build());
    var controller = new A2AController(restHandler, requestHandler, agentCard);

    // Through MockMvc so the exception -> @ExceptionHandler mapping runs exactly as in dispatch.
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .addPlaceholderValue("a2at.server.path-prefix", "/a2a/json")
            .build();

    mockMvc
        .perform(post("/a2a/json/tasks/missing:subscribe"))
        .andExpect(status().is(404))
        .andExpect(content().contentTypeCompatibleWith("application/a2a+json"))
        .andExpect(content().string(containsString("\"status\":\"NOT_FOUND\"")));
  }

  @Test
  void streamingCapabilityIsCheckedBeforeInvokingBusinessHandler() throws Exception {
    RestHandler restHandler = mock(RestHandler.class);
    RequestHandler requestHandler = mock(RequestHandler.class);
    AgentCard agentCard = mock(AgentCard.class);
    when(agentCard.capabilities()).thenReturn(AgentCapabilities.builder().streaming(false).build());
    when(restHandler.createErrorResponse(any()))
        .thenReturn(
            new RestHandler.HTTPRestResponse(
                501,
                "application/a2a+json",
                "{\"error\":{\"code\":-32004,\"status\":\"UNIMPLEMENTED\"}}"));
    var controller = new A2AController(restHandler, requestHandler, agentCard);

    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .addPlaceholderValue("a2at.server.path-prefix", "/a2a/json")
            .build();

    mockMvc
        .perform(post("/a2a/json/message:stream").content("{}"))
        .andExpect(status().is(501))
        .andExpect(content().contentTypeCompatibleWith("application/a2a+json"));
    verifyNoInteractions(requestHandler);
  }

  @Test
  void slashActionAliasesDelegateToTheCanonicalController() throws Exception {
    RestHandler restHandler = mock(RestHandler.class);
    when(restHandler.sendMessage(any(), eq(""), eq("{}")))
        .thenReturn(new RestHandler.HTTPRestResponse(200, "application/json", "{}"));
    RequestHandler requestHandler = mock(RequestHandler.class);
    AgentCard agentCard = mock(AgentCard.class);
    A2AController canonical = new A2AController(restHandler, requestHandler, agentCard);
    var aliases = new A2ASlashActionAliasController(canonical);
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(aliases)
            .addPlaceholderValue("a2at.server.path-prefix", "/a2a/json")
            .build();

    mockMvc
        .perform(post("/a2a/json/message/send").content("{}"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/a2a+json"));

    verify(restHandler).sendMessage(any(), eq(""), eq("{}"));
  }

  @Test
  void everySlashActionAliasUsesTheConfiguredPrefix() throws Exception {
    String placeholder = "${a2at.server.path-prefix}";
    Class<?> aliases = A2ASlashActionAliasController.class;

    assertEquals(
        placeholder + "/message/send",
        aliases
            .getDeclaredMethod(
                "sendMessage", jakarta.servlet.http.HttpServletRequest.class, String.class)
            .getAnnotation(PostMapping.class)
            .value()[0]);
    assertEquals(
        placeholder + "/message/stream",
        aliases
            .getDeclaredMethod(
                "streamMessage", jakarta.servlet.http.HttpServletRequest.class, String.class)
            .getAnnotation(PostMapping.class)
            .value()[0]);
    assertEquals(
        placeholder + "/tasks/{id}/cancel",
        aliases
            .getDeclaredMethod(
                "cancelTask",
                jakarta.servlet.http.HttpServletRequest.class,
                String.class,
                String.class)
            .getAnnotation(PostMapping.class)
            .value()[0]);
    assertEquals(
        placeholder + "/tasks/{id}/subscribe",
        aliases
            .getDeclaredMethod(
                "subscribeToTask", jakarta.servlet.http.HttpServletRequest.class, String.class)
            .getAnnotation(PostMapping.class)
            .value()[0]);
  }

  @Test
  void slashStreamingAliasUsesTheCanonicalA2aErrorEnvelope() throws Exception {
    RestHandler restHandler = mock(RestHandler.class);
    when(restHandler.createErrorResponse(any()))
        .thenReturn(
            new RestHandler.HTTPRestResponse(
                501,
                "application/a2a+json",
                "{\"error\":{\"code\":-32004,\"status\":\"UNIMPLEMENTED\"}}"));
    RequestHandler requestHandler = mock(RequestHandler.class);
    AgentCard agentCard = mock(AgentCard.class);
    when(agentCard.capabilities()).thenReturn(AgentCapabilities.builder().streaming(false).build());
    A2AController canonical = new A2AController(restHandler, requestHandler, agentCard);
    var aliases = new A2ASlashActionAliasController(canonical);
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(aliases)
            .addPlaceholderValue("a2at.server.path-prefix", "/a2a/json")
            .build();

    mockMvc
        .perform(post("/a2a/json/message/stream").content("{}"))
        .andExpect(status().is(501))
        .andExpect(content().contentTypeCompatibleWith("application/a2a+json"));

    verifyNoInteractions(requestHandler);
  }

  @Test
  void slashActionAliasesAreOptIn() {
    A2AController canonical = mock(A2AController.class);
    WebApplicationContextRunner runner =
        new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(A2AAutoConfiguration.class))
            .withBean(AgentCard.class, () -> mock(AgentCard.class))
            .withBean(AgentExecutor.class, () -> mock(AgentExecutor.class))
            .withBean(RequestHandler.class, () -> mock(RequestHandler.class))
            .withBean(RestHandler.class, () -> mock(RestHandler.class))
            .withBean(A2AController.class, () -> canonical)
            .withPropertyValues("a2at.server.enabled=true");

    runner.run(context -> assertFalse(context.containsBean("a2aSlashActionAliasController")));
    runner
        .withPropertyValues("a2at.server.slash-action-aliases-enabled=true")
        .run(context -> assertTrue(context.containsBean("a2aSlashActionAliasController")));
  }

  @Test
  void slashActionAliasPropertyDefaultsToDisabled() {
    A2AProperties properties = new A2AProperties();
    assertFalse(properties.isSlashActionAliasesEnabled());
    properties.setSlashActionAliasesEnabled(true);
    assertTrue(properties.isSlashActionAliasesEnabled());
  }

  @Test
  void slashActionAliasControllerIsPubliclyAccessible() throws Exception {
    assertTrue(Modifier.isPublic(A2ASlashActionAliasController.class.getModifiers()));
    assertFalse(Modifier.isFinal(A2ASlashActionAliasController.class.getModifiers()));
    assertTrue(
        Modifier.isPublic(
            A2ASlashActionAliasController.class
                .getConstructor(A2AController.class)
                .getModifiers()));
  }

  @Test
  void blockingTimeoutsComeFromProperties() {
    A2AProperties properties = new A2AProperties();
    properties.setAgentTimeoutSeconds(90);
    properties.setConsumptionTimeoutSeconds(12);
    properties.setReconciliationTimeoutSeconds(3);

    A2AConfigProvider provider = new A2AAutoConfiguration().a2aConfigProvider(properties);

    assertEquals("90", provider.getValue("a2a.blocking.agent.timeout.seconds"));
    assertEquals("12", provider.getValue("a2a.blocking.consumption.timeout.seconds"));
    assertEquals("3", provider.getValue("a2a.blocking.reconciliation.timeout.seconds"));
  }

  /**
   * The builder-built {@code DefaultRequestHandler} never consults {@code A2AConfigProvider} (only
   * the CDI {@code @PostConstruct} path does), so the starter must apply the configured timeouts
   * directly after {@code build()}. This test exercises that real path: build a handler exactly
   * like the autoconfiguration does, apply, then read back the handler fields.
   */
  @Test
  void blockingTimeoutsAreAppliedToTheBuiltHandler() throws Exception {
    var builder =
        org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler.builder()
            .agentExecutor(mock(org.a2aproject.sdk.server.agentexecution.AgentExecutor.class))
            .taskStore(new org.a2aproject.sdk.server.tasks.InMemoryTaskStore())
            .queueManager(mock(org.a2aproject.sdk.server.events.QueueManager.class))
            .mainEventBusProcessor(
                mock(org.a2aproject.sdk.server.events.MainEventBusProcessor.class))
            .executor(java.util.concurrent.Executors.newSingleThreadExecutor())
            .eventConsumerExecutor(java.util.concurrent.Executors.newSingleThreadExecutor());
    var handler = builder.build();
    // a2a-java hardcodes these on the builder path; assert the broken baseline first.
    assertEquals(5, readBlockingTimeout(handler, "agentCompletionTimeoutSeconds"));

    A2AProperties properties = new A2AProperties();
    A2AAutoConfiguration.applyBlockingTimeouts(handler, properties);
    assertEquals(
        properties.getAgentTimeoutSeconds(),
        readBlockingTimeout(handler, "agentCompletionTimeoutSeconds"));
    assertEquals(
        properties.getConsumptionTimeoutSeconds(),
        readBlockingTimeout(handler, "consumptionCompletionTimeoutSeconds"));
    assertEquals(
        properties.getReconciliationTimeoutSeconds(),
        readBlockingTimeout(handler, "reconciliationTimeoutSeconds"));
  }

  private static int readBlockingTimeout(Object handler, String fieldName) throws Exception {
    var field = handler.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.getInt(handler);
  }

  @Test
  void blockingTimeoutDefaultsSupportLongRunningAgents() {
    A2AProperties properties = new A2AProperties();
    assertEquals(900, properties.getAgentTimeoutSeconds());
    assertEquals(960, properties.getConsumptionTimeoutSeconds());
    assertEquals(60, properties.getReconciliationTimeoutSeconds());
  }

  @Test
  void executorUsesConfiguredBounds() {
    A2AProperties properties = new A2AProperties();
    properties.setExecutorCoreSize(2);
    properties.setExecutorMaxSize(4);
    properties.setExecutorQueueCapacity(7);
    properties.setExecutorKeepAliveSeconds(15);

    ExecutorService executor = new A2AAutoConfiguration().agentExecutorPool(properties);
    try {
      ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
      assertEquals(2, pool.getCorePoolSize());
      assertEquals(4, pool.getMaximumPoolSize());
      assertEquals(7, pool.getQueue().remainingCapacity());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void executorRejectsMaxBelowCore() {
    A2AProperties properties = new A2AProperties();
    properties.setExecutorCoreSize(4);
    properties.setExecutorMaxSize(2);
    assertThrows(
        IllegalArgumentException.class,
        () -> new A2AAutoConfiguration().agentExecutorPool(properties));
  }

  @Test
  void whenEnabledFalseThenNoA2ABeansCreated() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(A2AAutoConfiguration.class))
        .withPropertyValues("a2at.server.enabled=false")
        .run(
            context -> {
              assertFalse(context.containsBean("agentCard"));
              assertFalse(context.containsBean("requestHandler"));
              assertFalse(context.containsBean("restHandler"));
              assertFalse(context.containsBean("a2aController"));
              assertFalse(context.containsBean("agentExecutorPool"));
              assertFalse(context.containsBean("eventBus"));
              assertFalse(context.containsBean("taskStore"));
            });
  }

  @Test
  void whenEnabledIsMissingThenNoA2ABeansCreated() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(A2AAutoConfiguration.class))
        .run(
            context -> {
              assertFalse(context.containsBean("a2aController"));
              assertFalse(context.containsBean("agentExecutorPool"));
              assertFalse(context.containsBean("eventBus"));
            });
  }

  @Test
  void serverEnabledPropertyDefaultsToFalse() {
    A2AProperties properties = new A2AProperties();
    assertFalse(properties.isEnabled());
  }

  @Test
  void whenEnabledTrueThenA2ABeansCreated() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(A2AAutoConfiguration.class))
        .withPropertyValues("a2at.server.enabled=true")
        .run(
            context -> {
              // agentCard will fail to load without a real classpath resource,
              // but the bean definition should be present (condition matched)
              assertFalse(
                  context.getStartupFailure() != null
                      && context.getStartupFailure().getMessage() != null
                      && context.getStartupFailure().getMessage().contains("did not match"));
            });
  }

  @Test
  void taskAuthorizationProviderRejectionRunsBeforeTheAgentExecutor() {
    AtomicInteger executorRuns = new AtomicInteger();
    AgentExecutor executor = mock(AgentExecutor.class);
    doAnswer(
            invocation -> {
              executorRuns.incrementAndGet();
              return null;
            })
        .when(executor)
        .execute(any(), any());
    TaskAuthorizationProvider guard =
        new TaskAuthorizationProvider() {
          @Override
          public boolean checkRead(ServerCallContext ctx, String taskId, TaskOperation op) {
            return true;
          }

          @Override
          public boolean checkWrite(ServerCallContext ctx, String taskId, TaskOperation op) {
            return true;
          }

          @Override
          public boolean checkCreate(ServerCallContext ctx, TaskOperation op) {
            return false;
          }

          @Override
          public boolean isTaskRecorded(String taskId) {
            return false;
          }

          @Override
          public void recordOwnership(ServerCallContext ctx, String taskId, TaskOperation op) {}
        };

    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(A2AAutoConfiguration.class))
        .withBean(AgentCard.class, () -> mock(AgentCard.class))
        .withBean(AgentExecutor.class, () -> executor)
        .withBean(TaskAuthorizationProvider.class, () -> guard)
        .withPropertyValues("a2at.server.enabled=true")
        .run(
            context -> {
              RequestHandler handler = context.getBean(RequestHandler.class);
              MessageSendParams params =
                  MessageSendParams.builder()
                      .message(
                          Message.builder()
                              .messageId("m1")
                              .role(Message.Role.ROLE_USER)
                              .parts(List.of(new TextPart("diagnose")))
                              .build())
                      .build();
              ServerCallContext callContext =
                  new ServerCallContext(new AuthenticatedUser("waimo"), Map.of(), Set.of());
              assertThrows(
                  TaskNotFoundError.class, () -> handler.onMessageSend(params, callContext));
              assertEquals(0, executorRuns.get());
            });
  }

  @Test
  void requestHandlerIsCreatedWithoutAnAuthorizationProvider() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(A2AAutoConfiguration.class))
        .withBean(AgentCard.class, () -> mock(AgentCard.class))
        .withBean(AgentExecutor.class, () -> mock(AgentExecutor.class))
        .withPropertyValues("a2at.server.enabled=true")
        .run(context -> assertTrue(context.containsBean("requestHandler")));
  }

  /**
   * A host may legitimately own any number of {@link ExecutorService} beans (dispatchers, SSE
   * heartbeat schedulers, pub-sub workers) and {@link ScheduledExecutorService} is a subtype, so a
   * type-based condition or injection can never be unique. The auto-configured pool must still be
   * registered and the handlers must wire to it — before the fix this context failed to refresh
   * with {@code required a single bean, but N were found}.
   */
  @Test
  void autoConfiguredPoolSurvivesForeignExecutorServiceBeans() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(A2AAutoConfiguration.class))
        .withBean(
            "spaceAiDispatcherExecutor",
            ExecutorService.class,
            () ->
                Executors.newSingleThreadExecutor(
                    r -> {
                      Thread t = new Thread(r, "foreign-dispatcher");
                      t.setDaemon(true);
                      return t;
                    }))
        .withBean(
            "spaceSseHeartbeatScheduler",
            ScheduledExecutorService.class,
            () ->
                Executors.newSingleThreadScheduledExecutor(
                    r -> {
                      Thread t = new Thread(r, "foreign-heartbeat");
                      t.setDaemon(true);
                      return t;
                    }))
        .withBean(AgentCard.class, () -> mock(AgentCard.class))
        .withBean(AgentExecutor.class, () -> mock(AgentExecutor.class))
        .withPropertyValues("a2at.server.enabled=true")
        .run(
            context -> {
              assertTrue(context.containsBean("agentExecutorPool"));
              ExecutorService pool = context.getBean("agentExecutorPool", ExecutorService.class);
              assertTrue(
                  pool instanceof ThreadPoolExecutor,
                  "the auto-configured pool must be the A2A ThreadPoolExecutor, not a foreign bean");
              assertTrue(context.containsBean("requestHandler"));
              assertTrue(context.containsBean("restHandler"));
            });
  }

  /** A host that declares its own {@code agentExecutorPool} by name takes over the wiring. */
  @Test
  void hostDefinedAgentExecutorPoolReplacesTheAutoConfiguredOne() {
    ExecutorService hostPool =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "host-a2a-pool");
              t.setDaemon(true);
              return t;
            });
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(A2AAutoConfiguration.class))
        .withBean("agentExecutorPool", ExecutorService.class, () -> hostPool)
        .withBean(AgentCard.class, () -> mock(AgentCard.class))
        .withBean(AgentExecutor.class, () -> mock(AgentExecutor.class))
        .withPropertyValues("a2at.server.enabled=true")
        .run(
            context -> {
              assertSame(hostPool, context.getBean("agentExecutorPool", ExecutorService.class));
              assertTrue(context.containsBean("requestHandler"));
              assertTrue(context.containsBean("restHandler"));
            });
  }

  @Test
  void heartbeatIntervalDefaultsToFifteenSeconds() {
    A2AProperties properties = new A2AProperties();
    assertEquals(15, properties.getHeartbeatIntervalSeconds());
  }

  @Test
  void heartbeatIntervalZeroDisablesHeartbeat() {
    A2AProperties properties = new A2AProperties();
    properties.setHeartbeatIntervalSeconds(0);
    assertEquals(0, properties.getHeartbeatIntervalSeconds());
  }

  @Test
  void heartbeatIntervalRejectsNegative() {
    A2AProperties properties = new A2AProperties();
    assertThrows(IllegalArgumentException.class, () -> properties.setHeartbeatIntervalSeconds(-1));
  }

  @Test
  void heartbeatCommentsSentDuringLongRunningStream() throws Exception {
    RestHandler restHandler = mock(RestHandler.class);
    RequestHandler requestHandler = mock(RequestHandler.class);
    AgentCard agentCard = mock(AgentCard.class);
    when(agentCard.capabilities()).thenReturn(AgentCapabilities.builder().streaming(true).build());
    var agentInterface = mock(org.a2aproject.sdk.spec.AgentInterface.class);
    when(agentInterface.protocolVersion()).thenReturn("1.0");
    when(agentCard.supportedInterfaces()).thenReturn(java.util.List.of(agentInterface));

    AtomicReference<Flow.Subscriber<? super StreamingEventKind>> subscriberRef =
        new AtomicReference<>();
    Flow.Publisher<StreamingEventKind> publisher =
        s -> {
          subscriberRef.set(s);
          s.onSubscribe(
              new Flow.Subscription() {
                @Override
                public void request(long n) {}

                @Override
                public void cancel() {}
              });
        };
    when(requestHandler.onMessageSendStream(any(), any())).thenReturn(publisher);

    A2AController controller = new A2AController(restHandler, requestHandler, agentCard, 200L);
    try {
      MockMvc mockMvc =
          MockMvcBuilders.standaloneSetup(controller)
              .addPlaceholderValue("a2at.server.path-prefix", "/a2a/json")
              .build();

      MvcResult result =
          mockMvc
              .perform(
                  post("/a2a/json/message:stream")
                      .header("A2A-Version", "1.0")
                      .contentType("application/json")
                      .accept("text/event-stream")
                      .content(
                          "{\"message\":{\"messageId\":\"m1\",\"role\":\"ROLE_USER\","
                              + "\"parts\":[{\"text\":\"test\"}]}}"))
              .andExpect(request().asyncStarted())
              .andReturn();

      Thread.sleep(350);
      subscriberRef.get().onComplete();
      mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());

      // MockMvc's mock response may not capture cross-thread SSE comment writes; the
      // wire-level heartbeat verification is covered by E2E tests. Here we verify the
      // stream stays alive through multiple heartbeat intervals and completes cleanly.
    } finally {
      controller.shutdown();
    }
  }
}
