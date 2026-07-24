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

import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;

import java.util.UUID;

/**
 * Shared base class for sample agent executors.
 *
 * <p>Provides common utilities for text extraction and status message
 * construction that all agent executors need.
 */
public abstract class BaseAgentExecutor implements AgentExecutor {

    protected static String extractText(Message message) {
        if (message == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Part<?> part : message.parts()) {
            if (part instanceof TextPart tp) {
                sb.append(tp.text());
            }
        }
        return sb.toString();
    }

    protected static Message buildStatusMessage(String contextId, String taskId, String text) {
        return Message.builder()
                .messageId(UUID.randomUUID().toString())
                .contextId(contextId)
                .taskId(taskId)
                .role(Message.Role.ROLE_AGENT)
                .parts(new TextPart(text))
                .build();
    }
}
