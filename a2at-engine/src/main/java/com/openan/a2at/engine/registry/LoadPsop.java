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

package com.openan.a2at.engine.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openan.a2at.engine.model.Workflow;
import com.openan.a2at.engine.model.WorkflowSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Load and search PSOP workflows from the orchestration center's external API.
 * <ul>
 *   <li>{@code load} -- GET /api/v1/orchestrate/psop/{psop_id} (full workflow)</li>
 *   <li>{@code search} -- POST /api/v1/orchestrate/search (summary list by intent)</li>
 * </ul>
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
            urlBuilder.append("?access_token=")
                    .append(URLEncoder.encode(accessToken, StandardCharsets.UTF_8));
        }
        String url = urlBuilder.toString();
        log.info("[Registry] Loading PSOP from {} (ssl_verify={})", url, sslVerify);

        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30));
        clientBuilder.followRedirects(HttpClient.Redirect.ALWAYS);
        if (!sslVerify) {
            // Disable TLS verification for development with self-signed certs
            try {
                javax.net.ssl.SSLContext trustAllCtx = javax.net.ssl.SSLContext.getInstance("TLS");
                trustAllCtx.init(null, new javax.net.ssl.TrustManager[]{new javax.net.ssl.X509TrustManager() {
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                }}, null);
                // Disable hostname verification: self-signed certs
                // have no SAN, so endpoint identification fails.
                System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
                clientBuilder.sslContext(trustAllCtx);
                log.warn("[Registry] TLS verification disabled for PSOP load (development only)");
            } catch (Exception e) {
                log.warn("[Registry] Failed to disable TLS: {}", e.getMessage());
            }
        }
        HttpClient client = clientBuilder.build();

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

    /**
     * Search for matching PSOP workflows from the orchestration center.
     *
     * <p>Uses POST /api/v1/orchestrate/search with a natural-language intent.
     * Returns a ranked list of summary objects (id, name, description, score,
     * ...). To get the full workflow with steps, take
     * {@link WorkflowSearchResult#getWorkflowId()} and call
     * {@link #load(String, String, String, boolean)}.
     *
     * @param baseUrl     orchestration center base URL (e.g. "https://127.0.0.1:5001")
     * @param intent      natural-language search intent
     * @param topN        max number of results (1-20, default 5)
     * @param accessToken external auth token (null if not needed)
     * @param sslVerify   set false for self-signed certs (dev only)
     * @return ranked list of workflow summaries
     */
    public static List<WorkflowSearchResult> search(
            String baseUrl, String intent, int topN,
            String accessToken, boolean sslVerify) throws Exception {
        StringBuilder urlBuilder = new StringBuilder(baseUrl)
                .append("/api/v1/orchestrate/search");
        if (accessToken != null && !accessToken.isEmpty()) {
            urlBuilder.append("?access_token=")
                    .append(URLEncoder.encode(accessToken, StandardCharsets.UTF_8));
        }
        String url = urlBuilder.toString();
        log.info("[Registry] Searching PSOP at {} (intent={}, top_n={})", url, intent, topN);

        String jsonBody = mapper.writeValueAsString(Map.of("intent", intent, "top_n", topN));

        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30));
        clientBuilder.followRedirects(HttpClient.Redirect.ALWAYS);
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
                // Disable hostname verification: self-signed certs
                // have no SAN, so endpoint identification fails.
                System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
                clientBuilder.sslContext(trustAllCtx);
            } catch (Exception e) {
                log.warn("[Registry] Failed to disable TLS: {}", e.getMessage());
            }
        }
        HttpClient client = clientBuilder.build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Orchestration center returned " + resp.statusCode());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = mapper.readValue(resp.body(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawResults = (List<Map<String, Object>>) data.getOrDefault("data", List.of());
        List<WorkflowSearchResult> results = new java.util.ArrayList<>();
        for (Map<String, Object> raw : rawResults) {
            results.add(WorkflowSearchResult.builder()
                    .workflowId((String) raw.getOrDefault("workflow_id", raw.getOrDefault("id", "")))
                    .workflowType((String) raw.getOrDefault("workflow_type", ""))
                    .name((String) raw.getOrDefault("name", ""))
                    .description((String) raw.getOrDefault("description", null))
                    .createdAt(raw.get("created_at") != null ? raw.get("created_at").toString() : "")
                    .score(raw.get("score") instanceof Number n ? n.doubleValue() : 1.0)
                    .userIntent((String) raw.getOrDefault("user_intent", null))
                    .relatedPreflow((String) raw.getOrDefault("related_preflow", null))
                    .tasksSummary((String) raw.getOrDefault("tasks_summary", null))
                    .build());
        }
        log.info("[Registry] Search returned {} workflow(s)", results.size());
        return results;
    }

    /** Convenience: search with defaults (top_n=5, no token, ssl_verify=true). */
    public static List<WorkflowSearchResult> search(String baseUrl, String intent) throws Exception {
        return search(baseUrl, intent, 5, null, true);
    }
}
