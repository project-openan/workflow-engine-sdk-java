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

package com.openan.a2at.engine.control;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Reactive hooks for agent-pushed A2A-T extension data.
 *
 * <p>Distinct from {@link ControlPoint} (which drives the workflow forward):
 * these methods react to peer-initiated extension traffic rather than
 * making flow decisions. The Authorization-T / Notification-T handlers
 * invoke them when an agent pushes authorization requests or notifications
 * back in a task response.
 *
 * <p>The subscription <i>result</i> (e.g. a recovery outcome the agent pushes
 * later) flows back through {@code sendExtensionMessage}'s response stream,
 * not through {@link #onNotification}; that hook only fires when an agent
 * voluntarily includes a Notification-T payload in a {@code sendMessage}
 * task response.
 */
public interface ExtensionCallback {

    /**
     * Authorization approval decision. Return true/false. Do NOT send
     * Authorization-T confirmation -- the SDK injects it automatically.
     * Default: auto-approve.
     */
    default CompletableFuture<Boolean> onAuthorization(String agentName, Map<String, Object> authRequest) {
        return CompletableFuture.completedFuture(true);
    }

    /**
     * Handle a received Notification-T. Do NOT send messages here.
     * Default: no-op.
     */
    default CompletableFuture<Void> onNotification(String agentName, Map<String, Object> notification) {
        return CompletableFuture.completedFuture(null);
    }
}
