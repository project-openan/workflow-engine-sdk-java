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

import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.control.EventType;
import com.openan.a2at.engine.model.ExecutionResult;
import com.openan.a2at.engine.model.Workflow;
import com.openan.a2at.engine.model.WorkflowSearchResult;
import com.openan.a2at.engine.client.DefaultWorkflowEngineClient;
import com.openan.a2at.engine.client.WorkflowEngineClient;
import com.openan.a2at.engine.client.WorkflowEngineClientConfig;
import org.a2aproject.sdk.spec.AgentCard;
import com.openan.a2at.engine.client.AgentCardJacksonModule;
import com.openan.a2at.engine.registry.LoadPsop;
import com.openan.a2at.engine.runner.ExecutePsop;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
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
 * <p>Server-side negotiation-capable (extends {@link NegotiationBaseAgentExecutor}):
 * a top-level task first triggers a Negotiation-T round (INPUT_REQUIRED); on the
 * follow-up it searches the orchestration center for a matching PSOP, loads and
 * executes the workflow (dispatching sub-tasks to OMC agents), and returns the
 * merged result. Sub-tasks (merge_analysis) are handled inline.
 *
 * <p>Agent cards are loaded from classpath JSON (correct Java URLs), avoiding
 * stale registry entries from the Python version.
 */
public class TransportWorkbenchAgentExecutor extends NegotiationBaseAgentExecutor {
    private static final Logger log = LoggerFactory.getLogger(TransportWorkbenchAgentExecutor.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new AgentCardJacksonModule());

    private static final String SUBTASK_MARKER = "## Current Task";
    private static final String MERGE_KEYWORD = "汇总";
    private static final String FALLBACK_PSOP_ID = "psop_spn_cross_city_diagnosis";
    private static final List<String> AGENT_CARD_RESOURCES = List.of(
            "agentcard/spn_domain_agent.json",
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
    protected String resolveEnvPath() {
        return a2atEnvPath;
    }

    @Override
    protected String executeBusiness(RequestContext ctx, AgentEmitter emitter, String input) {
        String taskId = ctx.getTaskId();
        String contextId = ctx.getContextId();
        try {
            String responseText = input.contains(SUBTASK_MARKER)
                    ? handleSubTask(input)
                    : handleTopLevelTask(input);
            return responseText;
        } catch (Exception e) {
            log.error("[Workbench-Agent] Business failed: {}", e.getMessage(), e);
            return "Workbench execution failed: " + e.getMessage();
        }
    }

    @Override
    protected String defaultNegotiationText() {
        return "工作台需确认跨城故障处置范围与授权后再执行协同诊断，请补充。";
    }

    @Override
    protected String defaultNegotiationConcern() {
        return "workbench needs scope confirmation before orchestration";
    }

    // ------------------------------------------------------------------
    // Top-level task: search PSOP, load workflow, execute
    // ------------------------------------------------------------------

    private String handleTopLevelTask(String messageText) throws Exception {
        log.info("[Workbench-Agent] Top-level task, searching PSOP...");
        List<AgentCard> agentCards = loadAgentCardsFromConfig();
        log.info("[Workbench-Agent] Loaded {} agent card(s) from config", agentCards.size());
        String psopId = searchPsop(messageText);
        Workflow workflow = LoadPsop.load(orchUrl, psopId, null, sslVerify);
        log.info("[Workbench-Agent] Workflow: {} ({} steps)", workflow.getName(), workflow.getSteps().size());

        // Create engine client explicitly so we can pre-position extensions
        // (Authorization-T + Notification-T) before starting the workflow.
        WorkflowEngineClient engineClient = new DefaultWorkflowEngineClient(
                agentCards, null,
                WorkflowEngineClientConfig.builder()
                        .sslVerify(sslVerify)
                        .a2atEnvPath(a2atEnvPath)
                        .credentialsConfigPath(credentialsPath)
                        .build());

        // Pre-position Authorization-T and Notification-T to all SPN agents.
        // These are one-shot operations: the workbench sends the authorization
        // policy and notification subscription upfront. The act of sending
        // the Authorization-T message means the policies are default-approved
        // (whitelist authorization, no customer takeover needed).
        prePositionExtensions(engineClient, agentCards);
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
        return buildResultText(result, controlPoint);
    }

    private static void prePositionExtensions(WorkflowEngineClient engineClient,
                                               List<AgentCard> agentCards) {
        String authUri = "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Authorization-T/v1";
        String notifUri = "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Notification-T/v1";
        // Natural-language input for the A2A-T SDK prompt generation (LLM + template).
        // For Notification-T the SDK has a "subscribe_incident" scenario that renders
        // the subscription template. For Authorization-T the SDK has no scenario yet,
        // so generation will fall back to using the input text as-is.
        String authInput = "任务类型新增授权，操作名称业务抢通，操作类型光模块更换，操作对象SPN专线业务，授权策略OMC自动执行，触发执行条件业务投诉诊断确认故障，预期输出返回是否设置成功";
        String notifInput = "通知主题为service-recovery-execution-result，订阅条件为业务抢通方案执行结果，上报通知数据格式为TextPart";
        for (AgentCard card : agentCards) {
            String name = card.name();
            if (name == null || name.contains("Workbench")) {
                continue;
            }
            log.info("[Workbench-Agent] Pre-positioning Authorization-T to {}", name);
            engineClient.sendExtensionMessage(name, "下发授权放行策略",
                    authInput, authUri).join();
            log.info("[Workbench-Agent] Pre-positioning Notification-T to {}", name);
            engineClient.sendExtensionMessage(name, "订阅业务抢通结果通知",
                    notifInput, notifUri).join();
        }
        log.info("[Workbench-Agent] Extension pre-positioning complete");
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
                log.info("[Workbench-Agent] Found PSOP: {} (score={})", psopId, results.get(0).getScore());
                return psopId;
            }
        } catch (Exception e) {
            log.warn("[Workbench-Agent] PSOP search failed, using fallback: {}", e.getMessage());
        }
        return FALLBACK_PSOP_ID;
    }

    private static String buildResultText(ExecutionResult result, WorkbenchControlPoint controlPoint) {
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
        sb.append("\nPre-positioned Authorization-T whitelist + Notification-T subscription");
        sb.append("Recovery self-triggered by SPN agents via whitelist policy");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Sub-task: merge_analysis (Workbench Agent processes its own step)
    // ------------------------------------------------------------------

    private String handleSubTask(String messageText) {
        log.info("[Workbench-Agent] Sub-task received (from workflow executor)");
        if (!messageText.contains(MERGE_KEYWORD)) {
            return "Sub-task processed: " + messageText;
        }
        return analyzeFaultLocation(messageText);
    }

    private static String analyzeFaultLocation(String messageText) {
        String mergeResult = "汇总分析完成。";
        boolean hasShanghaiFault = messageText.contains("上海")
                && (messageText.contains("故障") || messageText.contains("Down"));
        boolean hasGuangzhouFault = messageText.contains("广州")
                && (messageText.contains("故障") || messageText.contains("Down"));

        if (hasShanghaiFault) {
            mergeResult += "故障定位：上海地市OMC，端口Down，"
                    + "光功率-28dBm低于阈值。需更换光模块抢通。";
        } else if (hasGuangzhouFault) {
            mergeResult += "故障定位：广州地市OMC。需排查并抢通。";
        } else {
            mergeResult += "两地市均未见异常。";
        }
        log.info("[Workbench-Agent] Merge result: {}", mergeResult);
        return mergeResult;
    }

    // ------------------------------------------------------------------
    // Event callback
    // ------------------------------------------------------------------

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
                    case EventType.AGENT_ARTIFACT_UPDATE -> log.info("  [ARTIFACT_UPDATE] agent={}, artifact={}, chunks={}",
                            data.get("agent"), data.get("artifact_name"), data.get("last_chunk"));
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
