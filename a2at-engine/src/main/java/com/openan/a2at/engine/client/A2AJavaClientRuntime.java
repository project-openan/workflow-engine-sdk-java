/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.openan.a2at.engine.client;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.MessageSendParams;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Runtime seam for sending A2A messages via the a2a-java SDK's
 * {@link org.a2aproject.sdk.client.Client}.
 *
 * <p>The engine's {@link DefaultWorkflowEngineClient} delegates message
 * sending to this runtime when one is provided (or auto-created). This
 * avoids hand-written HTTP code and reuses the SDK's transport, SSE
 * parsing, and error handling.
 *
 * <p>Mirrors {@code A2AJavaClientRuntime} from the a2a-t-sdk-java
 * sample module, but lives in the engine package so the engine can
 * depend on it directly.
 */
public interface A2AJavaClientRuntime {

    /**
     * Send a message to an agent and collect the streaming events.
     *
     * @param agentCard   the target agent's card as a map (from config or registry)
     * @param params      the message send parameters (message, context, metadata)
     * @param callContext client call context with auth/extension headers
     * @param eventSink   optional callback invoked for each intermediate event (status updates,
     *                    artifact updates, messages). Null = no real-time forwarding.
     * @param logSink     optional log consumer for SDK diagnostics
     * @return an iterable of {@link ClientEvent} produced by the agent
     */
    Iterable<ClientEvent> sendMessage(
            AgentCard agentCard,
            MessageSendParams params,
            ClientCallContext callContext,
            Consumer<ClientEvent> eventSink,
            Consumer<String> logSink);

    /**
     * Release any cached resources (e.g. HTTP clients).
     */
    void close();
}
