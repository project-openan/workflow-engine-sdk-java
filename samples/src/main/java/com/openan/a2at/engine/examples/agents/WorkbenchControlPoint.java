package com.openan.a2at.engine.examples.agents;

import com.openan.a2at.engine.client.WorkflowEngineClient;
import com.openan.a2at.engine.control.ControlPoint;
import com.openan.a2at.engine.control.DefaultControlPoint;
import com.openan.a2at.engine.model.JumpCondition;
import com.openan.a2at.engine.model.RouteDecision;
import com.openan.a2at.engine.model.TaskRequest;
import com.openan.a2at.engine.model.TaskResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ControlPoint for the SPN cross-city diagnosis workflow.
 *
 * <p>Handles task dispatch (with city-specific message enrichment),
 * route decisions (fault-based routing to recovery steps), negotiation
 * responses, authorization approvals, and notification reception.
 *
 * <p>SRP: this class only contains workflow decision logic, separating
 * it from the agent executor that handles message I/O.
 */
public class WorkbenchControlPoint extends DefaultControlPoint {
    private static final Logger log = LoggerFactory.getLogger(WorkbenchControlPoint.class);

    private final AtomicBoolean authorizationCalled = new AtomicBoolean(false);
    private final AtomicBoolean notificationCalled = new AtomicBoolean(false);

    public boolean wasAuthorizationCalled() { return authorizationCalled.get(); }
    public boolean wasNotificationCalled() { return notificationCalled.get(); }

    @Override
    public CompletableFuture<TaskResponse> onTask(
            TaskRequest request, WorkflowEngineClient engineClient) {
        String step = request.getStepName();
        String agentName = request.getAgentName();
        String enrichedMessage = enrichMessageForStep(request.getMessage(), step);

        final String finalMessage = enrichedMessage;
        return engineClient.sendMessage(agentName, finalMessage)
                .thenApply(r -> {
                    boolean success = r.getText() != null && !r.getText().isEmpty();
                    log.info("[onTask] Response from {}: {} chars, success={}",
                            agentName, r.getText() != null ? r.getText().length() : 0, success);
                    return TaskResponse.builder().success(success).output(r.getText()).build();
                })
                .exceptionally(e -> {
                    log.error("[onTask] Failed for {}: {}", agentName, e.getMessage());
                    return TaskResponse.builder()
                            .success(false)
                            .error("Agent call failed: " + e.getMessage())
                            .build();
                });
    }

    private static String enrichMessageForStep(String message, String step) {
        return switch (step) {
            case "diagnosis_city1" -> message + "\n\n## 城市差异化参数\n"
                    + "客户A上海-广州间SPN专线中断，上海OMC告警端口Down，光功率-28dBm低于阈值。"
                    + "端口所属单板line-card-03，端口号port-7。";
            case "diagnosis_city2" -> message + "\n\n## 城市差异化参数\n"
                    + "客户A上海-广州间SPN专线中断，广州OMC侧需排查端口状态和光功率是否正常。";
            case "recovery_city1", "recovery_city2" -> message + "\n\n## 抢通指令\n"
                    + "向故障OMC下发抢通授权确认，执行抢通方案，完成后上报抢通成功结果。";
            default -> message;
        };
    }

    @Override
    public CompletableFuture<RouteDecision> onRoute(
            String stepName, Map<String, Object> results, List<JumpCondition> conditions) {
        if (!"merge_analysis".equals(stepName)) {
            return super.onRoute(stepName, results, conditions);
        }
        String city1 = String.valueOf(results.getOrDefault("diagnosis_city1", ""));
        String city2 = String.valueOf(results.getOrDefault("diagnosis_city2", ""));
        String nextStep = determineFaultRoute(city1, city2);
        log.info("[onRoute] {} -> {}", stepName, nextStep);
        return CompletableFuture.completedFuture(
                RouteDecision.builder().nextStep(nextStep).reason("fault analysis").build());
    }

    private static String determineFaultRoute(String city1Result, String city2Result) {
        boolean city1Fault = city1Result.contains("故障") || city1Result.contains("Down");
        boolean city2Fault = city2Result.contains("故障") || city2Result.contains("Down");
        if (!city1Fault && !city2Fault) return "endNode";
        return city2Fault && !city1Fault ? "recovery_city2" : "recovery_city1";
    }

    @Override
    public CompletableFuture<String> onNegotiation(
            String agentName, String negotiationText, Map<String, Object> receiveResult) {
        log.info("[onNegotiation] agent={}: {}", agentName, negotiationText);
        return CompletableFuture.completedFuture(
                "根据工作台上下文，客户A上海-广州间SPN专线中断，"
                + "上海OMC告警端口Down，光功率-28dBm。");
    }

    @Override
    public CompletableFuture<Boolean> onAuthorization(
            String agentName, Map<String, Object> authRequest) {
        authorizationCalled.set(true);
        log.info("[onAuthorization] agent={}, repair_plan={}, risk={}",
                agentName, authRequest.get("repair_plan"), authRequest.get("risk_level"));
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Void> onNotification(
            String agentName, Map<String, Object> notification) {
        notificationCalled.set(true);
        log.info("[onNotification] {} reports: {}", agentName, notification.get("message"));
        return CompletableFuture.completedFuture(null);
    }
}
