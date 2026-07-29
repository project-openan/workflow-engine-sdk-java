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

import net.openan.a2at.sdk.llm.LLMClient;
import net.openan.a2at.sdk.llm.LLMClientConfig;
import net.openan.a2at.sdk.llm.LLMClientFactory;
import net.openan.a2at.sdk.llm.LLMConfigLoader;
import net.openan.a2at.sdk.llm.LLMResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin LLM helper shared by the sample agents and the workbench control point. Loads the A2A-T .env
 * once and builds the OpenAI-compatible (deepseek) client via LLMClientFactory. Every call asks for
 * a strict JSON object with a single "text" field, so output is parseable for both diagnosis prose
 * and route decisions. All call sites MUST pass a deterministic fallback: when the .env is absent,
 * the client fails to init, or the network call errors, the helper returns null and the caller uses
 * its fallback. This keeps the deterministic unit tests green while the demo drives the real model.
 */
public final class LlmHelper {

    private static final Logger log = LoggerFactory.getLogger(LlmHelper.class);
    private static volatile LLMClient shared;

    private LlmHelper() {}

    public static String text(String envPath, String system, String user, String fallback) {
        LLMClient client = client(envPath);
        if (client == null) {
            return fallback;
        }
        try {
            Map<String, String> sys = new LinkedHashMap<>();
            sys.put("role", "system");
            sys.put("content", system);
            Map<String, String> usr = new LinkedHashMap<>();
            usr.put("role", "user");
            usr.put("content", user);
            Map<String, Object> textProp = new LinkedHashMap<>();
            textProp.put("type", "string");
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", Map.of("text", textProp));
            schema.put("required", List.of("text"));
            schema.put("additionalProperties", false);
            LLMResponse resp = client.structured(List.of(sys, usr), schema, 0.0, 1000);
            String content = resp != null ? resp.content() : null;
            String extracted = extractTextField(content);
            if (extracted == null || extracted.isBlank()) {
                log.warn("[LlmHelper] empty model reply, using fallback");
                return fallback;
            }
            return extracted;
        } catch (Exception e) {
            log.warn("[LlmHelper] LLM call failed, using fallback: {}", e.getMessage());
            return fallback;
        }
    }

    private static LLMClient client(String envPath) {
        if (envPath == null || envPath.isBlank() || Boolean.getBoolean("a2at.llm.disabled")) {
            return null;
        }
        if (shared != null) {
            return shared;
        }
        synchronized (LlmHelper.class) {
            if (shared != null) {
                return shared;
            }
            try {
                LLMClientConfig config = LLMConfigLoader.load(Path.of(envPath));
                shared = LLMClientFactory.create(config.provider(), config);
                log.info(
                        "[LlmHelper] LLM client ready: provider={}, model={}, baseUrl={}",
                        config.provider(),
                        config.model(),
                        config.baseUrl());
                return shared;
            } catch (Throwable t) {
                log.warn(
                        "[LlmHelper] LLM client init failed (extensions will fall back): {}",
                        t.getMessage());
                return null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static String extractTextField(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String trimmed = content.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return trimmed;
        }
        String json = trimmed.substring(start, end + 1);
        try {
            Map<String, Object> parsed =
                    new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
            Object text = parsed.get("text");
            return text != null ? text.toString() : null;
        } catch (Exception e) {
            return trimmed;
        }
    }
}
