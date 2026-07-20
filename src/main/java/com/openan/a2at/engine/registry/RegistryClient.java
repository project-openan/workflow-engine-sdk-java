package com.openan.a2at.engine.registry;

import com.openan.a2at.engine.client.AgentCardNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * Optional helper for fetching AgentCards from the Registry Center.
 * Users can use this, or fetch AgentCards from any other source.
 */
public class RegistryClient {
    private static final Logger log = LoggerFactory.getLogger(RegistryClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String baseUrl;
    private final boolean sslVerify;
    private final HttpClient httpClient;

    public RegistryClient(String url, boolean sslVerify) {
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.sslVerify = sslVerify;
        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.ALWAYS);
        if (!sslVerify) {
            try {
                javax.net.ssl.SSLContext trustAllCtx = javax.net.ssl.SSLContext.getInstance("TLS");
                trustAllCtx.init(null, new javax.net.ssl.TrustManager[]{new javax.net.ssl.X509TrustManager() {
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                }}, null);
                clientBuilder.sslContext(trustAllCtx);
                log.warn("[Registry] TLS verification disabled (development only)");
            } catch (Exception e) {
                log.warn("[Registry] Failed to disable TLS: {}", e.getMessage());
            }
        }
        this.httpClient = clientBuilder.build();
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
        StringBuilder urlBuilder = new StringBuilder(baseUrl)
                .append("/rest/v1/registry-center/agent-cards?name=")
                .append(java.net.URLEncoder.encode(name != null ? name : "", java.nio.charset.StandardCharsets.UTF_8));
        if (organization != null && !organization.isEmpty()) {
            urlBuilder.append("&organization=")
                    .append(java.net.URLEncoder.encode(organization, java.nio.charset.StandardCharsets.UTF_8));
        }
        String url = urlBuilder.toString();
        log.info("[Registry] Fetching agent card: name={}, org={}", name, organization);
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
        return AgentCardNormalizer.normalize(cards.get(0));
    }

    public String getBaseUrl() {
        return baseUrl;
    }
}
