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

import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallInterceptor;
import org.a2aproject.sdk.client.transport.spi.interceptors.PayloadAndHeaders;
import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Auth interceptor supporting custom header names (non-Bearer Authorization).
 *
 * <p>Mirrors the Python SDK's {@code CustomAuthInterceptor}. Some agents
 * require tokens in custom headers (e.g. {@code X-API-Key}) or with custom
 * prefixes (e.g. {@code Token} instead of {@code Bearer}).
 */
public class CustomAuthInterceptor extends ClientCallInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CustomAuthInterceptor.class);

    private final AgentCredentialService credentialService;
    private final Map<String, Map<String, Object>> schemeConfigs;

    public CustomAuthInterceptor(AgentCredentialService credentialService,
                                 Map<String, Map<String, Object>> schemeConfigs) {
        this.credentialService = credentialService;
        this.schemeConfigs = schemeConfigs != null ? schemeConfigs : Map.of();
    }

    @Override
    public PayloadAndHeaders intercept(
            String method,
            Object payload,
            Map<String, String> headers,
            AgentCard agentCard,
            ClientCallContext context
    ) {
        if (agentCard == null
                || agentCard.securityRequirements() == null
                || agentCard.securityRequirements().isEmpty()
                || agentCard.securitySchemes() == null
                || agentCard.securitySchemes().isEmpty()) {
            return new PayloadAndHeaders(payload, headers);
        }

        Map<String, String> newHeaders = new HashMap<>(headers);

        for (org.a2aproject.sdk.spec.SecurityRequirement req : agentCard.securityRequirements()) {
            for (Map.Entry<String, java.util.List<String>> entry : req.schemes().entrySet()) {
                String schemeName = entry.getKey();
                Map<String, Object> schemeCfg = schemeConfigs.getOrDefault(schemeName, Map.of());
                String credential = credentialService.getCredential(schemeName, context);
                if (credential == null) {
                    continue;
                }
                String authHeader = (String) schemeCfg.get("auth_header");
                if (authHeader != null && !authHeader.isEmpty()) {
                    String prefix = (String) schemeCfg.getOrDefault("auth_header_prefix", "");
                    newHeaders.put(authHeader, prefix + credential);
                    log.info("[CustomAuth] Set header {} for scheme {}", authHeader, schemeName);
                } else {
                    newHeaders.put("Authorization", "Bearer " + credential);
                    log.info("[CustomAuth] Set Bearer header for scheme {}", schemeName);
                }
                String acceptHeader = (String) schemeCfg.get("accept_header");
                if (acceptHeader != null && !acceptHeader.isEmpty()) {
                    newHeaders.put("Accept", acceptHeader);
                    log.info("[CustomAuth] Override Accept header to {}", acceptHeader);
                }
                break;
            }
        }
        return new PayloadAndHeaders(payload, newHeaders);
    }
}
