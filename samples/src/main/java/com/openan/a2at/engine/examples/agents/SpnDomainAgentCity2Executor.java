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

import com.openan.a2at.engine.examples.StartAgentsServer;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * SPN Domain Agent for City2 (Western Guangdong / Yuexi OMC).
 *
 * <p>Server-side negotiation-capable (extends {@link NegotiationBaseAgentExecutor}).
 * Yuexi side is NORMAL. Diagnosis/recovery text is LLM-generated when
 * the A2A-T .env is configured, else deterministic. Authorization-T and
 * Notification-T are pre-positioned before the workflow starts.
 */
public class SpnDomainAgentCity2Executor extends NegotiationBaseAgentExecutor {
    private static final Logger log = LoggerFactory.getLogger(SpnDomainAgentCity2Executor.class);

    private static final String NORMAL_DIAGNOSIS_RESULT =
            "诊断结果：粤西城市OMC诊断结果 - 端口状态正常，光功率-17dBm(正常范围)，无异常告警。\n"
            + "修复建议：粤西城市无需修复，故障不在此地市。\n"
            + "故障根因：无根因(此地市正常)。";

    private static final String RECOVERY_RESULT =
            "粤西侧OMC抢通完成，业务恢复正常。";

    @Override
    protected String resolveEnvPath() {
        return StartAgentsServer.resolveEnvPath();
    }

   @Override
   protected String executeBusiness(RequestContext ctx, AgentEmitter emitter, String input) {
        String result = llmDiagnosisResult(input, NORMAL_DIAGNOSIS_RESULT);
        log.info("[SPN-Domain-Agent-City2] Diagnosis complete (Yuexi), no fault, no recovery needed");
        return result;
    }
    @Override
    protected String defaultNegotiationText() {
        return "粤西OMC诊断需要确认客户专线故障是否涉及粤西侧端口，请补充。";
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

    private static String llmDiagnosisResult(String input, String fallback) {
        String env = StartAgentsServer.resolveEnvPath();
        String sys = "你是SPN领域粤西OMC故障诊断专家。根据输入诊断信息，粤西侧端口正常、光功率-17dBm(正常范围)、无异常告警。输出诊断结果：粤西无需修复、故障不在此地市。简洁专业，中文。";
        String user = "输入：\n" + input + "\n\n粤西OMC端口port-3=UP，光功率-17dBm，无告警。请输出诊断结论。";
        return LlmHelper.text(env, sys, user, fallback);
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
