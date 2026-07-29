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

package com.openan.a2at.engine.examples.spring;

import com.openan.a2at.engine.client.A2ATExtension;
import com.openan.a2at.engine.examples.agents.BaseAgentExecutor;
import com.openan.a2at.engine.examples.agents.WorkbenchOrchestrator;

import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring-managed Workbench AgentExecutor.
 *
 * <p>Same business logic as {@link
 * com.openan.a2at.engine.examples.agents.TransportWorkbenchAgentExecutor} -- receives a Task-T from
 * the upper layer, delegates to {@link WorkbenchOrchestrator} for the full pipeline, and returns
 * the result. The difference is purely the server container: this runs inside Spring Boot instead
 * of the JDK HttpServer-based {@code JdkHttpA2AServer}.
 */
@Component
public class SpringWorkbenchExecutor extends BaseAgentExecutor {
    private static final Logger log = LoggerFactory.getLogger(SpringWorkbenchExecutor.class);

    @Value("${a2a.orch-url:https://127.0.0.1:5001}")
    private String orchUrl;

    @Value("${a2a.credentials-path:}")
    private String credentialsPath;

    @Value("${a2a.ssl-verify:false}")
    private boolean sslVerify;

    @Value("${a2a.a2at-env-path:}")
    private String a2atEnvPath;

    @Override
    public void execute(RequestContext ctx, AgentEmitter emitter) throws A2AError {
        String taskId = ctx.getTaskId();
        String contextId = ctx.getContextId();
        String input = extractText(ctx.getMessage());
        log.info(
                "[SpringWorkbench] Received task: taskId={}, text={} chars",
                taskId,
                input.length());

        emitter.submit(buildStatusMessage(contextId, taskId, "Task received"));
        emitter.startWork(buildStatusMessage(contextId, taskId, "Processing"));

        try {
            String result =
                    new WorkbenchOrchestrator(orchUrl, credentialsPath, sslVerify, a2atEnvPath)
                            .run(input);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put(A2ATExtension.TASK_T.uri(), result);
            List<Part<?>> parts = List.of(new TextPart("SPN cross-city fault diagnosis summary"));
            emitter.addArtifact(
                    parts, "result", "cross-city-diagnosis-summary", metadata, false, true);
            emitter.complete(buildStatusMessage(contextId, taskId, "Completed"));
            log.info("[SpringWorkbench] Task completed");
        } catch (Exception e) {
            log.error("[SpringWorkbench] Failed: {}", e.getMessage(), e);
            emitter.fail(buildStatusMessage(contextId, taskId, "Failed: " + e.getMessage()));
        }
    }

    @Override
    public void cancel(RequestContext ctx, AgentEmitter emitter) throws A2AError {
        emitter.cancel();
    }
}
