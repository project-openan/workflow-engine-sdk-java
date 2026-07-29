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

import com.openan.a2at.engine.client.LlmHelper;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SPN Domain Agent for City1 (Eastern Guangdong / Yuedong OMC).
 *
 * <p>Server-side negotiation-capable (extends {@link NegotiationBaseAgentExecutor}): on a new task
 * it replies INPUT_REQUIRED to start a Negotiation-T round, and on the follow-up it runs the
 * diagnosis/recovery business. Yuedong side has a FAULT (port Down, optical power -28dBm).
 * Diagnosis/recovery text is LLM-generated (deepseek) when the A2A-T .env is configured, else
 * deterministic. Authorization-T and Notification-T are pre-positioned before the workflow starts,
 * so this agent no longer injects them in response metadata.
 */
public class SpnDomainAgentCity1Executor extends NegotiationBaseAgentExecutor {
    private static final Logger log = LoggerFactory.getLogger(SpnDomainAgentCity1Executor.class);

    private static final String FAULT_DIAGNOSIS_RESULT =
            "诊断结果：粤东城市OMC诊断结果 - 端口Down，光功率-28dBm(低于阈值)，存在故障。\n"
                    + "修复方案：更换粤东OMC端口光模块，恢复端口Down状态。此修复方案需授权后执行（授权已预置）。\n"
                    + "故障根因：粤东OMC端口光模块故障。";

    private static final String RECOVERY_RESULT = "粤东OMC端口光模块已更换，端口恢复Up，专线业务恢复正常。";

    private static String llmDiagnosisResult(String input, String fallback) {
        String env = EnvResolver.resolveEnvPath();
        String sys = "你是SPN领域粤东OMC故障诊断专家。根据输入诊断信息，输出诊断结果、修复方案和故障根因，提及粤东。简洁专业，中文。";
        String user = "输入：\n" + input + "\n\n已知：粤东OMC端口port-7=DOWN，光功率-28dBm（阈值-20dBm）。请输出诊断结论。";
        return LlmHelper.text(env, sys, user, fallback);
    }

    private static String llmRecoveryResult(String input, String fallback) {
        String env = EnvResolver.resolveEnvPath();
        String sys = "你是SPN领域粤东OMC抢通执行专家。用一句话报告抢通成功结果，提及粤东OMC端口恢复Up、专线业务恢复。中文。";
        String user = "输入：\n" + input + "\n\n已更换光模块，端口恢复Up。请输出抢通结果。";
        return LlmHelper.text(env, sys, user, fallback);
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    protected boolean needsNegotiation(String input) {
        return true;
    }

    @Override
    protected String resolveEnvPath() {
        return EnvResolver.resolveEnvPath();
    }

    @Override
    protected String executeBusiness(RequestContext ctx, AgentEmitter emitter, String input) {
        String diagnosisResult = llmDiagnosisResult(input, FAULT_DIAGNOSIS_RESULT);
        String recoveryResult = selfTriggerRecovery(ctx, diagnosisResult);
        return diagnosisResult + "\n\n" + recoveryResult;
    }

    /**
     * After diagnosis, check the pre-positioned Authorization-T whitelist policy. If the repair
     * action matches the whitelist, execute recovery and return the result (reported as
     * Notification-T metadata by the base class). If not in whitelist, return a refusal message.
     */
    private String selfTriggerRecovery(RequestContext ctx, String diagnosisResult) {
        String policy = getAuthorizationPolicy();
        boolean inWhitelist =
                policy != null
                        && !policy.isEmpty()
                        && (policy.contains("业务抢通")
                                || policy.contains("光模块")
                                || policy.contains("授权"));
        if (inWhitelist) {
            log.info("[SPN-Domain-Agent] Fault in whitelist, self-triggering recovery");
            String recoveryResult = llmRecoveryResult(diagnosisResult, RECOVERY_RESULT);
            log.info(
                    "[SPN-Domain-Agent] Recovery result reported via Notification-T: {}",
                    recoveryResult);
            pushNotificationResult(recoveryResult);
            return recoveryResult;
        }
        log.info("[SPN-Domain-Agent] Fault not in whitelist, refusing recovery");
        String refusalResult = "操作不在白名单内，拒绝执行抢通。";
        pushNotificationResult(refusalResult);
        return refusalResult;
    }

    @Override
    protected String defaultNegotiationText() {
        return "粤东OMC诊断需要确认客户专线故障的详细端口信息后再执行，请补充。";
    }

    @Override
    protected Map<String, Object> buildResponseMetadata(RequestContext ctx, String response) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(NegotiationUtils.TASK_PROMPT_KEY, response);
        return metadata;
    }

    @Override
    protected String buildResultSummary() {
        return "SPN专线故障诊断结果";
    }

    @Override
    protected String buildArtifactName() {
        return "spn-fault-diagnosis";
    }
}
