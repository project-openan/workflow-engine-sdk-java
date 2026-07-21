package com.openan.a2at.engine.examples;

import com.openan.a2at.engine.control.ControlPoint;
import com.openan.a2at.engine.control.DefaultControlPoint;
import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.control.EventType;
import com.openan.a2at.engine.model.*;
import com.openan.a2at.engine.runner.ExecutePsop;
import com.openan.a2at.engine.registry.LoadPsop;
import com.openan.a2at.engine.registry.RegistryClient;
import com.openan.a2at.engine.server.WorkbenchAgentServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Workbench Agent Demo: SPN cross-city fault diagnosis & recovery.
 *
 * <p>The Demo is now a real A2A Agent (server). It starts an embedded HTTP
 * server, registers itself in the registry, and waits for incoming Task-T
 * messages. When it receives a message (e.g. "diagnose SPN fault and recover"),
 * it loads the PSOP workflow from the orchestration center and executes it:
 * <ol>
 *   <li>diagnosis_city1 - dispatch to SPN Domain Agent (Shanghai OMC)</li>
 *   <li>diagnosis_city2 - dispatch to SPN Domain Agent City2 (Guangzhou OMC)</li>
 *   <li>merge_analysis - Workbench Agent itself merges results (local, no A2A call)</li>
 *   <li>recovery_city1/city2 - dispatch recovery to the fault city OMC</li>
 * </ol>
 *
 * <p>The Workbench Agent is both server (receives tasks) and client
 * (dispatches sub-tasks to OMC agents via the workflow engine).
 */
public class SpnCrossCityDiagnosisDemo {
    private static final Logger log = LoggerFactory.getLogger(SpnCrossCityDiagnosisDemo.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    static final String REGISTRY_URL = "https://127.0.0.1:5000";
    static final String ORCH_URL = "https://127.0.0.1:5001";
    static final String PSOP_ID = "psop_spn_cross_city_diagnosis";
    static final String CRED_FILE = "spn_agent_credentials.json";
    static final String WB_HOST = "127.0.0.1";
    static final int WB_PORT = 26337;
    static final String WORKBENCH_AGENT_NAME = "Transport Workbench Agent";

    // Shared state for verification
    static final AtomicBoolean authorizationCalled = new AtomicBoolean(false);
    static final AtomicBoolean notificationCalled = new AtomicBoolean(false);

    public static void main(String[] args) throws Exception {
        boolean sslVerify = false;

        // ========== 1. Start Workbench Agent Server ==========
        log.info("=== Step 1: Start Workbench Agent Server ===");
        Map<String, Object> agentCard = WorkbenchAgentServer.buildAgentCard(WB_HOST, WB_PORT);
        WorkbenchAgentServer server = new WorkbenchAgentServer(WB_HOST, WB_PORT, agentCard,
                SpnCrossCityDiagnosisDemo::handleIncomingTask);
        server.start();
        log.info("Workbench Agent listening on http://{}:{}/", WB_HOST, WB_PORT);

        // ========== 2. Register in Registry ==========
        log.info("=== Step 2: Register in Registry ===");
        try {
            RegistryClient registry = new RegistryClient(REGISTRY_URL, sslVerify);
            registry.registerAgentCard(agentCard);
            log.info("Registered Workbench Agent in registry");
        } catch (Exception e) {
            log.warn("Registry registration failed (continuing): {}", e.getMessage());
        }

        // ========== 3. Self-trigger: send Task-T to self ==========
        log.info("=== Step 3: Self-trigger (send Task-T to Workbench Agent) ===");
        String taskText = "SPN跨城专线故障诊断与抢通：客户A上海-广州间SPN专线中断，请协同两地市OMC并行诊断，汇总分析确定故障在哪个地市，授权抢通，OMC上报抢通结果";
        log.info("Sending task: {}", taskText);

        String response = sendTaskToSelf(taskText);
        log.info("=== Workbench Agent Response ===");
        log.info("Response: {} chars", response != null ? response.length() : 0);
        if (response != null) {
            log.info("Response preview: {}",
                    response.length() > 200 ? response.substring(0, 200) + "..." : response);
        }

        // ========== 4. Results ==========
        log.info("=== Results ===");
        log.info("Authorization was triggered: {}", authorizationCalled.get());
        log.info("Notification was received: {}", notificationCalled.get());

        if (authorizationCalled.get() && notificationCalled.get()) {
            log.info("=== DEMO PASSED: Full story cycle completed ===");
        } else {
            log.warn("=== DEMO PARTIAL: auth={}, notif={} ===",
                    authorizationCalled.get(), notificationCalled.get());
        }

        server.close();
    }

    // ==================== A2A Server Message Handler ====================

    /**
     * Called when the Workbench Agent receives a Task-T message.
     * Loads the PSOP workflow and executes it.
     */
    private static WorkbenchAgentServer.AgentResponse handleIncomingTask(
            String messageText, Map<String, Object> metadata) throws Exception {
        log.info("[WorkbenchHandler] Received task: {}", messageText);

        // Fetch AgentCards from registry
        RegistryClient registry = new RegistryClient(REGISTRY_URL, false);
        List<Map<String, Object>> agentCards = registry.fetchAgentCards();
        // Add our own card so the workflow engine can find "Transport Workbench Agent"
        agentCards = new ArrayList<>(agentCards);
        agentCards.add(WorkbenchAgentServer.buildAgentCard(WB_HOST, WB_PORT));
        log.info("[WorkbenchHandler] Got {} agent card(s)", agentCards.size());

        // Load PSOP workflow
        Workflow workflow = LoadPsop.load(ORCH_URL, PSOP_ID, null, false);
        log.info("[WorkbenchHandler] Workflow: {} ({} steps)", workflow.getName(), workflow.getSteps().size());

        // Create ControlPoint
        ControlPoint controlPoint = createControlPoint();

        // Event callback for logging
        EventCallback eventCallback = createEventCallback();

        // Execute workflow
        ExecutionResult result = ExecutePsop.builder()
                .psop(workflow)
                .agentCards(agentCards)
                .controlPoint(controlPoint)
                .runtimeIntent(messageText)
                .lang("zh")
                .sslVerify(false)
                .credentialsConfigPath(getCredentialsPath())
                .eventCallback(eventCallback)
                .onFinish((r, events) -> {
                    log.info("[onFinish] Success={}, Events={}", r.isSuccess(), events.size());
                    return CompletableFuture.completedFuture(null);
                })
                .execute()
                .join();

        // Build response text from execution history
        StringBuilder responseText = new StringBuilder();
        responseText.append("Workflow execution ").append(result.isSuccess() ? "succeeded" : "failed").append(".\n");
        if (result.getHistory() != null) {
            for (Map<String, Object> h : result.getHistory()) {
                responseText.append("- Step: ").append(h.get("step"))
                        .append(", Agent: ").append(h.get("agent"))
                        .append(", Status: ").append(h.get("status")).append("\n");
            }
        }
        if (result.getError() != null) {
            responseText.append("Error: ").append(result.getError());
        }

        return new WorkbenchAgentServer.AgentResponse(responseText.toString());
    }

    // ==================== ControlPoint ====================

    private static ControlPoint createControlPoint() {
        return new DefaultControlPoint() {
            @Override
            public CompletableFuture<TaskResponse> onTask(
                    TaskRequest request, com.openan.a2at.engine.client.WorkflowEngineClient engineClient) {
                String step = request.getStepName();
                String agentName = request.getAgentName();

                // Workbench Agent handles merge_analysis locally (no A2A call to itself)
                if (WORKBENCH_AGENT_NAME.equals(agentName)) {
                    log.info("[onTask] Local processing for Workbench Agent (step={})", step);
                    String context = request.getContext() != null ? request.getContext() : "";
                    // Merge diagnosis results: determine which city has the fault
                    String mergeResult = "汇总分析完成。";
                    if (context.contains("上海") || context.contains("Shanghai")) {
                        mergeResult += "故障定位：上海地市OMC，端口Down，光功率-28dBm低于阈值。需更换光模块抢通。";
                    } else if (context.contains("广州") || context.contains("Guangzhou")) {
                        mergeResult += "故障定位：广州地市OMC。需排查并抢通。";
                    } else {
                        mergeResult += "两地市均未见异常。";
                    }
                    log.info("[onTask] Merge result: {}", mergeResult);
                    return CompletableFuture.completedFuture(
                            TaskResponse.builder().success(true).output(mergeResult).build());
                }

                // OMC agents: inject city-specific params and send via A2A
                String enrichedMessage = request.getMessage();
                if ("diagnosis_city1".equals(step)) {
                    enrichedMessage += "\n\n## 城市差异化参数\n客户A上海-广州间SPN专线中断，上海OMC告警端口Down，光功率-28dBm低于阈值。"
                            + "端口所属单板line-card-03，端口号port-7，最近维护2026-07-15。请进行根因诊断。";
                    log.info("[onTask] Injected Shanghai-specific params for {}", step);
                } else if ("diagnosis_city2".equals(step)) {
                    enrichedMessage += "\n\n## 城市差异化参数\n客户A上海-广州间SPN专线中断，广州OMC侧需排查端口状态和光功率是否正常。";
                    log.info("[onTask] Injected Guangzhou-specific params for {}", step);
                } else if ("recovery_city1".equals(step) || "recovery_city2".equals(step)) {
                    enrichedMessage += "\n\n## 抢通指令\n向故障OMC下发抢通授权确认，执行抢通方案（更换光模块、恢复端口），完成后上报抢通成功结果。";
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
                    if (c2Text.contains("故障") && !c1Text.contains("故障")) {
                        nextStep = "recovery_city2";
                    }
                    if (!c1Text.contains("故障") && !c2Text.contains("故障")
                            && !c1Text.contains("Down") && !c2Text.contains("Down")) {
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
                        agentName, negotiationText != null ? negotiationText : "(empty)");
                return CompletableFuture.completedFuture(
                        "根据工作台上下文，客户A上海-广州间SPN专线中断，上海OMC告警端口Down，"
                        + "光功率-28dBm。端口所属单板line-card-03，端口号port-7，最近维护2026-07-15。");
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
    }

    // ==================== Event Callback ====================

    private static EventCallback createEventCallback() {
        return new EventCallback() {
            @Override
            public void onEvent(String type, Map<String, Object> data) {
                switch (type) {
                    case EventType.START -> log.info("  [START] {}", data.get("workflow"));
                    case EventType.STEP_START -> log.info("  [STEP_START] {}", data.get("step"));
                    case EventType.TASK_REQUEST -> log.info("  [TASK_REQUEST] agent={}", data.get("agent"));
                    case EventType.TASK_RESPONSE -> log.info("  [TASK_RESPONSE] agent={} output={} chars",
                            data.get("agent"), String.valueOf(data.get("output")).length());
                    case EventType.TASK_STATUS_CHANGED -> log.info("  [TASK_STATUS] {} -> {}",
                            data.get("agent"), data.get("status"));
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
    }

    // ==================== Utilities ====================

    private static String getCredentialsPath() {
        return SpnCrossCityDiagnosisDemo.class.getClassLoader()
                .getResource(CRED_FILE).getPath();
    }

    /**
     * Send a Task-T message to the Workbench Agent's own HTTP endpoint.
     * This simulates an external caller sending a task.
     */
    private static String sendTaskToSelf(String taskText) throws Exception {
        Map<String, Object> message = Map.of(
                "role", "user",
                "parts", List.of(Map.of("type", "text", "text", taskText)));
        Map<String, Object> params = Map.of("message", message);
        Map<String, Object> rpcRequest = Map.of(
                "jsonrpc", "2.0",
                "method", "message/send",
                "params", params,
                "id", UUID.randomUUID().toString());
        String json = mapper.writeValueAsString(rpcRequest);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(60))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + WB_HOST + ":" + WB_PORT + "/message:send"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        log.info("[SelfTrigger] Response status: {}", resp.statusCode());

        if (resp.statusCode() == 200) {
            Map<String, Object> rpcResponse = mapper.readValue(resp.body(), Map.class);
            Map<String, Object> result = (Map<String, Object>) rpcResponse.get("result");
            if (result != null) {
                List<Map<String, Object>> artifacts = (List<Map<String, Object>>) result.get("artifacts");
                if (artifacts != null && !artifacts.isEmpty()) {
                    List<Map<String, Object>> parts = (List<Map<String, Object>>) artifacts.get(0).get("parts");
                    if (parts != null && !parts.isEmpty()) {
                        return (String) parts.get(0).get("text");
                    }
                }
            }
        }
        return resp.body();
    }
}