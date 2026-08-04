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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** Tests for AgentCardNormalizer: OpenAPI -> structured format conversion. */
class AgentCardNormalizerTest {

    @Test
    void normalizeNoOpWhenAlreadyStructured() {
        Map<String, Object> card =
                Map.of(
                        "name",
                        "Agent1",
                        "securitySchemes",
                        Map.of(
                                "bearerAuth",
                                Map.of("httpAuthSecurityScheme", Map.of("scheme", "bearer"))));
        Map<String, Object> result = AgentCardNormalizer.normalize(card);
        assertEquals("Agent1", result.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> schemes = (Map<String, Object>) result.get("securitySchemes");
        assertNotNull(schemes.get("bearerAuth"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertsOpenApiBearerScheme() {
        Map<String, Object> card =
                Map.of(
                        "name",
                        "Agent1",
                        "securitySchemes",
                        Map.of(
                                "bearerAuth",
                                Map.of("scheme", "bearer", "description", "Bearer auth")));
        Map<String, Object> result = AgentCardNormalizer.normalize(card);
        Map<String, Object> schemes = (Map<String, Object>) result.get("securitySchemes");
        Map<String, Object> bearer = (Map<String, Object>) schemes.get("bearerAuth");
        assertNotNull(bearer.get("httpAuthSecurityScheme"));
        Map<String, Object> httpAuth = (Map<String, Object>) bearer.get("httpAuthSecurityScheme");
        assertEquals("bearer", httpAuth.get("scheme"));
        assertEquals("Bearer auth", httpAuth.get("description"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertsOpenApiApiKeyScheme() {
        Map<String, Object> card =
                Map.of(
                        "name",
                        "Agent1",
                        "securitySchemes",
                        Map.of(
                                "apiKeyAuth",
                                Map.of("type", "apiKey", "in", "header", "name", "X-API-Key")));
        Map<String, Object> result = AgentCardNormalizer.normalize(card);
        Map<String, Object> schemes = (Map<String, Object>) result.get("securitySchemes");
        Map<String, Object> apiKeyWrapper = (Map<String, Object>) schemes.get("apiKeyAuth");
        assertNotNull(apiKeyWrapper.get("apiKeySecurityScheme"));
        Map<String, Object> apiKey =
                (Map<String, Object>) apiKeyWrapper.get("apiKeySecurityScheme");
        assertEquals("header", apiKey.get("location"));
        assertEquals("X-API-Key", apiKey.get("name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void autoPopulatesSecurityRequirementsFromSchemes() {
        Map<String, Object> card =
                Map.of(
                        "name",
                        "Agent1",
                        "securitySchemes",
                        Map.of(
                                "bearerAuth",
                                Map.of("httpAuthSecurityScheme", Map.of("scheme", "bearer"))));
        Map<String, Object> result = AgentCardNormalizer.normalize(card);
        Object secReqs = result.get("securityRequirements");
        assertNotNull(secReqs);
        List<Map<String, Object>> reqs = (List<Map<String, Object>>) secReqs;
        assertEquals(1, reqs.size());
        Map<String, Object> schemesMap = (Map<String, Object>) reqs.get(0).get("schemes");
        assertNotNull(schemesMap.get("bearerAuth"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void normalizesListStyleSecurityRequirements() {
        Map<String, Object> card =
                Map.of(
                        "name", "Agent1",
                        "securitySchemes",
                                Map.of(
                                        "bearerAuth",
                                        Map.of(
                                                "httpAuthSecurityScheme",
                                                Map.of("scheme", "bearer"))),
                        "securityRequirements", List.of(Map.of("schemes", List.of("bearerAuth"))));
        Map<String, Object> result = AgentCardNormalizer.normalize(card);
        List<Map<String, Object>> reqs =
                (List<Map<String, Object>>) result.get("securityRequirements");
        Map<String, Object> schemesMap = (Map<String, Object>) reqs.get(0).get("schemes");
        assertNotNull(schemesMap.get("bearerAuth"));
    }

    @Test
    void normalizeReturnsInputWhenNotMap() {
        Object result = AgentCardNormalizer.normalize(null);
        assertNull(result);
    }

    @Test
    void preservesNameAndCapabilities() {
        Map<String, Object> card =
                Map.of(
                        "name", "MyAgent",
                        "description", "An agent",
                        "capabilities", Map.of("streaming", true));
        Map<String, Object> result = AgentCardNormalizer.normalize(card);
        assertEquals("MyAgent", result.get("name"));
        assertEquals("An agent", result.get("description"));
    }
}
