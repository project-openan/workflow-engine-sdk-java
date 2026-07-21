package com.openan.a2at.engine.client;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SseNormalization: bare Task wrapping, artifactUpdate,
 * statusUpdate, already-SSE pass-through.
 */
class SseNormalizationTest {

    @Test
    @SuppressWarnings("unchecked")
    void wrapsBareTaskIntoTaskKey() {
        Map<String, Object> bare = Map.of("id", "task-1", "status", Map.of("state", "COMPLETED"));
        Map<String, Object> result = SseNormalization.normalize(bare);
        assertNotNull(result.get("task"));
        Map<String, Object> task = (Map<String, Object>) result.get("task");
        assertEquals("task-1", task.get("id"));
    }

    @Test
    void wrapsArtifactUpdate() {
        Map<String, Object> bare = Map.of("artifact", Map.of("name", "a1"), "taskId", "task-1");
        Map<String, Object> result = SseNormalization.normalize(bare);
        assertNotNull(result.get("artifactUpdate"));
    }

    @Test
    void wrapsStatusUpdate() {
        Map<String, Object> bare = Map.of("status", Map.of("state", "WORKING"), "taskId", "task-1");
        Map<String, Object> result = SseNormalization.normalize(bare);
        // bare has both "status" and "taskId" -> wraps as statusUpdate
        assertNotNull(result.get("statusUpdate"));
    }

    @Test
    void passThroughWhenAlreadySseShaped() {
        Map<String, Object> already = Map.of("task", Map.of("id", "t1"));
        Map<String, Object> result = SseNormalization.normalize(already);
        assertSame(already, result);
    }

    @Test
    void passThroughWhenMessageKey() {
        Map<String, Object> already = Map.of("message", Map.of("role", "user"));
        Map<String, Object> result = SseNormalization.normalize(already);
        assertSame(already, result);
    }

    @Test
    void returnsNullForNull() {
        assertNull(SseNormalization.normalize(null));
    }
}
