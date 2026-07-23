package com.openan.a2at.engine.examples;

import com.openan.a2at.engine.client.DefaultWorkflowEngineClient;
import com.openan.a2at.engine.client.WorkflowEngineClientConfig;
import com.openan.a2at.engine.client.AgentCardMapper;
import org.a2aproject.sdk.spec.AgentCard;
import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.control.EventType;
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
//        java.security.Security.insertProviderAt(new com.sun.crypto.provider.SunJCE(), 1);
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
       if (response != null) {
            log.info("Response ({} chars):\n{}", response.length(), response);
        } else {
            log.warn("Response was null");
        }

        // Step 3: Shutdown
        log.info("=== Demo complete, shutting down ===");
        agentsServer.stop();
        // Force exit: JDK HttpClient and SDK internal thread pools may leave
        // non-daemon threads that prevent the JVM from exiting.
        System.exit(0);
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
        Map<String, Object> cardMap = mapper.readValue(new java.io.File(cardPath), Map.class);
        AgentCard agentCard = AgentCardMapper.toSdkAgentCard(cardMap);

        // Create engine client with only the Workbench Agent's card
        DefaultWorkflowEngineClient engineClient = new DefaultWorkflowEngineClient(
                List.of(agentCard), null,
                WorkflowEngineClientConfig.builder()
                        .sslVerify(false)
                        .a2atEnvPath(StartAgentsServer.resolveEnvPath())
                        .build());

        // Set up EventCallback to receive intermediate state data in real time
        engineClient.setEventCallback(new EventCallback() {
            @Override
            public void onEvent(String type, Map<String, Object> data) {
                switch (type) {
                    case EventType.AGENT_STATUS_UPDATE ->
                        log.info("  >> [STATUS] agent={}, state={}, final={}",
                                data.get("agent"), data.get("state"), data.get("is_final"));
                    case EventType.AGENT_ARTIFACT_UPDATE ->
                        log.info("  >> [ARTIFACT] agent={}, name={}, text={}",
                                data.get("agent"), data.get("artifact_name"),
                                data.get("text"));
                    case EventType.AGENT_MESSAGE_EVENT ->
                        log.info("  >> [MESSAGE] agent={}, text={}",
                                data.get("agent"), data.get("text"));
                    case EventType.AGENT_REQUEST ->
                        log.info("  >> [REQUEST] agent={}, {} chars",
                                data.get("agent"),
                                data.get("request") != null ? String.valueOf(data.get("request")).length() : 0);
                    case EventType.AGENT_RESPONSE ->
                        log.info("  >> [RESPONSE] agent={}, response={}",
                                data.get("agent"),
                                data.get("response") != null ? data.get("response") : "(empty)");
                    default -> { /* other event types not shown in this demo */ }
                }
            }
        });

        // sendMessage handles A2A-T protocol internally
        SendMessageResult result = engineClient.sendMessage(WB_AGENT_NAME, taskText).join();
        log.info("[SelfTrigger] Task state: {}", result.getTaskState());
        engineClient.close();
        return result.getText();
    }

}
