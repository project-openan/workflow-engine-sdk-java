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

package com.openan.a2at.engine.client;

/**
 * A2A-T extension types supported by the workflow execution engine.
 *
 * <p>Each enum constant encapsulates the full extension URI so callers never need to hardcode URI
 * strings. Use these with {@link WorkflowEngineClient#sendExtensionMessage}.
 *
 * <p>The engine handles these extensions automatically:
 *
 * <ul>
 *   <li>{@link #TASK_T} - structured task prompt generation (in-workflow)
 *   <li>{@link #NEGOTIATION_T} - negotiation auto-loop (in-workflow)
 *   <li>{@link #AUTHORIZATION_T} - whitelist pre-positioning (before workflow)
 *   <li>{@link #NOTIFICATION_T} - result subscription pre-positioning (before workflow)
 * </ul>
 *
 * <p>{@code DATA-NEGOTIATION-T/v1} is intentionally absent -- it is an SDK-internal metadata key
 * for negotiation context, not a user-declared extension.
 */
public enum A2ATExtension {

    /** Structured task prompt. Handled automatically during workflow execution. */
    TASK_T("https://projects.tmforum.org/a2aproject/telecommunication/extensions/Task-T/v1"),

    /** Negotiation text exchange. Handled automatically via auto-loop. */
    NEGOTIATION_T(
            "https://projects.tmforum.org/a2aproject/telecommunication/extensions/NEGOTIATION-T"),

    /** Authorization whitelist. Pre-positioned before workflow starts. */
    AUTHORIZATION_T(
            "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Authorization-T/v1"),

    /** Result notification subscription. Pre-positioned before workflow starts. */
    NOTIFICATION_T(
            "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Notification-T/v1");

    private final String uri;

    A2ATExtension(String uri) {
        this.uri = uri;
    }

    /**
     * @return the full extension URI used as metadata key and A2A-Extensions header value
     */
    public String uri() {
        return uri;
    }

    /**
     * @return short display name (e.g. {@code "Authorization-T"})
     */
    public String displayName() {
        return name().replace('_', '-');
    }
}
