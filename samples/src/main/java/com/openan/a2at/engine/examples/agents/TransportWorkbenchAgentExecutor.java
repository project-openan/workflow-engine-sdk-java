package com.openan.a2at.engine.examples.agents;

import com.openan.a2at.engine.control.ControlPoint;
import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.control.EventType;
import com.openan.a2at.engine.model.ExecutionResult;
import com.openan.a2at.engine.model.Workflow;
import com.openan.a2at.engine.model.WorkflowSearchResult;
import com.openan.a2at.engine.registry.LoadPsop;
import com.openan.a2at.engine.runner.ExecutePsop;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Transport Workbench Agent - the orchestrator.
 *
 * <p>This agent is both server (receives tasks) and client (dispatches
 * sub-tasks to OMC agents via the workflow engine). When it receives a
 * top-level task (e.g. "diagnose SPN fault"), it searches the orchestration
 * center for a matching PSOP workflow, loads it, and executes it.
 *
 * <p>Agent cards are loaded from classpath JSON config files, not from the
 * registry center — the registry may contain stale entries from the Python
 * version with different ports or URL prefixes.
 *
 * <p>SRP: orchestration logic lives here. Workflow decision logic is
 * delegated to {@link WorkbenchControlPoint}.
 */
public class TransportWorkbenchAgentExecutor extends BaseAgentExecutor {
    private static final Logger log = LoggerFactory.getLogger(TransportWorkbenchAgentExecutor.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String SUBTASK_MARKER = "## Current Task";
    private static final String MERGE_KEYWORD = "汇总";
    private static final String FALLBACK_PSOP_ID = "psop_spn_cross_city_diagnosis";
    private static final List<String> AGENT_CARD_RESOURCES = List.of(
            "agentcard/spn_domain_agent.json",
            "agentcard/spn_domain_agent_city2.json",
            "agentcard/transport_workbench_agent.json");

    private final String orchUrl;
    private final String credentialsPath;
    private final boolean sslVerify;

    public TransportWorkbenchAgentExecutor(String registryUrl, String orchUrl,
                                           String credentialsPath, boolean sslVerify) {
        this.orchUrl = orchUrl;
        this.credentialsPath = credentialsPath;
        this.sslVerify = sslVerify;
    }

    @Override
    public void execute(RequestContext ctx, AgentEmitter emitter) throws A2AError {
        String taskId = ctx.getTaskId();
        String contextId = ctx.getContextId();
        String input = extractText(ctx.getMessage());
        log.info("[Workbench-Agent] Received task: taskId={}, text={} chars", taskId, input.length());

        emitter.submit(buildStatusMessage(contextId, taskId, "Task received"));
        emitter.startWork(buildStatusMessage(contextId, taskId, "Processing"));

        try {
            String responseText = input.contains(SUBTASK_MARKER)
                    ? handleSubTask(input)
                    : handleTopLevelTask(input);

            List<Part<?>> parts = List.of(new TextPart(responseText));
            emitter.addArtifact(parts, "workflow-result", "Result", Map.of(), true, false);
            log.info("[Workbench-Agent] Task completed: taskId={}", taskId);
        } catch (Exception e) {
            log.error("[Workbench-Agent] Task failed: {}", e.getMessage(), e);
            emitter.fail(buildStatusMessage(contextId, taskId, "Failed: " + e.getMessage()));
        }
    }

    @Override
    public void cancel(RequestContext ctx, AgentEmitter emitter) throws A2AError {
        emitter.cancel();
    }

    // ------------------------------------------------------------------
    // Top-level task: search PSOP, load workflow, execute
    // ------------------------------------------------------------------

    private String handleTopLevelTask(String messageText) throws Exception {
        log.info("[Workbench-Agent] Top-level task, searching PSOP...");

        List<Map<String, Object>> agentCards = loadAgentCardsFromConfig();
        log.info("[Workbench-Agent] Loaded {} agent card(s) from config", agentCards.size());

        String psopId = searchPsop(messageText);
        Workflow workflow = LoadPsop.load(orchUrl, psopId, null, sslVerify);
        log.info("[Workbench-Agent] Workflow: {} ({} steps)", workflow.getName(), workflow.getSteps().size());

        WorkbenchControlPoint controlPoint = new WorkbenchControlPoint();
        ExecutionResult result = ExecutePsop.builder()
                .psop(workflow)
                .agentCards(agentCards)
                .controlPoint(controlPoint)
                .runtimeIntent(messageText)
                .lang("zh")
                .sslVerify(sslVerify)
                .credentialsConfigPath(credentialsPath)
                .eventCallback(createEventCallback())
                .onFinish((r, events) -> {
                    log.info("[onFinish] Success={}, Events={}", r.isSuccess(), events.size());
                    return CompletableFuture.completedFuture(null);
                })
                .execute()
                .join();

        return buildResultText(result, controlPoint);
    }

    /**
     * Load our own agent cards from classpath JSON config files.
     * These cards have the correct Java agent URLs, avoiding stale
     * entries from the registry center.
     */
    private static List<Map<String, Object>> loadAgentCardsFromConfig() {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (String res : AGENT_CARD_RESOURCES) {
            try {
                var url = TransportWorkbenchAgentExecutor.class.getClassLoader().getResource(res);
                if (url != null) {
                    Map<String, Object> card = mapper.readValue(new java.io.File(url.getPath()), Map.class);
                    byName.put(String.valueOf(card.get("name")), card);
                }
            } catch (Exception e) {
                log.warn("Failed to load agent card {}: {}", res, e.getMessage());
            }
        }
        return new ArrayList<>(byName.values());
    }

    private String searchPsop(String messageText) {
        try {
            List<WorkflowSearchResult> results = LoadPsop.search(orchUrl, messageText, 3, null, sslVerify);
            if (!results.isEmpty()) {
                String psopId = results.get(0).getWorkflowId();
                log.info("[Workbench-Agent] Found PSOP: {} (score={})", psopId, results.get(0).getScore());
                return psopId;
            }
        } catch (Exception e) {
            log.warn("[Workbench-Agent] PSOP search failed, using fallback: {}", e.getMessage());
        }
        return FALLBACK_PSOP_ID;
    }

    private static String buildResultText(ExecutionResult result, WorkbenchControlPoint controlPoint) {
        StringBuilder sb = new StringBuilder();
        sb.append("Workflow execution ").append(result.isSuccess() ? "succeeded" : "failed").append(".\n");
        if (result.getHistory() != null) {
            for (Map<String, Object> h : result.getHistory()) {
                sb.append("- Step: ").append(h.get("step"))
                        .append(", Agent: ").append(h.get("agent"))
                        .append(", Status: ").append(h.get("status")).append("\n");
            }
        }
        if (result.getError() != null) {
            sb.append("Error: ").append(result.getError());
        }
        sb.append("\nAuthorization triggered: ").append(controlPoint.wasAuthorizationCalled());
        sb.append(", Notification received: ").append(controlPoint.wasNotificationCalled());
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Sub-task: merge_analysis (Workbench Agent processes its own step)
    // ------------------------------------------------------------------

    private String handleSubTask(String messageText) {
        log.info("[Workbench-Agent] Sub-task received (from workflow executor)");
        if (!messageText.contains(MERGE_KEYWORD)) {
            return "Sub-task processed: " + messageText.substring(0, Math.min(100, messageText.length()));
        }
        return analyzeFaultLocation(messageText);
    }

    private static String analyzeFaultLocation(String messageText) {
        String mergeResult = "汇总分析完成。";
        boolean hasShanghaiFault = messageText.contains("上海")
                && (messageText.contains("故障") || messageText.contains("Down"));
        boolean hasGuangzhouFault = messageText.contains("广州")
                && (messageText.contains("故障") || messageText.contains("Down"));

        if (hasShanghaiFault) {
            mergeResult += "故障定位：上海地市OMC，端口Down，"
                    + "光功率-28dBm低于阈值。需更换光模块抢通。";
        } else if (hasGuangzhouFault) {
            mergeResult += "故障定位：广州地市OMC。需排查并抢通。";
        } else {
            mergeResult += "两地市均未见异常。";
        }
        log.info("[Workbench-Agent] Merge result: {}", mergeResult);
        return mergeResult;
    }

    // ------------------------------------------------------------------
    // Event callback
    // ------------------------------------------------------------------

    private EventCallback createEventCallback() {
        return new EventCallback() {
            @Override
            public void onEvent(String type, Map<String, Object> data) {
                switch (type) {
                    case EventType.START -> log.info("  [START] {}", data.get("workflow"));
                    case EventType.STEP_START -> log.info("  [STEP_START] {}", data.get("step"));
                    case EventType.TASK_REQUEST -> log.info("  [TASK_REQUEST] agent={}", data.get("agent"));
                    case EventType.TASK_RESPONSE -> log.info("  [TASK_RESPONSE] agent={}", data.get("agent"));
                    case EventType.STEP_COMPLETE -> log.info("  [STEP_COMPLETE] {}", data.get("step"));
                    case EventType.ROUTE_DECISION -> log.info("  [ROUTE] {} -> {}", data.get("step"), data.get("next"));
                    case EventType.COMPLETE -> log.info("  [COMPLETE]");
                    case EventType.ERROR -> log.error("  [ERROR] {}", data.get("error"));
                    case EventType.CLOSE -> log.info("  [CLOSE]");
                    default -> {}
                }
            }
        };
    }
}
