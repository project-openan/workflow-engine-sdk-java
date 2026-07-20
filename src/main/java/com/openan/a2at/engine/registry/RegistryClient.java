package com.openan.a2at.engine.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

/**
 * Optional helper for fetching AgentCards from the Registry Center.
 * Users can use this, or fetch AgentCards from any other source.
 */
public class RegistryClient {
    private static final Logger log = LoggerFactory.getLogger(RegistryClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String baseUrl;
    private final boolean verifySsl;
    private final HttpClient httpClient;

    public RegistryClient(String url, boolean verifySsl) {
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.verifySsl = verifySsl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchAgentCards() throws Exception {
        String url = baseUrl + "/rest/v1/registry-center/agent-cards";
        log.info("[Registry] Fetching all agent cards from {}", baseUrl);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Registry returned " + resp.statusCode());
        }
        Map<String, Object> data = mapper.readValue(resp.body(), Map.class);
        List<Map<String, Object>> cards = (List<Map<String, Object>>) data.getOrDefault("agentCards", data.getOrDefault("data", List.of()));
        log.info("[Registry] Received {} agent card(s)", cards.size());
        return cards;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchAgentCard(String name) throws Exception {
        String url = baseUrl + "/rest/v1/registry-center/agent-cards?name=" + name;
        log.info("[Registry] Fetching agent card: name={}", name);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Registry returned " + resp.statusCode());
        }
        Map<String, Object> data = mapper.readValue(resp.body(), Map.class);
        List<Map<String, Object>> cards = (List<Map<String, Object>>) data.getOrDefault("agentCards", data.getOrDefault("data", List.of()));
        if (cards.isEmpty()) {
            log.warn("[Registry] Agent card not found: name={}", name);
            return null;
        }
        log.info("[Registry] Agent card found: name={}", name);
        return cards.get(0);
    }

    public String getBaseUrl() { return baseUrl; }
}
