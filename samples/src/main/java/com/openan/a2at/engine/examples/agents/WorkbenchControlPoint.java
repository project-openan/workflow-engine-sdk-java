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

import com.openan.a2at.engine.client.WorkflowEngineClient;
import com.openan.a2at.engine.control.ControlPoint;
import com.openan.a2at.engine.control.DefaultControlPoint;
import com.openan.a2at.engine.model.JumpCondition;
import com.openan.a2at.engine.model.RouteDecision;
import com.openan.a2at.engine.model.TaskRequest;
import com.openan.a2at.engine.model.TaskResponse;
import com.openan.a2at.engine.examples.StartAgentsServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * ControlPoint for the SPN cross-city diagnosis workflow.
 *
 * <p>Handles task dispatch (with city-specific message enrichment),
 * route decisions (fault-based routing to recovery steps), and negotiation
 * responses. Authorization-T and Notification-T are pre-positioned
 * before the workflow starts, not handled here.
 *
 * <p>SRP: this class only contains workflow decision logic, separating
 * it from the agent executor that handles message I/O.
 */
public class WorkbenchControlPoint extends DefaultControlPoint {
    private static final Logger log = LoggerFactory.getLogger(WorkbenchControlPoint.class);

    private final String a2atEnvPath;

    public WorkbenchControlPoint() {
        this(null);
    }

    public WorkbenchControlPoint(String a2atEnvPath) {
        this.a2atEnvPath = a2atEnvPath != null ? a2atEnvPath : StartAgentsServer.resolveEnvPath();
    }

    @Override
    public CompletableFuture<TaskResponse> onTask(
            TaskRequest request, WorkflowEngineClient engineClient) {
        String step = request.getStepName();
        String agentName = request.getAgentName();
        String enrichedMessage = enrichMessageForStep(request.getMessage(), step);
        return engineClient.sendMessage(agentName, enrichedMessage)
                .thenApply(r -> {
                    boolean success = r.getText() != null && !r.getText().isEmpty();
                    log.info("[onTask] Response from {}: {} chars, success={}",
                            agentName, r.getText() != null ? r.getText().length() : 0, success);
                    return TaskResponse.builder().success(success).output(r.getText()).build();
                })
                .exceptionally(e -> {
                    log.error("[onTask] Failed for {}: {}", agentName, e.getMessage());
                    return TaskResponse.builder()
                            .success(false)
                            .error("Agent call failed: " + e.getMessage())
                            .build();
                });
    }

    @Override
    public CompletableFuture<TaskResponse> onSelfTask(TaskRequest request) {
        String step = request.getStepName();
        log.info("[onSelfTask] Self-loop step={}, agent={} (local merge, no A2A-T)", step, request.getAgentName());
        String message = request.getMessage();
        String fallback = analyzeFaultLocation(message);
        String sys = "你是SPN跨城故障协同诊断汇总专家。根据两地市OMC诊断结果，输出故障定位结论。简洁专业，中文。";
        String result = LlmHelper.text(a2atEnvPath, sys, message, fallback);
        log.info("[onSelfTask] Merge result ({} chars): {}", result.length(), result);
        return CompletableFuture.completedFuture(
                TaskResponse.builder().success(true).output(result).build());
    }

    private static String analyzeFaultLocation(String messageText) {
        boolean hasYuedongFault = messageText.contains("粤东")
                && (messageText.contains("故障") || messageText.contains("Down"));
        boolean hasYuexiFault = messageText.contains("粤西")
                && (messageText.contains("故障") || messageText.contains("Down"));
        if (hasYuedongFault) {
            return "汇总分析完成。故障定位：粤东地市OMC，端口Down，光功率-28dBm低于阈值。";
        }
        if (hasYuexiFault) {
            return "汇总分析完成。故障定位：粤西地市OMC，需排查。";
        }
        return "汇总分析完成。两地市均未见异常。";
    }

    private static String enrichMessageForStep(String message, String step) {
        return switch (step) {
            case "diagnosis_city1" -> message + "\n\n## 城市差异化参数\n"
                    + "客户A粤东-粤西间SPN专线中断，粤东OMC告警端口Down，光功率-28dBm低于阈值。"
                    + "端口所属单板line-card-03，端口号port-7。";
            case "diagnosis_city2" -> message + "\n\n## 城市差异化参数\n"
                    + "客户A粤东-粤西间SPN专线中断，粤西OMC侧需排查端口状态和光功率是否正常。";
default -> message;
        };
    }

    @Override
    public CompletableFuture<RouteDecision> onRoute(
            String stepName, Map<String, Object> results,
            List<JumpCondition> conditions) {
        // merge_analysis has an unconditional next -> endNode, so the executor
        // never calls onRoute for it. Recovery is self-triggered by SPN agents
        // via the pre-positioned Authorization-T whitelist and reported through
        // the Notification-T channel. Just delegate to the default routing.
        return super.onRoute(stepName, results, conditions);
    }

    @Override
    public CompletableFuture<String> onNegotiation(
            String agentName, String negotiationText, Map<String, Object> receiveResult) {
        log.info("[onNegotiation] agent={}: {}", agentName, negotiationText);
        String fallback = "根据工作台上下文，客户A粤东-粤西间SPN专线中断，"
                + "粤东OMC告警端口Down，光功率-28dBm。";
        String sys = "你是SPN跨城专线故障工作台的协商澄清专家。根据协商请求，补充客户A粤东-粤西间SPN专线中断的上下文（粤东OMC告警端口Down、光功率-28dBm）。中文。";
        String clarification = LlmHelper.text(a2atEnvPath, sys, negotiationText, fallback);
        return CompletableFuture.completedFuture(clarification);
    }

}
