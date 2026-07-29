/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package com.openan.a2at.engine.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the A2A-T Spring Boot starter.
 *
 * <p>Partners set {@code a2at.server.agent-card} to point at their AgentCard JSON file. Everything
 * else (port, SSL, logging) uses the partner's existing Spring Boot configuration.
 */
@ConfigurationProperties(prefix = "a2at.server")
public class A2AProperties {

    /** Path to the AgentCard JSON file (classpath: or file: prefix supported). */
    private String agentCard = "classpath:agentcard.json";

    /** URL path prefix for A2A endpoints (extracted from AgentCard by default). */
    private String pathPrefix = "/a2a/json";

    public String getAgentCard() {
        return agentCard;
    }

    public void setAgentCard(String agentCard) {
        this.agentCard = agentCard;
    }

    public String getPathPrefix() {
        return pathPrefix;
    }

    public void setPathPrefix(String pathPrefix) {
        this.pathPrefix = pathPrefix;
    }
}
