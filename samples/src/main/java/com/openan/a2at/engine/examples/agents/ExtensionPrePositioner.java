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
        this.authInput = "\u4efb\u52a1\u7c7b\u578b\u65b0\u589e\u6388\u6743\uff0c\u64cd\u4f5c\u540d\u79f0\u4e1a\u52a1\u62a2\u901a\uff0c"
                + "\u64cd\u4f5c\u7c7b\u578b\u5149\u6a21\u5757\u66f4\u6362\uff0c"
                + "\u64cd\u4f5c\u5bf9\u8c61SPN\u4e13\u7ebf\u4e1a\u52a1\uff0c\u6ea2\u6743\u7b56\u7565OMC\u81ea\u52a8\u6267\u884c\uff0c"
                + "\u89e6\u53d1\u6267\u884c\u6761\u4ef6\u4e1a\u52a1\u6295\u8bc9\u8bca\u65ad\u786e\u8ba4\u6545\u969c\uff0c"
                + "\u9884\u671f\u8f93\u51fa\u8fd4\u56de\u662f\u5426\u6210\u529f\u3002";
        this.notifInput = "\u901a\u77e5\u4e3b\u9898\u4e3aservice-recovery-execution-result\uff0c"
                + "\u8ba2\u9605\u6761\u4ef6\u4e3a\u4e1a\u52a1\u62a2\u901a\u65b9\u6848\u6267\u884c\u7ed3\u679c\uff0c"
                + "\u4e0a\u62a5\u901a\u77e5\u6570\u636e\u683c\u5f0f\u4e3aTextPart\u3002";
    }

    /**
     * Pre-position Authorization-T + Notification-T to every non-workbench agent.
     */
    public void prePosition(WorkflowEngineClient engineClient, List<AgentCard> agentCards) {
        for (AgentCard card : agentCards) {
            String name = card.name();
            if (name.contains("Workbench")) {
                continue;
            }
            log.info("[PrePosition] Authorization-T to {}", name);
            engineClient.sendAuthorization(name, "\u4e0b\u53d1\u6388\u6743\u653e\u884c\u7b56\u7565", authInput).join();
            log.info("[PrePosition] Notification-T to {}", name);
            engineClient.sendNotification(name, "\u8ba2\u9605\u4e1a\u52a1\u62a2\u901a\u7ed3\u679c\u901a\u77e5", notifInput).join();
        }
        log.info("[PrePosition] Extension pre-positioning complete");
    }
}
