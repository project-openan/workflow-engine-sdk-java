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

package dev.openan.workflow.engine.examples.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openan.workflow.engine.client.AgentCardJacksonModule;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.spec.AgentCard;

/**
 * Starts and tracks {@link JdkHttpA2AServer} instances from agentcard resources. Centralizes the
 * load-card → resolve-bind-address → start boilerplate shared by the demos.
 */
public final class OmcAgentLauncher implements AutoCloseable {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new AgentCardJacksonModule());

  private final List<JdkHttpA2AServer> servers = new ArrayList<>();

  /** The {@link AgentCard} view of an agentcard resource, for building engine clients. */
  public static AgentCard cardFromResource(String resourcePath) throws Exception {
    String path = OmcAgentLauncher.class.getClassLoader().getResource(resourcePath).getPath();
    return MAPPER.readValue(new File(path), AgentCard.class);
  }

  /** Path of a classpath resource as an absolute file path (demo credentials lookup). */
  public static String resourcePath(String resourcePath) {
    var url = OmcAgentLauncher.class.getClassLoader().getResource(resourcePath);
    if (url == null) {
      throw new IllegalStateException(
          "Classpath resource not found: "
              + resourcePath
              + ". If this is the OMC credentials file, copy"
              + " spn_agent_credentials.example.json to spn_agent_credentials.json"
              + " and fill in the target OMC credentials.");
    }
    return url.getPath();
  }

  /** Load an agentcard resource, bind to the address declared in its first interface, and start. */
  public JdkHttpA2AServer startFromResource(String resourcePath, AgentExecutor executor)
      throws Exception {
    String path = OmcAgentLauncher.class.getClassLoader().getResource(resourcePath).getPath();
    @SuppressWarnings("unchecked")
    Map<String, Object> card = MAPPER.readValue(new File(path), Map.class);
    return startFromCard(card, executor);
  }

  /** Bind to the address declared in the card's first supportedInterface and start. */
  public JdkHttpA2AServer startFromCard(Map<String, Object> card, AgentExecutor executor)
      throws Exception {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> ifaces =
        (List<Map<String, Object>>) card.getOrDefault("supportedInterfaces", List.of());
    String url =
        ifaces.isEmpty() ? "https://127.0.0.1:0" : String.valueOf(ifaces.get(0).get("url"));
    java.net.URI uri = LocalServerAddress.requireLocalEndpoint(url, "Embedded OMC AgentCard");
    String host = uri.getHost() != null ? uri.getHost() : "127.0.0.1";
    int port =
        uri.getPort() >= 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
    JdkHttpA2AServer server = new JdkHttpA2AServer(host, port, card, executor);
    server.start();
    servers.add(server);
    return server;
  }

  public List<JdkHttpA2AServer> servers() {
    return servers;
  }

  /** Starts an already loaded and validated card, including externally configured local cards. */
  public JdkHttpA2AServer startFromCard(AgentCard card, AgentExecutor executor) throws Exception {
    return startFromCard(
        MAPPER.readValue(
            com.google.protobuf.util.JsonFormat.printer()
                .print(org.a2aproject.sdk.grpc.utils.ProtoUtils.ToProto.agentCard(card)),
            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}),
        executor);
  }

  @Override
  public void close() {
    for (JdkHttpA2AServer server : servers) {
      try {
        server.close();
      } catch (Exception ignored) {
        // shutdown is best-effort
      }
    }
    servers.clear();
  }
}
