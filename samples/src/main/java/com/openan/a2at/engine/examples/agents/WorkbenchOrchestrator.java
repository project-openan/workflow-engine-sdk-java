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

package com.openan.a2at.engine.examples.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openan.a2at.engine.client.A2ATransport;
import com.openan.a2at.engine.client.AgentCardJacksonModule;
import com.openan.a2at.engine.client.DefaultExtensionSender;
import com.openan.a2at.engine.client.DefaultWorkflowEngineClient;
import com.openan.a2at.engine.client.ExtensionSender;
import com.openan.a2at.engine.client.WorkflowEngineClient;
import com.openan.a2at.engine.client.WorkflowEngineClientConfig;
import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.control.EventType;
import com.openan.a2at.engine.model.ExecutionResult;
import com.openan.a2at.engine.model.Workflow;
import com.openan.a2at.engine.model.WorkflowSearchResult;
import com.openan.a2at.engine.registry.LoadPsop;
import com.openan.a2at.engine.runner.ExecutePsop;

import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Workflow orchestration for the SPN cross-city diagnosis.
 *
 * <p>Single responsibility: coordinate the full orchestration pipeline -- load agent cards,
 * search/load PSOP, create engine client, pre-position extensions, and run the workflow. Each
 * sub-step delegates to a dedicated collaborator:
 *
 * <ul>
 *   <li>{@link ExtensionPrePositioner} -- Authorization-T / Notification-T pre-positioning
 *   <li>{@link WorkbenchControlPoint} -- workflow decision callbacks (task dispatch, routing)
 *   <li>{@link NegotiationStrategy} -- negotiation clarification (injected into the control point)
 * </ul>
 *
 * <p>This class does NOT handle agent server I/O (that stays in {@link
 * TransportWorkbenchAgentExecutor}) or the details of any single A2A-T extension protocol.
 */
public class WorkbenchOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(WorkbenchOrchestrator.class);
    private static final ObjectMapper mapper =
            new ObjectMapper().registerModule(new AgentCardJacksonModule());

    private static final String FALLBACK_PSOP_ID = "psop_spn_cross_city_diagnosis";
    private static final List<String> AGENT_CARD_RESOURCES =
            List.of(
                    "agentcard/spn_domain_agent_city1.json",
                    "agentcard/spn_domain_agent_city2.json",
                    "agentcard/transport_workbench_agent.json");

    private final String orchUrl;
    private final String credentialsPath;
    private final boolean sslVerify;
    private final String a2atEnvPath;

    public WorkbenchOrchestrator(
            String orchUrl, String credentialsPath, boolean sslVerify, String a2atEnvPath) {
        this.orchUrl = orchUrl;
        this.credentialsPath = credentialsPath;
        this.sslVerify = sslVerify;
        this.a2atEnvPath = a2atEnvPath;
    }

    private static String buildResultText(ExecutionResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Workflow execution ")
                .append(result.isSuccess() ? "succeeded" : "failed")
                .append(".\n");
        if (result.getHistory() != null) {
            for (Map<String, Object> h : result.getHistory()) {
                sb.append("- Step: ")
                        .append(h.get("step"))
                        .append(", Agent: ")
                        .append(h.get("agent"))
                        .append(", Status: ")
                        .append(h.get("status"))
                        .append("\n");
            }
        }
        if (result.getError() != null) {
            sb.append("Error: ").append(result.getError());
        }
        return sb.toString();
    }

    /** Run the full orchestration pipeline and return the result text. */
    public String run(String messageText) throws Exception {
        log.info("[Orchestrator] Step 1: Load agent cards");
        List<AgentCard> agentCards = loadAgentCards();
        log.info("[Orchestrator] Loaded {} agent card(s)", agentCards.size());

        log.info("[Orchestrator] Step 2: Search + load PSOP workflow");
        String psopId = searchPsop(messageText);
        Workflow workflow = LoadPsop.load(orchUrl, psopId, null, sslVerify);
        log.info(
                "[Orchestrator] Workflow: {} ({} steps)",
                workflow.getName(),
                workflow.getSteps().size());

        log.info("[Orchestrator] Step 3: Create engine client");
        A2ATransport transport =
                new A2ATransport(
                        agentCards,
                        null,
                        WorkflowEngineClientConfig.builder()
                                .sslVerify(sslVerify)
                                .a2atEnvPath(a2atEnvPath)
                                .credentialsConfigPath(credentialsPath)
                                .build());
        WorkflowEngineClient engineClient = new DefaultWorkflowEngineClient(transport);
        ExtensionSender extensionSender = new DefaultExtensionSender(transport);

        log.info("[Orchestrator] Step 4: Pre-position extensions");
        new ExtensionPrePositioner().prePosition(extensionSender, agentCards);

        log.info("[Orchestrator] Step 5: Execute workflow");
        WorkbenchControlPoint controlPoint =
                new WorkbenchControlPoint(a2atEnvPath, new NegotiationStrategy(a2atEnvPath));
        ExecutionResult result =
                ExecutePsop.builder()
                        .psop(workflow)
                        .agentCards(agentCards)
                        .controlPoint(controlPoint)
                        .engineClient(engineClient)
                        .runtimeIntent(messageText)
                        .lang("zh")
                        .sslVerify(sslVerify)
                        .credentialsConfigPath(credentialsPath)
                        .a2atEnvPath(a2atEnvPath)
                        .eventCallback(createLogCallback())
                        .onFinish(
                                (r, events) -> {
                                    log.info(
                                            "[onFinish] Success={}, Events={}",
                                            r.isSuccess(),
                                            events.size());
                                    return CompletableFuture.completedFuture(null);
                                })
                        .execute()
                        .join();
        return buildResultText(result);
    }

    private List<AgentCard> loadAgentCards() {
        Map<String, AgentCard> byName = new LinkedHashMap<>();
        for (String res : AGENT_CARD_RESOURCES) {
            try {
                var url = getClass().getClassLoader().getResource(res);
                if (url != null) {
                    AgentCard card =
                            mapper.readValue(new java.io.File(url.getPath()), AgentCard.class);
                    byName.put(card.name(), card);
                }
            } catch (Exception e) {
                log.warn("Failed to load agent card {}: {}", res, e.getMessage());
            }
        }
        return new ArrayList<>(byName.values());
    }

    private String searchPsop(String messageText) {
        try {
            List<WorkflowSearchResult> results =
                    LoadPsop.search(orchUrl, messageText, 3, null, sslVerify);
            if (!results.isEmpty()) {
                String psopId = results.get(0).getWorkflowId();
                log.info(
                        "[Orchestrator] Found PSOP: {} (score={})",
                        psopId,
                        results.get(0).getScore());
                return psopId;
            }
        } catch (Exception e) {
            log.warn("[Orchestrator] PSOP search failed, using fallback: {}", e.getMessage());
        }
        return FALLBACK_PSOP_ID;
    }

    private EventCallback createLogCallback() {
        return new EventCallback() {
            @Override
            public void onEvent(String type, Map<String, Object> data) {
                switch (type) {
                    case EventType.START -> log.info("  [START] {}", data.get("workflow"));
                    case EventType.STEP_START -> log.info("  [STEP_START] {}", data.get("step"));
                    case EventType.TASK_REQUEST ->
                            log.info("  [TASK_REQUEST] agent={}", data.get("agent"));
                    case EventType.TASK_RESPONSE ->
                            log.info("  [TASK_RESPONSE] agent={}", data.get("agent"));
                    case EventType.AGENT_STATUS_UPDATE ->
                            log.info(
                                    "  [STATUS_UPDATE] agent={}, state={}, final={}",
                                    data.get("agent"),
                                    data.get("state"),
                                    data.get("is_final"));
                    case EventType.AGENT_ARTIFACT_UPDATE ->
                            log.info(
                                    "  [ARTIFACT_UPDATE] agent={}, artifact={}",
                                    data.get("agent"),
                                    data.get("artifact_name"));
                    case EventType.AGENT_MESSAGE_EVENT ->
                            log.info(
                                    "  [MESSAGE] agent={}, {} chars",
                                    data.get("agent"),
                                    data.get("text") != null
                                            ? ((String) data.get("text")).length()
                                            : 0);
                    case EventType.STEP_COMPLETE ->
                            log.info("  [STEP_COMPLETE] {}", data.get("step"));
                    case EventType.ROUTE_DECISION ->
                            log.info("  [ROUTE] {} -> {}", data.get("step"), data.get("next"));
                    case EventType.COMPLETE -> log.info("  [COMPLETE]");
                    case EventType.ERROR -> log.error("  [ERROR] {}", data.get("error"));
                    case EventType.CLOSE -> log.info("  [CLOSE]");
                    default -> {}
                }
            }
        };
    }
}
