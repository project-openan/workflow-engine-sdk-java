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

package dev.openan.workflow.engine.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openan.workflow.engine.client.AgentCardNormalizer;
import dev.openan.workflow.engine.client.SslContextFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * Optional helper for fetching AgentCards from the Registry Center. Users can use this, or fetch
 * AgentCards from any other source.
 */
public class RegistryClient {
    private static final Logger log = LoggerFactory.getLogger(RegistryClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient httpClient;

    public RegistryClient(String url, boolean sslVerify) {
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        HttpClient.Builder clientBuilder =
                HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(30))
                        .followRedirects(HttpClient.Redirect.ALWAYS);
        if (!sslVerify) {
            clientBuilder.sslContext(SslContextFactory.createTrustAll());
        }
        this.httpClient = clientBuilder.build();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchAgentCards() throws Exception {
        String url = baseUrl + "/rest/v1/registry-center/agent-cards";
        log.info("[Registry] Fetching all agent cards from {}", baseUrl);
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Registry returned " + resp.statusCode());
        }
        Map<String, Object> data = mapper.readValue(resp.body(), Map.class);
        List<Map<String, Object>> cards =
                (List<Map<String, Object>>)
                        data.getOrDefault("agentCards", data.getOrDefault("data", List.of()));
        // Normalize each card's security schemes to a compatible format
        cards = cards.stream().map(AgentCardNormalizer::normalize).toList();
        log.info("[Registry] Received {} agent card(s)", cards.size());
        return cards;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchAgentCard(String name) throws Exception {
        return fetchAgentCard(name, null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchAgentCard(String name, String organization) throws Exception {
        StringBuilder urlBuilder =
                new StringBuilder(baseUrl)
                        .append("/rest/v1/registry-center/agent-cards?name=")
                        .append(
                                java.net.URLEncoder.encode(
                                        name != null ? name : "",
                                        java.nio.charset.StandardCharsets.UTF_8));
        if (organization != null && !organization.isEmpty()) {
            urlBuilder
                    .append("&organization=")
                    .append(
                            java.net.URLEncoder.encode(
                                    organization, java.nio.charset.StandardCharsets.UTF_8));
        }
        String url = urlBuilder.toString();
        log.info("[Registry] Fetching agent card: name={}, org={}", name, organization);
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Registry returned " + resp.statusCode());
        }
        Map<String, Object> data = mapper.readValue(resp.body(), Map.class);
        List<Map<String, Object>> cards =
                (List<Map<String, Object>>)
                        data.getOrDefault("agentCards", data.getOrDefault("data", List.of()));
        if (cards.isEmpty()) {
            log.warn("[Registry] Agent card not found: name={}", name);
            return null;
        }
        log.info("[Registry] Agent card found: name={}", name);
        return AgentCardNormalizer.normalize(cards.get(0));
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Register or update an AgentCard in the registry.
     *
     * @param agentCard the agent card to register (must include name, supportedInterfaces, etc.)
     * @return the registry response as a Map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> registerAgentCard(Map<String, Object> agentCard) throws Exception {
        String url = baseUrl + "/rest/v1/registry-center/agent-cards";
        Map<String, Object> payload = Map.of("agentCards", List.of(agentCard));
        String json = mapper.writeValueAsString(payload);
        log.info("[Registry] Registering agent card: name={}", agentCard.get("name"));
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> result = mapper.readValue(resp.body(), Map.class);
        if (resp.statusCode() == 200 || resp.statusCode() == 201) {
            log.info("[Registry] Agent card registered: name={}", agentCard.get("name"));
        } else {
            log.warn("[Registry] Registration returned {}: {}", resp.statusCode(), resp.body());
        }
        return result;
    }
}
