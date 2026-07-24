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

package com.openan.a2at.engine;

import com.openan.a2at.engine.control.EventType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies EventType constants match the Python SDK values.
 */
class EventTypeTest {

    @Test
    void lifecycleEventsMatchPython() {
        assertEquals("start", EventType.START);
        assertEquals("complete", EventType.COMPLETE);
        assertEquals("close", EventType.CLOSE);
        assertEquals("error", EventType.ERROR);
    }

    @Test
    void stepTaskEventsMatchPython() {
        assertEquals("step_start", EventType.STEP_START);
        assertEquals("step_complete", EventType.STEP_COMPLETE);
        assertEquals("task_request", EventType.TASK_REQUEST);
        assertEquals("task_response", EventType.TASK_RESPONSE);
        assertEquals("task_status_changed", EventType.TASK_STATUS_CHANGED);
        assertEquals("route_decision", EventType.ROUTE_DECISION);
        assertEquals("workflow_complete", EventType.WORKFLOW_COMPLETE);
    }

    @Test
    void agentTrafficEventsMatchPython() {
        assertEquals("agent_request", EventType.AGENT_REQUEST);
        assertEquals("agent_response", EventType.AGENT_RESPONSE);
    }

    @Test
    void intermediateAgentEventsExist() {
        assertEquals("agent_status_update", EventType.AGENT_STATUS_UPDATE);
        assertEquals("agent_artifact_update", EventType.AGENT_ARTIFACT_UPDATE);
        assertEquals("agent_message_event", EventType.AGENT_MESSAGE_EVENT);
    }

    @Test
    void extensionEventsMatchPython() {
        assertEquals("negotiation_request", EventType.NEGOTIATION_REQUEST);
        assertEquals("negotiation_resolved", EventType.NEGOTIATION_RESOLVED);
        assertEquals("negotiation_failed", EventType.NEGOTIATION_FAILED);
        assertEquals("authorization_request", EventType.AUTHORIZATION_REQUEST);
        assertEquals("authorization_resolved", EventType.AUTHORIZATION_RESOLVED);
        assertEquals("notification", EventType.NOTIFICATION);
    }
}
