package com.openan.a2at.engine.examples.agents;

import com.openan.a2at.engine.examples.StartAgentsServer;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SPN Domain Agent for City2 (Guangzhou OMC).
 *
 * <p>Server-side negotiation-capable (extends {@link NegotiationBaseAgentExecutor}).
 * Guangzhou side is NORMAL. Recovery injects Notification-T. Diagnosis text is
 * LLM-generated when the A2A-T .env is configured, else deterministic.
 */
public class SpnDomainAgentCity2Executor extends NegotiationBaseAgentExecutor {
    private static final Logger log = LoggerFactory.getLogger(SpnDomainAgentCity2Executor.class);

    private static final String NORMAL_DIAGNOSIS_RESULT =
            "诊断结果：广州城市OMC诊断结果 - 端口状态正常，光功率-17dBm(正常范围)，无异常告警。\n"
            + "修复建议：广州城市无需修复，故障不在此地市。\n"
            + "故障根因：无根因(此地市正常)。";

    private static final String RECOVERY_RESULT =
            "广州侧OMC抢通完成，业务恢复正常。";

    @Override
    protected String resolveEnvPath() {
        return StartAgentsServer.resolveEnvPath();
    }

    @Override
    protected String executeBusiness(RequestContext ctx, AgentEmitter emitter, String input) {
        String taskId = ctx.getTaskId();
        String contextId = ctx.getContextId();
        boolean isRecovery = input.contains("## 抢通指令") || input.toLowerCase().contains("recovery");

        if (isRecovery) {
            emitter.updateStatus(TaskState.TASK_STATE_WORKING,
                    BaseAgentExecutor.buildStatusMessage(contextId, taskId, "广州侧正在执行抢通操作..."));
            sleepBriefly();
            emitter.updateStatus(TaskState.TASK_STATE_WORKING,
                    BaseAgentExecutor.buildStatusMessage(contextId, taskId, "广州侧抢通进度：正在排查端口状态"));
            sleepBriefly();
            return llmRecoveryResult(input, RECOVERY_RESULT);
        }
        emitter.updateStatus(TaskState.TASK_STATE_WORKING,
                BaseAgentExecutor.buildStatusMessage(contextId, taskId, "正在查询广州OMC端口状态..."));
        sleepBriefly();
        emitter.addArtifact(
                List.of(new TextPart("端口状态：port-3 = UP\n光功率：-17dBm（正常范围）")),
                "port-status", "端口状态查询", Map.of(), false, false);
        sleepBriefly();
        emitter.updateStatus(TaskState.TASK_STATE_WORKING,
                BaseAgentExecutor.buildStatusMessage(contextId, taskId, "广州侧诊断中间结果：端口正常，光功率正常，无异常告警"));
        sleepBriefly();
        return llmDiagnosisResult(input, NORMAL_DIAGNOSIS_RESULT);
    }

    @Override
    protected Map<String, Object> buildResponseMetadata(RequestContext ctx, String response) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        boolean isRecovery = response.contains("抢通") || response.contains("业务恢复")
                || response.contains("recovery");
        if (isRecovery) {
            metadata.put("Notification-T", Map.of(
                    "topic", "recovery_result",
                    "status", "recovery_successful",
                    "message", RECOVERY_RESULT));
            log.info("[SPN-Domain-Agent-City2] Injected Notification-T: recovery_successful");
        }
        return metadata;
    }

    @Override
    protected String defaultNegotiationText() {
        return "广州OMC诊断需要确认客户专线故障是否涉及广州侧端口，请补充。";
    }

    private static String llmDiagnosisResult(String input, String fallback) {
        String env = StartAgentsServer.resolveEnvPath();
        String sys = "你是SPN领域广州OMC故障诊断专家。根据输入诊断信息，广州侧端口正常、光功率-17dBm(正常范围)、无异常告警。输出诊断结果：广州无需修复、故障不在此地市。简洁专业，中文。";
        String user = "输入：\n" + input + "\n\n广州OMC端口port-3=UP，光功率-17dBm，无告警。请输出诊断结论。";
        return LlmHelper.text(env, sys, user, fallback);
    }

    private static String llmRecoveryResult(String input, String fallback) {
        String env = StartAgentsServer.resolveEnvPath();
        String sys = "你是SPN领域广州OMC抢通执行专家。用一句话报告广州侧抢通完成、业务恢复。中文。";
        String user = "输入：\n" + input + "\n\n广州侧无需更换光模块，业务正常。请输出抢通结果。";
        return LlmHelper.text(env, sys, user, fallback);
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
