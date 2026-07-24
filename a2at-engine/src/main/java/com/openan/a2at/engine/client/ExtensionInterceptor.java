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

import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallInterceptor;
import org.a2aproject.sdk.client.transport.spi.interceptors.PayloadAndHeaders;
import org.a2aproject.sdk.spec.AgentCard;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Injects A2A extension URIs from AgentCard into HTTP headers.
 *
 * <p> Sets the{@code A2A-Extensions} header so the remote agent knows which extensions
 * the client supports (e.g. Task-T, Negotiation-T).
 */
class ExtensionInterceptor extends ClientCallInterceptor {

    public static final String HTTP_EXTENSION_HEADER = "A2A-Extensions";

    private static final Logger log = LoggerFactory.getLogger(ExtensionInterceptor.class);

    private final List<String> extensionUris;

    public ExtensionInterceptor(List<String> extensionUris) {
        this.extensionUris = extensionUris != null ? new ArrayList<>(extensionUris) : List.of();
    }

    @NotNull
    @Override
    public PayloadAndHeaders intercept(
            @NotNull String method,
            Object payload,
            @NotNull Map<String, String> headers,
            AgentCard agentCard,
            ClientCallContext context
    ) {
        if (extensionUris.isEmpty()) {
            return new PayloadAndHeaders(payload, headers);
        }
        // Merge existing extension header values with our URIs
        Set<String> merged = new LinkedHashSet<>();
        String existing = headers.get(HTTP_EXTENSION_HEADER);
        if (existing != null && !existing.isEmpty()) {
            for (String v : existing.split(",")) {
                String trimmed = v.trim();
                if (!trimmed.isEmpty()) {
                    merged.add(trimmed);
                }
            }
        }
        merged.addAll(extensionUris);
        String extensionValue = String.join(",", merged);
        Map<String, String> newHeaders = new java.util.HashMap<>(headers);
        newHeaders.put(HTTP_EXTENSION_HEADER, extensionValue);
        newHeaders.put("x-a2a-extensions", extensionValue);
        log.info("[Extensions] Set {}={}", HTTP_EXTENSION_HEADER, extensionValue);
        return new PayloadAndHeaders(payload, newHeaders);
    }
}
