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

package dev.openan.workflow.engine.observability;

import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowStep;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import net.openan.observability.LogSeverity;
import net.openan.observability.Observability;
import net.openan.observability.ObservationSpan;
import net.openan.observability.SignalKind;

/** Emits workflow and step signals through a host-provided Observability SDK API. */
public final class WorkflowObservability {
  private final Observability observe;
  private final String workflowId;
  private final String workflowType;
  private final long workflowStartedNanos;
  private final ObservationSpan workflowSpan;

  public WorkflowObservability(Observability observe, Workflow workflow, String executionId) {
    this.observe = observe;
    this.workflowId = valueOr(workflow.getId(), executionId);
    this.workflowType = valueOr(workflow.getName(), "workflow");
    this.workflowStartedNanos = System.nanoTime();
    this.workflowSpan =
        observe.trace("a2at.workflow", SignalKind.INTERNAL, workflowAttributes("running"));
    observe.log(
        "gen_ai.agent.a2at.workflow",
        LogSeverity.INFO,
        "workflow started",
        workflowAttributes("running"),
        workflowSpan);
  }

  public StepObservation startStep(WorkflowStep step, int index) {
    String name = valueOr(step.getName(), "unnamed");
    String ownerAgent = ownerAgent(step);
    Map<String, Object> attributes = stepAttributes(name, index, ownerAgent, "running");
    ObservationSpan span =
        observe.trace("a2at.step." + name, SignalKind.INTERNAL, attributes, workflowSpan);
    observe.log("gen_ai.agent.a2at.step", LogSeverity.INFO, "step started", attributes, span);
    return new StepObservation(name, index, ownerAgent, System.nanoTime(), span);
  }

  public void finishStep(StepObservation step, boolean success, Throwable error) {
    String status = success ? "completed" : "failed";
    Map<String, Object> attributes = stepAttributes(step.name, step.index, step.ownerAgent, status);
    step.span.attributes(attributes);
    if (error == null && success) step.span.ok();
    else if (error != null) step.span.error(error);
    else step.span.error();
    observe.log(
        "gen_ai.agent.a2at.step",
        success ? LogSeverity.INFO : LogSeverity.ERROR,
        success ? "step completed" : "step failed",
        attributes,
        step.span);
    observe.metric(
        "step.duration",
        elapsedMillis(step.startedNanos),
        Map.of("workflow.step", step.name, "gen_ai.agent.name", step.ownerAgent));
    step.span.close();
  }

  /** Runs asynchronous step work with the step span restored as the current context. */
  public <T> T withStepContext(StepObservation step, Supplier<T> action) {
    Objects.requireNonNull(action, "action");
    if (step == null) return action.get();
    try (var scope = step.span.makeCurrent()) {
      return action.get();
    }
  }

  /** Runs asynchronous step work with the step span restored as the current context. */
  public void withStepContext(StepObservation step, Runnable action) {
    withStepContext(
        step,
        () -> {
          action.run();
          return null;
        });
  }

  public void finishWorkflow(boolean success, Throwable error) {
    String status = success ? "completed" : "failed";
    Map<String, Object> attributes = workflowAttributes(status);
    workflowSpan.attributes(attributes);
    if (error == null && success) workflowSpan.ok();
    else if (error != null) workflowSpan.error(error);
    else workflowSpan.error();
    observe.log(
        "gen_ai.agent.a2at.workflow",
        success ? LogSeverity.INFO : LogSeverity.ERROR,
        success ? "workflow completed" : "workflow failed",
        attributes,
        workflowSpan);
    Map<String, Object> metric = Map.of("workflow.type", workflowType, "workflow.status", status);
    observe.metric("workflow.duration", elapsedMillis(workflowStartedNanos), metric);
    observe.metric("workflow.count", 1, metric);
    workflowSpan.close();
  }

  private Map<String, Object> workflowAttributes(String status) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("workflow.id", workflowId);
    attributes.put("workflow.type", workflowType);
    attributes.put("workflow.status", status);
    return attributes;
  }

  private static Map<String, Object> stepAttributes(
      String name, int index, String ownerAgent, String status) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("workflow.step", name);
    attributes.put("workflow.step.index", index);
    attributes.put("workflow.step.status", status);
    attributes.put("workflow.step.owner_agent", ownerAgent);
    return attributes;
  }

  private static String ownerAgent(WorkflowStep step) {
    if (step.getSubtasks() == null || step.getSubtasks().isEmpty()) return "workflow-engine";
    return valueOr(step.getSubtasks().get(0).getAgent(), "workflow-engine");
  }

  private static String valueOr(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static long elapsedMillis(long startedNanos) {
    return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }

  /** A step span and its timing state. */
  public static final class StepObservation {
    private final String name;
    private final int index;
    private final String ownerAgent;
    private final long startedNanos;
    private final ObservationSpan span;

    private StepObservation(
        String name, int index, String ownerAgent, long startedNanos, ObservationSpan span) {
      this.name = name;
      this.index = index;
      this.ownerAgent = ownerAgent;
      this.startedNanos = startedNanos;
      this.span = span;
    }
  }
}
