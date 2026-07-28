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

import com.openan.a2at.engine.client.WorkflowEngineClient;
import com.openan.a2at.engine.client.ExtensionSender;
import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Pre-positions Authorization-T and Notification-T to downstream agents.
 *
 * <p>Single responsibility: send the two pre-positioning control messages
 * (whitelist policy + result subscription) to each non-workbench agent
 * <b>before</b> the workflow starts. This is a separate concern from
 * workflow execution and from negotiation -- the orchestrator calls this
 * once, then proceeds to run the PSOP workflow.
 */
public class ExtensionPrePositioner {

    private static final Logger log = LoggerFactory.getLogger(ExtensionPrePositioner.class);

    private final String authInput;
    private final String notifInput;

    public ExtensionPrePositioner() {
        this.authInput = "任务类型新增授权，操作名称业务抢通，"
                + "操作类型光模块更换，"
                + "操作对象SPN专线业务，溢权策略OMC自动执行，"
                + "触发执行条件业务投诉诊断确认故障，"
                + "预期输出返回是否成功。";
        this.notifInput = "通知主题为service-recovery-execution-result，"
                + "订阅条件为业务抢通方案执行结果，"
                + "上报通知数据格式为TextPart。";
    }

    /**
     * Pre-position Authorization-T + Notification-T to every non-workbench agent.
     */
    public void prePosition(ExtensionSender sender, List<AgentCard> agentCards) {
        for (AgentCard card : agentCards) {
            String name = card.name();
            if (name.contains("Workbench")) {
                continue;
            }
            log.info("[PrePosition] Authorization-T to {}", name);
            sender.sendAuthorization(name, "下发授权放行策略", authInput).join();
            log.info("[PrePosition] Notification-T to {}", name);
            sender.sendNotification(name, "订阅业务抢通结果通知", notifInput).join();
        }
        log.info("[PrePosition] Extension pre-positioning complete");
    }
}
