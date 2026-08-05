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

package dev.openan.workflow.engine.examples.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot Workbench Agent -- northbound A2A server.
 *
 * <p>This is the Workbench Agent itself: a Spring Boot service that exposes A2A-T endpoints
 * (message:send, message:stream) via the {@code spring-boot-starter} auto-configuration. The
 * business logic lives in {@link SpringWorkbenchExecutor} (implements {@code AgentExecutor}).
 *
 * <p>Demo orchestration (starting OMC agents, sending Task-T, shutting down) is handled separately
 * by {@link SpringSpnDemo}, mirroring the existing {@code SpnCrossCityDiagnosisDemo} pattern.
 *
 * <p>Can also be run standalone as a Spring Boot service (no demo logic).
 */
@SpringBootApplication
public class SpringWorkbenchApplication {

    private static final Logger log = LoggerFactory.getLogger(SpringWorkbenchApplication.class);

    public static void main(String[] args) {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        SpringApplication.run(SpringWorkbenchApplication.class, args);
        log.info("[SpringWorkbench] A2A server started -- ready to receive A2A-T messages");
    }
}
