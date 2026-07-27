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

package com.openan.a2at.engine.examples.agents;

import com.openan.a2at.engine.client.DefaultWorkflowEngineClient;
import com.openan.a2at.engine.client.WorkflowEngineClient;
import com.openan.a2at.engine.client.WorkflowEngineClientConfig;
import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.control.EventType;
import com.openan.a2at.engine.model.ExecutionResult;
import com.openan.a2at.engine.model.Workflow;
import com.openan.a2at.engine.model.WorkflowSearchResult;
import com.openan.a2at.engine.client.AgentCardJacksonModule;
import com.openan.a2at.engine.registry.LoadPsop;
import com.openan.a2at.engine.runner.ExecutePsop;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Transport Workbench Agent - the orchestrator.
 *
 * <p>Receives a Task-T from the upper layer and directly executes (no
 * negotiation with the upper layer). Internally it:
 * <ol>
 *   <li>Pre-positions Authorization-T + Notification-T to SPN agents</li>
 *   <li>Searches and loads the matching PSOP workflow</li>
 *   <li>Runs the workflow (parallel Task-T diagnosis to SPN agents, with
 *       Negotiation-T between workbench and SPN agents)</li>
 *   <li>Returns the merged diagnosis result</li>
 * </ol>
 *
 * <p>Negotiation-T happens ONLY between the workbench and SPN Domain Agents,
 * not between the upper layer and the workbench.
 */
public class TransportWorkbenchAgentExecutor extends BaseAgentExecutor {
    private static final Logger log = LoggerFactory.getLogger(TransportWorkbenchAgentExecutor.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new AgentCardJacksonModule());

    private static final String FALLBACK_PSOP_ID = "psop_spn_cross_city_diagnosis";
    private static final List<String> AGENT_CARD_RESOURCES = List.of(
            "agentcard/spn_domain_agent_city1.json",
            "agentcard/spn_domain_agent_city2.json",
            "agentcard/transport_workbench_agent.json");

    private final String orchUrl;
    private final String credentialsPath;
    private final boolean sslVerify;
    private final String a2atEnvPath;

    public TransportWorkbenchAgentExecutor(String registryUrl, String orchUrl,
                                           String credentialsPath, boolean sslVerify) {
        this(registryUrl, orchUrl, credentialsPath, sslVerify, null);
    }

    public TransportWorkbenchAgentExecutor(String registryUrl, String orchUrl,
                                           String credentialsPath, boolean sslVerify,
                                           String a2atEnvPath) {
        this.orchUrl = orchUrl;
        this.credentialsPath = credentialsPath;
        this.sslVerify = sslVerify;
        this.a2atEnvPath = a2atEnvPath;
    }

    @Override
    public void execute(RequestContext ctx, AgentEmitter emitter) throws A2AError {
        String taskId = ctx.getTaskId();
        String contextId = ctx.getContextId();
        String input = extractText(ctx.getMessage());
        log.info("[Workbench] Received task: taskId={}, text={} chars", taskId, input.length());
        emitter.submit(buildStatusMessage(contextId, taskId, "Task received"));
        emitter.startWork(buildStatusMessage(contextId, taskId, "Processing"));
        try {
            String result = handleTopLevelTask(input);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put(NegotiationUtils.TASK_PROMPT_KEY, result);
            List<Part<?>> parts = List.of(new TextPart("跨城故障协同诊断汇总结果"));
            emitter.addArtifact(parts, "result", "cross-city-diagnosis-summary", metadata, false, true);
            emitter.complete(buildStatusMessage(contextId, taskId, "Completed"));
            log.info("[Workbench] Task completed");
        } catch (Exception e) {
            log.error("[Workbench] Failed: {}", e.getMessage(), e);
            emitter.fail(buildStatusMessage(contextId, taskId, "Failed: " + e.getMessage()));
        }
    }

    @Override
    public void cancel(RequestContext ctx, AgentEmitter emitter) throws A2AError {
        emitter.cancel();
    }

    private String handleTopLevelTask(String messageText) throws Exception {
        log.info("[Workbench] Step 1: Load agent cards");
        List<AgentCard> agentCards = loadAgentCardsFromConfig();
        log.info("[Workbench] Loaded {} agent card(s)", agentCards.size());

        log.info("[Workbench] Step 2: Search PSOP workflow");
        String psopId = searchPsop(messageText);
        Workflow workflow = LoadPsop.load(orchUrl, psopId, null, sslVerify);
        log.info("[Workbench] Workflow: {} ({} steps)", workflow.getName(), workflow.getSteps().size());

        log.info("[Workbench] Step 3: Create engine client");
        WorkflowEngineClient engineClient = new DefaultWorkflowEngineClient(
                agentCards, null,
                WorkflowEngineClientConfig.builder()
                        .sslVerify(sslVerify)
                        .a2atEnvPath(a2atEnvPath)
                        .credentialsConfigPath(credentialsPath)
                        .build());

        log.info("[Workbench] Step 4: Pre-position Authorization-T + Notification-T");
        prePositionExtensions(engineClient, agentCards);

        log.info("[Workbench] Step 5: Execute workflow (parallel diagnosis + merge)");
        WorkbenchControlPoint controlPoint = new WorkbenchControlPoint(a2atEnvPath);
        ExecutionResult result = ExecutePsop.builder()
                .psop(workflow)
                .agentCards(agentCards)
                .controlPoint(controlPoint)
                .engineClient(engineClient)
                .runtimeIntent(messageText)
                .lang("zh")
                .sslVerify(sslVerify)
                .credentialsConfigPath(credentialsPath)
                .a2atEnvPath(a2atEnvPath)
                .eventCallback(createEventCallback())
                .onFinish((r, events) -> {
                    log.info("[onFinish] Success={}, Events={}", r.isSuccess(), events.size());
                    return CompletableFuture.completedFuture(null);
                })
                .execute()
                .join();
        return buildResultText(result);
    }

    private static void prePositionExtensions(WorkflowEngineClient engineClient,
                                               List<AgentCard> agentCards) {
        String authInput = "任务类型新增授权，操作名称业务抢通，操作类型光模块更换，"
                + "操作对象SPN专线业务，授权策略OMC自动执行，"
                + "触发执行条件业务投诉诊断确认故障，预期输出返回是否设置成功";
        String notifInput = "通知主题为service-recovery-execution-result，"
                + "订阅条件为业务抢通方案执行结果，"
                + "上报通知数据格式为TextPart";
        for (AgentCard card : agentCards) {
            String name = card.name();
            if (name.contains("Workbench")) {
                continue;
            }
            log.info("[Workbench] Pre-positioning Authorization-T to {}", name);
            engineClient.sendAuthorization(name, "下发授权放行策略", authInput).join();
            log.info("[Workbench] Pre-positioning Notification-T to {}", name);
            engineClient.sendNotification(name, "订阅业务抢通结果通知", notifInput).join();
        }
        log.info("[Workbench] Extension pre-positioning complete");
    }

    private static List<AgentCard> loadAgentCardsFromConfig() {
        Map<String, AgentCard> byName = new LinkedHashMap<>();
        for (String res : AGENT_CARD_RESOURCES) {
            try {
                var url = TransportWorkbenchAgentExecutor.class.getClassLoader().getResource(res);
                if (url != null) {
                    AgentCard card = mapper.readValue(new java.io.File(url.getPath()), AgentCard.class);
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
            List<WorkflowSearchResult> results = LoadPsop.search(orchUrl, messageText, 3, null, sslVerify);
            if (!results.isEmpty()) {
                String psopId = results.get(0).getWorkflowId();
                log.info("[Workbench] Found PSOP: {} (score={})", psopId, results.get(0).getScore());
                return psopId;
            }
        } catch (Exception e) {
            log.warn("[Workbench] PSOP search failed, using fallback: {}", e.getMessage());
        }
        return FALLBACK_PSOP_ID;
    }

    private static String buildResultText(ExecutionResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Workflow execution ").append(result.isSuccess() ? "succeeded" : "failed").append(".\n");
        if (result.getHistory() != null) {
            for (Map<String, Object> h : result.getHistory()) {
                sb.append("- Step: ").append(h.get("step"))
                        .append(", Agent: ").append(h.get("agent"))
                        .append(", Status: ").append(h.get("status")).append("\n");
            }
        }
        if (result.getError() != null) {
            sb.append("Error: ").append(result.getError());
        }
        return sb.toString();
    }

    private EventCallback createEventCallback() {
        return new EventCallback() {
            @Override
            public void onEvent(String type, Map<String, Object> data) {
                switch (type) {
                    case EventType.START -> log.info("  [START] {}", data.get("workflow"));
                    case EventType.STEP_START -> log.info("  [STEP_START] {}", data.get("step"));
                    case EventType.TASK_REQUEST -> log.info("  [TASK_REQUEST] agent={}", data.get("agent"));
                    case EventType.TASK_RESPONSE -> log.info("  [TASK_RESPONSE] agent={}", data.get("agent"));
                    case EventType.AGENT_STATUS_UPDATE -> log.info("  [STATUS_UPDATE] agent={}, state={}, final={}",
                            data.get("agent"), data.get("state"), data.get("is_final"));
                    case EventType.AGENT_ARTIFACT_UPDATE -> log.info("  [ARTIFACT_UPDATE] agent={}, artifact={}",
                            data.get("agent"), data.get("artifact_name"));
                    case EventType.AGENT_MESSAGE_EVENT -> log.info("  [MESSAGE] agent={}, {} chars",
                            data.get("agent"),
                            data.get("text") != null ? ((String) data.get("text")).length() : 0);
                    case EventType.STEP_COMPLETE -> log.info("  [STEP_COMPLETE] {}", data.get("step"));
                    case EventType.ROUTE_DECISION -> log.info("  [ROUTE] {} -> {}", data.get("step"), data.get("next"));
                    case EventType.COMPLETE -> log.info("  [COMPLETE]");
                    case EventType.ERROR -> log.error("  [ERROR] {}", data.get("error"));
                    case EventType.CLOSE -> log.info("  [CLOSE]");
                    default -> {}
                }
            }
        };
    }
}
