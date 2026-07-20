package com.openan.a2at.engine.registry;

import com.openan.a2at.engine.model.Workflow;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        String url = baseUrl + "/api/v1/orchestrate/psop/" + psopId;
        log.info("[Registry] Loading PSOP from {} (ssl_verify={})", url, sslVerify);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET();
        if (accessToken != null && !accessToken.isEmpty()) {
            // Add as query param
            url += "?access_token=" + accessToken;
            reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET();
        }
        HttpResponse<String> resp = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
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
