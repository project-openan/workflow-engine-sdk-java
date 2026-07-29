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

package com.openan.a2at.engine.examples.embedded;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openan.a2at.engine.client.A2ATransport;
import com.openan.a2at.engine.client.AgentCardJacksonModule;
import com.openan.a2at.engine.client.DefaultWorkflowEngineClient;
import com.openan.a2at.engine.client.WorkflowEngineClientConfig;
import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.control.EventType;
import com.openan.a2at.engine.examples.agents.EnvResolver;
import com.openan.a2at.engine.model.SendMessageResult;

import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * SPN cross-city fault diagnosis demo entry point.
 *
 * <p>Starts all 3 A2A agents (via {@link StartAgentsServer}) in a background thread, waits for them
 * to bind, then uses {@link DefaultWorkflowEngineClient} to send a Task-T message to the Workbench
 * Agent. The client internally handles A2A-T protocol: Task-T prompt generation, SSE streaming
 * response parsing, negotiation auto-loop, etc.
 *
 * <p>Architecture:
 *
 * <ul>
 *   <li>Transport Workbench Agent (port 26337) - orchestrator + merge
 *   <li>SPN Domain Agent City1 (port 26335) - Yuedong OMC, has fault
 *   <li>SPN Domain Agent City2 (port 26336) - Yuexi OMC, normal
 * </ul>
 */
public class SpnCrossCityDiagnosisDemo {
    private static final Logger log = LoggerFactory.getLogger(SpnCrossCityDiagnosisDemo.class);
    private static final ObjectMapper mapper =
            new ObjectMapper().registerModule(new AgentCardJacksonModule());

    private static final String AGENT_CARD_RESOURCE = "agentcard/transport_workbench_agent.json";
    private static final String WB_AGENT_NAME = "Transport Workbench Agent";
    private static final long AGENT_STARTUP_WAIT_SECONDS = 3;

    public static void main(String[] args) throws Exception {
        log.info("=== SPN Cross-City Fault Diagnosis Demo ===");

        log.info("=== Step 1: Start all A2A agents ===");
        StartAgentsServer agentsServer = new StartAgentsServer();
        Thread agentThread = new Thread(agentsServer, "agents-starter");
        agentThread.setDaemon(true);
        agentThread.start();
        log.info("Waiting for agents to start...");
        TimeUnit.SECONDS.sleep(AGENT_STARTUP_WAIT_SECONDS);

        log.info("=== Step 2: Send Task-T to Workbench Agent ===");
        String taskText = "SPN跨城专线故障诊断：" + "客户A粤东-粤西间SPN专线中断，" + "请协同两地市OMC并行诊断，" + "汇总分析确定故障在哪个地市";
        log.info("Sending task: {}", taskText);
        String response = sendTaskToWorkbench(taskText);
        log.info("=== Workbench Agent Response ===");
        if (response != null) {
            log.info("Response ({} chars):\n{}", response.length(), response);
        } else {
            log.warn("Response was null");
        }

        log.info("=== Demo complete, shutting down ===");
        agentsServer.stop();
        // Force exit: JDK HttpClient and SDK internal thread pools may leave
        // non-daemon threads that prevent the JVM from exiting.
        System.exit(0);
    }

    /**
     * Send a Task-T message to the Workbench Agent via the workflow engine client.
     *
     * <p>Uses {@link DefaultWorkflowEngineClient} which internally handles: A2A REST
     * message:stream, SSE response parsing (statusUpdate/artifactUpdate), Task-T prompt generation,
     * and negotiation auto-loop.
     */
    private static String sendTaskToWorkbench(String taskText) throws Exception {
        String cardPath =
                SpnCrossCityDiagnosisDemo.class
                        .getClassLoader()
                        .getResource(AGENT_CARD_RESOURCE)
                        .getPath();
        AgentCard agentCard = mapper.readValue(new java.io.File(cardPath), AgentCard.class);

        A2ATransport transport =
                new A2ATransport(
                        List.of(agentCard),
                        null,
                        WorkflowEngineClientConfig.builder()
                                .sslVerify(false)
                                .a2atEnvPath(EnvResolver.resolveEnvPath())
                                .build());
        DefaultWorkflowEngineClient engineClient = new DefaultWorkflowEngineClient(transport);

        engineClient.setEventCallback(
                new EventCallback() {
                    @Override
                    public void onEvent(String type, Map<String, Object> data) {
                        switch (type) {
                            case EventType.AGENT_STATUS_UPDATE ->
                                    log.info(
                                            "  >> [STATUS] agent={}, state={}, final={}",
                                            data.get("agent"),
                                            data.get("state"),
                                            data.get("is_final"));
                            case EventType.AGENT_ARTIFACT_UPDATE ->
                                    log.info(
                                            "  >> [ARTIFACT] agent={}, name={}, text={}",
                                            data.get("agent"),
                                            data.get("artifact_name"),
                                            data.get("text"));
                            case EventType.AGENT_MESSAGE_EVENT ->
                                    log.info(
                                            "  >> [MESSAGE] agent={}, text={}",
                                            data.get("agent"),
                                            data.get("text"));
                            case EventType.AGENT_REQUEST ->
                                    log.info(
                                            "  >> [REQUEST] agent={}, {} chars",
                                            data.get("agent"),
                                            data.get("request") != null
                                                    ? String.valueOf(data.get("request")).length()
                                                    : 0);
                            case EventType.AGENT_RESPONSE ->
                                    log.info(
                                            "  >> [RESPONSE] agent={}, response={}",
                                            data.get("agent"),
                                            data.get("response") != null
                                                    ? data.get("response")
                                                    : "(empty)");
                            default -> {
                                /* other event types not shown in this demo */
                            }
                        }
                    }
                });

        // sendMessage handles A2A-T protocol internally
        SendMessageResult result = engineClient.sendMessage(WB_AGENT_NAME, taskText).join();
        log.info("[SelfTrigger] Task state: {}", result.getTaskState());
        engineClient.close();
        transport.close();
        return result.getText();
    }
}
