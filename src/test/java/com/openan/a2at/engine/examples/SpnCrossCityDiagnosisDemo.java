package com.openan.a2at.engine.examples;

import com.openan.a2at.engine.control.ControlPoint;
import com.openan.a2at.engine.control.DefaultControlPoint;
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
        log.info("=== Step 4: ControlPoint (based on DefaultControlPoint) ===");
        AtomicBoolean authorizationCalled = new AtomicBoolean(false);
        AtomicBoolean notificationCalled = new AtomicBoolean(false);

        // ControlPoint: override onTask to inject city-specific diagnostic params,
        // override onRoute to analyze merged results and route to the fault city.
        ControlPoint controlPoint = new DefaultControlPoint() {
            @Override
            public CompletableFuture<TaskResponse> onTask(
                    TaskRequest request, com.openan.a2at.engine.client.WorkflowEngineClient engineClient) {
                String step = request.getStepName();
                String enrichedMessage = request.getMessage();
                if ("diagnosis_city1".equals(step)) {
                    enrichedMessage += "\n\n## \u57ce\u5e02\u5dee\u5f02\u5316\u53c2\u6570\n\u5ba2\u6237A\u4e0a\u6d77-\u5e7f\u5dde\u95f4SPN\u4e13\u7ebf\u4e2d\u65ad\uff0c\u4e0a\u6d77OMC\u544a\u8b66\u7aef\u53e3Down\uff0c\u5149\u529f\u7387-28dBm\u4f4e\u4e8e\u9608\u503c\u3002"
                            + "\u7aef\u53e3\u6240\u5c5e\u5355\u677fline-card-03\uff0c\u7aef\u53e3\u53f7port-7\uff0c\u6700\u8fd1\u7ef4\u62a42026-07-15\u3002\u8bf7\u8fdb\u884c\u6839\u56e0\u8bca\u65ad\u3002";
                    log.info("[onTask] Injected Shanghai-specific params for {}", step);
                } else if ("diagnosis_city2".equals(step)) {
                    enrichedMessage += "\n\n## \u57ce\u5e02\u5dee\u5f02\u5316\u53c2\u6570\n\u5ba2\u6237A\u4e0a\u6d77-\u5e7f\u5dde\u95f4SPN\u4e13\u7ebf\u4e2d\u65ad\uff0c\u5e7f\u5ddeOMC\u4fa7\u9700\u6392\u67e5\u7aef\u53e3\u72b6\u6001\u548c\u5149\u529f\u7387\u662f\u5426\u6b63\u5e38\u3002";
                    log.info("[onTask] Injected Guangzhou-specific params for {}", step);
                } else if ("recovery_city1".equals(step) || "recovery_city2".equals(step)) {
                    enrichedMessage += "\n\n## \u62a2\u901a\u6307\u4ee4\n\u5411\u6545\u969cOMC\u4e0b\u53d1\u62a2\u901a\u6388\u6743\u786e\u8ba4\uff0c\u6267\u884c\u62a2\u901a\u65b9\u6848\uff08\u66f4\u6362\u5149\u6a21\u5757\u3001\u6062\u590d\u7aef\u53e3\uff09\uff0c\u5b8c\u6210\u540e\u4e0a\u62a5\u62a2\u901a\u6210\u529f\u7ed3\u679c\u3002";
                    log.info("[onTask] Injected recovery params for {}", step);
                }
                final String finalMessage = enrichedMessage;
                return engineClient.sendMessage(request.getAgentName(), finalMessage)
                        .thenApply(r -> {
                            boolean success = r.getText() != null && !r.getText().isEmpty();
                            log.info("[onTask] Response from {}: {} chars, success={}",
                                    request.getAgentName(),
                                    r.getText() != null ? r.getText().length() : 0, success);
                            return TaskResponse.builder().success(success).output(r.getText()).build();
                        })
                        .exceptionally(e -> {
                            log.error("[onTask] Task failed for {}: {}", request.getAgentName(), e.getMessage());
                            return TaskResponse.builder().success(false).error("Agent call failed: " + e.getMessage()).build();
                        });
            }

            @Override
            public CompletableFuture<RouteDecision> onRoute(
                    String stepName, Map<String, Object> results,
                    List<JumpCondition> conditions) {
                if ("merge_analysis".equals(stepName)) {
                    Object city1Result = results.get("diagnosis_city1");
                    Object city2Result = results.get("diagnosis_city2");
                    String c1Text = city1Result != null ? city1Result.toString() : "";
                    String c2Text = city2Result != null ? city2Result.toString() : "";
                    log.info("[onRoute] merge_analysis: city1={} chars, city2={} chars",
                            c1Text.length(), c2Text.length());
                    String nextStep = "recovery_city1";
                    if (c2Text.contains("\u6545\u969c") && !c1Text.contains("\u6545\u969c")) {
                        nextStep = "recovery_city2";
                    }
                    if (!c1Text.contains("\u6545\u969c") && !c2Text.contains("\u6545\u969c")) {
                        nextStep = "endNode";
                    }
                    log.info("[onRoute] {} -> {}", stepName, nextStep);
                    return CompletableFuture.completedFuture(
                            RouteDecision.builder().nextStep(nextStep).reason("fault analysis").build());
                }
                return super.onRoute(stepName, results, conditions);
            }

            @Override
            public CompletableFuture<String> onNegotiation(
                    String agentName, String negotiationText,
                    Map<String, Object> receiveResult) {
                log.info("[onNegotiation] agent={}: {}",
                        agentName,
                        negotiationText != null ? negotiationText : "(empty)");
                return CompletableFuture.completedFuture(
                        "\u6839\u636e\u5de5\u4f5c\u53f0\u4e0a\u4e0b\u6587\uff0c\u5ba2\u6237A\u4e0a\u6d77-\u5e7f\u5dde\u95f4SPN\u4e13\u7ebf\u4e2d\u65ad\uff0c\u4e0a\u6d77OMC\u544a\u8b66\u7aef\u53e3Down\uff0c"
                        + "\u5149\u529f\u7387-28dBm\u3002\u7aef\u53e3\u6240\u5c5e\u5355\u677fline-card-03\uff0c\u7aef\u53e3\u53f7port-7\uff0c\u6700\u8fd1\u7ef4\u62a42026-07-15\u3002");
            }

            @Override
            public CompletableFuture<Boolean> onAuthorization(
                    String agentName, Map<String, Object> authRequest) {
                authorizationCalled.set(true);
                log.info("[onAuthorization] agent={}, repair_plan={}, risk={}",
                        agentName, authRequest.get("repair_plan"), authRequest.get("risk_level"));
                log.info("[onAuthorization] User approved (demo auto-approve)");
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public CompletableFuture<Void> onNotification(
                    String agentName, Map<String, Object> notification) {
                notificationCalled.set(true);
                log.info("[onNotification] {} reports: {}", agentName, notification.get("message"));
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
