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

package dev.openan.workflow.engine.examples.agents;

import dev.openan.workflow.engine.client.LlmHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Negotiation clarification strategy for the SPN cross-city workbench.
 *
 * <p>Single responsibility: when a downstream agent returns INPUT_REQUIRED (Negotiation-T),
 * generate a clarification text that supplements the missing parameters. Uses LLM when the A2A-T
 * .env is configured, with a deterministic fallback.
 *
 * <p>Extracted from {@code WorkbenchControlPoint.onNegotiation} so that the control point only
 * cares about workflow orchestration (task dispatch, routing), while negotiation policy evolves
 * independently.
 */
public class NegotiationStrategy implements dev.openan.workflow.engine.control.NegotiationStrategy {

    private static final Logger log = LoggerFactory.getLogger(NegotiationStrategy.class);

    private final String a2atEnvPath;

    public NegotiationStrategy(String a2atEnvPath) {
        this.a2atEnvPath = a2atEnvPath;
    }

    /**
     * Generate a clarification for the given negotiation request.
     *
     * @param agentName the agent requesting negotiation
     * @param negotiationText the concern/question raised by the agent
     * @param receiveResult the raw negotiation context (may be null/empty)
     * @return clarification text to send back to the agent
     */
    public CompletableFuture<String> resolve(
            String agentName, String negotiationText, Map<String, Object> receiveResult) {
        log.info("[NegotiationStrategy] agent={}: {}", agentName, negotiationText);
        String fallback = "根据工作台上下文，客户A粤东-粤西间SPN专线中断，" + "粤东OMC告警端口Down，光功率-28dBm。";
        String sys =
                "你是SPN跨城专线故障工作台的协商澄清专家。根据协商请求，"
                        + "补充客户A粤东-粤西间SPN专线中断的上下文"
                        + "（粤东OMC告警端口Down、光功率-28dBm）。中文。";
        String clarification = LlmHelper.text(a2atEnvPath, sys, negotiationText, fallback);
        return CompletableFuture.completedFuture(clarification);
    }
}
