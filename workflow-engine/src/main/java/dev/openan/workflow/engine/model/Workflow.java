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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A directed workflow definition: an ordered list of {@link WorkflowStep steps} wired by jump
 * conditions. The executor validates the graph before running (duplicate, missing, reserved, or
 * cyclic step names fail fast) and takes a private snapshot at construction, so runtime status
 * changes never leak back into the caller's instance.
 */
@Data
@NoArgsConstructor
@Builder
public class Workflow {
  @Builder.Default private String id = "";
  @Builder.Default private String name = "";
  @Builder.Default private String description = "";
  @Builder.Default private List<WorkflowStep> steps = List.of();

  /** All-args constructor with defensive copies; Lombok's builder routes through it. */
  public Workflow(String id, String name, String description, List<WorkflowStep> steps) {
    this.id = id == null ? "" : id;
    this.name = name == null ? "" : name;
    this.description = description == null ? "" : description;
    this.steps = steps == null ? List.of() : new ArrayList<>(steps);
  }

  /** Unmodifiable view; steps are snapshots of the loaded definition. */
  public List<WorkflowStep> getSteps() {
    return steps == null ? null : Collections.unmodifiableList(steps);
  }

  /** Stores a defensive copy of the supplied step list. */
  public void setSteps(List<WorkflowStep> steps) {
    this.steps = steps == null ? List.of() : new ArrayList<>(steps);
  }

  /**
   * Builds a workflow from a PSOP-style map (as returned by the orchestration center).
   *
   * @throws IllegalArgumentException if the map is null, or any field has an unexpected type
   */
  @SuppressWarnings("unchecked")
  public static Workflow fromMap(Map<String, Object> data) {
    if (data == null) throw new IllegalArgumentException("Workflow data must not be null");
    Workflow wf = new Workflow();
    wf.setId(stringValue(data, "id", ""));
    wf.setName(stringValue(data, "name", ""));
    wf.setDescription(stringValue(data, "description", ""));
    List<WorkflowStep> steps = parseSteps(mapList(data.get("steps"), "steps"));
    wf.setSteps(steps);
    return wf;
  }

  private static List<WorkflowStep> parseSteps(List<Map<String, Object>> stepList) {
    List<WorkflowStep> steps = new ArrayList<>();
    for (Map<String, Object> s : stepList) {
      List<Task> subtasks = parseSubtasks(mapList(s.get("subtasks"), "steps[].subtasks"));
      List<JumpCondition> nextList = parseNextSteps(mapList(s.get("next"), "steps[].next"));
      List<String> contextFrom = parseContextFrom(s.get("context_from"));
      int layer = s.get("layer") instanceof Number num ? num.intValue() : 0;
      Object rawStepType = s.containsKey("step_type") ? s.get("step_type") : s.get("type");
      String stValue = rawStepType == null ? "AllSuccess" : requireString(rawStepType, "step_type");
      steps.add(
          WorkflowStep.builder()
              .name(stringValue(s, "name", ""))
              .subtasks(subtasks)
              .next(nextList)
              .layer(layer)
              .contextFrom(contextFrom)
              .stepType(StepType.fromValue(stValue))
              .build());
    }
    return steps;
  }

  private static List<Task> parseSubtasks(List<Map<String, Object>> stList) {
    List<Task> subtasks = new ArrayList<>();
    for (Map<String, Object> t : stList) {
      subtasks.add(
          Task.builder()
              .agent(stringValue(t, "agent", ""))
              .skill(stringValue(t, "skill", ""))
              .description(stringValue(t, "description", ""))
              .input(parseBusinessInput(t.get("input")))
              .build());
    }
    return subtasks;
  }

  private static BusinessInput parseBusinessInput(Object raw) {
    if (raw == null) return null;
    if (!(raw instanceof Map<?, ?> input)) {
      throw new IllegalArgumentException("subtask input must be an object with text or data");
    }
    if (input.size() != 1 || input.containsKey("text") == input.containsKey("data")) {
      throw new IllegalArgumentException("subtask input must contain exactly one of text or data");
    }
    Object value = input.get(input.containsKey("text") ? "text" : "data");
    if (value == null) {
      throw new IllegalArgumentException("subtask input value must not be null");
    }
    return input.containsKey("text")
        ? BusinessInput.text(requireString(value, "subtask input text"))
        : BusinessInput.data(value);
  }

  private static List<JumpCondition> parseNextSteps(List<Map<String, Object>> jcList) {
    List<JumpCondition> nextList = new ArrayList<>();
    for (Map<String, Object> jc : jcList) {
      nextList.add(
          JumpCondition.builder()
              .step(stringValue(jc, "step", ""))
              .condition(stringValue(jc, "condition", ""))
              .build());
    }
    return nextList;
  }

  private static List<String> parseContextFrom(Object cfRaw) {
    if (cfRaw instanceof List<?> values) {
      List<String> result = new ArrayList<>(values.size());
      for (Object value : values) result.add(requireString(value, "context_from[]"));
      return List.copyOf(result);
    }
    if (cfRaw instanceof String cfStr && !cfStr.isEmpty()) return List.of(cfStr);
    if (cfRaw != null && !(cfRaw instanceof String)) {
      throw new IllegalArgumentException("context_from must be a string or a list of strings");
    }
    return null;
  }

  private static String stringValue(Map<String, Object> data, String key, String fallback) {
    Object value = data.get(key);
    return value == null ? fallback : requireString(value, key);
  }

  private static String requireString(Object value, String field) {
    if (value instanceof String text) return text;
    throw new IllegalArgumentException(field + " must be a string");
  }

  private static List<Map<String, Object>> mapList(Object value, String field) {
    if (value == null) return List.of();
    if (!(value instanceof List<?> values)) {
      throw new IllegalArgumentException(field + " must be a list");
    }
    List<Map<String, Object>> result = new ArrayList<>(values.size());
    for (Object item : values) {
      if (!(item instanceof Map<?, ?> raw)) {
        throw new IllegalArgumentException(field + " entries must be objects");
      }
      Map<String, Object> entry = new java.util.LinkedHashMap<>();
      for (Map.Entry<?, ?> fieldEntry : raw.entrySet()) {
        if (!(fieldEntry.getKey() instanceof String key)) {
          throw new IllegalArgumentException(field + " object keys must be strings");
        }
        entry.put(key, fieldEntry.getValue());
      }
      result.add(java.util.Collections.unmodifiableMap(entry));
    }
    return List.copyOf(result);
  }
}
