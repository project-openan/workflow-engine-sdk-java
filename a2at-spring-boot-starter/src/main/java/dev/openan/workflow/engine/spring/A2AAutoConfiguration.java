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

import org.a2aproject.sdk.server.AgentCardCacheMetadata;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.config.A2AConfigProvider;
import org.a2aproject.sdk.server.events.InMemoryQueueManager;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
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
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Spring Boot autoconfiguration for the A2A-T server side.
 *
 * <p>When {@code a2at-spring-boot-starter} is on the classpath of a web application, this
 * autoconfiguration assembles all SDK server components as Spring beans:
 *
 * <ul>
 *   <li>{@link AgentCard} - loaded from {@code a2at.server.agent-card} path
 *   <li>{@link RequestHandler} - the {@link DefaultRequestHandler}
 *   <li>{@link RestHandler} - the REST protocol handler
 *   <li>{@link MainEventBusProcessor} - the event bus
 *   <li>{@link A2AController} - the Spring MVC controller (message:send + message:stream)
 * </ul>
 *
 * <p>The partner only needs to provide an {@link AgentExecutor} implementation as a
 * {@code @Component} or {@code @Bean}.
 */
@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnClass(AgentExecutor.class)
@EnableConfigurationProperties(A2AProperties.class)
public class A2AAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(A2AAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public AgentCard agentCard(A2AProperties props, ResourceLoader resourceLoader)
            throws Exception {
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
    public A2AConfigProvider a2aConfigProvider() {
        return new A2AConfigProvider() {
            @Override
            public String getValue(String key) {
                return switch (key) {
                    case "a2a.blocking.agent.timeout.seconds" -> "30";
                    case "a2a.blocking.consumption.timeout.seconds" -> "5";
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

    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnMissingBean
    public ExecutorService agentExecutorPool() {
        return new ThreadPoolExecutor(
                8,
                8,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "a2a-agent-executor");
                    t.setDaemon(true);
                    return t;
                });
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
        proc.ensureStarted();
        log.info("[A2A] MainEventBusProcessor started");
        return proc;
    }

    @Bean(initMethod = "")
    @ConditionalOnMissingBean
    public RequestHandler requestHandler(
            AgentExecutor executor,
            InMemoryTaskStore store,
            InMemoryQueueManager qm,
            PushNotificationConfigStore pushStore,
            MainEventBusProcessor proc,
            ExecutorService pool) {
        return DefaultRequestHandler.create(executor, store, qm, pushStore, proc, pool, pool);
    }

    @Bean
    @ConditionalOnMissingBean
    public RestHandler restHandler(AgentCard card, RequestHandler handler, ExecutorService pool) {
        return new RestHandler(card, new AgentCardCacheMetadata(card, null), handler, pool);
    }

    @Bean
    @ConditionalOnMissingBean
    public A2AController a2aController(
            RestHandler restHandler, RequestHandler requestHandler, A2AProperties properties) {
        return new A2AController(restHandler, requestHandler, properties);
    }
}
