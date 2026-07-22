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

import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentExtension;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.AgentSkill;

import java.util.List;
import java.util.Map;

/**
 * Converts registry-format AgentCard maps to the a2a-java SDK's typed
 * {@link AgentCard} record.
 *
 * <p>The mapping mirrors {@code RegistryAgentCardMapper.toA2AJavaAgentCard}
 * from the a2a-t-sdk-java sample module and {@code EmbeddedA2AServer.toTypedAgentCard}
 * from the samples module.
 */
public final class AgentCardMapper {

    private AgentCardMapper() {
    }

    /**
     * Convert a registry-format AgentCard map to a typed SDK AgentCard.
     *
     * @param card the agent card as a map (from JSON config or registry)
     * @return a typed AgentCard suitable for {@link org.a2aproject.sdk.client.Client}
     */
    @SuppressWarnings("unchecked")
    public static AgentCard toSdkAgentCard(Map<String, Object> card) {
        Map<String, Object> provider = (Map<String, Object>) card.get("provider");
        Map<String, Object> caps = (Map<String, Object>) card.get("capabilities");
        List<Map<String, Object>> exts = caps != null
                ? (List<Map<String, Object>>) caps.getOrDefault("extensions", List.of())
                : List.of();
        List<Map<String, Object>> skills = (List<Map<String, Object>>) card.getOrDefault("skills", List.of());
        List<Map<String, Object>> ifaces = (List<Map<String, Object>>) card.getOrDefault("supportedInterfaces", List.of());
        return new AgentCard(
                str(card.get("name")),
                str(card.get("description")),
                provider == null ? null
                        : new AgentProvider(str(provider.get("organization")), str(provider.get("url"))),
                str(card.get("version")),
                null,
                new AgentCapabilities(
                        caps != null && Boolean.TRUE.equals(caps.get("streaming")),
                        caps != null && Boolean.TRUE.equals(caps.get("pushNotifications")),
                        caps != null && Boolean.TRUE.equals(caps.get("extendedAgentCard")),
                        exts.stream()
                                .map(e -> new AgentExtension(str(e.get("description")), Map.of(), false, str(e.get("uri"))))
                                .toList()),
                strList(card.get("defaultInputModes")),
                strList(card.get("defaultOutputModes")),
                skills.stream()
                        .map(s -> new AgentSkill(
                                str(s.get("id")), str(s.get("name")), str(s.get("description")),
                                strList(s.get("tags")), List.of(), List.of(), List.of(), List.of()))
                        .toList(),
                Map.of(),
                List.of(),
                null,
                ifaces.stream()
                        .map(i -> new AgentInterface(
                                str(i.get("protocolBinding")),
                                str(i.get("url")),
                                "",
                                str(i.get("protocolVersion"))))
                        .toList(),
                List.of());
    }

    private static List<String> strList(Object value) {
        return value instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
