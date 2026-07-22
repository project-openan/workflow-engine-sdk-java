package com.openan.a2at.engine.examples.agents;

import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SPN Domain Agent for City2 (Guangzhou OMC).
 *
 * <p>Simulates fault diagnosis: Guangzhou side is NORMAL (no fault).
 * For recovery tasks, reports recovery success via Notification-T.
 */
public class SpnDomainAgentCity2Executor extends BaseAgentExecutor {
    private static final Logger log = LoggerFactory.getLogger(SpnDomainAgentCity2Executor.class);

    private static final String NORMAL_DIAGNOSIS_RESULT =
            "诊断结果：广州城市OMC诊断结果 - 端口状态正常，光功率-17dBm(正常范围)，无异常告警。\n"
            + "修复建议：广州城市无需修复，故障不在此地市。\n"
            + "故障根因：无根因(此地市正常)。";

    private static final String RECOVERY_RESULT =
            "广州侧OMC抢通完成，业务恢复正常。";

    @Override
    public void execute(RequestContext ctx, AgentEmitter emitter) throws A2AError {
        String taskId = ctx.getTaskId();
        String contextId = ctx.getContextId();
        String input = extractText(ctx.getMessage());
        log.info("[SPN-Domain-Agent-City2] Received task: taskId={}, text={} chars", taskId, input.length());

        emitter.submit(buildStatusMessage(contextId, taskId, "Diagnosis task received"));
        emitter.startWork(buildStatusMessage(contextId, taskId, "Running SPN fault diagnosis"));

        boolean isRecovery = input.contains("## 抢通指令") || input.toLowerCase().contains("recovery");

        String responseText;
        Map<String, Object> metadata = new HashMap<>();

        if (isRecovery) {
            responseText = RECOVERY_RESULT;
            metadata.put("Notification-T", Map.of(
                    "topic", "recovery_result",
                    "status", "recovery_successful",
                    "message", RECOVERY_RESULT));
            log.info("[SPN-Domain-Agent-City2] Recovery completed, injecting Notification-T");
        } else {
            responseText = NORMAL_DIAGNOSIS_RESULT;
            log.info("[SPN-Domain-Agent-City2] Diagnosis completed (normal)");
        }

        List<Part<?>> parts = List.of(new TextPart(responseText));
        emitter.addArtifact(parts, "diagnosis-result", "SPN diagnosis result", metadata, true, false);
        log.info("[SPN-Domain-Agent-City2] Task completed: taskId={}", taskId);
    }

    @Override
    public void cancel(RequestContext ctx, AgentEmitter emitter) throws A2AError {
        emitter.cancel();
    }
}
