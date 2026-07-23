package com.openan.a2at.engine.examples.agents;

import com.openan.a2at.engine.examples.StartAgentsServer;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * SPN Domain Agent for City1 (Shanghai OMC).
 *
 * <p>Server-side negotiation-capable (extends {@link NegotiationBaseAgentExecutor}):
 * on a new task it replies INPUT_REQUIRED to start a Negotiation-T round, and on
 * the follow-up it runs the diagnosis/recovery business. Shanghai side has a
 * FAULT (port Down, optical power -28dBm). Diagnosis/recovery text is
 * LLM-generated (deepseek) when the A2A-T .env is configured, else deterministic.
 * Authorization-T and Notification-T are pre-positioned before the workflow
 * starts, so this agent no longer injects them in response metadata.
 */
public class SpnDomainAgentExecutor extends NegotiationBaseAgentExecutor {
    private static final Logger log = LoggerFactory.getLogger(SpnDomainAgentExecutor.class);

    private static final String FAULT_DIAGNOSIS_RESULT =
            "诊断结果：上海城市OMC诊断结果 - 端口Down，光功率-28dBm(低于阈值)，存在故障。\n"
            + "修复方案：更换上海OMC端口光模块，恢复端口Down状态。此修复方案需授权后执行（授权已预置）。\n"
            + "故障根因：上海OMC端口光模块故障。";

    private static final String RECOVERY_RESULT =
            "上海OMC端口光模块已更换，端口恢复Up，专线业务恢复正常。";

    @Override
    protected String resolveEnvPath() {
        return StartAgentsServer.resolveEnvPath();
    }

    @Override
    protected String executeBusiness(RequestContext ctx, AgentEmitter emitter, String input) {
        String taskId = ctx.getTaskId();
        String contextId = ctx.getContextId();
        // Diagnosis branch
        emitter.updateStatus(TaskState.TASK_STATE_WORKING,
                BaseAgentExecutor.buildStatusMessage(contextId, taskId, "正在查询上海OMC端口状态..."));
        sleepBriefly();
        emitter.addArtifact(
                List.of(new TextPart("端口状态：port-7 = DOWN\n光功率：-28dBm（阈值-20dBm）")),
                "port-status", "端口状态查询", Map.of(), false, false);
        sleepBriefly();
        emitter.updateStatus(TaskState.TASK_STATE_WORKING,
                BaseAgentExecutor.buildStatusMessage(contextId, taskId, "检测到端口Down，正在分析光功率数据..."));
        sleepBriefly();
        emitter.updateStatus(TaskState.TASK_STATE_WORKING,
                BaseAgentExecutor.buildStatusMessage(contextId, taskId, "诊断中间结果：光功率-28dBm严重低于阈值，疑似光模块故障"));
        sleepBriefly();
        String diagnosisResult = llmDiagnosisResult(input, FAULT_DIAGNOSIS_RESULT);
        // After diagnosis, self-trigger recovery based on pre-positioned
        // Authorization-T whitelist policy. If the repair action matches the
        // whitelist, execute recovery and report result via Notification-T.
        // If not in whitelist, report refusal via Notification-T.
        selfTriggerRecovery(ctx, emitter, diagnosisResult);
        return diagnosisResult;
    }

    /**
     * After diagnosis, check the pre-positioned Authorization-T whitelist policy.
     * If the repair action (“光模块更换” / optical module replacement) is in the
     * whitelist, self-execute recovery and report the result via the
     * Notification-T channel (artifact metadata). If not in whitelist, report
     * refusal via the same channel.
     */
    private void selfTriggerRecovery(RequestContext ctx, AgentEmitter emitter, String diagnosisResult) {
        String taskId = ctx.getTaskId();
        String contextId = ctx.getContextId();
        String notifUri = "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Notification-T/v1";
        String policy = getAuthorizationPolicy();
        boolean inWhitelist = policy != null && !policy.isEmpty()
                && (policy.contains("业务抦通") || policy.contains("光模块") || policy.contains("授权"));
        if (inWhitelist) {
            log.info("[SPN-Domain-Agent] Fault in whitelist, self-triggering recovery");
            emitter.updateStatus(TaskState.TASK_STATE_WORKING,
                    BaseAgentExecutor.buildStatusMessage(contextId, taskId, "故障在白名单内，自主执行抦通：更换光模块..."));
            sleepBriefly();
            emitter.updateStatus(TaskState.TASK_STATE_WORKING,
                    BaseAgentExecutor.buildStatusMessage(contextId, taskId, "光模块已更换，端口恢复Up"));
            sleepBriefly();
            String recoveryResult = llmRecoveryResult(diagnosisResult, RECOVERY_RESULT);
            emitter.addArtifact(
                    List.of(new TextPart(recoveryResult)),
                    "recovery-result", "抦通结果",
                    Map.of(notifUri, recoveryResult), false, true);
            log.info("[SPN-Domain-Agent] Recovery result reported via Notification-T: {}", recoveryResult);
        } else {
            log.info("[SPN-Domain-Agent] Fault not in whitelist, refusing recovery");
            String refusalResult = "操作不在白名单内，拒绝执行抦通。";
            emitter.addArtifact(
                    List.of(new TextPart(refusalResult)),
                    "recovery-result", "抦通拒绝",
                    Map.of(notifUri, refusalResult), false, true);
            log.info("[SPN-Domain-Agent] Refusal reported via Notification-T: {}", refusalResult);
        }
    }

    @Override
    protected String defaultNegotiationText() {
        return "上海OMC诊断需要确认客户专线故障的详细端口信息后再执行，请补充。";
    }

    private static String llmDiagnosisResult(String input, String fallback) {
        String env = StartAgentsServer.resolveEnvPath();
        String sys = "你是SPN领域上海OMC故障诊断专家。根据输入诊断信息，输出诊断结果、修复方案和故障根因，提及上海。简洁专业，中文。";
        String user = "输入：\n" + input + "\n\n已知：上海OMC端口port-7=DOWN，光功率-28dBm（阈值-20dBm）。请输出诊断结论。";
        return LlmHelper.text(env, sys, user, fallback);
    }

    private static String llmRecoveryResult(String input, String fallback) {
        String env = StartAgentsServer.resolveEnvPath();
        String sys = "你是SPN领域上海OMC抢通执行专家。用一句话报告抢通成功结果，提及上海OMC端口恢复Up、专线业务恢复。中文。";
        String user = "输入：\n" + input + "\n\n已更换光模块，端口恢复Up。请输出抢通结果。";
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
