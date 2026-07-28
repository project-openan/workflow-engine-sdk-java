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

package com.openan.a2at.engine.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ExtensionRegistry: URI matching, dedup, built-in handlers.
 */
class ExtensionRegistryTest {

    @Test
    void preRegistersTwoBuiltinHandlers() {
        ExtensionRegistry reg = new ExtensionRegistry();
        List<ExtensionHandler> handlers = reg.getHandlersForExtensions(List.of(
                "https://example.com/Task-T",
                "https://example.com/Negotiation-T",
                "https://example.com/Authorization-T",
                "https://example.com/Notification-T"
        ));
        // Authorization-T and Notification-T are pre-positioning operations,
        // not part of the workflow engine's extension handler chain.
        assertEquals(2, handlers.size());
    }

    @Test
    void matchesTaskTHandler() {
        ExtensionRegistry reg = new ExtensionRegistry();
        List<ExtensionHandler> handlers = reg.getHandlersForExtensions(List.of(
                "https://a2a.example.org/extensions/Task-T/v1"
        ));
        assertEquals(1, handlers.size());
        assertEquals("Task-T", handlers.get(0).extensionKeyword());
    }

    @Test
    void matchesNegotiationTHandler() {
        ExtensionRegistry reg = new ExtensionRegistry();
        List<ExtensionHandler> handlers = reg.getHandlersForExtensions(List.of(
                "https://a2a.example.org/extensions/NEGOTIATION-T/v1"
        ));
        assertEquals(1, handlers.size());
        assertEquals("Negotiation-T", handlers.get(0).extensionKeyword());
    }

    @Test
    void deduplicatesWhenMultipleUrisMatchSameHandler() {
        ExtensionRegistry reg = new ExtensionRegistry();
        List<ExtensionHandler> handlers = reg.getHandlersForExtensions(List.of(
                "https://example.com/Task-T/v1",
                "https://example.com/Task-T/v2"
        ));
        assertEquals(1, handlers.size(), "Task-T should be matched once even with two URIs");
    }

    @Test
    void returnsEmptyForNoExtensions() {
        ExtensionRegistry reg = new ExtensionRegistry();
        List<ExtensionHandler> handlers = reg.getHandlersForExtensions(List.of());
        assertTrue(handlers.isEmpty());
    }

    @Test
    void returnsEmptyForUnmatchedUris() {
        ExtensionRegistry reg = new ExtensionRegistry();
        List<ExtensionHandler> handlers = reg.getHandlersForExtensions(List.of(
                "https://example.com/Unknown-Extension"
        ));
        assertTrue(handlers.isEmpty());
    }

    @Test
    void nullUrisHandledGracefully() {
        ExtensionRegistry reg = new ExtensionRegistry();
        List<ExtensionHandler> handlers = reg.getHandlersForExtensions(null);
        assertTrue(handlers.isEmpty());
    }

    @Test
    void customHandlerCanBeRegistered() {
        ExtensionRegistry reg = new ExtensionRegistry();
        ExtensionHandler custom = new ExtensionHandler() {
            @Override
            public String extensionKeyword() {
                return "Custom-T";
            }

            @Override
            public java.util.concurrent.CompletableFuture<java.util.Map<String, Object>> beforeSend(
                    org.a2aproject.sdk.spec.AgentCard agentCard, String messageText,
                    java.util.Map<String, Object> metadata, net.openan.a2at.sdk.client.A2ATClient a2atClient, com.openan.a2at.engine.control.ControlPoint controlPoint) {
                return java.util.concurrent.CompletableFuture.completedFuture(metadata);
            }

            @Override
            public java.util.concurrent.CompletableFuture<com.openan.a2at.engine.model.SendMessageResult> afterReceive(
                    org.a2aproject.sdk.spec.AgentCard agentCard,
                    com.openan.a2at.engine.model.SendMessageResult result,
                    net.openan.a2at.sdk.client.A2ATClient a2atClient,
                    com.openan.a2at.engine.control.ControlPoint controlPoint,
                    com.openan.a2at.engine.control.ExtensionCallback extensionCallback,
                    com.openan.a2at.engine.control.EventCallback eventCallback) {
                return java.util.concurrent.CompletableFuture.completedFuture(result);
            }
        };
        reg.register(custom);
        List<ExtensionHandler> handlers = reg.getHandlersForExtensions(List.of(
                "https://example.com/Custom-T/v1"
        ));
        assertTrue(handlers.size() >= 1);
        assertEquals("Custom-T", handlers.get(0).extensionKeyword());
    }
}
