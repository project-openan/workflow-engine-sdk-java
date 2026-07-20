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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
}
