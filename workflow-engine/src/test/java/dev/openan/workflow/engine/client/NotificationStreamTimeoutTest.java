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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
  void terminalEventEndsNotificationStreamImmediately() throws Exception {
    DefaultA2AJavaClientRuntime runtime = runtimeWithOneSecondIdleBudget();
    CountDownLatch done = new CountDownLatch(1);
    done.countDown();
    long started = System.nanoTime();

    runtime.awaitNotificationActivity("agent", done, () -> System.nanoTime());

    assertTrue(
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500,
        "terminal stream should return immediately");
  }
}
