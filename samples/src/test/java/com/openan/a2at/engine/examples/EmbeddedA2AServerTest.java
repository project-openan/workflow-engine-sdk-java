package com.openan.a2at.engine.examples;

import com.openan.a2at.engine.client.DefaultWorkflowEngineClient;
import com.openan.a2at.engine.client.AgentCardMapper;
import org.a2aproject.sdk.spec.AgentCard;
import com.openan.a2at.engine.client.WorkflowEngineClientConfig;
import com.openan.a2at.engine.examples.agents.SpnDomainAgentExecutor;
import com.openan.a2at.engine.examples.server.EmbeddedA2AServer;
import com.openan.a2at.engine.model.SendMessageResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: start an EmbeddedA2AServer, send a message via
 * DefaultWorkflowEngineClient (the real A2A client), and verify the
 * response text is extracted correctly from the SSE stream.
 */
class EmbeddedA2AServerTest {

    private EmbeddedA2AServer server;
    private DefaultWorkflowEngineClient client;
    private int port;
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String AGENT_NAME = "Test Agent";

    @BeforeEach
    void setUp() throws Exception {
        // Unit tests must stay deterministic and offline: disable LLM calls so
        // agents fall back to their hardcoded diagnostic text (asserted below).
        System.setProperty("a2at.llm.disabled", "true");
        port = 28000 + (int) (Math.random() * 1000);
        Map<String, Object> card = Map.of(
                "name", AGENT_NAME,
                "description", "test",
                "provider", Map.of("organization", "test", "url", ""),
                "version", "1.0.0",
                "capabilities", Map.of("streaming", true, "pushNotifications", false),
                "defaultInputModes", List.of("text/plain"),
                "defaultOutputModes", List.of("text/plain"),
                "skills", List.of(Map.of("id", "test", "name", "test", "description", "test", "tags", List.of())),
                "supportedInterfaces", List.of(Map.of("protocolBinding", "HTTP+JSON", "protocolVersion", "1.0", "url", "http://127.0.0.1:" + port))
        );
        server = new EmbeddedA2AServer("127.0.0.1", port, card, new SpnDomainAgentExecutor());
        server.start();
        Thread.sleep(500);

        client = new DefaultWorkflowEngineClient(
                List.of(AgentCardMapper.toSdkAgentCard(card)), null,
                WorkflowEngineClientConfig.builder().sslVerify(false).build());
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
        if (server != null) server.close();
    }

    @Test
    void testGetAgentCard() throws Exception {
        HttpClient http = HttpClient.newBuilder().build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/"))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        Map<String, Object> card = mapper.readValue(resp.body(), Map.class);
        assertEquals(AGENT_NAME, card.get("name"));
    }

    @Test
    void testSendMessage() throws Exception {
        SendMessageResult result = client.sendMessage(AGENT_NAME, "diagnose SPN fault").join();
        assertNotNull(result);
        assertFalse(result.getText().isEmpty(), "Response text should not be empty, got: " + result.getText());
        assertTrue(result.getText().contains("\u4e0a\u6d77"), "Diagnosis result should mention Shanghai, got: " + result.getText());
    }
}
