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
package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class WireLogRedactionTest {
  @Test
  void masksUnderscoredCredentialNamesInJsonAndHeaders() {
    assertEquals(
        "{\"api_key\":\"***\",\"access_session\":\"***\"}",
        WireLog.redact("{\"api_key\":\"hidden\",\"access_session\":\"hidden\"}"));
    assertEquals(
        List.of("***"),
        WireLog.safeHeaders(Map.of("X-Api_Key", List.of("hidden"))).get("X-Api_Key"));
  }

  @Test
  void masksAuthorizationValuesInRemoteErrorText() {
    assertEquals(
        "Rejected Bearer ***; Basic ***",
        WireLog.redact("Rejected Bearer private-token; Basic c2VjcmV0"));
    assertTrue(
        WireLog.redact("{\"detail\":\"Rejected Bearer private-token\"}").contains("Bearer ***"));
    assertFalse(
        WireLog.redact("{\"detail\":\"Rejected Bearer private-token\"}").contains("private-token"));
  }

  @Test
  void largeEscapedContentDoesNotUseRecursiveRegexBacktracking() {
    String payload =
        "{\"result\":\"" + "escaped \\\"text\\\" ".repeat(8000) + "\",\"token\":\"hidden\"}";
    assertEquals(payload.replace("hidden", "***"), WireLog.redact(payload));
  }

  @Test
  void redactsNestedValuesEscapedKeysAndIncompleteSecretsWithoutReserializingOtherFields() {
    String original =
        "{ \"password\": {\"nested\":[\"secret\",{\"value\":\"hidden\"}]}, "
            + "\"to\\u006ben\":\"hidden\", \"ok\" : [1, 2] }";
    assertEquals(
        "{ \"password\": \"***\", \"to\\u006ben\":\"***\", \"ok\" : [1, 2] }",
        WireLog.redact(original));
    assertEquals(
        "data: {\"accessSession\":\"***\"", WireLog.redact("data: {\"accessSession\":\"partial"));
    assertEquals("password=***&ok=1", WireLog.redact("password=hidden&ok=1"));
  }

  @Test
  void splitSdkTextChunksDoNotExposeFragmentsOfCredentials() {
    List<String> observed = new ArrayList<>();
    WireLog.Body body =
        new WireLog.Body(true, true, frame -> observed.add(WireLog.redact(frame[0])));
    for (String chunk :
        List.of("id: 1\ndata: {\"to", "ken\":\"sensitive", "-tail\",\"ok\":true}\n", "\n")) {
      body.accept(ByteBuffer.wrap(chunk.getBytes(StandardCharsets.UTF_8)));
    }
    assertEquals(List.of("id: 1\ndata: {\"token\":\"***\",\"ok\":true}\n\n"), observed);
    body.end(false);
    assertEquals(1, observed.size());
  }

  @Test
  void extensionUriKeysCarryingBusinessContentAreNotRedacted() {
    String payload =
        "{\"artifact\":{\"metadata\":{"
            + "\"https://projects.tmforum.org/a2aproject/telecommunication/extensions/Authorization-T/v1\":"
            + "\"## 动网操作授权策略\\n新增授权策略\"}},"
            + "\"accessToken\":\"hidden\"}}";
    String redacted = WireLog.redact(payload);
    assertTrue(redacted.contains("Authorization-T/v1\":\"## 动网操作授权策略"));
    assertFalse(redacted.contains("hidden"));
    assertTrue(redacted.contains("accessToken\":\"***\""));
    // header names still match: the HTTP Authorization header keeps its credential masked
    assertTrue(WireLog.sensitive("Authorization"));
    assertFalse(
        WireLog.sensitive(
            "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Authorization-T/v1"));
  }
}
