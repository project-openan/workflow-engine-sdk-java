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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of A2A-T extension handlers.
 *
 * <p>The workflow engine only handles Task-T (task prompt generation) and Negotiation-T (auto
 * negotiation loop). Authorization-T and Notification-T are pre-positioning operations done once
 * before the workflow starts (see {@link WorkflowEngineClient#sendExtensionMessage}), so they are
 * NOT part of the workflow's extension handler chain.
 */
class ExtensionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExtensionRegistry.class);

    private final Map<String, ExtensionHandler> handlers = new LinkedHashMap<>();

    public ExtensionRegistry() {
        register(new TaskTHandler());
        register(new NegotiationTHandler());
    }

    public void register(ExtensionHandler handler) {
        handlers.put(handler.extensionKeyword(), handler);
    }

    /**
     * Find handlers matching the given extension URIs.
     *
     * @param extensionUris list of extension URIs from the AgentCard
     * @return matched handlers (deduplicated)
     */
    public List<ExtensionHandler> getHandlersForExtensions(List<String> extensionUris) {
        List<ExtensionHandler> matched = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (extensionUris == null) {
            return matched;
        }
        for (String uri : extensionUris) {
            if (uri == null) {
                continue;
            }
            for (Map.Entry<String, ExtensionHandler> entry : handlers.entrySet()) {
                // Case-insensitive: URIs commonly use uppercase (NEGOTIATION-T)
                // while the handler keyword uses mixed case (Negotiation-T).
                if (uri.toLowerCase().contains(entry.getKey().toLowerCase())
                        && !seen.contains(entry.getKey())) {
                    matched.add(entry.getValue());
                    seen.add(entry.getKey());
                    break;
                }
            }
        }
        return matched;
    }
}
