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

import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transport Workbench Agent - the orchestrator.
 *
 * <p>Single responsibility: <b>agent server I/O</b>. Receives a Task-T from the upper layer,
 * delegates the full orchestration pipeline to {@link WorkbenchOrchestrator}, and returns the
 * result. This class does NOT contain workflow logic, pre-positioning, or negotiation strategy --
 * those live in dedicated classes.
 */
public class TransportWorkbenchAgentExecutor extends BaseAgentExecutor {
    private static final Logger log =
            LoggerFactory.getLogger(TransportWorkbenchAgentExecutor.class);

    private final String orchUrl;
    private final String credentialsPath;
    private final boolean sslVerify;
    private final String a2atEnvPath;

    public TransportWorkbenchAgentExecutor(
            String registryUrl, String orchUrl, String credentialsPath, boolean sslVerify) {
        this(registryUrl, orchUrl, credentialsPath, sslVerify, null);
    }

    public TransportWorkbenchAgentExecutor(
            String registryUrl,
            String orchUrl,
            String credentialsPath,
            boolean sslVerify,
            String a2atEnvPath) {
        this.orchUrl = orchUrl;
        this.credentialsPath = credentialsPath;
        this.sslVerify = sslVerify;
        this.a2atEnvPath = a2atEnvPath;
    }

    @Override
    public void execute(RequestContext ctx, AgentEmitter emitter) throws A2AError {
        String taskId = ctx.getTaskId();
        String contextId = ctx.getContextId();
        String input = extractText(ctx.getMessage());
        log.info("[Workbench] Received task: taskId={}, text={} chars", taskId, input.length());
        emitter.submit(buildStatusMessage(contextId, taskId, "Task received"));
        emitter.startWork(buildStatusMessage(contextId, taskId, "Processing"));
        try {
            String result =
                    new WorkbenchOrchestrator(orchUrl, credentialsPath, sslVerify, a2atEnvPath)
                            .run(input);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put(NegotiationUtils.TASK_PROMPT_KEY, result);
            List<Part<?>> parts = List.of(new TextPart("跨城故障协同诊断汇总结果"));
            emitter.addArtifact(
                    parts, "result", "cross-city-diagnosis-summary", metadata, false, true);
            emitter.complete(buildStatusMessage(contextId, taskId, "Completed"));
            log.info("[Workbench] Task completed");
        } catch (Exception e) {
            log.error("[Workbench] Failed: {}", e.getMessage(), e);
            emitter.fail(buildStatusMessage(contextId, taskId, "Failed: " + e.getMessage()));
        }
    }

    @Override
    public void cancel(RequestContext ctx, AgentEmitter emitter) throws A2AError {
        emitter.cancel();
    }
}
