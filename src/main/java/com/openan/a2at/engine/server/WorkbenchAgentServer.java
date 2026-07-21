package com.openan.a2at.engine.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Embedded A2A HTTP server for the Workbench Agent.
 *
 * <p>Implements A2A REST protocol endpoints:
 * <ul>
 *   <li>{@code GET /} - return AgentCard</li>
 *   <li>{@code POST /message:send} - non-streaming (JSON-RPC 2.0)</li>
 *   <li>{@code POST /message:stream} - streaming (SSE)</li>
 * </ul>
 *
 * <p>The Workbench Agent is both server (receives tasks from callers) and
 * client (dispatches sub-tasks to OMC agents via the workflow engine).
 */
public class WorkbenchAgentServer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(WorkbenchAgentServer.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final HttpServer server;
    private final ExecutorService executor;
    private final Map<String, Object> agentCard;
    private final MessageHandler messageHandler;

    @FunctionalInterface
    public interface MessageHandler {
        AgentResponse handle(String messageText, Map<String, Object> metadata) throws Exception;
    }

    public record AgentResponse(String text, Map<String, Object> metadata) {
        public AgentResponse(String text) { this(text, null); }
    }

    public WorkbenchAgentServer(String host, int port,
                                Map<String, Object> agentCard,
                                MessageHandler messageHandler) throws IOException {
        this.agentCard = agentCard;
        this.messageHandler = messageHandler;
        this.executor = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "workbench-agent-" + UUID.randomUUID().toString().substring(0, 8));
            t.setDaemon(true);
            return t;
        });
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.server.setExecutor(executor);
        this.server.createContext("/", new A2AHandler());
        log.info("[WorkbenchAgent] Server started on http://{}:{}/", host, port);
    }

    public void start() { server.start(); }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdown();
        log.info("[WorkbenchAgent] Server stopped");
    }

    public Map<String, Object> getAgentCard() { return agentCard; }

    public static Map<String, Object> buildAgentCard(String host, int port) {
        return Map.of(
                "name", "Transport Workbench Agent",
                "description", "Transport Workbench Agent - receives fault diagnosis tasks, orchestrates OMC agents",
                "version", "1.0.0",
                "defaultInputModes", List.of("application/json", "text/plain"),
                "defaultOutputModes", List.of("application/json", "text/plain"),
                "provider", Map.of("organization", "Huawei", "url", "https://www.huawei.com"),
                "skills", List.of(Map.of(
                        "id", "cross-city-fault-diagnosis",
                        "name", "cross-city-fault-diagnosis",
                        "description", "receive fault diagnosis task, orchestrate OMC agents, merge and recover",
                        "tags", List.of("SPN", "fault-diagnosis", "cross-city"))),
                "capabilities", Map.of(
                        "streaming", true,
                        "pushNotifications", false,
                        "extensions", List.of(
                                Map.of("uri", "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Task-T/v1",
                                        "description", "Extension of structured prompt Task-T requests."),
                                Map.of("uri", "https://projects.tmforum.org/a2aproject/telecommunication/extensions/NEGOTIATION-T",
                                        "description", "Extension for A2A-T negotiation text exchange."))),
                "supportedInterfaces", List.of(Map.of(
                        "protocolBinding", "HTTP+JSON",
                        "protocolVersion", "1.0",
                        "url", "http://" + host + ":" + port)));
    }

    private class A2AHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            try {
                if ("GET".equalsIgnoreCase(method) && ("/".equals(path) || "/.well-known/agent-card".equals(path))) {
                    sendJson(exchange, 200, agentCard);
                    return;
                }
                if ("POST".equalsIgnoreCase(method) && "/message:send".equals(path)) {
                    handleSendMessage(exchange);
                    return;
                }
                if ("POST".equalsIgnoreCase(method) && "/message:stream".equals(path)) {
                    handleStreamMessage(exchange);
                    return;
                }
                exchange.sendResponseHeaders(404, -1);
            } catch (Exception e) {
                log.error("[WorkbenchAgent] Handler error: {}", e.getMessage(), e);
                sendJson(exchange, 500, Map.of("error", Map.of("code", 500, "message", e.getMessage())));
            } finally {
                exchange.close();
            }
        }

        @SuppressWarnings("unchecked")
        private void handleSendMessage(HttpExchange exchange) throws IOException {
            String body = readBody(exchange);
            log.info("[WorkbenchAgent] Received message:send, body={} chars", body.length());
            Map<String, Object> request = mapper.readValue(body, Map.class);
            String rpcId = String.valueOf(request.getOrDefault("id", ""));
            Map<String, Object> params = (Map<String, Object>) request.getOrDefault("params", Map.of());
            Map<String, Object> message = (Map<String, Object>) params.getOrDefault("message", Map.of());
            Map<String, Object> metadata = (Map<String, Object>) params.get("metadata");
            String messageText = extractTextFromMessage(message);
            log.info("[WorkbenchAgent] Message text: {}", messageText);
            try {
                AgentResponse agentResponse = messageHandler.handle(messageText, metadata);
                Map<String, Object> task = buildTaskResponse(agentResponse, rpcId);
                Map<String, Object> rpcResponse = Map.of("jsonrpc", "2.0", "result", task, "id", rpcId);
                sendJson(exchange, 200, rpcResponse);
            } catch (Exception e) {
                log.error("[WorkbenchAgent] Message handling failed: {}", e.getMessage(), e);
                Map<String, Object> error = Map.of("jsonrpc", "2.0",
                        "error", Map.of("code", -32603, "message", e.getMessage()), "id", rpcId);
                sendJson(exchange, 500, error);
            }
        }

        @SuppressWarnings("unchecked")
        private void handleStreamMessage(HttpExchange exchange) throws IOException {
            String body = readBody(exchange);
            log.info("[WorkbenchAgent] Received message:stream, body={} chars", body.length());
            Map<String, Object> request = mapper.readValue(body, Map.class);
            String rpcId = String.valueOf(request.getOrDefault("id", ""));
            Map<String, Object> params = (Map<String, Object>) request.getOrDefault("params", Map.of());
            Map<String, Object> message = (Map<String, Object>) params.getOrDefault("message", Map.of());
            Map<String, Object> metadata = (Map<String, Object>) params.get("metadata");
            String messageText = extractTextFromMessage(message);
            log.info("[WorkbenchAgent] Stream message text: {}", messageText);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                sendSse(os, Map.of("task", Map.of(
                        "id", rpcId, "status", Map.of("state", "working"), "metadata", Map.of())));
                AgentResponse agentResponse = messageHandler.handle(messageText, metadata);
                Map<String, Object> task = buildTaskResponse(agentResponse, rpcId);
                sendSse(os, Map.of("task", task));
            } catch (Exception e) {
                log.error("[WorkbenchAgent] Stream handling failed: {}", e.getMessage(), e);
            }
        }

        @SuppressWarnings("unchecked")
        private String extractTextFromMessage(Map<String, Object> message) {
            if (message == null) return "";
            Object parts = message.get("parts");
            if (parts instanceof List<?> partList) {
                StringBuilder sb = new StringBuilder();
                for (Object p : partList) {
                    if (p instanceof Map<?, ?> partMap) {
                        Object text = partMap.get("text");
                        if (text != null) sb.append(text);
                    }
                }
                return sb.toString();
            }
            Object text = message.get("text");
            return text != null ? text.toString() : "";
        }

        private Map<String, Object> buildTaskResponse(AgentResponse response, String taskId) {
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("id", taskId);
            task.put("status", Map.of("state", "completed"));
            task.put("artifacts", List.of(Map.of(
                    "artifactId", UUID.randomUUID().toString(),
                    "parts", List.of(Map.of("type", "text", "text", response.text())))));
            Map<String, Object> taskMeta = response.metadata() != null ? new HashMap<>(response.metadata()) : new HashMap<>();
            task.put("metadata", taskMeta);
            return task;
        }

        private void sendSse(OutputStream os, Map<String, Object> data) throws IOException {
            String json = mapper.writeValueAsString(data);
            String frame = "data:" + json.replace("\n", "") + "\n\n";
            os.write(frame.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void sendJson(HttpExchange exchange, int status, Object data) throws IOException {
        String json = mapper.writeValueAsString(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }
}
