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
import net.openan.a2at.sdk.client.A2ATClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.List;
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
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> beforeSend(
            Map<String, Object> agentCard,
            String messageText,
            Map<String, Object> metadata,
            A2ATClient a2atClient,
            Object controlPoint
    ) {
        String notifUri = findNotificationTUri(agentCard);
        if (notifUri == null) {
            return CompletableFuture.completedFuture(metadata);
        }
        if (metadata != null && metadata.containsKey(notifUri)) {
            log.info("[Notification-T] Metadata already preset, skipping subscription injection");
            return CompletableFuture.completedFuture(metadata);
        }
        Map<String, Object> result = metadata != null ? new java.util.HashMap<>(metadata) : new java.util.HashMap<>();
        Map<String, Object> subscription = new java.util.HashMap<>();
        subscription.put("action", "subscribe");
        subscription.put("topic", "recovery_result");
        result.put(notifUri, subscription);
        log.info("[Notification-T] Injected subscription for '{}'", getAgentName(agentCard));
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<SendMessageResult> afterReceive(
            Map<String, Object> agentCard,
            SendMessageResult result,
            A2ATClient a2atClient,
            Object controlPoint,
            Object eventCallback
    ) {
        Map<String, Object> metadata = result.getMetadata();
        Object notification = null;
        if (metadata != null) {
            notification = metadata.get("Notification-T");
            if (notification == null) {
                for (var entry : metadata.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    if (key.contains("Notification-T")) {
                        notification = entry.getValue();
                        break;
                    }
                }
            }
        }
        if (notification == null || controlPoint == null) {
            return CompletableFuture.completedFuture(result);
        }
        String agentName = getAgentName(agentCard);
        log.info("[Notification-T] Received notification from '{}': {}", agentName, notification);

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
        if (controlPoint instanceof com.openan.a2at.engine.control.ControlPoint cp) {
            Map<String, Object> notifMap = notification instanceof Map
                    ? (Map<String, Object>) notification : Map.of();
            return cp.onNotification(agentName, notifMap);
        }
        // Fallback: reflection for non-ControlPoint objects
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


    @SuppressWarnings("unchecked")
    private static String findNotificationTUri(Map<String, Object> agentCard) {
        Map<String, Object> caps = (Map<String, Object>) agentCard.get("capabilities");
        if (caps == null) {
            return null;
        }
        List<Map<String, Object>> extensions = (List<Map<String, Object>>) caps.get("extensions");
        if (extensions == null) {
            return null;
        }
        for (Map<String, Object> ext : extensions) {
            String uri = (String) ext.get("uri");
            if (uri != null && uri.contains("Notification-T")) {
                return uri;
            }
        }
        return null;
    }
    private static String getAgentName(Map<String, Object> agentCard) {
        Object name = agentCard.get("name");
        return name != null ? name.toString() : "";
    }
}
