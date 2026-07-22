package com.openan.a2at.engine.examples;

import com.openan.a2at.engine.client.DefaultWorkflowEngineClient;
import com.openan.a2at.engine.client.WorkflowEngineClientConfig;
import com.openan.a2at.engine.model.SendMessageResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * SPN cross-city fault diagnosis demo entry point.
 *
 * <p>Starts all 3 A2A agents (via {@link StartAgentsServer}) in a background
 * thread, waits for them to bind, then uses {@link DefaultWorkflowEngineClient}
 * to send a Task-T message to the Workbench Agent. The client internally
 * handles A2A-T protocol: Task-T prompt generation, SSE streaming response
 * parsing, negotiation auto-loop, etc.
 *
 * <p>Architecture:
 * <ul>
 *   <li>Transport Workbench Agent (port 26337) - orchestrator + merge</li>
 *   <li>SPN Domain Agent (port 26335) - Shanghai OMC, has fault</li>
 *   <li>SPN Domain Agent City2 (port 26336) - Guangzhou OMC, normal</li>
 * </ul>
 */
public class SpnCrossCityDiagnosisDemo {
    private static final Logger log = LoggerFactory.getLogger(SpnCrossCityDiagnosisDemo.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String AGENT_CARD_RESOURCE = "agentcard/transport_workbench_agent.json";
    private static final String WB_AGENT_NAME = "Transport Workbench Agent";
    private static final long AGENT_STARTUP_WAIT_SECONDS = 3;

    public static void main(String[] args) throws Exception {
        log.info("=== SPN Cross-City Fault Diagnosis Demo ===");

        // Step 1: Start all agents in background
        log.info("=== Step 1: Start all A2A agents ===");
        StartAgentsServer agentsServer = new StartAgentsServer();
        Thread agentThread = new Thread(agentsServer, "agents-server");
        agentThread.setDaemon(true);
        agentThread.start();

        log.info("Waiting for agents to start...");
        TimeUnit.SECONDS.sleep(AGENT_STARTUP_WAIT_SECONDS);

        // Step 2: Send Task-T to Workbench Agent via WorkflowEngineClient
        log.info("=== Step 2: Send Task-T to Workbench Agent ===");
        String taskText = "SPN跨城专线故障诊断与抢通："
                + "客户A上海-广州间SPN专线中断，"
                + "请协同两地市OMC并行诊断，"
                + "汇总分析确定故障在哪个地市，"
                + "授权抢通，OMC上报抢通结果";
        log.info("Sending task: {}", taskText);

        String response = sendTaskToWorkbench(taskText);
        log.info("=== Workbench Agent Response ===");
        log.info("Response: {} chars", response != null ? response.length() : 0);
        if (response != null) {
            log.info("Response preview: {}",
                    response.length() > 200 ? response.substring(0, 200) + "..." : response);
        }

        // Step 3: Shutdown
        log.info("=== Demo complete, shutting down ===");
        agentsServer.stop();
    }

    /**
     * Send a Task-T message to the Workbench Agent via the workflow engine client.
     *
     * <p>Uses {@link DefaultWorkflowEngineClient} which internally handles:
     * A2A REST message:stream, SSE response parsing (statusUpdate/artifactUpdate),
     * Task-T prompt generation, and negotiation auto-loop.
     */
    @SuppressWarnings("unchecked")
    private static String sendTaskToWorkbench(String taskText) throws Exception {
        // Load the Workbench Agent's AgentCard from classpath
        String cardPath = SpnCrossCityDiagnosisDemo.class.getClassLoader()
                .getResource(AGENT_CARD_RESOURCE).getPath();
        Map<String, Object> agentCard = mapper.readValue(new java.io.File(cardPath), Map.class);

        // Create engine client with only the Workbench Agent's card
        DefaultWorkflowEngineClient engineClient = new DefaultWorkflowEngineClient(
                List.of(agentCard), null,
                WorkflowEngineClientConfig.builder()
                        .sslVerify(false)
                        .build());

        // sendMessage handles A2A-T protocol internally
        SendMessageResult result = engineClient.sendMessage(WB_AGENT_NAME, taskText).join();
        log.info("[SelfTrigger] Task state: {}", result.getTaskState());
        engineClient.close();
        return result.getText();
    }
}
