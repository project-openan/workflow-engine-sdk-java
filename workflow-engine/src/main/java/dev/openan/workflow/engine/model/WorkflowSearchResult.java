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

package dev.openan.workflow.engine.model;

import dev.openan.workflow.engine.registry.LoadPsop;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Summary of a PSOP workflow returned by the search endpoint.
 *
 * <p>Mirrors the Python orchestration center's {@code WorkflowSearchResult}. Returned by {@link
 * LoadPsop#search}. To get the full workflow with steps, take
 * {@code getWorkflowId()} and call {@link LoadPsop#load}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowSearchResult {
    private String workflowId;
    private String workflowType;
    private String name;
    private String description;
    @Builder.Default private List<String> tags = List.of();
    private String createdAt;
    @Builder.Default private double score = 1.0;
    private String userIntent;
    private String relatedPreflow;
    private String tasksSummary;
}
