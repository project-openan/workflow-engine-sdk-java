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

import com.openan.a2at.engine.model.SendMessageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Authorization-T extension handler.
 *
 * <p>Mirrors the Python SDK's {@code AuthorizationTHandler}. When an agent
 * sends an Authorization-T request in metadata, this handler delegates to
 * the ControlPoint's {@code onAuthorization} method. If denied, the task
 * state is set to AUTHORIZATION_DENIED.
 */
public class AuthorizationTHandler implements ExtensionHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationTHandler.class);

    @Override
    public String extensionKeyword() {
        return "Authorization-T";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeSend(
            Map<String, Object> agentCard,
            String messageText,
            Map<String, Object> metadata,
            Object a2atClient,
            Object controlPoint
    ) {
        return CompletableFuture.completedFuture(metadata);
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<SendMessageResult> afterReceive(
            Map<String, Object> agentCard,
            SendMessageResult result,
            Object a2atClient,
            Object controlPoint,
            Object eventCallback
    ) {
        Map<String, Object> metadata = result.getMetadata();
        Object authRequest = metadata != null ? metadata.get("Authorization-T") : null;
        if (authRequest == null || controlPoint == null) {
            return CompletableFuture.completedFuture(result);
        }
        String agentName = getAgentName(agentCard);
        log.info("[Authorization-T] Agent '{}' requests authorization", agentName);

        Object finalAuthRequest = authRequest instanceof String || authRequest instanceof Map
                ? authRequest : authRequest.toString();

        // Emit authorization_request event
        if (eventCallback != null) {
            try {
                eventCallback.getClass().getMethod("onEvent", String.class, Map.class)
                        .invoke(eventCallback, "authorization_request",
                                Map.of("agent", agentName, "auth_request", finalAuthRequest));
            } catch (Exception ignored) {
                // Event callback invocation is best-effort
            }
        }

        // Call controlPoint.onAuthorization(agentName, authRequest) via reflection
        return callOnAuthorization(controlPoint, agentName, finalAuthRequest)
                .thenCompose(approved -> {
                    Map<String, Object> newMetadata = metadata != null
                            ? new HashMap<>(metadata) : new HashMap<>();
                    if (approved) {
                        newMetadata.put("authorization_approved", true);
                        log.info("[Authorization-T] Approved for '{}'", agentName);
                        emitEvent(eventCallback, "authorization_resolved",
                                Map.of("agent", agentName, "decision", "approved"));
                    } else {
                        result.setTaskState("AUTHORIZATION_DENIED");
                        if (result.getText() == null || result.getText().isEmpty()) {
                            result.setText("Authorization denied");
                        }
                        log.warn("[Authorization-T] Denied for '{}'", agentName);
                        emitEvent(eventCallback, "authorization_resolved",
                                Map.of("agent", agentName, "decision", "denied"));
                    }
                    result.setMetadata(newMetadata);
                    return CompletableFuture.completedFuture(result);
                });
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<Boolean> callOnAuthorization(
            Object controlPoint, String agentName, Object authRequest) {
        try {
            Object future = controlPoint.getClass()
                    .getMethod("onAuthorization", String.class, Map.class)
                    .invoke(controlPoint, agentName,
                            authRequest instanceof Map ? authRequest : Map.of());
            if (future instanceof CompletableFuture) {
                return (CompletableFuture<Boolean>) future;
            }
        } catch (Exception e) {
            log.warn("[Authorization-T] onAuthorization call failed: {}", e.getMessage());
        }
        return CompletableFuture.completedFuture(true);
    }

    private static void emitEvent(Object eventCallback, String type, Map<String, Object> data) {
        if (eventCallback == null) {
            return;
        }
        try {
            eventCallback.getClass().getMethod("onEvent", String.class, Map.class)
                    .invoke(eventCallback, type, data);
        } catch (Exception ignored) {
            // Event callback invocation is best-effort
        }
    }

    private static String getAgentName(Map<String, Object> agentCard) {
        Object name = agentCard.get("name");
        return name != null ? name.toString() : "";
    }
}
