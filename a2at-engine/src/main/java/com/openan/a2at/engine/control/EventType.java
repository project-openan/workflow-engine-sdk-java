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

package com.openan.a2at.engine.control;

/** Execution event type constants. */
public final class EventType {
    public static final String STEP_START = "step_start";
    public static final String STEP_COMPLETE = "step_complete";
    public static final String TASK_REQUEST = "task_request";
    public static final String TASK_RESPONSE = "task_response";
    public static final String TASK_STATUS_CHANGED = "task_status_changed";
    public static final String ROUTE_DECISION = "route_decision";
    public static final String ERROR = "error";
    public static final String WORKFLOW_COMPLETE = "workflow_complete";
   public static final String AGENT_REQUEST = "agent_request";
   public static final String AGENT_RESPONSE = "agent_response";
    public static final String AGENT_STATUS_UPDATE = "agent_status_update";
    public static final String AGENT_ARTIFACT_UPDATE = "agent_artifact_update";
    public static final String AGENT_MESSAGE_EVENT = "agent_message_event";
    public static final String NEGOTIATION_REQUEST = "negotiation_request";
    public static final String NEGOTIATION_RESOLVED = "negotiation_resolved";
    public static final String NEGOTIATION_FAILED = "negotiation_failed";
    public static final String AUTHORIZATION_REQUEST = "authorization_request";
    public static final String AUTHORIZATION_RESOLVED = "authorization_resolved";
    public static final String NOTIFICATION = "notification";
    public static final String START = "start";
    public static final String COMPLETE = "complete";
    public static final String CLOSE = "close";

    private EventType() {}
}
