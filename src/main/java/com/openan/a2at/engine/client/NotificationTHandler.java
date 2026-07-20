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

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Notification-T extension handler.
 *
 * <p>Mirrors the Python SDK's {@code NotificationTHandler}. When an agent
 * sends a Notification-T message in metadata, this handler delegates to
 * the ControlPoint's {@code onNotification} method.
 */
public class NotificationTHandler implements ExtensionHandler {

    private static final Logger log = LoggerFactory.getLogger(NotificationTHandler.class);

    @Override
    public String extensionKeyword() {
        return "Notification-T";
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
    public CompletableFuture<SendMessageResult> afterReceive(
            Map<String, Object> agentCard,
            SendMessageResult result,
            Object a2atClient,
            Object controlPoint,
            Object eventCallback
    ) {
        Map<String, Object> metadata = result.getMetadata();
        Object notification = metadata != null ? metadata.get("Notification-T") : null;
        if (notification == null || controlPoint == null) {
            return CompletableFuture.completedFuture(result);
        }
        String agentName = getAgentName(agentCard);
        log.info("[Notification-T] Received notification from '{}'", agentName);

        // Emit notification event
        if (eventCallback != null) {
            try {
                eventCallback.getClass().getMethod("onEvent", String.class, Map.class)
                        .invoke(eventCallback, "notification",
                                Map.of("agent", agentName,
                                        "notification", notification instanceof String || notification instanceof Map
                                                ? notification : notification.toString()));
            } catch (Exception ignored) {
                // Event callback invocation is best-effort
            }
        }

        // Call controlPoint.onNotification(agentName, notification) via reflection
        return callOnNotification(controlPoint, agentName, notification)
                .thenApply(v -> result);
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<Void> callOnNotification(
            Object controlPoint, String agentName, Object notification) {
        try {
            Object future = controlPoint.getClass()
                    .getMethod("onNotification", String.class, Map.class)
                    .invoke(controlPoint, agentName,
                            notification instanceof Map ? notification : Map.of());
            if (future instanceof CompletableFuture) {
                return (CompletableFuture<Void>) future;
            }
        } catch (Exception e) {
            log.warn("[Notification-T] onNotification call failed: {}", e.getMessage());
        }
        return CompletableFuture.completedFuture(null);
    }

    private static String getAgentName(Map<String, Object> agentCard) {
        Object name = agentCard.get("name");
        return name != null ? name.toString() : "";
    }
}
