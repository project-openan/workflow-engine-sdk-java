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

package dev.openan.workflow.engine.core;

import dev.openan.workflow.engine.client.WireLog;
import dev.openan.workflow.engine.client.WorkflowEngineClient;
import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.control.EventType;
import dev.openan.workflow.engine.model.ExecutionResult;
import dev.openan.workflow.engine.model.JumpCondition;
import dev.openan.workflow.engine.model.NegotiationRequest;
import dev.openan.workflow.engine.model.RouteDecision;
import dev.openan.workflow.engine.model.RouteRequest;
import dev.openan.workflow.engine.model.StepType;
import dev.openan.workflow.engine.model.Task;
import dev.openan.workflow.engine.model.TaskExecutionResult;
import dev.openan.workflow.engine.model.TaskRequest;
import dev.openan.workflow.engine.model.TaskResult;
import dev.openan.workflow.engine.model.TaskStatus;
import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowInput;
import dev.openan.workflow.engine.model.WorkflowStep;
import dev.openan.workflow.engine.util.SensitiveDataRedactor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point. Traverses DAG, calls ControlPoint at decision points. Mirrors Python
 * WorkflowExecutor.
 */
public class WorkflowExecutor {
  private static final Logger log = LoggerFactory.getLogger(WorkflowExecutor.class);
  private static final Set<String> TERMINAL_ROUTES = Set.of("end", "retry", "endNode");
  private static final com.fasterxml.jackson.databind.ObjectMapper WORKFLOW_SNAPSHOT_MAPPER =
      new com.fasterxml.jackson.databind.ObjectMapper();

  private final Workflow workflow;
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean stopped = new AtomicBoolean();
  private final Set<CompletableFuture<?>> activeTasks = ConcurrentHashMap.newKeySet();
  private final ControlPoint controlPoint;
  private final WorkflowEngineClient engineClient;
  private final EventCallback eventCallback;
  private final ContextBuilder contextBuilder;
  private final String lang;
  private final String executionId = java.util.UUID.randomUUID().toString();
  private final Map<String, Map<String, Object>> stepOutputs = new ConcurrentHashMap<>();
  private final Map<String, List<TaskExecutionResult>> stepExecutionResults =
      new ConcurrentHashMap<>();
  private final List<Map<String, Object>> executionHistory =
      Collections.synchronizedList(new ArrayList<>());

  public WorkflowExecutor(
      Workflow workflow,
      ControlPoint controlPoint,
      WorkflowEngineClient engineClient,
      EventCallback eventCallback,
      String runtimeIntent,
      String lang) {
    // Definitions are mutable for JSON/builder compatibility. Snapshot them so runtime status
    // changes and caller mutations cannot leak between concurrent executions.
    this.workflow = WORKFLOW_SNAPSHOT_MAPPER.convertValue(workflow, Workflow.class);
    this.controlPoint = controlPoint;
    this.engineClient = engineClient;
    this.eventCallback = eventCallback != null ? eventCallback : new EventCallback();
    this.contextBuilder = new ContextBuilder(this.workflow, runtimeIntent);
    this.lang = lang != null ? lang : "zh";
    log.info(
        "[Executor] Workflow: {}, steps={}, lang={}",
        workflow.getName(),
        workflow.getSteps().size(),
        lang);
  }

  private static boolean isTerminalRoute(String stepName) {
    return TERMINAL_ROUTES.contains(stepName);
  }

  private static <T> void completeFrom(
      CompletableFuture<T> destination, CompletableFuture<T> source) {
    destination.whenComplete(
        (value, error) -> {
          if (error != null && !source.isDone()) source.cancel(true);
        });
    source.whenComplete(
        (value, error) -> {
          if (error != null) destination.completeExceptionally(error);
          else if (value == null)
            destination.completeExceptionally(new IllegalStateException("Callback returned null"));
          else destination.complete(value);
        });
  }

  private static List<TaskExecutionResult> collectExecutionResults(
      List<StepResult> completedResults) {
    return completedResults.stream().flatMap(result -> result.taskResults().stream()).toList();
  }

  /** Keeps every subtask result even when descriptions repeat within one parallel step. */
  private static Map<String, Object> collectTaskResults(List<StepResult> completedResults) {
    Map<String, Long> descriptionCounts =
        completedResults.stream()
            .filter(result -> result.taskDesc() != null)
            .collect(
                Collectors.groupingBy(
                    StepResult::taskDesc, LinkedHashMap::new, Collectors.counting()));
    Map<String, Object> results = new LinkedHashMap<>();
    for (StepResult result : completedResults) {
      if (result.taskDesc() == null) continue;
      boolean duplicate = descriptionCounts.getOrDefault(result.taskDesc(), 0L) > 1;
      String key =
          duplicate
              ? result.taskDesc() + " [" + result.agentName() + "#" + result.subtaskIndex() + "]"
              : result.taskDesc();
      results.put(key, result.outputs());
    }
    return results;
  }

  /**
   * Current step outputs (mutable, updated during execution). Mirrors Python SDK's {@code
   * current_step_outputs} property.
   */
  public Map<String, Map<String, Object>> getCurrentStepOutputs() {
    return new HashMap<>(stepOutputs);
  }

  /**
   * Execution history (mutable, updated during execution). Mirrors Python SDK's {@code history}
   * property.
   */
  public List<Map<String, Object>> getHistory() {
    return new ArrayList<>(executionHistory);
  }

  private void emit(String type, Map<String, Object> data) {
    try {
      Map<String, Object> correlated = new LinkedHashMap<>(data);
      correlated.put("executionId", executionId);
      eventCallback.onEvent(type, Collections.unmodifiableMap(correlated));
    } catch (Exception e) {
      log.warn("Event callback error: {}", e.getMessage());
    }
  }

  private static boolean isUnconditional(JumpCondition edge) {
    return edge.getCondition() == null || edge.getCondition().isBlank();
  }

  private CompletableFuture<Void> executeSteps(
      Deque<Integer> pending,
      Set<Integer> scheduled,
      Set<Integer> executed,
      Set<Integer> activated,
      AtomicBoolean failed) {
    if (pending.isEmpty() || failed.get() || stopped.get()) {
      return CompletableFuture.completedFuture(null);
    }

    // Collect all ready steps (predecessors complete) and deferred steps
    List<Integer> readySteps = new ArrayList<>();
    List<Integer> deferredSteps = new ArrayList<>();
    while (!pending.isEmpty()) {
      int idx = pending.pollFirst();
      if (idx >= workflow.getSteps().size() || executed.contains(idx)) {
        continue;
      }
      var step = workflow.getSteps().get(idx);
      // A direct predecessor can still be inactive while an earlier branch is running and may
      // activate it later. Wait for every active ancestor before deciding which join inputs exist.
      var ancestors = contextBuilder.getAllPredecessors(step.getName());
      boolean activePredecessorsComplete =
          ancestors.stream()
              .filter(
                  predecessor -> {
                    Integer predecessorIndex = contextBuilder.findStepIndex(predecessor);
                    return predecessorIndex != null && activated.contains(predecessorIndex);
                  })
              .allMatch(stepOutputs::containsKey);
      if (activePredecessorsComplete) {
        readySteps.add(idx);
      } else {
        deferredSteps.add(idx);
      }
    }
    // Add deferred steps back
    for (int idx : deferredSteps) {
      pending.addLast(idx);
    }
    if (readySteps.isEmpty()) {
      if (!deferredSteps.isEmpty()) {
        String details =
            deferredSteps.stream()
                .map(
                    idx -> {
                      WorkflowStep step = workflow.getSteps().get(idx);
                      List<String> missing =
                          contextBuilder.getAllPredecessors(step.getName()).stream()
                              .filter(
                                  predecessor -> {
                                    Integer predecessorIndex =
                                        contextBuilder.findStepIndex(predecessor);
                                    return predecessorIndex != null
                                        && activated.contains(predecessorIndex)
                                        && !stepOutputs.containsKey(predecessor);
                                  })
                              .toList();
                      return step.getName() + " <- " + missing;
                    })
                .collect(Collectors.joining(", "));
        return CompletableFuture.failedFuture(
            new IllegalStateException(
                "Workflow dependency deadlock; unresolved active predecessors: " + details));
      }
      return CompletableFuture.completedFuture(null);
    }
    // Execute all ready steps in parallel
    List<CompletableFuture<Void>> stepFutures = new ArrayList<>();
    for (int idx : readySteps) {
      executed.add(idx);
      var step = workflow.getSteps().get(idx);
      stepFutures.add(executeStep(step, scheduled, activated, pending, failed));
    }
    return CompletableFuture.allOf(stepFutures.toArray(new CompletableFuture[0]))
        .thenCompose(v -> executeSteps(pending, scheduled, executed, activated, failed));
  }

  private static IllegalStateException routeEvaluationFailure(
      WorkflowStep step, JumpCondition edge, Throwable error) {
    Throwable cause = unwrapCompletionException(error);
    if (cause instanceof RouteEvaluationException routeError) {
      return routeError;
    }
    return new RouteEvaluationException(
        "onRoute failed for edge "
            + step.getName()
            + " -> "
            + edge.getStep()
            + ": "
            + failureMessage(cause),
        cause);
  }

  private static String failureMessage(Throwable error) {
    Throwable cause = unwrapCompletionException(error);
    String message = cause.getMessage();
    return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
  }

  private void detectCycle(int node, List<List<Integer>> graph, int[] state) {
    if (state[node] == 2) return;
    if (state[node] == 1) {
      throw new IllegalArgumentException(
          "Workflow graph contains a cycle at step " + workflow.getSteps().get(node).getName());
    }
    state[node] = 1;
    for (int next : graph.get(node)) detectCycle(next, graph, state);
    state[node] = 2;
  }

  private CompletableFuture<StepResult> executeSubtasks(WorkflowStep step) {
    var workflowInput = contextBuilder.buildWorkflowInput(step, stepExecutionResults);
    List<CompletableFuture<StepResult>> futures = new ArrayList<>();
    for (int i = 0; i < step.getSubtasks().size(); i++) {
      final int subtaskIndex = i;
      final var task = step.getSubtasks().get(i);
      var request = buildTaskRequest(step, task, subtaskIndex, workflowInput);
      emit(
          EventType.TASK_REQUEST,
          Map.of("step", step.getName(), "agent", task.getAgent(), "task", task.getDescription()));
      CompletableFuture<TaskResult> dispatch = dispatchTask(step, request);
      CompletableFuture<StepResult> processed =
          dispatch
              .thenApply(r -> processTaskResult(step, task, subtaskIndex, r))
              .exceptionally(e -> processTaskError(step, task, subtaskIndex, e));
      processed.whenComplete(
          (result, error) -> {
            if (processed.isCancelled()) dispatch.cancel(true);
          });
      futures.add(processed);
    }
    if (step.getStepType() == StepType.ANY_SUCCESS) {
      return anySuccess(futures);
    }
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
        .thenApply(v -> collectAllSuccess(futures));
  }

  private String taskId(String stepName, int index) {
    return java.util
        .UUID
        .nameUUIDFromBytes(
            (executionId + ":" + stepName + ":" + index)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .toString();
  }

  private TaskRequest buildTaskRequest(
      WorkflowStep step, Task task, int subtaskIndex, WorkflowInput workflowInput) {
    log.info(
        "[Executor] Dispatching task: step={}, agent={}, subtask_index={}, desc={}",
        step.getName(),
        task.getAgent(),
        subtaskIndex,
        task.getDescription());
    return TaskRequest.builder()
        .agentName(task.getAgent())
        .skill(task.getSkill())
        .instruction(task.getDescription())
        .language(lang)
        .stepName(step.getName())
        .executionId(executionId)
        .taskId(taskId(step.getName(), subtaskIndex))
        .input(
            task.getInput() == null
                ? dev.openan.workflow.engine.model.BusinessInput.text(task.getDescription())
                : task.getInput())
        .workflowInput(workflowInput)
        .build();
  }

  private CompletableFuture<TaskResult> dispatchTask(WorkflowStep step, TaskRequest request) {
    CompletableFuture<TaskResult> result = new CompletableFuture<>();
    activeTasks.add(result);
    result.orTimeout(engineClient.callbackTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
    result.whenComplete((value, error) -> activeTasks.remove(result));
    CompletableFuture.runAsync(
        () -> {
          try {
            if (result.isDone() || stopped.get()) return;
            if (step.getStepType() == StepType.SELF_LOOP) {
              completeFrom(
                  result,
                  java.util.Objects.requireNonNull(
                      controlPoint.onSelfTask(request), "onSelfTask returned null future"));
              return;
            }
            var prepared =
                java.util.Objects.requireNonNull(
                    controlPoint.onTask(request), "onTask returned null future");
            result.whenComplete(
                (value, error) -> {
                  if (error != null && !prepared.isDone()) prepared.cancel(true);
                });
            prepared.whenComplete(
                (content, error) -> {
                  if (result.isDone() || stopped.get()) {
                    log.debug(
                        "[Executor] Late callback ignored step={}, taskId={}",
                        request.getStepName(),
                        request.getTaskId());
                    return;
                  }
                  if (error != null) {
                    result.completeExceptionally(error);
                    return;
                  }
                  try {
                    Map<String, String> trace =
                        Map.of(
                            "executionId", request.getExecutionId(),
                            "logicalTaskId", request.getTaskId());
                    var sent =
                        WireLog.call(
                            trace,
                            () ->
                                engineClient.sendTask(
                                    request.getAgentName(),
                                    java.util.Objects.requireNonNull(
                                        content, "onTask returned null content"),
                                    negotiation ->
                                        controlPoint.onNegotiation(
                                            new NegotiationRequest(
                                                request,
                                                negotiation.originalSubmission(),
                                                negotiation.received(),
                                                negotiation.previousExchanges(),
                                                negotiation.remainingWait())),
                                    eventCallback));
                    result.whenComplete(
                        (value, failure) -> {
                          if (!sent.isDone()) sent.cancel(true);
                        });
                    completeFrom(result, sent.thenApply(ProtocolResultAdapter::toTaskResult));
                  } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                  }
                });
          } catch (RuntimeException error) {
            result.completeExceptionally(error);
          }
        });
    return result;
  }

  private StepResult collectAllSuccess(List<CompletableFuture<StepResult>> futures) {
    List<StepResult> completedResults = new ArrayList<>();
    boolean anyFailed = false;
    for (var f : futures) {
      var r = f.join();
      completedResults.add(r);
      if (!r.success()) {
        anyFailed = true;
      }
    }
    return new StepResult(
        null,
        null,
        -1,
        List.of(),
        !anyFailed,
        collectTaskResults(completedResults),
        collectExecutionResults(completedResults));
  }

  private static Throwable unwrapCompletionException(Throwable error) {
    Throwable current = Objects.requireNonNull(error, "error");
    while (current instanceof CompletionException && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private StepResult processTaskError(WorkflowStep step, Task task, int subtaskIndex, Throwable e) {
    return processTaskResult(step, task, subtaskIndex, FailureMapping.from(e));
  }

  /**
   * ANY_SUCCESS logic: iterate futures as they complete; on the first success, cancel the rest and
   * return success=true. If all fail, return success=false. Mirrors Python's asyncio.as_completed
   * loop.
   */
  private CompletableFuture<StepResult> anySuccess(List<CompletableFuture<StepResult>> futures) {
    if (futures.isEmpty()) {
      return CompletableFuture.completedFuture(
          new StepResult(null, null, -1, List.of(), true, Map.of(), List.of()));
    }
    CompletableFuture<StepResult> result = new CompletableFuture<>();
    int total = futures.size();
    int[] completed = {0};
    int[] failedCount = {0};
    boolean[] winnerChosen = {false};

    for (CompletableFuture<StepResult> f : futures) {
      f.handle(
          (sr, ex) -> {
            synchronized (completed) {
              completed[0]++;
              boolean success = (ex == null && sr != null && sr.success());
              if (success && !result.isDone()) {
                winnerChosen[0] = true;
                // Cancel all remaining
                for (CompletableFuture<StepResult> other : futures) {
                  if (!other.isDone()) {
                    other.cancel(true);
                  }
                }
                // Collect results from completed futures
                List<StepResult> completedResults = new ArrayList<>();
                for (CompletableFuture<StepResult> cf : futures) {
                  if (cf.isDone() && !cf.isCompletedExceptionally()) {
                    try {
                      var r = cf.join();
                      if (r != null && r.taskDesc() != null) {
                        completedResults.add(r);
                      }
                    } catch (Exception ignored) {
                      // Cancellation race, skip
                    }
                  }
                }
                result.complete(
                    new StepResult(
                        null,
                        null,
                        -1,
                        List.of(),
                        true,
                        collectTaskResults(completedResults),
                        collectExecutionResults(completedResults)));
              } else if (!success && !winnerChosen[0]) {
                failedCount[0]++;
                if (completed[0] == total && !result.isDone()) {
                  // All failed
                  List<StepResult> completedResults = new ArrayList<>();
                  for (CompletableFuture<StepResult> cf : futures) {
                    if (cf.isDone() && !cf.isCompletedExceptionally()) {
                      try {
                        var r = cf.join();
                        if (r != null && r.taskDesc() != null) {
                          completedResults.add(r);
                        }
                      } catch (Exception ignored) {
                        // Cancellation race, skip
                      }
                    }
                  }
                  result.complete(
                      new StepResult(
                          null,
                          null,
                          -1,
                          List.of(),
                          false,
                          collectTaskResults(completedResults),
                          collectExecutionResults(completedResults)));
                }
              }
            }
            return null;
          });
    }
    return result;
  }

  public CompletableFuture<ExecutionResult> run() {
    if (!started.compareAndSet(false, true))
      return CompletableFuture.failedFuture(
          new IllegalStateException("WorkflowExecutor is single-use"));
    // NOTE: START lifecycle event is emitted by the runner (ExecutePsop),
    // not here. Mirrors Python SDK where the executor emits only
    // step/task/route events and the runner emits start/complete/error/close.
    log.info(
        "[Executor] Starting workflow: {} ({} steps)",
        workflow.getName(),
        workflow.getSteps().size());
    try {
      validateWorkflowGraph();
    } catch (IllegalArgumentException e) {
      log.error("[Executor] Invalid workflow graph: {}", e.getMessage());
      emit(EventType.ERROR, Map.of("error", e.getMessage()));
      return CompletableFuture.completedFuture(
          ExecutionResult.builder()
              .success(false)
              .history(new ArrayList<>(executionHistory))
              .stepOutputs(new HashMap<>(stepOutputs))
              .error(e.getMessage())
              .build());
    }
    Deque<Integer> pending = new ConcurrentLinkedDeque<>();
    Set<Integer> scheduled = ConcurrentHashMap.newKeySet();
    Set<Integer> activated = ConcurrentHashMap.newKeySet();
    for (int i = 0; i < workflow.getSteps().size(); i++) {
      var s = workflow.getSteps().get(i);
      if (contextBuilder.getStepPredecessors(s.getName()).isEmpty()) {
        pending.add(i);
        scheduled.add(i);
        activated.add(i);
      }
    }
    Set<Integer> executed = ConcurrentHashMap.newKeySet();
    AtomicBoolean failed = new AtomicBoolean();
    CompletableFuture<ExecutionResult> execution =
        executeSteps(pending, scheduled, executed, activated, failed)
            .thenApply(
                v -> {
                  emit(EventType.WORKFLOW_COMPLETE, Map.of("success", !failed.get()));
                  log.info(
                      "[Executor] WORKFLOW_FINISHED executionId={}, workflow={}, success={},"
                          + " tasks={}",
                      executionId,
                      workflow.getName(),
                      !failed.get(),
                      executionHistory.size());
                  if (failed.get()) {
                    log.warn(
                        "[Executor] WORKFLOW_STOPPED executionId={}, reason=step_failed,"
                            + " skippedSteps={}",
                        executionId,
                        java.util.stream.IntStream.range(0, workflow.getSteps().size())
                            .filter(index -> !executed.contains(index))
                            .mapToObj(index -> workflow.getSteps().get(index).getName())
                            .toList());
                  }
                  return ExecutionResult.builder()
                      .success(!failed.get())
                      .history(new ArrayList<>(executionHistory))
                      .stepOutputs(new HashMap<>(stepOutputs))
                      .error(failed.get() ? "Step execution failed" : null)
                      .build();
                })
            .exceptionally(
                e -> {
                  Throwable failure = unwrapCompletionException(e);
                  String message = failureMessage(failure);
                  log.error("[Executor] DAG traversal error: {}", message, failure);
                  emit(EventType.ERROR, Map.of("error", message));
                  return ExecutionResult.builder()
                      .success(false)
                      .history(new ArrayList<>(executionHistory))
                      .stepOutputs(new HashMap<>(stepOutputs))
                      .error(message)
                      .build();
                });
    execution.whenComplete(
        (value, error) -> {
          stopped.set(true);
          activeTasks.forEach(task -> task.cancel(true));
        });
    return execution;
  }

  private CompletableFuture<Void> executeStep(
      WorkflowStep step,
      Set<Integer> scheduled,
      Set<Integer> activated,
      Deque<Integer> pending,
      AtomicBoolean failed) {
    emit(EventType.STEP_START, Map.of("step", step.getName()));
    log.info("--- Executing step: {} ---", step.getName());
    return executeSubtasks(step)
        .thenCompose(
            result -> {
              stepOutputs.put(step.getName(), result.results());
              stepExecutionResults.put(step.getName(), result.taskResults());
              if (!result.success()) {
                log.warn(
                    "[Executor] STEP_FAILED executionId={}, step={}, action=stop-downstream",
                    executionId,
                    step.getName());
                emit(
                    EventType.ERROR,
                    Map.of(
                        "step",
                        step.getName(),
                        "results",
                        result.results(),
                        "error",
                        "Step execution failed",
                        "errorCode",
                        "workflow.step_failed"));
                failed.set(true);
                return CompletableFuture.completedFuture(null);
              }
              emit(
                  EventType.STEP_COMPLETE,
                  Map.of("step", step.getName(), "results", result.results()));
              return determineNextSteps(step)
                  .thenAccept(
                      nextIndices -> {
                        for (int i = nextIndices.size() - 1; i >= 0; i--) {
                          int nxt = nextIndices.get(i);
                          activated.add(nxt);
                          if (scheduled.add(nxt)) {
                            pending.addFirst(nxt);
                          }
                        }
                      });
            });
  }

  private StepResult processTaskResult(
      WorkflowStep step, Task task, int subtaskIndex, TaskResult response) {
    task.setStatus(response.isSuccess() ? TaskStatus.SUCCESS : TaskStatus.FAILED);
    emit(
        EventType.TASK_STATUS_CHANGED,
        Map.of(
            "step",
            step.getName(),
            "subtask_index",
            subtaskIndex,
            "agent",
            task.getAgent(),
            "status",
            task.getStatus().getValue()));
    String status = response.isSuccess() ? "success" : "failed";
    log.info(
        "[Executor] Task executionId={}, step={}, taskId={}, agent={}, status={}",
        executionId,
        step.getName(),
        taskId(step.getName(), subtaskIndex),
        task.getAgent(),
        status);
    if (!response.isSuccess()) {
      log.warn(
          "[Executor] TASK_FAILED executionId={}, step={}, taskId={}, agent={}, errorCode={},"
              + " reason={}",
          executionId,
          step.getName(),
          taskId(step.getName(), subtaskIndex),
          task.getAgent(),
          response.getErrorCode(),
          SensitiveDataRedactor.redact(response.getError())
              .replace("\r", "\\r")
              .replace("\n", "\\n"));
    }
    List<Object> outputs = response.getOutputs();
    executionHistory.add(
        Map.of(
            "step",
            step.getName(),
            "subtask_index",
            subtaskIndex,
            "task",
            task.getDescription(),
            "agent",
            task.getAgent(),
            "status",
            status,
            "taskId",
            taskId(step.getName(), subtaskIndex),
            "outputs",
            outputs,
            "error",
            response.getError() == null ? "" : response.getError(),
            "errorCode",
            response.getErrorCode() == null ? "" : response.getErrorCode(),
            "errorDetails",
            response.getErrorDetails()));
    emit(
        EventType.TASK_RESPONSE,
        Map.of(
            "step",
            step.getName(),
            "subtask_index",
            subtaskIndex,
            "agent",
            task.getAgent(),
            "task",
            task.getDescription(),
            "outputs",
            outputs,
            "success",
            response.isSuccess(),
            "taskId",
            taskId(step.getName(), subtaskIndex),
            "error",
            response.getError() == null ? "" : response.getError(),
            "errorCode",
            response.getErrorCode() == null ? "" : response.getErrorCode(),
            "errorDetails",
            response.getErrorDetails()));
    return new StepResult(
        task.getDescription(),
        task.getAgent(),
        subtaskIndex,
        outputs,
        response.isSuccess(),
        null,
        List.of(
            new TaskExecutionResult(
                task.getAgent(),
                task.getSkill(),
                taskId(step.getName(), subtaskIndex),
                task.getDescription(),
                task.getStatus(),
                outputs,
                response.getReceivedMessages(),
                response.getError(),
                response.getErrorCode(),
                response.getErrorDetails())));
  }

  private void validateWorkflowGraph() {
    Map<String, Integer> indices = new HashMap<>();
    for (int i = 0; i < workflow.getSteps().size(); i++) {
      String name = workflow.getSteps().get(i).getName();
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("Workflow step name must not be blank");
      }
      if (isTerminalRoute(name)) {
        throw new IllegalArgumentException(
            "Workflow step name is reserved for a terminal route: " + name);
      }
      if (indices.put(name, i) != null) {
        throw new IllegalArgumentException("Duplicate workflow step name: " + name);
      }
    }
    List<List<Integer>> graph = new ArrayList<>();
    for (int i = 0; i < workflow.getSteps().size(); i++) graph.add(new ArrayList<>());
    for (int i = 0; i < workflow.getSteps().size(); i++) {
      WorkflowStep step = workflow.getSteps().get(i);
      if (step.getNext() == null) continue;
      Set<String> targets = new HashSet<>();
      for (JumpCondition jump : step.getNext()) {
        if (jump == null) {
          throw new IllegalArgumentException(
              "Step " + step.getName() + " contains a null outgoing edge");
        }
        String nextStep = jump.getStep();
        if (nextStep == null || nextStep.isBlank()) {
          throw new IllegalArgumentException(
              "Step " + step.getName() + " contains an outgoing edge with a blank target");
        }
        if (!targets.add(nextStep)) {
          throw new IllegalArgumentException(
              "Step " + step.getName() + " contains duplicate outgoing target " + nextStep);
        }
        if (isTerminalRoute(nextStep)) continue;
        Integer target = indices.get(nextStep);
        if (target == null) {
          throw new IllegalArgumentException(
              "Step " + step.getName() + " references missing step " + nextStep);
        }
        graph.get(i).add(target);
      }
    }
    for (WorkflowStep step : workflow.getSteps()) {
      List<String> contextFrom = step.getContextFrom();
      if (contextFrom == null || contextFrom.isEmpty()) continue;
      if (contextFrom.contains("*") && contextFrom.size() != 1) {
        throw new IllegalArgumentException(
            "Step " + step.getName() + " cannot combine '*' with named context sources");
      }
      if (contextFrom.contains("*")) continue;
      Set<String> ancestors = Set.copyOf(contextBuilder.getAllPredecessors(step.getName()));
      for (String source : contextFrom) {
        if (!indices.containsKey(source)) {
          throw new IllegalArgumentException(
              "Step " + step.getName() + " references missing context source " + source);
        }
        if (!ancestors.contains(source)) {
          throw new IllegalArgumentException(
              "Step "
                  + step.getName()
                  + " context source "
                  + source
                  + " is not an upstream dependency");
        }
      }
    }
    int[] state = new int[workflow.getSteps().size()];
    for (int i = 0; i < state.length; i++) detectCycle(i, graph, state);
  }

  private CompletableFuture<List<Integer>> determineNextSteps(WorkflowStep step) {
    if (step.getNext() == null || step.getNext().isEmpty()) {
      return CompletableFuture.completedFuture(List.of());
    }
    var workflowInput = contextBuilder.buildWorkflowInput(step, stepExecutionResults);
    var currentResults = stepExecutionResults.getOrDefault(step.getName(), List.of());
    List<RouteEvaluation> evaluations =
        step.getNext().stream()
            .map(edge -> evaluateRoute(step, edge, workflowInput, currentResults))
            .toList();
    CompletableFuture<Void> completed =
        CompletableFuture.allOf(
            evaluations.stream().map(RouteEvaluation::decision).toArray(CompletableFuture[]::new));
    return completed.thenApply(ignored -> collectAllowedTargets(step, evaluations));
  }

  private RouteEvaluation evaluateRoute(
      WorkflowStep step,
      JumpCondition edge,
      WorkflowInput workflowInput,
      List<TaskExecutionResult> currentResults) {
    if (isUnconditional(edge)) {
      return new RouteEvaluation(
          edge,
          false,
          CompletableFuture.completedFuture(RouteDecision.allow("unconditional edge")));
    }
    var request =
        new RouteRequest(
            executionId,
            step.getName(),
            edge.getStep(),
            edge.getCondition(),
            workflowInput,
            currentResults);
    CompletableFuture<RouteDecision> decision;
    try {
      decision = controlPoint.onRoute(request);
    } catch (RuntimeException error) {
      decision = CompletableFuture.failedFuture(routeEvaluationFailure(step, edge, error));
    }
    if (decision == null) {
      decision =
          CompletableFuture.failedFuture(
              routeEvaluationFailure(
                  step, edge, new NullPointerException("onRoute returned null future")));
    }
    CompletableFuture<RouteDecision> callbackDecision = decision;
    CompletableFuture<RouteDecision> bounded = new CompletableFuture<>();
    activeTasks.add(bounded);
    bounded.whenComplete(
        (value, error) -> {
          activeTasks.remove(bounded);
          if (error != null && !callbackDecision.isDone()) callbackDecision.cancel(true);
        });
    callbackDecision.whenComplete(
        (value, error) -> {
          if (error != null) bounded.completeExceptionally(error);
          else bounded.complete(value);
        });
    bounded.orTimeout(engineClient.callbackTimeoutSeconds(), TimeUnit.SECONDS);
    if (stopped.get()) bounded.cancel(true);
    return new RouteEvaluation(
        edge,
        true,
        bounded.handle(
            (value, error) -> {
              if (error != null) {
                throw new CompletionException(routeEvaluationFailure(step, edge, error));
              }
              if (value == null) {
                throw new CompletionException(
                    routeEvaluationFailure(
                        step, edge, new NullPointerException("onRoute returned null decision")));
              }
              return value;
            }));
  }

  private List<Integer> collectAllowedTargets(
      WorkflowStep step, List<RouteEvaluation> evaluations) {
    List<Integer> indices = new ArrayList<>();
    for (RouteEvaluation evaluation : evaluations) {
      var decision = evaluation.decision().join();
      emitRouteDecision(step, evaluation, decision);
      if (!decision.allowed() || isTerminalRoute(evaluation.edge().getStep())) continue;
      Integer index = contextBuilder.findStepIndex(evaluation.edge().getStep());
      if (index == null) {
        throw new IllegalStateException(
            "Route target does not exist: " + evaluation.edge().getStep());
      }
      indices.add(index);
    }
    return indices;
  }

  private void emitRouteDecision(
      WorkflowStep step, RouteEvaluation evaluation, RouteDecision decision) {
    String condition =
        evaluation.edge().getCondition() == null ? "" : evaluation.edge().getCondition();
    log.info(
        "Route edge '{} -> {}': allowed={}",
        step.getName(),
        evaluation.edge().getStep(),
        decision.allowed());
    emit(
        EventType.ROUTE_DECISION,
        Map.of(
            "step", step.getName(),
            "next", evaluation.edge().getStep(),
            "condition", condition,
            "conditional", evaluation.conditional(),
            "allowed", decision.allowed(),
            "reason", decision.reason()));
  }

  private static final class RouteEvaluationException extends IllegalStateException {
    private RouteEvaluationException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private record RouteEvaluation(
      JumpCondition edge, boolean conditional, CompletableFuture<RouteDecision> decision) {}

  private record StepResult(
      String taskDesc,
      String agentName,
      int subtaskIndex,
      List<Object> outputs,
      boolean success,
      Map<String, Object> results,
      List<TaskExecutionResult> taskResults) {}
}
