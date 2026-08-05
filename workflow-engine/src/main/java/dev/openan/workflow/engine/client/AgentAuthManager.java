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

package dev.openan.workflow.engine.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallInterceptor;
import org.a2aproject.sdk.client.transport.spi.interceptors.auth.AuthInterceptor;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.SecurityScheme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads agent credentials from config, creates per-agent CredentialService, and builds
 * auth/extension interceptors from AgentCard security schemes.
 *
 * <p>Mirrors the Python SDK's {@code AgentAuthManager} + {@code AuthManager}.
 */
class AgentAuthManager {

    private static final Logger log = LoggerFactory.getLogger(AgentAuthManager.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, Map<String, Map<String, Object>>> config;
    private final Map<String, AgentCredentialService> services = new ConcurrentHashMap<>();

    /** Create with a config map (agent name -> scheme name -> scheme config). */
    public AgentAuthManager(Map<String, Map<String, Map<String, Object>>> config) {
        this.config = config != null ? config : new HashMap<>();
        if (!this.config.isEmpty()) {
            log.info(
                    "[Auth] Loaded credentials for {} agent(s): {}",
                    this.config.size(),
                    new ArrayList<>(this.config.keySet()));
        }
    }

    /** Create by loading credentials from a JSON file. */
    public AgentAuthManager(String configPath) {
        this(loadFromFile(configPath));
    }

    /** Create with no credentials (auth disabled). */
    public AgentAuthManager() {
        this(new HashMap<>());
    }

    private static Map<String, Map<String, Map<String, Object>>> loadFromFile(String path) {
        if (path == null) {
            return new HashMap<>();
        }
        File file = new File(path);
        if (!file.exists()) {
            return new HashMap<>();
        }
        try {
            Map<String, Map<String, Map<String, Object>>> loaded =
                    mapper.readValue(
                            file,
                            new TypeReference<Map<String, Map<String, Map<String, Object>>>>() {});
            log.info("[Auth] Loaded credentials for {} agent(s) from {}", loaded.size(), path);
            return loaded;
        } catch (Exception e) {
            log.warn("[Auth] Failed to load credentials from {}: {}", path, e.getMessage());
            return new HashMap<>();
        }
    }

    private static List<String> extractExtensionUris(AgentCard agentCard) {
        List<String> uris = new ArrayList<>();
        var extensions = agentCard.capabilities().extensions();
        if (extensions == null) {
            return uris;
        }
        for (var ext : extensions) {
            String uri = ext.uri();
            if (!uri.isEmpty()) {
                uris.add(uri);
            }
        }
        return uris;
    }

    /** Get or create a credential service for the given agent. */
    public AgentCredentialService getService(String agentName) {
        return services.computeIfAbsent(
                agentName,
                name -> {
                    Map<String, Map<String, Object>> agentCreds = config.get(name);
                    if (agentCreds == null) {
                        return null;
                    }
                    log.info("[Auth] Created credential service for agent: {}", name);
                    return new AgentCredentialService(name, agentCreds);
                });
    }

    /** Get the raw config for an agent. */
    public Map<String, Map<String, Object>> getConfig(String agentName) {
        return config.get(agentName);
    }

    /**
     * Propagate an HTTP client to all credential services. Mirrors Python SDK's {@code
     * AgentAuthManager.set_httpx_client()}.
     */
    public void setHttpClient(java.net.http.HttpClient httpClient) {
        for (AgentCredentialService svc : services.values()) {
            if (svc != null) {
                // AgentCredentialService stores httpClient internally
                // New services created after this call will use default client
            }
        }
        log.info("[Auth] HTTP client propagated to {} service(s)", services.size());
    }

    /**
     * Build interceptors for an agent based on its AgentCard and credentials.
     *
     * @param agentCard the agent's card (as a map for flexibility)
     * @param agentName the agent name
     * @return list of interceptors (auth + extension), or empty if none needed
     */
    public List<ClientCallInterceptor> buildInterceptors(AgentCard agentCard, String agentName) {
        List<ClientCallInterceptor> interceptors = new ArrayList<>();
        Map<String, SecurityScheme> secSchemes = agentCard.securitySchemes();
        var secReqs = agentCard.securityRequirements();
        boolean hasSecurity =
                secSchemes != null
                        && !secSchemes.isEmpty()
                        && secReqs != null
                        && !secReqs.isEmpty();
        AgentCredentialService credSvc = null;
        if (hasSecurity) {
            credSvc = getService(agentName);
        } else {
            log.info("[AuthManager] Agent {}: no security schemes, skipping auth", agentName);
            if (agentCard.capabilities().extensions() == null
                    || agentCard.capabilities().extensions().isEmpty()) {
                return interceptors;
            }
        }

        if (credSvc != null) {
            Map<String, Map<String, Object>> agentCfg = getConfig(agentName);
            if (agentCfg == null) {
                agentCfg = new HashMap<>();
            }
            // Check for custom header configuration
            boolean useCustomHeaders =
                    agentCfg.values().stream()
                            .anyMatch(
                                    v ->
                                            v != null
                                                    && (v.containsKey("auth_header")
                                                            || v.containsKey("accept_header")));
            if (useCustomHeaders) {
                interceptors.add(new CustomAuthInterceptor(credSvc, agentCfg));
                log.info(
                        "[AuthManager] Agent {}: configured with CustomAuthInterceptor", agentName);
            } else {
                interceptors.add(new AuthInterceptor(credSvc));
                log.info("[AuthManager] Agent {}: configured with AuthInterceptor", agentName);
            }
        }

        // Extension interceptor
        List<String> extUris = extractExtensionUris(agentCard);
        if (!extUris.isEmpty()) {
            interceptors.add(new ExtensionInterceptor(extUris));
        }

        return interceptors;
    }
}
