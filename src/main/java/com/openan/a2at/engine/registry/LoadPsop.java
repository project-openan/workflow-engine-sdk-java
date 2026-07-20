package com.openan.a2at.engine.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openan.a2at.engine.model.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * Load a PSOP workflow from the orchestration center's external API.
 * Uses GET /api/v1/orchestrate/psop/{psop_id}.
 */
public class LoadPsop {
    private static final Logger log = LoggerFactory.getLogger(LoadPsop.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    public static Workflow load(String baseUrl, String psopId, String accessToken, boolean sslVerify)
           throws Exception {
        StringBuilder urlBuilder = new StringBuilder(baseUrl)
                .append("/api/v1/orchestrate/psop/")
                .append(psopId);
        if (accessToken != null && !accessToken.isEmpty()) {
            urlBuilder.append("?access_token=").append(accessToken);
        }
        String url = urlBuilder.toString();
        log.info("[Registry] Loading PSOP from {} (ssl_verify={})", url, sslVerify);
        if (!sslVerify) {
            // TODO: configure trust-all SSL context for development use
            // Production should always verify server certificates
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Orchestration center returned " + resp.statusCode());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = mapper.readValue(resp.body(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> psopData = (Map<String, Object>) data.getOrDefault("data", data);
        Workflow wf = Workflow.fromMap(psopData);
        log.info("[Registry] Loaded workflow: {}, {} steps", wf.getName(), wf.getSteps().size());
        return wf;
    }

    public static Workflow load(String baseUrl, String psopId) throws Exception {
        return load(baseUrl, psopId, null, true);
    }
}
