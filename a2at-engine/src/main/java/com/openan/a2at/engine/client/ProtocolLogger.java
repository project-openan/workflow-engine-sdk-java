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

package com.openan.a2at.engine.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Logs complete A2A protocol messages (headers + body) for protocol-level verification against real
 * network captures. Uses a dedicated "PROTOCOL" logger so output can be independently enabled or
 * suppressed via logging configuration.
 *
 * <p>Request side: serializes {@link MessageSendParams} to pretty-printed JSON and logs all headers
 * from {@code ClientCallContext}.
 *
 * <p>Response side: serializes each {@link ClientEvent} payload (Task, TaskStatusUpdateEvent,
 * TaskArtifactUpdateEvent, Message) to JSON.
 */
final class ProtocolLogger {

    private static final Logger log = LoggerFactory.getLogger("PROTOCOL");

    private static final ObjectMapper mapper =
            new ObjectMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                    .registerModule(
                            new SimpleModule()
                                    .addSerializer(
                                            OffsetDateTime.class, ToStringSerializer.instance));

    private ProtocolLogger() {}

    /**
     * Log the full request (headers + body) before sending to an agent.
     *
     * @param agentName target agent display name
     * @param endpoint agent URL
     * @param params the message send parameters (protocol body)
     * @param headers HTTP headers from ClientCallContext
     */
    static void logRequest(
            String agentName,
            String endpoint,
            MessageSendParams params,
            Map<String, String> headers) {
        if (!log.isInfoEnabled()) {
            return;
        }
        try {
            String bodyJson = mapper.writeValueAsString(params);
            log.info(
                    ">>> [{}] REQUEST to {}\n=== Headers ===\n{}\n=== Body ===\n{}",
                    agentName,
                    endpoint,
                    formatHeaders(headers),
                    bodyJson);
        } catch (Exception e) {
            log.warn(">>> [{}] Failed to serialize request: {}", agentName, e.getMessage());
        }
    }

    /**
     * Log each response event (full payload) received from an agent.
     *
     * @param agentName source agent display name
     * @param event the received client event
     */
    static void logResponseEvent(String agentName, ClientEvent event) {
        if (!log.isInfoEnabled()) {
            return;
        }
        try {
            Object payload = extractPayload(event);
            String eventType = event.getClass().getSimpleName();
            if (payload == null) {
                log.info("<<< [{}] RESPONSE [{}]: (no serializable payload)", agentName, eventType);
                return;
            }
            String json = mapper.writeValueAsString(payload);
            log.info("<<< [{}] RESPONSE [{}]\n{}", agentName, eventType, json);
        } catch (Exception e) {
            log.warn("<<< [{}] Failed to serialize response event: {}", agentName, e.getMessage());
        }
    }

    /**
     * Extract the serializable protocol payload from a ClientEvent. Returns the inner SDK spec
     * object (Task, TaskStatusUpdateEvent, TaskArtifactUpdateEvent, or Message) rather than the
     * event wrapper.
     */
    private static Object extractPayload(ClientEvent event) {
        if (event instanceof TaskEvent te) {
            return te.getTask();
        }
        if (event instanceof TaskUpdateEvent tue) {
            if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
                return sue;
            }
            if (tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent ae) {
                return ae;
            }
            return tue.getTask();
        }
        if (event instanceof MessageEvent me) {
            return me.getMessage();
        }
        return null;
    }

    /** Format headers map as "Key: Value" lines for readable logging. */
    private static String formatHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        headers.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\n"));
        return sb.toString().trim();
    }
}
