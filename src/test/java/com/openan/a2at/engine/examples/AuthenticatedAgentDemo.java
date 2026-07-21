package com.openan.a2at.engine.examples;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openan.a2at.engine.client.WorkflowEngineClient;
import com.openan.a2at.engine.control.ControlPoint;
import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.control.EventType;
import com.openan.a2at.engine.model.*;
import com.openan.a2at.engine.runner.ExecutePsop;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Demonstrates agent authentication with the SDK.
 *
 * <p>Starts two mock HTTP servers:
 * <ol>
 *   <li>Login endpoint — returns a fixed accessSession token</li>
 *   <li>A2A agent endpoint — validates the Bearer token, returns a diagnosis</li>
 * </ol>
 * Then runs a single-step workflow calling the SPN Domain Agent,
 * with AgentCard declaring Bearer auth. The SDK automatically:
 * <ul>
 *   <li>Calls the login endpoint to obtain the token</li>
 *   <li>Attaches "Authorization: Bearer <token>" to the agent request</li>
 *   <li>Completes the A2A message exchange</li>
 * </ul>
 */
public class AuthenticatedAgentDemo {
    private static final Logger log = LoggerFactory.getLogger(AuthenticatedAgentDemo.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        // --- 1. Start mock login server (returns a fixed token) ---
        int loginPort = 18080;
        HttpServer loginServer = HttpServer.create(new InetSocketAddress(loginPort), 0);
        loginServer.createContext("/auth/login", exchange -> {
            log.info("[MockLogin] Received login request");
            String resp = "{\"accessSession\":\"mock-bearer-token-xyz\"}";
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) { os.write(bytes); }
        });
        loginServer.start();
        log.info("Mock login server started on port {}", loginPort);

        // --- 2. Start mock A2A agent server (validates Bearer token) ---
        int agentPort = 18081;
        AtomicBoolean tokenReceived = new AtomicBoolean(false);
        HttpServer agentServer = HttpServer.create(new InetSocketAddress(agentPort), 0);
        agentServer.createContext("/message:send", exchange -> {
            // Check Authorization header
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            log.info("[MockAgent] Received message:send, Authorization={}",
                    authHeader != null ? authHeader.substring(0, Math.min(40, authHeader.length())) + "..." : "(none)");
            if (authHeader != null && authHeader.startsWith("Bearer mock-bearer-token-xyz")) {
                tokenReceived.set(true);
                // Return a mock diagnosis result
                String resp = "{\"task\":{\"id\":\"task-1\",\"status\":{\"state\":\"TASK_STATE_COMPLETED\"}," +
                        "\"artifacts\":[{\"parts\":[{\"text\":\"" +
                        "SPN 专线故障诊断结果: 客户A上海-广州间专线中断, 根因: 上海OMC端口Down, 光功率-28dBm(低于阈值), 修复建议: 检查上海侧OMC端口光模块." +
                        "\"}]}]}}";
                byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.getResponseHeaders().add("A2A-Version", "1.0");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var os = exchange.getResponseBody()) { os.write(bytes); }
            } else {
                String resp = "{\"error\":{\"code\":401,\"message\":\"Unauthorized: missing or invalid Bearer token\"}}";
                byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(401, bytes.length);
                try (var os = exchange.getResponseBody()) { os.write(bytes); }
            }
        });
        agentServer.start();
        log.info("Mock A2A agent server started on port {}", agentPort);

        try {
            runWorkflow(loginPort, agentPort, tokenReceived);
        } finally {
            loginServer.stop(0);
            agentServer.stop(0);
            log.info("Mock servers stopped");
        }
    }

    private static void runWorkflow(int loginPort, int agentPort, AtomicBoolean tokenReceived) throws Exception {
        // --- 3. Build AgentCard for SPN Domain Agent with Bearer auth ---
        String loginUrl = "http://127.0.0.1:" + loginPort + "/auth/login";
        String agentUrl = "http://127.0.0.1:" + agentPort;

        Map<String, Object> agentCard = new LinkedHashMap<>();
        agentCard.put("name", "SPN Domain Agent");
        agentCard.put("capabilities", Map.of(
                "streaming", false,
                "extensions", List.of(
                        Map.of("uri", "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Task-T/v1", "required", false)
                )
        ));
        agentCard.put("supportedInterfaces", List.of(
                Map.of("protocolBinding", "HTTP+JSON", "url", agentUrl, "protocolVersion", "1.0")
        ));
        // Declare Bearer auth scheme
        agentCard.put("securitySchemes", Map.of(
                "bearerAuth", Map.of("httpAuthSecurityScheme", Map.of("scheme", "Bearer"))
        ));
        agentCard.put("securityRequirements", List.of(
                Map.of("schemes", Map.of("bearerAuth", Map.of()))
        ));

        // --- 4. Configure credentials (login_url, username, password) ---
        Map<String, Object> credentialsConfig = new HashMap<>();
        Map<String, Object> agentCreds = new HashMap<>();
        Map<String, Object> schemeConfig = new HashMap<>();
        schemeConfig.put("login_url", loginUrl);
        schemeConfig.put("method", "POST");
        schemeConfig.put("content_type", "application/json");
        schemeConfig.put("request_fields", Map.of(
                "username", "admin",
                "password", "admin123"
        ));
        schemeConfig.put("token_field", "accessSession");
        schemeConfig.put("token_ttl", 3600);
        agentCreds.put("bearerAuth", schemeConfig);
        credentialsConfig.put("SPN Domain Agent", agentCreds);

        // Write credentials to a temp file
        java.nio.file.Path credFile = java.nio.file.Files.createTempFile("agent_credentials", ".json");
        java.nio.file.Files.writeString(credFile, mapper.writeValueAsString(credentialsConfig));
        log.info("Credentials config written to {}", credFile);

        // --- 5. Build a single-step workflow that calls SPN Domain Agent ---
        Workflow workflow = Workflow.builder()
                .name("SPN Auth Diagnosis")
                .steps(List.of(
                        WorkflowStep.builder()
                                .name("diagnose")
                                .layer(0)
                                .subtasks(List.of(
                                        Task.builder()
                                                .agent("SPN Domain Agent")
                                                .description("SPN专线业务投诉诊断: 客户A上海-广州间专线中断, OMC告警端口Down")
                                                .build()
                                ))
                                .next(List.of())
                                .build()
                ))
                .build();

        // --- 6. Implement ControlPoint ---
        ControlPoint controlPoint = new ControlPoint() {
            @Override
            public CompletableFuture<TaskResponse> onTask(
                    TaskRequest req, WorkflowEngineClient ec) {
                log.info("[onTask] agent={}, sending message (SDK auto-auth handles Bearer token)", req.getAgentName());
                return ec.sendMessage(req.getAgentName(), req.getMessage())
                        .thenApply(r -> {
                            log.info("[onTask] response: {} chars, taskState={}", r.getText().length(), r.getTaskState());
                            return TaskResponse.builder()
                                    .success(true)
                                    .output(r.getText())
                                    .build();
                        })
                        .exceptionally(e -> {
                            log.error("[onTask] failed: {}", e.getMessage());
                            return TaskResponse.builder()
                                    .success(false)
                                    .error("Agent call failed: " + e.getMessage())
                                    .build();
                        });
            }

            @Override
            public CompletableFuture<RouteDecision> onRoute(
                    String stepName, Map<String, Object> results,
                    List<JumpCondition> conditions) {
                return CompletableFuture.completedFuture(
                        RouteDecision.builder().nextStep(conditions.get(0).getStep()).build());
            }
        };

        // --- 7. Execute with ExecutePsop.Builder ---
        log.info("=== Starting authenticated workflow execution ===");
        ExecutionResult result = ExecutePsop.builder()
                .psop(workflow)
                .agentCards(List.of(agentCard))
                .controlPoint(controlPoint)
                .runtimeIntent("SPN 跨城专线故障诊断")
                .lang("zh")
                .sslVerify(false)
                .credentialsConfigPath(credFile.toString())
                .eventCallback(new EventCallback() {
                    @Override
                    public void onEvent(String type, Map<String, Object> data) {
                        if (EventType.AGENT_REQUEST.equals(type)) {
                            log.info("  [Event] {} agent={}", type, data.get("agent"));
                        } else if (EventType.AGENT_RESPONSE.equals(type)) {
                            log.info("  [Event] {} agent={}", type, data.get("agent"));
                        } else if (EventType.COMPLETE.equals(type)) {
                            log.info("  [Event] COMPLETE");
                        }
                    }
                })
                .execute()
                .join();

        // --- 8. Verify results ---
        log.info("=== Results ===");
        log.info("Success: {}", result.isSuccess());
        log.info("Bearer token was attached to agent request: {}", tokenReceived.get());
        log.info("History: {} task(s)", result.getHistory() != null ? result.getHistory().size() : 0);
        if (result.getHistory() != null && !result.getHistory().isEmpty()) {
            Map<String, Object> first = result.getHistory().get(0);
            log.info("First task: agent={}, status={}, output={}",
                    first.get("agent"), first.get("status"),
                    String.valueOf(first.get("output")).substring(0, Math.min(120, String.valueOf(first.get("output")).length())));
        }
        if (result.getError() != null) {
            log.error("Error: {}", result.getError());
        }

        // Cleanup temp file
        java.nio.file.Files.deleteIfExists(credFile);

        if (result.isSuccess() && tokenReceived.get()) {
            log.info("=== DEMO PASSED: SDK successfully authenticated and completed the workflow ===");
        } else {
            log.error("=== DEMO FAILED: success={}, tokenReceived={}", result.isSuccess(), tokenReceived.get());
        }
    }
}
