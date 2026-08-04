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

import com.fasterxml.jackson.databind.ObjectMapper;

import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.client.transport.spi.interceptors.auth.CredentialService;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Obtains Bearer tokens via login endpoints for agents requiring authentication.
 *
 * <p>Mirrors the Python SDK's {@code AgentCredentialService}. Caches tokens with a configurable TTL
 * and refreshes them before expiry.
 */
class AgentCredentialService implements CredentialService {

    private static final Logger log = LoggerFactory.getLogger(AgentCredentialService.class);

    private static final ObjectMapper mapper = new ObjectMapper();

    private final String agentName;

    private final Map<String, Map<String, Object>> schemeConfigs;

    private final HttpClient httpClient;

    private final Map<String, TokenEntry> tokenCache = new ConcurrentHashMap<>();

    public AgentCredentialService(
            String agentName, Map<String, Map<String, Object>> schemeConfigs) {
        this(agentName, schemeConfigs, null);
    }

    public AgentCredentialService(
            String agentName,
            Map<String, Map<String, Object>> schemeConfigs,
            HttpClient httpClient) {
        this.agentName = agentName;
        this.schemeConfigs = schemeConfigs != null ? schemeConfigs : Map.of();
        if (httpClient != null) {
            this.httpClient = httpClient;
        } else {
            HttpClient.Builder b =
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(30))
                            .followRedirects(HttpClient.Redirect.ALWAYS);

            // Disable TLS verification (mirrors Python's verify=False for login endpoints)
            b.sslContext(SslContextFactory.createTrustAll());
            this.httpClient = b.build();
        }
    }

    @SuppressWarnings("unchecked")
    private static String extractNestedValue(Map<String, Object> data, String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        Object current = data;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<String, Object>) current).get(part);
            if (current == null) {
                return null;
            }
        }
        return current != null ? current.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildLoginBody(Map<String, Object> schemeCfg) {
        Map<String, Object> body = new HashMap<>();
        Object requestFields = schemeCfg.get("request_fields");
        if (requestFields instanceof Map) {
            for (var entry : ((Map<String, Object>) requestFields).entrySet()) {
                String val = entry.getValue() != null ? entry.getValue().toString() : "";
                body.put(entry.getKey(), CredentialCrypto.decryptIfNeeded(val));
            }
        } else {
            String username = (String) schemeCfg.get("username");
            String password = CredentialCrypto.decryptIfNeeded((String) schemeCfg.get("password"));
            if (username == null || password == null) return body;
            body.put(schemeCfg.getOrDefault("username_field", "username").toString(), username);
            body.put(schemeCfg.getOrDefault("password_field", "password").toString(), password);
        }
        return body;
    }

    private static long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return 3600;
            }
        }
        return 3600;
    }

    /**
     * Mask sensitive fields (password, value, accessSession) for safe logging. Mirrors Python SDK's
     * {@code _sanitize_body()}.
     */
    private static Map<String, Object> sanitizeBody(Map<String, Object> body) {
        Map<String, Object> sanitized = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : body.entrySet()) {
            String key = e.getKey().toLowerCase();
            if (key.equals("password") || key.equals("value") || key.equals("accesssession")) {
                sanitized.put(e.getKey(), "***");
            } else {
                sanitized.put(e.getKey(), e.getValue());
            }
        }
        return sanitized;
    }

    @Override
    public String getCredential(@NotNull String securitySchemeName, ClientCallContext context) {
        Map<String, Object> schemeCfg = schemeConfigs.get(securitySchemeName);
        if (schemeCfg == null) {
            return null;
        }

        // Check cache
        TokenEntry cached = tokenCache.get(securitySchemeName);
        if (cached != null && !cached.isExpired()) {
            log.info("[Auth] Cache hit for agent {} scheme {}", agentName, securitySchemeName);
            return cached.token;
        }

        // Login and cache
        String token = login(schemeCfg);
        if (token != null) {
            long ttl = toLong(schemeCfg.get("token_ttl"));
            tokenCache.put(
                    securitySchemeName,
                    new TokenEntry(token, System.currentTimeMillis() / 1000 + ttl));
            log.info("[Auth] Login succeeded: agent={}, scheme={}", agentName, securitySchemeName);
        }

        return token;
    }

    private String login(Map<String, Object> schemeCfg) {
        String loginUrl = (String) schemeCfg.get("login_url");
        if (loginUrl == null || loginUrl.isEmpty()) {
            return null;
        }

        String method = schemeCfg.getOrDefault("method", "POST").toString().toUpperCase();
        String contentType = schemeCfg.getOrDefault("content_type", "application/json").toString();
        String tokenField = schemeCfg.getOrDefault("token_field", "accessSession").toString();
        Map<String, Object> body = buildLoginBody(schemeCfg);
        try {
            log.info(
                    "[Auth] Login attempt: agent={}, method={}, url={}, params={}",
                    agentName,
                    method,
                    loginUrl,
                    sanitizeBody(body));
            HttpRequest.BodyPublisher bodyPublisher;
            if ("application/x-www-form-urlencoded".equals(contentType)) {
                StringBuilder form = new StringBuilder();
                for (Map.Entry<String, Object> e : body.entrySet()) {
                    if (!form.isEmpty()) {
                        form.append("&");
                    }
                    form.append(e.getKey()).append("=").append(e.getValue());
                }
                bodyPublisher = HttpRequest.BodyPublishers.ofString(form.toString());
            } else {
                bodyPublisher =
                        HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body));
            }
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(loginUrl))
                            .header("Content-Type", contentType)
                            .method(method, bodyPublisher)
                            .timeout(Duration.ofSeconds(30))
                            .build();
            HttpResponse<String> resp =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.error("[Auth] Login failed: agent={}, status={}", agentName, resp.statusCode());
                return null;
            }
            Map<String, Object> data = mapper.readValue(resp.body(), Map.class);
            String token = extractNestedValue(data, tokenField);
            if (token == null) {
                token =
                        (String)
                                data.getOrDefault(
                                        "accessSession",
                                        data.getOrDefault("access_token", data.get("token")));
            }
            return token;
        } catch (Exception e) {
            log.error(
                    "[Auth] Login failed: agent={}, url={}, error={}",
                    agentName,
                    loginUrl,
                    e.getMessage());
            return null;
        }
    }

    private record TokenEntry(String token, long expiresAt) {
        boolean isExpired() {
            return System.currentTimeMillis() / 1000 >= expiresAt - 60;
        }
    }
}
