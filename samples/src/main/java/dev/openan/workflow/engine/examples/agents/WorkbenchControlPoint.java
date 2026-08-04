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

package dev.openan.workflow.engine.examples.agents;

import dev.openan.workflow.engine.client.LlmHelper;
import dev.openan.workflow.engine.client.WorkflowEngineClient;
import dev.openan.workflow.engine.control.DefaultControlPoint;
import dev.openan.workflow.engine.model.JumpCondition;
import dev.openan.workflow.engine.model.RouteDecision;
import dev.openan.workflow.engine.model.TaskRequest;
import dev.openan.workflow.engine.model.TaskResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * ControlPoint for the SPN cross-city diagnosis workflow.
 *
 * <p>Handles task dispatch (with city-specific message enrichment), route decisions (fault-based
 * routing to recovery steps), and negotiation responses. Authorization-T and Notification-T are
 * pre-positioned before the workflow starts, not handled here.
 *
 * <p>SRP: this class only contains workflow decision logic, separating it from the agent executor
 * that handles message I/O.
 */
public class WorkbenchControlPoint extends DefaultControlPoint {
    private static final Logger log = LoggerFactory.getLogger(WorkbenchControlPoint.class);

    private final String a2atEnvPath;
    private final NegotiationStrategy negotiationStrategy;

    public WorkbenchControlPoint() {
        this(null, null);
    }

    public WorkbenchControlPoint(String a2atEnvPath) {
        this(a2atEnvPath, null);
    }

    public WorkbenchControlPoint(String a2atEnvPath, NegotiationStrategy negotiationStrategy) {
        this.a2atEnvPath = a2atEnvPath != null ? a2atEnvPath : EnvResolver.resolveEnvPath();
        this.negotiationStrategy =
                negotiationStrategy != null
                        ? negotiationStrategy
                        : new NegotiationStrategy(this.a2atEnvPath);
    }

    private static String analyzeFaultLocation(String messageText) {
        boolean hasYuedongFault =
                messageText.contains("粤东")
                        && (messageText.contains("故障") || messageText.contains("Down"));
        boolean hasYuexiFault =
                messageText.contains("粤西")
                        && (messageText.contains("故障") || messageText.contains("Down"));
        if (hasYuedongFault) {
            return "汇总分析完成。故障定位：粤东地市OMC，端口Down，光功率-28dBm低于阈值。";
        }
        if (hasYuexiFault) {
            return "汇总分析完成。故障定位：粤西地市OMC，需排查。";
        }
        return "汇总分析完成。两地市均未见异常。";
    }

    /**
     * Build a targeted task message containing ONLY the parameters relevant to the target agent's
     * city. The upstream context (which mixes both cities' info) is NOT forwarded -- each sub-agent
     * receives a clean, self-contained task description with only its own scope.
     */
    private static String buildTargetedTaskMessage(String step) {
        return switch (step) {
            case "diagnosis_city1" -> buildCity1Task();
            case "diagnosis_city2" -> buildCity2Task();
            default -> "请执行SPN专线故障诊断任务。";
        };
    }

    private static String buildCity1Task() {
        return
"""
## 任务
SPN专线故障诊断 - 粤东OMC侧

## 故障参数
- 端口状态：port-7 = DOWN
- 光功率：-28dBm（阈值-20dBm）
- 所属单板：line-card-03
- 端口号：port-7

请针对粤东OMC侧端口故障进行诊断。请用中文回复。"""
                .stripIndent();
    }

    private static String buildCity2Task() {
        return
"""
## 任务
SPN专线故障诊断 - 粤西OMC侧

## 排查要求
- 排查粤西OMC端口状态是否正常
- 确认光功率是否在正常范围
- 确认是否存在异常告警

请针对粤西OMC侧进行排查。请用中文回复。"""
                .stripIndent();
    }

    @Override
    public CompletableFuture<TaskResponse> onTask(
            TaskRequest request, WorkflowEngineClient engineClient) {
        String step = request.getStepName();
        String agentName = request.getAgentName();
        String targetedMessage = buildTargetedTaskMessage(step);
        return engineClient
                .sendMessage(agentName, targetedMessage)
                .thenApply(
                        r -> {
                            boolean success = r.getText() != null && !r.getText().isEmpty();
                            log.info(
                                    "[onTask] Response from {}: {} chars, success={}",
                                    agentName,
                                    r.getText() != null ? r.getText().length() : 0,
                                    success);
                            return TaskResponse.builder()
                                    .success(success)
                                    .output(r.getText())
                                    .build();
                        })
                .exceptionally(
                        e -> {
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
        log.info(
                "[onSelfTask] Self-loop step={}, agent={} (local merge, no A2A-T)",
                step,
                request.getAgentName());
        String message = request.getMessage();
        String fallback = analyzeFaultLocation(message);
        String sys = "你是SPN跨城故障协同诊断汇总专家。根据两地市OMC诊断结果，输出故障定位结论。简洁专业，中文。";
        String result = LlmHelper.text(a2atEnvPath, sys, message, fallback);
        log.info("[onSelfTask] Merge result ({} chars): {}", result.length(), result);
        return CompletableFuture.completedFuture(
                TaskResponse.builder().success(true).output(result).build());
    }

    @Override
    public CompletableFuture<RouteDecision> onRoute(
            String stepName, Map<String, Object> results, List<JumpCondition> conditions) {
        // merge_analysis has an unconditional next -> endNode, so the executor
        // never calls onRoute for it. Recovery is self-triggered by SPN agents
        // via the pre-positioned Authorization-T whitelist and reported through
        // the Notification-T channel. Just delegate to the default routing.
        return super.onRoute(stepName, results, conditions);
    }

    @Override
    public CompletableFuture<String> onNegotiation(
            String agentName, String negotiationText, Map<String, Object> receiveResult) {
        return negotiationStrategy.resolve(agentName, negotiationText, receiveResult);
    }
}
