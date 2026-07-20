package com.openan.a2at.engine.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ExtensionRegistry: URI matching, dedup, built-in handlers.
 */
class ExtensionRegistryTest {

    @Test
    void preRegistersFourBuiltinHandlers() {
        ExtensionRegistry reg = new ExtensionRegistry();
        List<ExtensionHandler> handlers = reg.getHandlersForExtensions(List.of(
                "https://example.com/Task-T",
                "https://example.com/Negotiation-T",
                "https://example.com/Authorization-T",
                "https://example.com/Notification-T"
        ));
        assertEquals(4, handlers.size());
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
                    java.util.Map<String, Object> agentCard, String messageText,
                    java.util.Map<String, Object> metadata, Object a2atClient, Object controlPoint) {
                return java.util.concurrent.CompletableFuture.completedFuture(metadata);
            }

            @Override
            public java.util.concurrent.CompletableFuture<com.openan.a2at.engine.model.SendMessageResult> afterReceive(
                    java.util.Map<String, Object> agentCard,
                    com.openan.a2at.engine.model.SendMessageResult result,
                    Object a2atClient, Object controlPoint, Object eventCallback) {
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
