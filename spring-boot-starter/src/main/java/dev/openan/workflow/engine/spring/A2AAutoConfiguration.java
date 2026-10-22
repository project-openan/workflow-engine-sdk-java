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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openan.workflow.engine.client.AgentCardJacksonModule;
import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.a2aproject.sdk.server.AgentCardCacheMetadata;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.auth.TaskAuthorizationProvider;
import org.a2aproject.sdk.server.config.A2AConfigProvider;
import org.a2aproject.sdk.server.events.InMemoryQueueManager;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.requesthandlers.AuthorizationRequestHandlerDecorator;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.BasePushNotificationSender;
import org.a2aproject.sdk.server.tasks.InMemoryPushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.PushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.PushNotificationSender;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.transport.rest.handler.RestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Spring Boot autoconfiguration for the A2A-T server side.
 *
 * <p>When {@code spring-boot-starter} is on the classpath of a web application, this
 * autoconfiguration assembles all SDK server components as Spring beans:
 *
 * <ul>
 *   <li>{@link AgentCard} - loaded from {@code a2at.server.agent-card} path
 *   <li>{@link RequestHandler} - the {@link DefaultRequestHandler}
 *   <li>{@link RestHandler} - the REST protocol handler
 *   <li>{@link MainEventBusProcessor} - the event bus
 *   <li>{@link A2AController} - the Spring MVC controller (message send/stream plus task query,
 *       list, cancel, and subscribe endpoints)
 *   <li>Optional slash-style aliases for gateways that cannot publish colon-style action paths
 * </ul>
 *
 * <p>Optional host-side request validation: declare a {@link TaskAuthorizationProvider} bean and
 * every A2A operation is offered to it before the agent executor runs. {@code checkCreate} guards
 * message send/stream (new task creation), {@code checkRead}/{@code checkWrite} guard task
 * query/cancel, and rejection returns a standard A2A error envelope to the caller. Without such a
 * bean the server behaves exactly as before.
 *
 * <p>The partner only needs to provide an {@link AgentExecutor} implementation as a
 * {@code @Component} or {@code @Bean}. Set {@code a2at.server.enabled=true} to enable the
 * server-side autoconfiguration.
 */
@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnClass(AgentExecutor.class)
@ConditionalOnProperty(name = "a2at.server.enabled", havingValue = "true")
@EnableConfigurationProperties(A2AProperties.class)
public class A2AAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(A2AAutoConfiguration.class);

  @Bean
  @ConditionalOnMissingBean
  public AgentCard agentCard(A2AProperties props, ResourceLoader resourceLoader) throws Exception {
    ObjectMapper mapper = new ObjectMapper().registerModule(new AgentCardJacksonModule());
    Resource resource = resourceLoader.getResource(props.getAgentCard());
    if (!resource.exists()) {
      throw new IllegalStateException("AgentCard not found: " + props.getAgentCard());
    }
    try (InputStream is = resource.getInputStream()) {
      AgentCard card = mapper.readValue(is, AgentCard.class);
      log.info("[A2A] Loaded AgentCard: name={}, version={}", card.name(), card.version());
      return card;
    }
  }

  @Bean
  @ConditionalOnMissingBean
  public A2AConfigProvider a2aConfigProvider(A2AProperties properties) {
    // Kept for SDK components that resolve configuration through CDI; the builder-built
    // DefaultRequestHandler never reads it, so requestHandler() applies the blocking timeouts
    // directly (see applyBlockingTimeouts).
    return new A2AConfigProvider() {
      @Override
      public String getValue(String key) {
        return switch (key) {
          case "a2a.blocking.agent.timeout.seconds" ->
              Integer.toString(properties.getAgentTimeoutSeconds());
          case "a2a.blocking.consumption.timeout.seconds" ->
              Integer.toString(properties.getConsumptionTimeoutSeconds());
          case "a2a.blocking.reconciliation.timeout.seconds" ->
              Integer.toString(properties.getReconciliationTimeoutSeconds());
          default -> null;
        };
      }

      @Override
      public Optional<String> getOptionalValue(String key) {
        String v = getValue(key);
        return v != null ? Optional.of(v) : Optional.empty();
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean
  public InMemoryTaskStore taskStore() {
    return new InMemoryTaskStore();
  }

  @Bean
  @ConditionalOnMissingBean
  public MainEventBus eventBus() {
    return new MainEventBus();
  }

  @Bean
  @ConditionalOnMissingBean
  public InMemoryQueueManager queueManager(InMemoryTaskStore store, MainEventBus bus) {
    return new InMemoryQueueManager(store, bus);
  }

  @Bean
  @ConditionalOnMissingBean
  public PushNotificationConfigStore pushStore() {
    return new InMemoryPushNotificationConfigStore();
  }

  /**
   * The A2A server executor. Matches by bean <em>name</em>, never by type: a host application may
   * legitimately define any number of its own {@link ExecutorService} beans (dispatchers, SSE
   * schedulers, pub-sub workers), and a type-based condition would silently suppress this pool
   * while {@link #requestHandler} and {@link #restHandler} still need a single unambiguous
   * candidate. Hosts override it by declaring their own {@code agentExecutorPool} bean.
   */
  @Bean(name = "agentExecutorPool", destroyMethod = "shutdownNow")
  @ConditionalOnMissingBean(name = "agentExecutorPool")
  public ExecutorService agentExecutorPool(A2AProperties properties) {
    if (properties.getExecutorMaxSize() < properties.getExecutorCoreSize()) {
      throw new IllegalArgumentException(
          "a2at.server.executor-max-size must be >= executor-core-size");
    }
    return new ThreadPoolExecutor(
        properties.getExecutorCoreSize(),
        properties.getExecutorMaxSize(),
        properties.getExecutorKeepAliveSeconds(),
        TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(properties.getExecutorQueueCapacity()),
        r -> {
          Thread t = new Thread(r, "a2a-agent-executor");
          t.setDaemon(true);
          return t;
        },
        new ThreadPoolExecutor.CallerRunsPolicy());
  }

  @Bean
  @ConditionalOnMissingBean
  public MainEventBusProcessor eventBusProcessor(
      MainEventBus bus,
      InMemoryTaskStore store,
      InMemoryQueueManager qm,
      PushNotificationConfigStore pushStore) {
    PushNotificationSender sender = new BasePushNotificationSender(pushStore);
    MainEventBusProcessor proc = new MainEventBusProcessor(bus, store, sender, qm);
    log.info("[A2A] MainEventBusProcessor configured; Spring manages its lifecycle");
    return proc;
  }

  @Bean
  @ConditionalOnMissingBean
  public RequestHandler requestHandler(
      AgentExecutor executor,
      InMemoryTaskStore store,
      InMemoryQueueManager qm,
      PushNotificationConfigStore pushStore,
      MainEventBusProcessor proc,
      @Qualifier("agentExecutorPool") ExecutorService pool,
      A2AProperties properties,
      ObjectProvider<TaskAuthorizationProvider> authorizationProvider) {
    DefaultRequestHandler delegate =
        DefaultRequestHandler.builder()
            .agentExecutor(executor)
            .taskStore(store)
            .queueManager(qm)
            .pushConfigStore(pushStore)
            .mainEventBusProcessor(proc)
            .executor(pool)
            .eventConsumerExecutor(pool)
            .build();
    applyBlockingTimeouts(delegate, properties);
    RequestHandler handler = new SpringCompatibleRequestHandler(delegate);
    TaskAuthorizationProvider provider = authorizationProvider.getIfAvailable();
    if (provider != null) {
      // The decorator carries CDI Instance fields like the delegate, so it also stays behind the
      // Spring-compatible facade. Every A2A operation (message send/stream, task query, cancel,
      // subscribe, push-notification config) is offered to the host provider before the agent
      // executor runs; a rejection surfaces as a standard A2A error to the caller.
      handler =
          new SpringCompatibleRequestHandler(
              new AuthorizationRequestHandlerDecorator(handler, provider));
      log.info("[A2A] TaskAuthorizationProvider detected; operation-level authorization enabled");
    }
    return handler;
  }

  @Bean
  @ConditionalOnMissingBean
  public RestHandler restHandler(
      AgentCard card,
      RequestHandler handler,
      @Qualifier("agentExecutorPool") ExecutorService pool) {
    return new RestHandler(card, new AgentCardCacheMetadata(card, null), handler, pool);
  }

  @Bean
  @ConditionalOnMissingBean
  public A2AController a2aController(
      RestHandler restHandler,
      RequestHandler requestHandler,
      AgentCard agentCard,
      A2AProperties properties) {
    return new A2AController(
        restHandler, requestHandler, agentCard, properties.getHeartbeatIntervalSeconds() * 1000L);
  }

  /**
   * Adds non-standard slash-style action aliases without changing the canonical A2A endpoints. This
   * compatibility surface is disabled unless explicitly enabled for a constrained gateway.
   */
  @Bean
  @ConditionalOnMissingBean(A2ASlashActionAliasController.class)
  @ConditionalOnProperty(name = "a2at.server.slash-action-aliases-enabled", havingValue = "true")
  A2ASlashActionAliasController a2aSlashActionAliasController(A2AController delegate) {
    return new A2ASlashActionAliasController(delegate);
  }

  /**
   * a2a-java's {@code DefaultRequestHandler.Builder.build()} hardcodes blocking-call timeouts
   * (agent=5s, consumption=2s, reconciliation=1s) and never consults {@link A2AConfigProvider};
   * only the CDI {@code @PostConstruct} path reads the provider. A builder-assembled handler
   * therefore returns a WORKING task snapshot after 5 seconds even though the agent keeps running,
   * which silently breaks blocking {@code message:send} for every real agent task (LLM calls,
   * multi-step workflows). Apply the configured values directly after the build.
   */
  static void applyBlockingTimeouts(DefaultRequestHandler handler, A2AProperties properties) {
    try {
      setBlockingTimeout(
          handler, "agentCompletionTimeoutSeconds", properties.getAgentTimeoutSeconds());
      setBlockingTimeout(
          handler,
          "consumptionCompletionTimeoutSeconds",
          properties.getConsumptionTimeoutSeconds());
      setBlockingTimeout(
          handler, "reconciliationTimeoutSeconds", properties.getReconciliationTimeoutSeconds());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "Failed to apply a2a blocking timeouts; the a2a-java SDK field layout changed?", e);
    }
    log.info(
        "[A2A] Blocking timeouts applied: agent={}s, consumption={}s, reconciliation={}s",
        properties.getAgentTimeoutSeconds(),
        properties.getConsumptionTimeoutSeconds(),
        properties.getReconciliationTimeoutSeconds());
  }

  private static void setBlockingTimeout(
      DefaultRequestHandler handler, String fieldName, int seconds)
      throws ReflectiveOperationException {
    java.lang.reflect.Field field = DefaultRequestHandler.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    field.setInt(handler, seconds);
  }
}
