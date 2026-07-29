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

import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * Configuration for DefaultWorkflowEngineClient.
 *
 * <p>Mirrors the Python SDK's {@code WorkflowEngineClient.__init__} parameters. Use the builder to
 * create an instance.
 */
@Getter
public class WorkflowEngineClientConfig {

    private final boolean sslVerify;
    private final String caCertsPath;
    private final long sendTimeoutSeconds;
    private final AuthProvider authProvider;
    private final String credentialsConfigPath;
    private final Map<String, Map<String, Map<String, Object>>> credentialsConfig;
    private final String a2atEnvPath;
    private final int maxNegotiationRounds;
    private final List<ExtensionHandler> customHandlers;
    private final String preferredProtocol;

    private WorkflowEngineClientConfig(Builder b) {
        this.sslVerify = b.sslVerify;
        this.caCertsPath = b.caCertsPath;
        this.sendTimeoutSeconds = b.sendTimeoutSeconds;
        this.authProvider = b.authProvider;
        this.credentialsConfigPath = b.credentialsConfigPath;
        this.credentialsConfig = b.credentialsConfig;
        this.a2atEnvPath = b.a2atEnvPath;
        this.maxNegotiationRounds = b.maxNegotiationRounds;
        this.customHandlers = b.customHandlers;
        this.preferredProtocol = b.preferredProtocol;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private boolean sslVerify = true;
        private String caCertsPath = null;
        private long sendTimeoutSeconds = 600;
        private AuthProvider authProvider;
        private String credentialsConfigPath = null;
        private Map<String, Map<String, Map<String, Object>>> credentialsConfig = null;
        private String a2atEnvPath = null;
        private int maxNegotiationRounds = 3;
        private List<ExtensionHandler> customHandlers = null;
        private String preferredProtocol = null;

        public Builder sslVerify(boolean v) {
            this.sslVerify = v;
            return this;
        }

        public Builder sendTimeoutSeconds(long v) {
            this.sendTimeoutSeconds = v;
            return this;
        }

        public Builder authProvider(AuthProvider v) {
            this.authProvider = v;
            return this;
        }

        public Builder caCertsPath(String v) {
            this.caCertsPath = v;
            return this;
        }

        public Builder credentialsConfigPath(String v) {
            this.credentialsConfigPath = v;
            return this;
        }

        public Builder credentialsConfig(Map<String, Map<String, Map<String, Object>>> v) {
            this.credentialsConfig = v;
            return this;
        }

        public Builder a2atEnvPath(String v) {
            this.a2atEnvPath = v;
            return this;
        }

        public Builder maxNegotiationRounds(int v) {
            this.maxNegotiationRounds = v;
            return this;
        }

        public Builder preferredProtocol(String v) {
            this.preferredProtocol = v;
            return this;
        }

        public Builder customHandlers(List<ExtensionHandler> v) {
            this.customHandlers = v;
            return this;
        }

        public WorkflowEngineClientConfig build() {
            return new WorkflowEngineClientConfig(this);
        }
    }
}
