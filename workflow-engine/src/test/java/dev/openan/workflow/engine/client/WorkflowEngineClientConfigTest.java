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

import java.util.*;
import org.junit.jupiter.api.Test;

class WorkflowEngineClientConfigTest {

  @Test
  void rejectsFileAndInlineCredentialsTogether() {
    Map<String, Map<String, Map<String, Object>>> inline =
        Map.of("agent", Map.of("scheme", Map.of("token", "value")));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                WorkflowEngineClientConfig.builder()
                    .credentialsConfigPath("credentials.json")
                    .credentialsConfig(inline)
                    .build());

    assertTrue(error.getMessage().contains("mutually exclusive"));
  }

  @Test
  void notificationAcknowledgementDefaultsToFiveMinutesAndRemainsConfigurable() {
    assertEquals(
        300, WorkflowEngineClientConfig.builder().build().getNotificationAckTimeoutSeconds());
    assertEquals(
        45,
        WorkflowEngineClientConfig.builder()
            .notificationAckTimeoutSeconds(45)
            .build()
            .getNotificationAckTimeoutSeconds());
  }

  @Test
  void rejectsInvalidResourceLimits() {
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkflowEngineClientConfig.builder().taskPollIntervalMillis(99).build());
    assertEquals(
        100,
        WorkflowEngineClientConfig.builder()
            .taskPollIntervalMillis(100)
            .build()
            .getTaskPollIntervalMillis());
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkflowEngineClientConfig.builder().maxNegotiationExchanges(0).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkflowEngineClientConfig.builder().sendTimeoutSeconds(0).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkflowEngineClientConfig.builder().notificationAckTimeoutSeconds(0).build());
  }

  @Test
  void credentialKeyIsExplicitAndConfigurationIsSnapshotted() {
    Map<String, Object> scheme = new LinkedHashMap<>(Map.of("username", "test"));
    var config =
        WorkflowEngineClientConfig.builder()
            .credentialsConfig(Map.of("agent", Map.of("auth", scheme)))
            .credentialEncryptionKey("test-key")
            .build();
    scheme.clear();
    assertEquals("test", config.getCredentialsConfig().get("agent").get("auth").get("username"));
    assertEquals("test-key", config.getCredentialEncryptionKey());
  }
}
