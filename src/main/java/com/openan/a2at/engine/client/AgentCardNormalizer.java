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

package com.openan.a2at.engine.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentCard normalization -- converts OpenAPI-style security scheme
 * notation to a format compatible with the a2a-java-sdk AgentCard record.
 *
 * <p>Mirrors the Python SDK's {@code agentcard_normalizer.normalize_agent_dict()}.
 * Handles two input formats:
 * <ol>
 *   <li>Already-correct format (from registry center) -- normalization is a no-op.</li>
 *   <li>OpenAPI-style (flat {@code scheme} field, list-style
 *       {@code securityRequirements}) -- converted to structured format.</li>
 * </ol>
 */
public final class AgentCardNormalizer {

    private static final Logger log = LoggerFactory.getLogger(AgentCardNormalizer.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final List<String> PROTO_ONEOF_KEYS = List.of(
            "httpAuthSecurityScheme", "apiKeySecurityScheme",
            "oauth2SecurityScheme", "openIdConnectSecurityScheme",
            "mtlsSecurityScheme"
    );

    private AgentCardNormalizer() {
    }

    /**
     * Normalize an AgentCard map to a compatible format.
     *
     * @param agentDict raw agent card as a map
     * @return normalized map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> normalize(Map<String, Object> agentDict) {
        if (agentDict == null) {
            return agentDict;
        }
        Map<String, Object> result = new LinkedHashMap<>(agentDict);

        if (result.containsKey("securitySchemes")) {
            result.put("securitySchemes", normalizeSecuritySchemes(result.get("securitySchemes")));
        }
        if (result.containsKey("securityRequirements")) {
            result.put("securityRequirements", normalizeSecurityRequirements(result.get("securityRequirements")));
        }
        // Auto-populate securityRequirements from securitySchemes if missing
        Object secSchemes = result.get("securitySchemes");
        if (secSchemes instanceof Map && !((Map<?, ?>) secSchemes).isEmpty()
                && !(result.get("securityRequirements") instanceof List
                     && !((List<?>) result.get("securityRequirements")).isEmpty())) {
            List<String> names = new ArrayList<>(((Map<String, Object>) secSchemes).keySet());
            List<Map<String, Object>> reqs = new ArrayList<>();
            Map<String, Object> schemesMap = new LinkedHashMap<>();
            for (String s : names) {
                schemesMap.put(s, Map.of());
            }
            reqs.add(Map.of("schemes", schemesMap));
            result.put("securityRequirements", reqs);
            log.info("Auto-populated securityRequirements from securitySchemes: {}", names);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeSecuritySchemes(Object secSchemes) {
        if (!(secSchemes instanceof Map)) {
            return secSchemes != null ? (Map<String, Object>) secSchemes : Map.of();
        }
        Map<String, Object> input = (Map<String, Object>) secSchemes;
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String name = entry.getKey();
            Object schemeObj = entry.getValue();
            if (!(schemeObj instanceof Map)) {
                result.put(name, schemeObj);
                continue;
            }
            Map<String, Object> scheme = (Map<String, Object>) schemeObj;
            // Already in structured format
            boolean alreadyStructured = PROTO_ONEOF_KEYS.stream().anyMatch(scheme::containsKey);
            if (alreadyStructured) {
                result.put(name, scheme);
                continue;
            }
            // OpenAPI-style: flat "scheme": "bearer"
            Object schemeField = scheme.get("scheme");
            if (schemeField instanceof String) {
                Map<String, Object> httpAuth = new LinkedHashMap<>();
                httpAuth.put("scheme", schemeField);
                copyIfPresent(scheme, httpAuth, "description");
                copyIfPresent(scheme, httpAuth, "bearerFormat");
                result.put(name, Map.of("httpAuthSecurityScheme", httpAuth));
                continue;
            }
            // OpenAPI-style: apiKey
            if ("apiKey".equals(scheme.get("type"))) {
                Map<String, Object> apiKey = new LinkedHashMap<>();
                copyIfPresent(scheme, apiKey, "in", "location");
                copyIfPresent(scheme, apiKey, "name");
                copyIfPresent(scheme, apiKey, "description");
                result.put(name, Map.of("apiKeySecurityScheme", apiKey));
                continue;
            }
            result.put(name, scheme);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> normalizeSecurityRequirements(Object secReqs) {
        if (!(secReqs instanceof List)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : (List<?>) secReqs) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> req = (Map<String, Object>) item;
            Object schemes = req.get("schemes");
            if (schemes instanceof List) {
                Map<String, Object> schemesMap = new LinkedHashMap<>();
                for (Object s : (List<?>) schemes) {
                    schemesMap.put(s.toString(), Map.of());
                }
                result.add(Map.of("schemes", schemesMap));
            } else if (schemes instanceof Map) {
                result.add(req);
            } else {
                result.add(req);
            }
        }
        return result;
    }

    private static void copyIfPresent(Map<String, Object> src, Map<String, Object> dst, String... keys) {
        for (String key : keys) {
            if (src.containsKey(key)) {
                String dstKey = keys.length > 1 ? keys[1] : key;
                dst.put(dstKey, src.get(key));
            }
        }
    }
}
