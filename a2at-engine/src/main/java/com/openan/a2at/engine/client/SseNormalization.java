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

import java.util.Map;
import java.util.Set;

/**
 * SSE response normalization for non-standard agent responses.
 *
 * <p>Mirrors the Python SDK's {@code sse_normalization}. Some A2A agents
 * return bare Task or Message objects instead of properly wrapped SSE
 * envelopes. This utility coerces such responses into the expected shape.
 *
 * <p>Unlike the Python version (which monkey-patches protobuf's Parse),
 * the Java version provides a standalone static method that the
 * DefaultWorkflowEngineClient calls after deserializing each response.
 */
public final class SseNormalization {

    private static final Logger log = LoggerFactory.getLogger(SseNormalization.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final Set<String> STREAM_RESPONSE_KEYS = Set.of(
            "task", "message", "statusUpdate", "artifactUpdate"
    );

    private SseNormalization() {
    }

    /**
     * Coerce a non-SSE response map into a StreamResponse-shaped map.
     *
     * <p>If the data already has StreamResponse keys, it is returned as-is.
     * If it looks like a bare Task, it is wrapped in a {@code "task"} key.
     * If it looks like an Artifact update, it is wrapped in
     * {@code "artifactUpdate"}.
     *
     * @param data the raw response as a map
     * @return normalized map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> normalize(Map<String, Object> data) {
        if (data == null) {
            return data;
        }
        if (!STREAM_RESPONSE_KEYS.stream().anyMatch(data::containsKey)) {
            if (data.containsKey("id") && data.containsKey("status")) {
                log.info("[A2A] Non-SSE response detected: bare Task, wrapping");
                return Map.of("task", data);
            }
            if (data.containsKey("artifact") && data.containsKey("taskId")) {
                return Map.of("artifactUpdate", data);
            }
            if (data.containsKey("status") && data.containsKey("taskId")) {
                return Map.of("statusUpdate", data);
            }
        }
        return data;
    }
}
