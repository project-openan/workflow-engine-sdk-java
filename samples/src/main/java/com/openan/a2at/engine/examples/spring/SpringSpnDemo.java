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

package com.openan.a2at.engine.examples.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openan.a2at.engine.client.A2ATransport;
import com.openan.a2at.engine.client.AgentCardJacksonModule;
import com.openan.a2at.engine.client.DefaultWorkflowEngineClient;
import com.openan.a2at.engine.client.WorkflowEngineClientConfig;
import com.openan.a2at.engine.examples.agents.EnvResolver;
import com.openan.a2at.engine.examples.agents.SpnDomainAgentCity1Executor;
import com.openan.a2at.engine.examples.agents.SpnDomainAgentCity2Executor;
import com.openan.a2at.engine.examples.server.JdkHttpA2AServer;
import com.openan.a2at.engine.model.SendMessageResult;

import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Demo entry point for the SPN cross-city diagnosis.
 *
 * <p>Mirrors {@link com.openan.a2at.engine.examples.embedded.SpnCrossCityDiagnosisDemo} but with
 * the Workbench Agent running as a Spring Boot service. The demo orchestrates:
 *
 * <ol>
 *   <li>Start Spring Boot Workbench Agent (A2A server on port 26337)
 *   <li>Start OMC agents (embedded servers on ports 26335, 26336)
 *   <li>Send a Task-T message to the Workbench Agent via the SDK client
 *   <li>Print the response and shut everything down
 * </ol>
 *
 * <p>Demonstrates heterogeneous architecture: Workbench (Spring Boot) and OMC (JDK HttpServer)
 * communicate via the unified A2A-T protocol.
 */
public class SpringSpnDemo {
    private static final Logger log = LoggerFactory.getLogger(SpringSpnDemo.class);
    private static final ObjectMapper mapper =
            new ObjectMapper().registerModule(new AgentCardJacksonModule());

    private static final String WB_AGENT_NAME = "Transport Workbench Agent";
    private static final long STARTUP_WAIT = 3;

    private final List<JdkHttpA2AServer> omcServers = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        new SpringSpnDemo().run();
    }

    private static String[] args() {
        return new String[0];
    }

    public void run() throws Exception {
        log.info("=== SPN Cross-City Diagnosis Demo (Spring Workbench) ===");

        log.info("=== Step 1: Start Spring Boot Workbench Agent ===");
        var ctx = SpringApplication.run(SpringWorkbenchApplication.class, args());
        log.info("Spring Boot Workbench started");

        log.info("=== Step 2: Start OMC agents (embedded servers) ===");
        startOmcAgents();
        log.info("Waiting for agents to bind...");
        TimeUnit.SECONDS.sleep(STARTUP_WAIT);

        log.info("=== Step 3: Send Task-T to Workbench Agent ===");
        String taskText =
                "SPN cross-city fault diagnosis: "
                        + "Customer A Shanghai-Guangzhou SPN link down, "
                        + "dispatch two city OMCs for parallel diagnosis, "
                        + "aggregate analysis to locate the fault city, "
                        + "authorize recovery, OMC reports recovery result";
        log.info("Task: {}", taskText);

        String response = sendTaskToWorkbench(taskText);
        log.info("=== Workbench Agent Response ===");
        if (response != null) {
            log.info("Response ({} chars):", response.length());
            log.info("{}", response);
        } else {
            log.warn("No response received");
        }

        log.info("=== Demo complete, shutting down ===");
        shutdownOmcAgents();
        ctx.close();
        System.exit(0);
    }

    private void startOmcAgents() throws Exception {
        startOmcAgent("agentcard/spn_domain_agent_city1.json", new SpnDomainAgentCity1Executor());
        startOmcAgent("agentcard/spn_domain_agent_city2.json", new SpnDomainAgentCity2Executor());
    }

    @SuppressWarnings("unchecked")
    private void startOmcAgent(String resourcePath, AgentExecutor executor) throws Exception {
        String path = getClass().getClassLoader().getResource(resourcePath).getPath();
        Map<String, Object> card = mapper.readValue(new File(path), Map.class);
        List<Map<String, Object>> ifaces =
                (List<Map<String, Object>>) card.getOrDefault("supportedInterfaces", List.of());
        String url =
                ifaces.isEmpty() ? "https://127.0.0.1:0" : String.valueOf(ifaces.get(0).get("url"));
        java.net.URI uri = java.net.URI.create(url);
        String host = uri.getHost() != null ? uri.getHost() : "127.0.0.1";
        int port = Math.max(uri.getPort(), 0);
        JdkHttpA2AServer server = new JdkHttpA2AServer(host, port, card, executor);
        server.start();
        omcServers.add(server);
        log.info("Started OMC agent: {} on https://{}:{}/", card.get("name"), host, port);
    }

    private String sendTaskToWorkbench(String taskText) throws Exception {
        String cardPath =
                getClass()
                        .getClassLoader()
                        .getResource("agentcard/transport_workbench_agent.json")
                        .getPath();
        AgentCard wbCard = mapper.readValue(new File(cardPath), AgentCard.class);

        String credPath =
                getClass().getClassLoader().getResource("spn_agent_credentials.json").getPath();
        String envPath = EnvResolver.resolveEnvPath();

        A2ATransport transport =
                new A2ATransport(
                        List.of(wbCard),
                        null,
                        WorkflowEngineClientConfig.builder()
                                .sslVerify(false)
                                .a2atEnvPath(envPath)
                                .credentialsConfigPath(credPath)
                                .build());
        DefaultWorkflowEngineClient client = new DefaultWorkflowEngineClient(transport);
        try {
            SendMessageResult result = client.sendMessage(WB_AGENT_NAME, taskText).join();
            return result.getText();
        } finally {
            client.close();
        }
    }

    private void shutdownOmcAgents() {
        log.info("Shutting down OMC agents...");
        omcServers.forEach(
                s -> {
                    try {
                        s.close();
                    } catch (Exception ignored) {
                    }
                });
    }
}
