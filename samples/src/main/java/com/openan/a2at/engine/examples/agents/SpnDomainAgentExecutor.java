package com.openan.a2at.engine.examples.agents;

import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SPN Domain Agent for City1 (Shanghai OMC).
 *
 * <p>Simulates fault diagnosis: Shanghai side has a FAULT (port Down,
 * optical power -28dBm). For recovery tasks, reports recovery success
 * via Notification-T metadata.
 */
public class SpnDomainAgentExecutor extends BaseAgentExecutor {
    private static final Logger log = LoggerFactory.getLogger(SpnDomainAgentExecutor.class);

    private static final String FAULT_DIAGNOSIS_RESULT =
            "诊断结果：上海城市OMC诊断结果 - 端口Down，光功率-28dBm(低于阈值)，存在故障。\n"
            + "修复方案：更换上海OMC端口光模块，恢复端口Down状态。此修复方案需要人工授权后执行。\n"
            + "故障根因：上海OMC端口光模块故障。";

    private static final String RECOVERY_RESULT =
            "上海OMC端口光模块已更换，端口恢复Up，专线业务恢复正常。";

    @Override
    public void execute(RequestContext ctx, AgentEmitter emitter) throws A2AError {
        String taskId = ctx.getTaskId();
        String contextId = ctx.getContextId();
        String input = extractText(ctx.getMessage());
        log.info("[SPN-Domain-Agent] Received task: taskId={}, text={} chars", taskId, input.length());

        emitter.submit(buildStatusMessage(contextId, taskId, "Diagnosis task received"));
        emitter.startWork(buildStatusMessage(contextId, taskId, "Running SPN fault diagnosis"));

        boolean isRecovery = input.contains("## 抢通指令") || input.toLowerCase().contains("recovery");

        if (isRecovery) {
            // Intermediate progress: executing recovery plan
            emitter.updateStatus(TaskState.TASK_STATE_WORKING,
                    buildStatusMessage(contextId, taskId, "正在执行抢通操作：更换光模块..."));
            sleepBriefly();
            emitter.sendMessage(List.of(new TextPart("光模块更换进度：已拔出故障模块，正在插入新模块")));
            sleepBriefly();
            emitter.updateStatus(TaskState.TASK_STATE_WORKING,
                    buildStatusMessage(contextId, taskId, "光模块已更换，正在验证端口状态..."));
            sleepBriefly();
        } else {
            // Intermediate progress: step-by-step diagnosis
            emitter.updateStatus(TaskState.TASK_STATE_WORKING,
                    buildStatusMessage(contextId, taskId, "正在查询上海OMC端口状态..."));
            sleepBriefly();
            // Send intermediate artifact (partial diagnosis data)
            emitter.addArtifact(
                    List.of(new TextPart("端口状态：port-7 = DOWN\n光功率：-28dBm（阈值-20dBm）")),
                    "port-status", "端口状态查询", Map.of(), false, false);
            sleepBriefly();
            emitter.updateStatus(TaskState.TASK_STATE_WORKING,
                    buildStatusMessage(contextId, taskId, "检测到端口Down，正在分析光功率数据..."));
            sleepBriefly();
            emitter.sendMessage(List.of(new TextPart("诊断中间结果：光功率-28dBm严重低于阈值，疑似光模块故障")));
            sleepBriefly();
        }

        String responseText;
        Map<String, Object> metadata = new HashMap<>();

        if (isRecovery) {
            responseText = RECOVERY_RESULT;
            metadata.put("Notification-T", Map.of(
                    "topic", "recovery_result",
                    "status", "recovery_successful",
                    "message", RECOVERY_RESULT));
            log.info("[SPN-Domain-Agent] Recovery completed, injecting Notification-T");
        } else {
            responseText = FAULT_DIAGNOSIS_RESULT;
            metadata.put("Authorization-T", Map.of(
                    "needs_authorization", true,
                    "repair_plan", "更换上海OMC端口光模块，恢复端口Down状态",
                    "risk_level", "medium",
                    "affected_service", "客户A上海-广州间SPN专线"));
            log.info("[SPN-Domain-Agent] Diagnosis completed, injecting Authorization-T");
        }

        List<Part<?>> parts = List.of(new TextPart(responseText));
        emitter.addArtifact(parts, "diagnosis-result", "SPN diagnosis result", metadata, true, false);
        log.info("[SPN-Domain-Agent] Task completed: taskId={}", taskId);
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void cancel(RequestContext ctx, AgentEmitter emitter) throws A2AError {
        emitter.cancel();
    }
}
