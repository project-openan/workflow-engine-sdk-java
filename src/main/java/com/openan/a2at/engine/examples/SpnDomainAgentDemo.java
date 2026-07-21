package com.openan.a2at.engine.examples;

import com.openan.a2at.engine.client.WorkflowEngineClient;
import com.openan.a2at.engine.control.ControlPoint;
import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.control.EventType;
import com.openan.a2at.engine.model.*;
import com.openan.a2at.engine.runner.ExecutePsop;
import com.openan.a2at.engine.registry.LoadPsop;
import com.openan.a2at.engine.registry.RegistryClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * End-to-end demo with the real SPN Domain Agent (requires auth).
 *
 * <p>Full flow:
 * <ol>
 *   <li>Fetch AgentCards from the registry center (port 5000)</li>
 *   <li>Search for a PSOP workflow from the orchestration center (port 5001)</li>
 *   <li>Load the full PSOP that calls SPN Domain Agent</li>
 *   <li>Configure credentials (login_url, username, password, token_field)</li>
 *   <li>Execute: SDK auto-logs in, obtains accessSession, attaches Bearer header,
 *       sends A2A message to SPN Domain Agent, receives diagnosis result</li>
 * </ol>
 *
 * <p>Prerequisites:
 * <ul>
 *   <li>Registry center running on https://127.0.0.1:5000</li>
 *   <li>Orchestration center running on https://127.0.0.1:5001</li>
 *   <li>Agent server running (SPN Domain Agent on port 26335 with login endpoint)</li>
 * </ul>
 */
public class SpnDomainAgentDemo {
    private static final Logger log = LoggerFactory.getLogger(SpnDomainAgentDemo.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    // --- Service URLs (override via args) ---
    static final String REGISTRY_URL = "https://127.0.0.1:5000";
    static final String ORCH_URL = "https://127.0.0.1:5001";
    static final String SPN_AGENT_LOGIN_URL = "http://127.0.0.1:26335/rest/plat/smapp/v1/oauth/token";
    static final String SEARCH_INTENT = "SPN专线故障诊断";

    public static void main(String[] args) throws Exception {
        boolean sslVerify = false; // self-signed certs in dev

        // ========== 1. Fetch AgentCards from registry ==========
        log.info("=== Step 1: Fetch AgentCards from Registry Center ===");
        RegistryClient registry = new RegistryClient(REGISTRY_URL, sslVerify);
        List<Map<String, Object>> agentCards = registry.fetchAgentCards();
        log.info("Got {} agent card(s): {}", agentCards.size(),
                agentCards.stream().map(c -> c.get("name")).toList());

        // Find the SPN Domain Agent card
        Map<String, Object> spnCard = agentCards.stream()
                .filter(c -> "SPN Domain Agent".equals(c.get("name")))
                .findFirst()
                .orElse(null);
        if (spnCard == null) {
            throw new RuntimeException("SPN Domain Agent not found in registry");
        }
        log.info("Found SPN Domain Agent card with securitySchemes: {}", spnCard.get("securitySchemes") != null);

        // ========== 2. Search for PSOP workflow ==========
        log.info("=== Step 2: Search PSOP from Orchestration Center ===");
        List<WorkflowSearchResult> searchResults = LoadPsop.search(
                ORCH_URL, SEARCH_INTENT, 5, null, sslVerify);
        log.info("Search returned {} workflow(s)", searchResults.size());
        for (WorkflowSearchResult r : searchResults) {
            log.info("  - {} | {} | score={}", r.getWorkflowId(), r.getName(), r.getScore());
        }
        if (searchResults.isEmpty()) {
            throw new RuntimeException("No PSOP found for intent: " + SEARCH_INTENT);
        }

        // ========== 3. Load the full PSOP ==========
        log.info("=== Step 3: Load Full PSOP ===");
        String psopId = searchResults.get(0).getWorkflowId();
        Workflow workflow = LoadPsop.load(ORCH_URL, psopId, null, sslVerify);
        log.info("Loaded workflow: {} ({} steps)", workflow.getName(), workflow.getSteps().size());
        for (WorkflowStep step : workflow.getSteps()) {
            log.info("  Step: {} layer={}", step.getName(), step.getLayer());
            for (Task task : step.getSubtasks()) {
                log.info("    Agent: {} | Task: {}", task.getAgent(), task.getDescription());
            }
        }

        // ========== 4. Configure agent credentials ==========
        log.info("=== Step 4: Configure Agent Credentials ===");
        // Write a temp agent_credentials.json pointing to the SPN Domain Agent login endpoint
        Map<String, Object> credentialsConfig = new LinkedHashMap<>();
        Map<String, Object> agentEntry = new LinkedHashMap<>();
        Map<String, Object> bearerScheme = new LinkedHashMap<>();
        bearerScheme.put("login_url", SPN_AGENT_LOGIN_URL);
        bearerScheme.put("method", "PUT");
        bearerScheme.put("content_type", "application/json");
        // request_fields matches the SPN Domain Agent's login API
        Map<String, Object> requestFields = new LinkedHashMap<>();
        requestFields.put("grantType", "password");
        requestFields.put("userName", "admin");
        requestFields.put("value", "Admin@123");
        requestFields.put("ipaddr", "*");
        bearerScheme.put("request_fields", requestFields);
        bearerScheme.put("token_field", "accessSession");
        bearerScheme.put("token_ttl", 3600);
        agentEntry.put("bearerAuth", bearerScheme);
        credentialsConfig.put("SPN Domain Agent", agentEntry);

        Path credFile = Files.createTempFile("agent_credentials", ".json");
        Files.writeString(credFile, mapper.writeValueAsString(credentialsConfig));
        log.info("Credentials config written to {}", credFile);
        log.info("Login URL: {}", SPN_AGENT_LOGIN_URL);
        log.info("Scheme: bearerAuth, token_field=accessSession");

        // ========== 5. Execute with ControlPoint ==========
        log.info("=== Step 5: Execute PSOP (SDK auto-authenticates) ===");
        ControlPoint controlPoint = new ControlPoint() {
            @Override
            public CompletableFuture<TaskResponse> onTask(
                    TaskRequest req, WorkflowEngineClient ec) {
                log.info("[onTask] Dispatching to agent: {}", req.getAgentName());
                log.info("[onTask] Task: {}", req.getDescription());
                log.info("[onTask] Message length: {} chars", req.getMessage().length());
                // SDK will: 1) call login_url to get accessSession
                //          2) attach "Authorization: Bearer <token>" header
                //          3) POST message to the agent's /message:send endpoint
                return ec.sendMessage(req.getAgentName(), req.getMessage())
                        .thenApply(r -> {
                            log.info("[onTask] Response: {} chars, taskState={}",
                                    r.getText() != null ? r.getText().length() : 0,
                                    r.getTaskState());
                            return TaskResponse.builder()
                                    .success(!"INPUT_REQUIRED".equals(r.getTaskState()))
                                    .output(r.getText())
                                    .build();
                        })
                        .exceptionally(e -> {
                            log.error("[onTask] Failed: {}", e.getMessage());
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
                log.info("[onRoute] step={}, conditions={}", stepName,
                        conditions.stream().map(JumpCondition::getStep).toList());
                return CompletableFuture.completedFuture(
                        RouteDecision.builder()
                                .nextStep(conditions.get(0).getStep())
                                .reason("first branch")
                                .build());
            }
        };

        EventCallback eventCallback = new EventCallback() {
            @Override
            public void onEvent(String type, Map<String, Object> data) {
                switch (type) {
                    case EventType.START -> log.info("  [START] workflow={}", data.get("workflow"));
                    case EventType.STEP_START -> log.info("  [STEP_START] {}", data.get("step"));
                    case EventType.TASK_REQUEST -> log.info("  [TASK_REQUEST] agent={}", data.get("agent"));
                    case EventType.TASK_STATUS_CHANGED -> log.info("  [TASK_STATUS] agent={} status={}", data.get("agent"), data.get("status"));
                    case EventType.TASK_RESPONSE -> {
                        String output = String.valueOf(data.getOrDefault("output", ""));
                        log.info("  [TASK_RESPONSE] agent={} output={} chars", data.get("agent"), output.length());
                    }
                    case EventType.AGENT_REQUEST -> log.info("  [AGENT_REQUEST] agent={}", data.get("agent"));
                    case EventType.AGENT_RESPONSE -> log.info("  [AGENT_RESPONSE] agent={}", data.get("agent"));
                    case EventType.STEP_COMPLETE -> log.info("  [STEP_COMPLETE] {}", data.get("step"));
                    case EventType.COMPLETE -> log.info("  [COMPLETE] tasks={}",
                            ((List<?>) data.getOrDefault("history", List.of())).size());
                    case EventType.ERROR -> log.error("  [ERROR] {}", data.get("error"));
                    case EventType.CLOSE -> log.info("  [CLOSE]");
                    default -> { /* negotiation/auth/notification events */ }
                }
            }
        };

        ExecutionResult result = ExecutePsop.builder()
                .psop(workflow)
                .agentCards(agentCards)
                .controlPoint(controlPoint)
                .runtimeIntent(SEARCH_INTENT)
                .lang("zh")
                .sslVerify(false)
                .credentialsConfigPath(credFile.toString())
                .eventCallback(eventCallback)
                .onFinish((r, events) -> {
                    log.info("--- on_finish ---");
                    log.info("Success: {}", r.isSuccess());
                    log.info("History: {} task(s)", r.getHistory() != null ? r.getHistory().size() : 0);
                    log.info("Events: {} total", events.size());
                    return CompletableFuture.completedFuture(null);
                })
                .execute()
                .join();

        // ========== 6. Print results ==========
        log.info("=== Results ===");
        log.info("Workflow success: {}", result.isSuccess());
        log.info("History: {} task(s)", result.getHistory() != null ? result.getHistory().size() : 0);
        if (result.getHistory() != null) {
            for (Map<String, Object> h : result.getHistory()) {
                log.info("  Task: agent={} status={} output={} chars",
                        h.get("agent"), h.get("status"),
                        String.valueOf(h.get("output")).length());
            }
        }
        if (result.getError() != null) {
            log.error("Error: {}", result.getError());
        }

        // Cleanup
        Files.deleteIfExists(credFile);

        if (result.isSuccess()) {
            log.info("=== DEMO PASSED: SPN Domain Agent authenticated + workflow completed ===");
        } else {
            log.error("=== DEMO FAILED ===");
        }
    }
}
