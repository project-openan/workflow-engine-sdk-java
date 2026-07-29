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

package com.openan.a2at.engine.client;

import org.a2aproject.sdk.spec.AgentCard;

import java.util.Map;

/**
 * Custom authentication provider for injecting auth headers into outgoing A2A messages.
 *
 * <p>Implement this interface when the agent's authentication is not covered by the credentials
 * file or the AgentCard's security schemes. For example:
 *
 * <ul>
 *   <li>The AgentCard has no securitySchemes but the server still requires auth
 *   <li>The auth mechanism is not one of the A2A standard types (Bearer, API key, OAuth2)
 *   <li>Auth tokens come from an external identity provider (e.g. corporate SSO)
 * </ul>
 *
 * <p>Register via {@link WorkflowEngineClientConfig.Builder#authProvider}:
 *
 * <pre>{@code
 * WorkflowEngineClientConfig.builder()
 *     .authProvider((agentName, agentCard, headers) -> {
 *         String token = mySsoClient.getToken(agentName);
 *         headers.put("Authorization", "Bearer " + token);
 *     })
 *     .build();
 * }</pre>
 *
 * <p>The provider is called for every message send, regardless of whether the AgentCard declares
 * security schemes. If both a credentials file and a custom AuthProvider are configured, both run
 * and may add headers to the same map (custom provider runs first, credentials-based auth second).
 */
public interface AuthProvider {

    /**
     * Apply authentication headers for sending a message to the given agent.
     *
     * @param agentName the target agent name (matches AgentCard.name)
     * @param agentCard the agent's card (securitySchemes may be null or empty)
     * @param headers mutable header map to add auth headers to
     */
    void applyAuth(String agentName, AgentCard agentCard, Map<String, String> headers);
}
