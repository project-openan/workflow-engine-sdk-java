package com.openan.a2at.engine.registry;

import com.openan.a2at.engine.model.WorkflowSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests LoadPsop.search parsing logic using a local mock HTTP server.
 * No real orchestration center needed.
 */
class LoadPsopSearchTest {

    /**
     * Verifies that the search method correctly parses the orchestration
     * center's JSON response into WorkflowSearchResult objects.
     *
     * <p>Uses Java's built-in HttpServer to mock the endpoint, so no
     * external dependency is needed.
     */
    @Test
    void searchParsesResultsFromMockServer() throws Exception {
        // Start a mock HTTP server returning a canned search response
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(0), 0);
        server.createContext("/api/v1/orchestrate/search", exchange -> {
            String json = "{\"data\":[" +
                "{\"workflow_id\":\"psop-1\",\"workflow_type\":\"psop\",\"name\":\"fault_diag\",\"description\":\"SPN fault diagnosis\",\"score\":0.95,\"user_intent\":\"diagnose SPN\",\"tasks_summary\":\"3 steps\"}," +
                "{\"workflow_id\":\"psop-2\",\"workflow_type\":\"psop\",\"name\":\"energy_saving\",\"description\":\"Energy saving flow\",\"score\":0.80}" +
            "]}";
            byte[] resp = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (var os = exchange.getResponseBody()) { os.write(resp); }
        });
        server.start();
        int port = server.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port;

        try {
            List<WorkflowSearchResult> results = LoadPsop.search(baseUrl, "SPN fault", 5, null, false);

            assertEquals(2, results.size());

            WorkflowSearchResult r1 = results.get(0);
            assertEquals("psop-1", r1.getWorkflowId());
            assertEquals("psop", r1.getWorkflowType());
            assertEquals("fault_diag", r1.getName());
            assertEquals("SPN fault diagnosis", r1.getDescription());
            assertEquals(0.95, r1.getScore(), 0.001);
            assertEquals("diagnose SPN", r1.getUserIntent());
            assertEquals("3 steps", r1.getTasksSummary());

            WorkflowSearchResult r2 = results.get(1);
            assertEquals("psop-2", r2.getWorkflowId());
            assertEquals("energy_saving", r2.getName());
            assertEquals(0.80, r2.getScore(), 0.001);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void searchEmptyResultsFromMockServer() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(0), 0);
        server.createContext("/api/v1/orchestrate/search", exchange -> {
            String json = "{\"data\":[]}";
            byte[] resp = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (var os = exchange.getResponseBody()) { os.write(resp); }
        });
        server.start();
        int port = server.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port;

        try {
            List<WorkflowSearchResult> results = LoadPsop.search(baseUrl, "nonexistent", 5, null, false);
            assertTrue(results.isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void searchThrowsOnServerError() {
        com.sun.net.httpserver.HttpServer server;
        try {
            server = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress(0), 0);
        } catch (java.io.IOException e) {
            fail("Could not start mock server: " + e.getMessage());
            return;
        }
        server.createContext("/api/v1/orchestrate/search", exchange -> {
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port;

        try {
            assertThrows(RuntimeException.class, () ->
                LoadPsop.search(baseUrl, "test", 5, null, false));
        } finally {
            server.stop(0);
        }
    }
}
