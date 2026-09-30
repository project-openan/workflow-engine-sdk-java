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

package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.junit.jupiter.api.Test;

/**
 * Notification-T streams must not be reaped by the finality-based send timeout: subscription tasks
 * stay WORKING forever, and periodic heartbeats are what keep the stream alive.
 */
class NotificationStreamTimeoutTest {

  private DefaultA2AJavaClientRuntime runtimeWithOneSecondIdleBudget() {
    return new DefaultA2AJavaClientRuntime(true, null, 1L, null);
  }

  @Test
  void heartbeatsKeepNotificationStreamAliveBeyondSendTimeout() throws Exception {
    DefaultA2AJavaClientRuntime runtime = runtimeWithOneSecondIdleBudget();
    CountDownLatch done = new CountDownLatch(1);
    AtomicLong lastActivityNanos = new AtomicLong(System.nanoTime());

    // The stream outlives two 1s idle budgets while activity arrives every 300ms.
    Thread feeder =
        new Thread(
            () -> {
              try {
                for (int i = 0; i < 9; i++) {
                  lastActivityNanos.set(System.nanoTime());
                  Thread.sleep(300);
                }
              } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
    feeder.start();
    runtime.awaitNotificationActivity("agent", done, lastActivityNanos::get);
    feeder.join(TimeUnit.SECONDS.toMillis(4));

    assertTrue(done.await(0, TimeUnit.MILLISECONDS), "stream should have ended");
  }

  @Test
  void silentNotificationStreamIsReapedWithIdleTimeout() {
    DefaultA2AJavaClientRuntime runtime = runtimeWithOneSecondIdleBudget();
    CountDownLatch done = new CountDownLatch(1);
    AtomicLong lastActivityNanos = new AtomicLong(System.nanoTime());

    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () -> runtime.awaitNotificationActivity("agent", done, lastActivityNanos::get));
    assertTrue(error.getMessage().contains("idle for 1s"), error.getMessage());
  }

  @Test
  void idleDeadlineIsMeasuredFromLastActivity() throws Exception {
    DefaultA2AJavaClientRuntime runtime = runtimeWithOneSecondIdleBudget();
    CountDownLatch done = new CountDownLatch(1);
    AtomicLong lastActivityNanos = new AtomicLong(System.nanoTime());
    Thread heartbeat =
        new Thread(
            () -> {
              try {
                Thread.sleep(500);
                lastActivityNanos.set(System.nanoTime());
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
    heartbeat.start();
    long started = System.nanoTime();

    assertThrows(
        RuntimeException.class,
        () -> runtime.awaitNotificationActivity("agent", done, lastActivityNanos::get));
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    heartbeat.join();

    assertTrue(elapsedMillis >= 1_350, "must not expire at the initial 1s deadline");
    assertTrue(elapsedMillis < 2_000, "must not wait for a second fixed 1s window");
  }

  @Test
  void alreadyIdleStreamTimesOutWithoutAnotherFullWait() {
    DefaultA2AJavaClientRuntime runtime = runtimeWithOneSecondIdleBudget();
    long staleActivity = System.nanoTime() - TimeUnit.SECONDS.toNanos(2);
    long started = System.nanoTime();

    assertThrows(
        RuntimeException.class,
        () ->
            runtime.awaitNotificationActivity("agent", new CountDownLatch(1), () -> staleActivity));

    assertTrue(
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500,
        "an already idle stream should not receive a fresh timeout budget");
  }

  @Test
  void explicitCloseEndsNotificationStreamImmediately() throws Exception {
    DefaultA2AJavaClientRuntime runtime = runtimeWithOneSecondIdleBudget();
    CountDownLatch done = new CountDownLatch(1);
    done.countDown();
    long started = System.nanoTime();

    runtime.awaitNotificationActivity("agent", done, () -> System.nanoTime());

    assertTrue(
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500,
        "explicitly closed stream should return immediately");
  }

  @Test
  void taskResultStatesDoNotEndAnActiveNotificationStream() {
    CountDownLatch notificationDone = new CountDownLatch(1);
    AtomicLong lastActivityNanos = new AtomicLong(System.nanoTime() - TimeUnit.SECONDS.toNanos(2));
    AtomicLong eventCount = new AtomicLong();
    AtomicLong deliveredCount = new AtomicLong();
    AtomicReference<ClientEvent> lastEvent = new AtomicReference<>();
    List<ClientEvent> retainedEvents = new ArrayList<>();
    for (TaskState state :
        List.of(
            TaskState.TASK_STATE_COMPLETED,
            TaskState.TASK_STATE_FAILED,
            TaskState.TASK_STATE_CANCELED,
            TaskState.TASK_STATE_REJECTED,
            TaskState.TASK_STATE_INPUT_REQUIRED,
            TaskState.TASK_STATE_AUTH_REQUIRED)) {
      TaskStatus status = new TaskStatus(state);
      Task task =
          Task.builder()
              .id("task-1")
              .contextId("notification-context")
              .status(status)
              .artifacts(List.of())
              .history(List.of())
              .metadata(Map.of())
              .build();
      DefaultA2AJavaClientRuntime.onEvent(
          "agent",
          new TaskEvent(task),
          retainedEvents,
          lastEvent,
          ignored -> deliveredCount.incrementAndGet(),
          notificationDone,
          lastActivityNanos,
          eventCount);
      DefaultA2AJavaClientRuntime.onEvent(
          "agent",
          new TaskUpdateEvent(
              task, new TaskStatusUpdateEvent("task-1", status, "notification-context", Map.of())),
          retainedEvents,
          lastEvent,
          ignored -> deliveredCount.incrementAndGet(),
          notificationDone,
          lastActivityNanos,
          eventCount);
      assertEquals(1, notificationDone.getCount(), "task status must not close Notification-T");
    }
    TaskStatus heartbeat = new TaskStatus(TaskState.TASK_STATE_WORKING);
    Task heartbeatTask =
        Task.builder()
            .id("subscription-task")
            .contextId("notification-context")
            .status(heartbeat)
            .artifacts(List.of())
            .history(List.of())
            .metadata(Map.of())
            .build();
    DefaultA2AJavaClientRuntime.onEvent(
        "agent",
        new TaskUpdateEvent(
            heartbeatTask,
            new TaskStatusUpdateEvent(
                "subscription-task", heartbeat, "notification-context", Map.of())),
        retainedEvents,
        lastEvent,
        ignored -> deliveredCount.incrementAndGet(),
        notificationDone,
        lastActivityNanos,
        eventCount);
    assertEquals(
        1, notificationDone.getCount(), "heartbeat must still reach an active subscription");
    assertEquals(13, eventCount.get());
    assertEquals(13, deliveredCount.get());
    assertTrue(retainedEvents.isEmpty(), "long-lived streams must not retain every event");
    assertTrue(lastActivityNanos.get() > System.nanoTime() - TimeUnit.SECONDS.toNanos(1));

    CountDownLatch taskDone = new CountDownLatch(1);
    Task completedTask =
        Task.builder()
            .id("task-2")
            .contextId("task-context")
            .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
            .artifacts(List.of())
            .history(List.of())
            .metadata(Map.of())
            .build();
    DefaultA2AJavaClientRuntime.onEvent(
        "agent",
        new TaskEvent(completedTask),
        new ArrayList<>(),
        new AtomicReference<>(),
        null,
        taskDone,
        null,
        null);
    assertEquals(0, taskDone.getCount(), "ordinary Task-T must retain terminal handling");
  }
}
