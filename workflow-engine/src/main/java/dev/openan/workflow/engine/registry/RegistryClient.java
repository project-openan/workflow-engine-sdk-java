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

package dev.openan.workflow.engine.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openan.workflow.engine.client.AgentCardNormalizer;
import dev.openan.workflow.engine.client.SslContextFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional helper for fetching AgentCards from the Registry Center. Users can use this, or fetch
 * AgentCards from any other source.
 */
public class RegistryClient {
  private static final Logger log = LoggerFactory.getLogger(RegistryClient.class);
  private static final ObjectMapper mapper = new ObjectMapper();

  private final String baseUrl;
  private final HttpClient httpClient;
  private final java.time.Duration requestTimeout;
  private final HttpAuthProvider auth;

  public RegistryClient(String url, boolean sslVerify) {
    this(url, sslVerify, java.time.Duration.ofSeconds(30));
  }

  /** Creates a discovery client with a positive deadline for each complete HTTP response. */
  public RegistryClient(String url, boolean sslVerify, java.time.Duration requestTimeout) {
    // Preserve the old chain-only opt-out. Use TlsPolicy.insecure() explicitly to skip hostname
    // checks.
    this(
        url,
        HttpAuthProvider.none(),
        sslVerify ? TlsPolicy.strict() : legacyTrustAll(),
        requestTimeout);
  }

  /** Creates a client with per-request authentication, strict TLS by default. */
  public RegistryClient(String url, HttpAuthProvider auth) {
    this(url, auth, TlsPolicy.strict());
  }

  /** Creates a client with independent authentication and TLS policy. */
  public RegistryClient(String url, HttpAuthProvider auth, TlsPolicy tls) {
    this(url, auth, tls, java.time.Duration.ofSeconds(30));
  }

  /** Creates a client with authentication and a complete-response deadline. */
  public RegistryClient(
      String url, HttpAuthProvider auth, TlsPolicy tls, java.time.Duration requestTimeout) {
    if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
      throw new IllegalArgumentException("requestTimeout must be positive");
    }
    this.requestTimeout = requestTimeout;
    this.baseUrl = HttpAuthentication.baseUrl(url);
    this.auth = java.util.Objects.requireNonNull(auth, "auth");
    java.util.Objects.requireNonNull(tls, "tls");
    HttpClient.Builder clientBuilder =
        HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NEVER);
    tls.configure(clientBuilder);
    this.httpClient = clientBuilder.build();
  }

  private static TlsPolicy legacyTrustAll() {
    return TlsPolicy.verifiedHostname(SslContextFactory.createTrustAll());
  }

  private static HttpServiceException requestFailure(
      String service, HttpResponse<String> response) {
    Map<String, String> credentials = new java.util.LinkedHashMap<>();
    response
        .request()
        .headers()
        .map()
        .forEach((name, values) -> credentials.put(name, String.join(", ", values)));
    return new HttpServiceException(service, response.statusCode(), response.body(), credentials);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> fetchAgentCard(String name) throws Exception {
    return fetchAgentCard(name, null);
  }

  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> fetchAgentCards() throws Exception {
    String url = baseUrl + "/rest/v1/registry-center/agent-cards";
    log.info("[Registry] Fetching all agent cards from {}", baseUrl);
    HttpRequest request =
        HttpRequest.newBuilder().uri(URI.create(url)).timeout(requestTimeout).GET().build();
    HttpResponse<String> resp = send(request);
    if (resp.statusCode() != 200) {
      throw requestFailure("Registry", resp);
    }
    List<Map<String, Object>> cards = parseCards(resp);
    log.info("[Registry] Received {} agent card(s)", cards.size());
    return cards;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  /** Fetch a card by name and optional organization. Ambiguous matches are rejected. */
  public Map<String, Object> fetchAgentCard(String name, String organization) throws Exception {
    StringBuilder urlBuilder =
        new StringBuilder(baseUrl)
            .append("/rest/v1/registry-center/agent-cards?name=")
            .append(
                java.net.URLEncoder.encode(
                    name != null ? name : "", java.nio.charset.StandardCharsets.UTF_8));
    if (organization != null && !organization.isEmpty()) {
      urlBuilder
          .append("&organization=")
          .append(
              java.net.URLEncoder.encode(organization, java.nio.charset.StandardCharsets.UTF_8));
    }
    String url = urlBuilder.toString();
    log.info("[Registry] Fetching agent card: name={}, org={}", name, organization);
    HttpRequest request =
        HttpRequest.newBuilder().uri(URI.create(url)).timeout(requestTimeout).GET().build();
    HttpResponse<String> resp = send(request);
    if (resp.statusCode() != 200) {
      throw requestFailure("Registry", resp);
    }
    List<Map<String, Object>> cards = parseCards(resp);
    if (cards.isEmpty()) {
      log.warn("[Registry] Agent card not found: name={}", name);
      return null;
    }
    if (cards.size() > 1) {
      throw new IllegalStateException(
          "Multiple AgentCards matched. Supply a unique organization and name.");
    }
    log.info("[Registry] Agent card found: name={}", name);
    return cards.get(0);
  }

  /**
   * Register or update an AgentCard in the registry.
   *
   * @param agentCard the agent card to register (must include name, supportedInterfaces, etc.)
   * @return the registry response as a Map
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> registerAgentCard(Map<String, Object> agentCard) throws Exception {
    String url = baseUrl + "/rest/v1/registry-center/agent-cards";
    Map<String, Object> payload = Map.of("agentCards", List.of(agentCard));
    String json = mapper.writeValueAsString(payload);
    log.info("[Registry] Registering agent card: name={}", agentCard.get("name"));
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
    HttpResponse<String> resp = send(request);
    if (resp.statusCode() != 200 && resp.statusCode() != 201) {
      throw requestFailure("Registry registration", resp);
    }
    Map<String, Object> result =
        HttpResponseParser.parse(
            "Registry", "register agent card", resp.statusCode(), resp.body(), data -> data);
    log.info("[Registry] Agent card registered: name={}", agentCard.get("name"));
    return result;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> parseCards(HttpResponse<String> response) {
    return HttpResponseParser.parse(
        "Registry",
        "fetch agent cards",
        response.statusCode(),
        response.body(),
        data -> {
          List<Map<String, Object>> cards =
              (List<Map<String, Object>>)
                  data.getOrDefault("agentCards", data.getOrDefault("data", List.of()));
          return cards.stream().map(AgentCardNormalizer::normalize).toList();
        });
  }

  private HttpResponse<String> send(HttpRequest request) throws Exception {
    HttpRequest.Builder authenticated = HttpRequest.newBuilder(request, (name, value) -> true);
    HttpAuthentication.headers(auth, request.uri()).forEach(authenticated::setHeader);
    request = authenticated.build();
    var response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    try {
      // Also bound body consumption: a peer may send headers and then stall its body.
      return response.get(
          Math.max(1, requestTimeout.toMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.TimeoutException error) {
      response.cancel(true);
      throw new java.net.http.HttpTimeoutException("Registry response deadline exceeded");
    } catch (InterruptedException error) {
      response.cancel(true);
      Thread.currentThread().interrupt();
      throw error;
    }
  }
}
