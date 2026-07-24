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

package com.openan.a2at.engine.client;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.a2aproject.sdk.spec.APIKeySecurityScheme;
import org.a2aproject.sdk.spec.HTTPAuthSecurityScheme;
import org.a2aproject.sdk.spec.MutualTLSSecurityScheme;
import org.a2aproject.sdk.spec.OAuth2SecurityScheme;
import org.a2aproject.sdk.spec.OpenIdConnectSecurityScheme;
import org.a2aproject.sdk.spec.SecurityScheme;

import java.io.IOException;
import java.util.Map;

/**
 * Jackson module that teaches Jackson how to deserialize the
 * {@link SecurityScheme} interface by inspecting the oneOf key
 * (e.g. "httpAuthSecurityScheme", "apiKeySecurityScheme").
 *
 * <p>The A2A-T AgentCard JSON uses a discriminated oneOf structure for
 * security schemes: each scheme object has exactly one key that
 * identifies the concrete type. This module maps those keys to the
 * SDK's concrete SecurityScheme record implementations.
 */
public final class AgentCardJacksonModule extends SimpleModule {

    public AgentCardJacksonModule() {
        addDeserializer(SecurityScheme.class, new SecuritySchemeDeserializer());
        addDeserializer(org.a2aproject.sdk.spec.SecurityRequirement.class, new SecurityRequirementDeserializer());
    }

    public static final class SecuritySchemeDeserializer extends StdDeserializer<SecurityScheme> {

        private static final Map<String, String> TYPE_KEYS = Map.of(
                "httpAuthSecurityScheme", "httpAuthSecurityScheme",
                "apiKeySecurityScheme", "apiKeySecurityScheme",
                "oauth2SecurityScheme", "oauth2SecurityScheme",
                "openIdConnectSecurityScheme", "openIdConnectSecurityScheme",
                "mtlsSecurityScheme", "mtlsSecurityScheme");

        public SecuritySchemeDeserializer() {
            super(SecurityScheme.class);
        }

        @Override
        public SecurityScheme deserialize(JsonParser p, DeserializationContext ctxt)
                throws IOException, JsonProcessingException {
            JsonNode node = p.getCodec().readTree(p);
            for (var entry : TYPE_KEYS.entrySet()) {
                if (node.has(entry.getKey())) {
                    JsonNode inner = node.get(entry.getKey());
                    return switch (entry.getKey()) {
                        case "httpAuthSecurityScheme" -> new HTTPAuthSecurityScheme(
                                textOrNull(inner, "bearerFormat"),
                                textOrNull(inner, "scheme"),
                                textOrNull(inner, "description"));
                        case "apiKeySecurityScheme" -> new APIKeySecurityScheme(
                                inner.has("in") && inner.get("in").isTextual()
                                        ? APIKeySecurityScheme.Location.valueOf(inner.get("in").asText().toUpperCase())
                                        : null,
                                textOrNull(inner, "name"),
                                textOrNull(inner, "description"));
                        case "mtlsSecurityScheme" -> new MutualTLSSecurityScheme(
                                textOrNull(inner, "description"));
                        case "oauth2SecurityScheme" -> new OAuth2SecurityScheme(
                                null,
                                textOrNull(inner, "description"),
                                textOrNull(inner, "oauth2MetadataUrl"));
                        case "openIdConnectSecurityScheme" -> new OpenIdConnectSecurityScheme(
                                textOrNull(inner, "openIdConnectUrl"),
                                textOrNull(inner, "description"));
                       default -> null;
                    };
                }
            }
            return null;
        }

        private static String textOrNull(JsonNode node, String field) {
            return node.has(field) ? node.get(field).asText() : null;
        }
    }

    /**
     * Deserializes SecurityRequirement from JSON like:
     *   {"schemes": {"bearerAuth": {}}}
     * The SDK type is Map<String, List<String>>, so empty objects become empty lists.
     */
    public static final class SecurityRequirementDeserializer
            extends StdDeserializer<org.a2aproject.sdk.spec.SecurityRequirement> {

        public SecurityRequirementDeserializer() {
            super(org.a2aproject.sdk.spec.SecurityRequirement.class);
        }

        @Override
        public org.a2aproject.sdk.spec.SecurityRequirement deserialize(
                JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            var schemes = new java.util.LinkedHashMap<String, java.util.List<String>>();
            if (node.has("schemes") && node.get("schemes").isObject()) {
                var fields = node.get("schemes").fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    schemes.put(entry.getKey(), java.util.List.of());
                }
            }
            return new org.a2aproject.sdk.spec.SecurityRequirement(schemes);
        }
    }
}
