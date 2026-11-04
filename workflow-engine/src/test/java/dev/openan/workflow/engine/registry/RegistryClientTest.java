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
package dev.openan.workflow.engine.registry;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RegistryClientTest {
  @Test
  void singleCardLookupRejectsAmbiguousNames() throws Exception {
    var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/rest/v1/registry-center/agent-cards",
        exchange -> {
          String south = "{\"name\":\"Shared\",\"provider\":{\"organization\":\"South\"}}";
          String north = "{\"name\":\"Shared\",\"provider\":{\"organization\":\"North\"}}";
          String query = exchange.getRequestURI().getQuery();
          String cards = query.contains("organization=South") ? south : north + "," + south;
          byte[] response =
              ("{\"agentCards\":[" + cards + "]}")
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          try (var body = exchange.getResponseBody()) {
            body.write(response);
          }
        });
    server.start();
    try {
      var client = new RegistryClient("http://127.0.0.1:" + server.getAddress().getPort(), true);
      assertThrows(IllegalStateException.class, () -> client.fetchAgentCard("Shared"));
      assertEquals(
          Map.of("organization", "South"),
          client.fetchAgentCard("Shared", "South").get("provider"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsNonPositiveDeadline() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RegistryClient("http://localhost", true, Duration.ZERO));
  }

  @Test
  void deadlineIncludesStalledResponseBody() throws Exception {
    var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var release = new java.util.concurrent.CountDownLatch(1);
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(200, 100);
          exchange.getResponseBody().write('{');
          exchange.getResponseBody().flush();
          try {
            release.await(3, java.util.concurrent.TimeUnit.SECONDS);
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      var client =
          new RegistryClient(
              "http://127.0.0.1:" + server.getAddress().getPort(), true, Duration.ofMillis(200));
      assertTimeoutPreemptively(
          Duration.ofSeconds(2),
          () -> assertThrows(java.net.http.HttpTimeoutException.class, client::fetchAgentCards));
    } finally {
      release.countDown();
      server.stop(0);
    }
  }

  @Test
  void registrationFailureIsNotReportedAsAResult() throws Exception {
    var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/rest/v1/registry-center/agent-cards",
        exchange -> {
          byte[] response =
              "{\"detail\":\"invalid card\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(400, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try {
      var client =
          new RegistryClient(
              "http://127.0.0.1:" + server.getAddress().getPort(), true, Duration.ofSeconds(2));

      RuntimeException error =
          assertThrows(
              RuntimeException.class, () -> client.registerAgentCard(Map.of("name", "invalid")));
      assertEquals(
          "Registry registration returned 400: {\"detail\":\"invalid card\"}", error.getMessage());
    } finally {
      server.stop(0);
    }
  }
}
