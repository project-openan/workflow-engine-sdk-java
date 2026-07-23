package com.openan.a2at.engine.examples.agents;

import com.openan.a2at.engine.client.WorkflowEngineClient;
import com.openan.a2at.engine.control.ControlPoint;
import com.openan.a2at.engine.control.DefaultControlPoint;
import com.openan.a2at.engine.model.JumpCondition;
import com.openan.a2at.engine.model.RouteDecision;
import com.openan.a2at.engine.model.TaskRequest;
import com.openan.a2at.engine.model.TaskResponse;
import com.openan.a2at.engine.examples.StartAgentsServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * ControlPoint for the SPN cross-city diagnosis workflow.
 *
 * <p>Handles task dispatch (with city-specific message enrichment),
 * route decisions (fault-based routing to recovery steps), and negotiation
 * responses. Authorization-T and Notification-T are pre-positioned
 * before the workflow starts, not handled here.
 *
 * <p>SRP: this class only contains workflow decision logic, separating
 * it from the agent executor that handles message I/O.
 */
public class WorkbenchControlPoint extends DefaultControlPoint {
    private static final Logger log = LoggerFactory.getLogger(WorkbenchControlPoint.class);


    private final String a2atEnvPath;

    public WorkbenchControlPoint() {
        this(null);
    }

    public WorkbenchControlPoint(String a2atEnvPath) {
        this.a2atEnvPath = a2atEnvPath != null ? a2atEnvPath : StartAgentsServer.resolveEnvPath();
    }


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
                    + "执行抢通方案，完成后返回抢通结果。授权和通知订阅已预置。";
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
        String reason = LlmHelper.text(a2atEnvPath,
                "你是SPN跨城故障定位分析专家。用一句话说明故障所在城市和下一步动作。中文。",
                "上海诊断：" + city1 + "\n广州诊断：" + city2 + "\n已选定下一步：" + nextStep + "。请给一句话理由。",
                "fault analysis");
        log.info("[onRoute] {} -> {} ({})", stepName, nextStep, reason);
        return CompletableFuture.completedFuture(
                RouteDecision.builder().nextStep(nextStep).reason(reason).build());
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
        String fallback = "根据工作台上下文，客户A上海-广州间SPN专线中断，"
                + "上海OMC告警端口Down，光功率-28dBm。";
        String sys = "你是SPN跨城专线抢通工作台的协商澄清专家。根据协商请求，补充客户A上海-广州间SPN专线中断的上下文（上海OMC告警端口Down、光功率-28dBm）。中文。";
        String clarification = LlmHelper.text(a2atEnvPath, sys, negotiationText, fallback);
        return CompletableFuture.completedFuture(clarification);
    }

}
