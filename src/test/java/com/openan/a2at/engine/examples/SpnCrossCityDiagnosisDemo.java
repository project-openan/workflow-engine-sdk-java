package com.openan.a2at.engine.examples;

import com.openan.a2at.engine.client.WorkflowEngineClient;
import com.openan.a2at.engine.client.WorkflowEngineClient.NegotiationResolver;
import com.openan.a2at.engine.control.ControlPoint;
import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.control.EventType;
import com.openan.a2at.engine.model.*;
import com.openan.a2at.engine.runner.ExecutePsop;
import com.openan.a2at.engine.registry.LoadPsop;
import com.openan.a2at.engine.registry.RegistryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Full story demo: SPN cross-city fault diagnosis & recovery.
 *
 * <p>Three-step workflow with Transport Workbench Agent + two OMC agents:
 * <ol>
 *   <li>dispatch — Workbench receives task, dispatches to two cities</li>
 *   <li>parallel_diagnosis — SPN Domain Agent (Shanghai, Bearer auth, may negotiate)
 *       + SPN Domain Agent City2 (Guangzhou, Bearer auth, normal)</li>
 *   <li>merge_analysis — Workbench merges results, determines root cause</li>
 * </ol>
 *
 * <p>The Shanghai OMC (SPN Domain Agent) returns Authorization-T (repair plan
 * needing human approval) and Notification-T (recovery success) in metadata.
 * The SDK auto-detects these and calls onAuthorization / onNotification.
 */
public class SpnCrossCityDiagnosisDemo {
    private static final Logger log = LoggerFactory.getLogger(SpnCrossCityDiagnosisDemo.class);

    static final String REGISTRY_URL = "https://127.0.0.1:5000";
    static final String ORCH_URL = "https://127.0.0.1:5001";
    static final String PSOP_ID = "psop_spn_cross_city_diagnosis";
    static final String CRED_FILE = "spn_agent_credentials.json";
    static final int NEGOTIATION_MAX_ROUNDS = 3;

    public static void main(String[] args) throws Exception {
        boolean sslVerify = false;

        // ========== 1. Fetch AgentCards ==========
        log.info("=== Step 1: Fetch AgentCards ===");
        RegistryClient registry = new RegistryClient(REGISTRY_URL, sslVerify);
        List<Map<String, Object>> agentCards = registry.fetchAgentCards();
        log.info("Got {} agent card(s)", agentCards.size());

        // ========== 2. Load PSOP workflow ==========
        log.info("=== Step 2: Load PSOP ===");
        Workflow workflow = LoadPsop.load(ORCH_URL, PSOP_ID, null, sslVerify);
        log.info("Workflow: {} ({} steps)", workflow.getName(), workflow.getSteps().size());
        for (WorkflowStep step : workflow.getSteps()) {
            log.info("  Step: {} (layer={}, subtasks={})", step.getName(), step.getLayer(), step.getSubtasks().size());
            for (Task task : step.getSubtasks()) {
                log.info("    Agent: {} | {}", task.getAgent(), task.getDescription());
            }
        }

        // ========== 3. Configure credentials ==========
        log.info("=== Step 3: Configure Credentials ===");
        String credPath = SpnCrossCityDiagnosisDemo.class.getClassLoader()
                .getResource(CRED_FILE).getPath();
        log.info("Credentials file: {}", credPath);

        // ========== 4. Implement ControlPoint ==========
        log.info("=== Step 4: Implement ControlPoint ===");
        AtomicBoolean authorizationCalled = new AtomicBoolean(false);
        AtomicBoolean notificationCalled = new AtomicBoolean(false);

        ControlPoint controlPoint = new ControlPoint() {
            @Override
            public CompletableFuture<TaskResponse> onTask(
                    TaskRequest req, WorkflowEngineClient ec) {
                log.info("[onTask] step={} agent={}", req.getStepName(), req.getAgentName());

                // Use sendMessageWithNegotiation for OMC agents (they may negotiate)
                boolean isOmc = req.getAgentName().contains("SPN Domain Agent");
                if (isOmc) {
                    log.info("[onTask] Using sendMessageWithNegotiation (OMC may negotiate)");
                    return ec.sendMessageWithNegotiation(
                            req.getAgentName(), req.getMessage(),
                            NEGOTIATION_MAX_ROUNDS,
                            this::resolveNegotiation
                    ).thenApply(r -> {
                        log.info("[onTask] Response from {}: {} chars, state={}",
                                req.getAgentName(),
                                r.getText() != null ? r.getText().length() : 0,
                                r.getTaskState());
                        boolean success = r.getText() != null && !r.getText().isEmpty();
                        return TaskResponse.builder()
                                .success(success)
                                .output(r.getText())
                                .build();
                    }).exceptionally(e -> {
                        log.error("[onTask] Failed for {}: {}", req.getAgentName(), e.getMessage());
                        return TaskResponse.builder()
                                .success(false)
                                .error("Agent call failed: " + e.getMessage())
                                .build();
                    });
                } else {
                    // Transport Workbench Agent - simple send
                    log.info("[onTask] Using sendMessage (Workbench)");
                    return ec.sendMessage(req.getAgentName(), req.getMessage())
                            .thenApply(r -> {
                                log.info("[onTask] Response from {}: {} chars",
                                        req.getAgentName(),
                                        r.getText() != null ? r.getText().length() : 0);
                                return TaskResponse.builder()
                                        .success(true)
                                        .output(r.getText())
                                        .build();
                            });
                }
            }

            CompletableFuture<String> resolveNegotiation(
                    String agentName, String negotiationText,
                    Map<String, Object> receiveResult) {
                log.info("[NegotiationResolver] agent={}, concern={}",
                        agentName,
                        negotiationText != null ? negotiationText.substring(0, Math.min(100, negotiationText.length())) : "(empty)");
                // In production: look up predecessor data, call LLM, etc.
                // For demo: provide the supplementary data
                String clarification = "根据上层工作台上下文，客户A的上海-广州间SPN专线中断，"
                        + "上海OMC告警端口Down，光功率-28dBm。请补充：端口所属单板为 line-card-03，"
                        + "端口编号 port-7，最近一次维护时间为2026-07-15。";
                return CompletableFuture.completedFuture(clarification);
            }

            @Override
            public CompletableFuture<Boolean> onAuthorization(
                    String agentName, Map<String, Object> authRequest) {
                authorizationCalled.set(true);
                log.info("[onAuthorization] agent={} requests authorization", agentName);
                log.info("[onAuthorization] repair_plan={}", authRequest.get("repair_plan"));
                log.info("[onAuthorization] risk_level={}", authRequest.get("risk_level"));
                // In production: show dialog, wait for human approval
                // For demo: auto-approve
                log.info("[onAuthorization] Auto-approving (demo)");
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public CompletableFuture<RouteDecision> onRoute(
                    String stepName, Map<String, Object> results,
                    List<JumpCondition> conditions) {
                log.info("[onRoute] step={}, choosing first branch: {}",
                        stepName, conditions.get(0).getStep());
                return CompletableFuture.completedFuture(
                        RouteDecision.builder()
                                .nextStep(conditions.get(0).getStep())
                                .reason("sequential flow")
                                .build());
            }

            @Override
            public CompletableFuture<Void> onNotification(
                    String agentName, Map<String, Object> notification) {
                notificationCalled.set(true);
                log.info("[onNotification] agent={} reports recovery: {}",
                        agentName, notification.get("message"));
                // In production: update UI, report to WAIMO
                return CompletableFuture.completedFuture(null);
            }
        };

        // ========== 5. Execute ==========
        log.info("=== Step 5: Execute PSOP ===");
        EventCallback eventCallback = new EventCallback() {
            @Override
            public void onEvent(String type, Map<String, Object> data) {
                switch (type) {
                    case EventType.START -> log.info("  [START] {}", data.get("workflow"));
                    case EventType.STEP_START -> log.info("  [STEP_START] {}", data.get("step"));
                    case EventType.TASK_REQUEST -> log.info("  [TASK_REQUEST] agent={}", data.get("agent"));
                    case EventType.TASK_RESPONSE -> log.info("  [TASK_RESPONSE] agent={} output={} chars",
                            data.get("agent"),
                            String.valueOf(data.get("output")).length());
                    case EventType.TASK_STATUS_CHANGED -> log.info("  [TASK_STATUS] {} -> {}",
                            data.get("agent"), data.get("status"));
                    case EventType.AGENT_REQUEST -> log.info("  [AGENT_REQUEST] {}", data.get("agent"));
                    case EventType.AGENT_RESPONSE -> log.info("  [AGENT_RESPONSE] {}", data.get("agent"));
                    case EventType.NEGOTIATION_REQUEST -> log.info("  [NEGOTIATION_REQUEST] round={} concern={}",
                            data.get("round"), data.get("concern"));
                    case EventType.NEGOTIATION_RESOLVED -> log.info("  [NEGOTIATION_RESOLVED] round={}",
                            data.get("round"));
                    case EventType.AUTHORIZATION_REQUEST -> log.info("  [AUTHORIZATION_REQUEST] agent={}",
                            data.get("agent"));
                    case EventType.AUTHORIZATION_RESOLVED -> log.info("  [AUTHORIZATION_RESOLVED] decision={}",
                            data.get("decision"));
                    case EventType.NOTIFICATION -> log.info("  [NOTIFICATION] agent={}", data.get("agent"));
                    case EventType.STEP_COMPLETE -> log.info("  [STEP_COMPLETE] {}", data.get("step"));
                    case EventType.ROUTE_DECISION -> log.info("  [ROUTE] {} -> {}",
                            data.get("step"), data.get("next"));
                    case EventType.COMPLETE -> log.info("  [COMPLETE] tasks={}",
                            ((List<?>) data.getOrDefault("history", List.of())).size());
                    case EventType.ERROR -> log.error("  [ERROR] {}", data.get("error"));
                    case EventType.CLOSE -> log.info("  [CLOSE]");
                    default -> {}
                }
            }
        };

        ExecutionResult result = ExecutePsop.builder()
                .psop(workflow)
                .agentCards(agentCards)
                .controlPoint(controlPoint)
                .runtimeIntent("SPN跨城专线故障诊断与抢通")
                .lang("zh")
                .sslVerify(false)
                .credentialsConfigPath(credPath)
                .eventCallback(eventCallback)
                .onFinish((r, events) -> {
                    log.info("--- on_finish ---");
                    log.info("Success: {}, Events: {}", r.isSuccess(), events.size());
                    return CompletableFuture.completedFuture(null);
                })
                .execute()
                .join();

        // ========== 6. Results ==========
        log.info("=== Results ===");
        log.info("Workflow success: {}", result.isSuccess());
        log.info("Authorization was triggered: {}", authorizationCalled.get());
        log.info("Notification was received: {}", notificationCalled.get());
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

        if (result.isSuccess() && authorizationCalled.get() && notificationCalled.get()) {
            log.info("=== DEMO PASSED: Full story cycle completed (diagnosis + negotiation + authorization + notification) ===");
        } else {
            log.warn("=== DEMO PARTIAL: success={}, auth={}, notif={} ===",
                    result.isSuccess(), authorizationCalled.get(), notificationCalled.get());
        }
    }
}
