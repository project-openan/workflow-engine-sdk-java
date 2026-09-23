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
import dev.openan.workflow.engine.client.SslContextFactory;
import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowSearchResult;
import dev.openan.workflow.engine.util.SensitiveDataRedactor;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Load and search PSOP workflows from the orchestration center's external API.
 *
 * <ul>
 *   <li>{@code load} -- GET /api/v1/orchestrate/psop/{psop_id} (full workflow)
 *   <li>{@code search} -- POST /api/v1/orchestrate/search (summary list by intent)
 * </ul>
 *
 * <p>Defaults verify the server using JVM trust and hostname checks. Explicit {@code
 * sslVerify=false} skips both checks for this connection only, for controlled development without
 * local trust files. It does not remove the server's HTTPS certificate or satisfy a server
 * requirement for mTLS.
 */
public class LoadPsop {
  private static final Logger log = LoggerFactory.getLogger(LoadPsop.class);
  private static final ObjectMapper mapper = new ObjectMapper();
  private static final Timeouts DEFAULT_TIMEOUTS =
      new Timeouts(Duration.ofSeconds(30), Duration.ofSeconds(30));

  public static Workflow load(String baseUrl, String psopId, String accessToken, boolean sslVerify)
      throws Exception {
    return load(baseUrl, psopId, accessToken, sslVerify, DEFAULT_TIMEOUTS);
  }

  /** Loads one workflow with explicit connection and response-read deadlines. */
  public static Workflow load(
      String baseUrl, String psopId, String accessToken, boolean sslVerify, Timeouts timeouts)
      throws Exception {
    StringBuilder urlBuilder =
        new StringBuilder(baseUrl).append("/api/v1/orchestrate/psop/").append(psopId);
    if (accessToken != null && !accessToken.isEmpty()) {
      urlBuilder
          .append("?access_token=")
          .append(URLEncoder.encode(accessToken, StandardCharsets.UTF_8));
    }
    String url = urlBuilder.toString();
    log.info(
        "[Registry] Loading PSOP from {} (ssl_verify={})",
        anonymousUrl(url, accessToken),
        sslVerify);
    HttpResult resp = execute("GET", url, null, sslVerify, timeouts);
    if (resp.statusCode() != 200) {
      throw requestFailure(resp);
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> data = mapper.readValue(resp.body(), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> psopData = (Map<String, Object>) data.getOrDefault("data", data);
    Workflow wf = Workflow.fromMap(psopData);
    log.info("[Registry] Loaded workflow: {}, {} steps", wf.getName(), wf.getSteps().size());
    return wf;
  }

  public static Workflow load(String baseUrl, String psopId) throws Exception {
    return load(baseUrl, psopId, null, true);
  }

  public static List<WorkflowSearchResult> search(
      String baseUrl, String intent, int topN, String accessToken, boolean sslVerify)
      throws Exception {
    return search(baseUrl, intent, topN, accessToken, sslVerify, DEFAULT_TIMEOUTS);
  }

  /** Searches workflows with explicit connection and response-read deadlines. */
  public static List<WorkflowSearchResult> search(
      String baseUrl,
      String intent,
      int topN,
      String accessToken,
      boolean sslVerify,
      Timeouts timeouts)
      throws Exception {
    StringBuilder urlBuilder = new StringBuilder(baseUrl).append("/api/v1/orchestrate/search");
    if (accessToken != null && !accessToken.isEmpty()) {
      urlBuilder
          .append("?access_token=")
          .append(URLEncoder.encode(accessToken, StandardCharsets.UTF_8));
    }
    String url = urlBuilder.toString();
    log.info("[Registry] Searching PSOP at {} (top_n={})", anonymousUrl(url, accessToken), topN);
    String jsonBody = mapper.writeValueAsString(Map.of("intent", intent, "top_n", topN));
    HttpResult resp = execute("POST", url, jsonBody, sslVerify, timeouts);
    if (resp.statusCode() != 200) {
      throw requestFailure(resp);
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> data = mapper.readValue(resp.body(), Map.class);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rawResults =
        (List<Map<String, Object>>) data.getOrDefault("data", List.of());
    List<WorkflowSearchResult> results = new java.util.ArrayList<>();
    for (Map<String, Object> raw : rawResults) {
      results.add(
          WorkflowSearchResult.builder()
              .workflowId((String) raw.getOrDefault("workflow_id", raw.getOrDefault("id", "")))
              .workflowType((String) raw.getOrDefault("workflow_type", ""))
              .name((String) raw.getOrDefault("name", ""))
              .description((String) raw.getOrDefault("description", null))
              .createdAt(raw.get("created_at") != null ? raw.get("created_at").toString() : "")
              .score(raw.get("score") instanceof Number n ? n.doubleValue() : 1.0)
              .userIntent((String) raw.getOrDefault("user_intent", null))
              .relatedPreflow((String) raw.getOrDefault("related_preflow", null))
              .tasksSummary((String) raw.getOrDefault("tasks_summary", null))
              .build());
    }
    log.info("[Registry] Search returned {} workflow(s)", results.size());
    return results;
  }

  /** Preserve token presence for diagnostics without exposing any token characters or length. */
  static String anonymousUrl(String url, String token) {
    return token == null || token.isEmpty()
        ? url
        : url.substring(0, url.indexOf("?access_token=")) + "?access_token=<anonymous>";
  }

  private static RuntimeException requestFailure(HttpResult response) {
    String detail =
        SensitiveDataRedactor.redact(response.body()).replace("\r", "\\r").replace("\n", "\\n");
    if (detail.length() > 512) detail = detail.substring(0, 512) + "...";
    return new RuntimeException(
        "Orchestration center returned "
            + response.statusCode()
            + (detail.isBlank() ? "" : ": " + detail));
  }

  private static HttpResult execute(
      String method, String url, String jsonBody, boolean sslVerify, Timeouts timeouts)
      throws Exception {
    java.util.Objects.requireNonNull(timeouts, "timeouts");
    HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
    if (!sslVerify && connection instanceof HttpsURLConnection https) {
      // Development opt-out is connection-local: never replace JVM-wide TLS defaults.
      https.setSSLSocketFactory(
          SslContextFactory.create(false, null).orElseThrow().getSocketFactory());
      https.setHostnameVerifier((hostname, session) -> true);
      log.warn(
          "[Registry] INSECURE_TLS host={}, port={}: certificate and hostname verification disabled; development only",
          https.getURL().getHost(),
          https.getURL().getPort());
    }
    try {
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(timeouts.connectTimeoutMillis());
      connection.setReadTimeout(timeouts.readTimeoutMillis());
      connection.setRequestMethod(method);
      if (jsonBody != null) {
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream output = connection.getOutputStream()) {
          output.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
      }
      int status = connection.getResponseCode();
      InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
      String body = input == null ? "" : new String(input.readAllBytes(), StandardCharsets.UTF_8);
      if (input != null) {
        input.close();
      }
      return new HttpResult(status, body);
    } finally {
      connection.disconnect();
    }
  }

  /** Convenience: search with defaults (top_n=5, no token, ssl_verify=true). */
  public static List<WorkflowSearchResult> search(String baseUrl, String intent) throws Exception {
    return search(baseUrl, intent, 5, null, true);
  }

  /** Connection and response-read deadlines for orchestration-center calls. */
  public record Timeouts(Duration connectTimeout, Duration readTimeout) {
    public Timeouts {
      requirePositiveAndSupported(connectTimeout, "connectTimeout");
      requirePositiveAndSupported(readTimeout, "readTimeout");
    }

    private static void requirePositiveAndSupported(Duration value, String name) {
      if (value == null || value.isZero() || value.isNegative()) {
        throw new IllegalArgumentException(name + " must be positive");
      }
      long milliseconds;
      try {
        milliseconds = value.toMillis();
      } catch (ArithmeticException error) {
        throw new IllegalArgumentException(name + " exceeds HttpURLConnection limit", error);
      }
      if (milliseconds < 1) {
        throw new IllegalArgumentException(name + " must be at least 1 millisecond");
      }
      if (milliseconds > Integer.MAX_VALUE) {
        throw new IllegalArgumentException(name + " exceeds HttpURLConnection limit");
      }
    }

    int connectTimeoutMillis() {
      return Math.toIntExact(connectTimeout.toMillis());
    }

    int readTimeoutMillis() {
      return Math.toIntExact(readTimeout.toMillis());
    }
  }

  private record HttpResult(int statusCode, String body) {}
}
