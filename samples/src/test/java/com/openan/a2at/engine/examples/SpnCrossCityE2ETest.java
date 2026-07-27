package com.openan.a2at.engine.examples;

import com.openan.a2at.engine.client.AgentCardJacksonModule;
import com.openan.a2at.engine.client.DefaultWorkflowEngineClient;
import com.openan.a2at.engine.client.WorkflowEngineClient;
import com.openan.a2at.engine.client.WorkflowEngineClientConfig;
import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.control.EventType;
import com.openan.a2at.engine.examples.agents.SpnDomainAgentCity2Executor;
import com.openan.a2at.engine.examples.agents.SpnDomainAgentCity1Executor;
import com.openan.a2at.engine.examples.agents.WorkbenchControlPoint;
import com.openan.a2at.engine.examples.server.EmbeddedA2AServer;
import com.openan.a2at.engine.model.ExecutionResult;
import com.openan.a2at.engine.model.JumpCondition;
import com.openan.a2at.engine.model.StepType;
import com.openan.a2at.engine.model.Task;
import com.openan.a2at.engine.model.Workflow;
import com.openan.a2at.engine.model.WorkflowStep;
import com.openan.a2at.engine.runner.ExecutePsop;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end business integration test for the SPN cross-city diagnosis
 * workflow. Starts two real SPN agents (EmbeddedA2AServer + A2A-T protocol
 * over HTTPS+SSE), pre-positions Authorization-T and Notification-T, runs a
 * 3-step workflow (diagnose x2 + SelfLoop merge), and asserts the full
 * business path: negotiation -> diagnosis -> whitelist self-recovery ->
 * Notification-T report -> local merge (no A2A-T to self).
 *
 * <p>LLM is disabled so agent outputs are deterministic fallback text.
 */
class SpnCrossCityE2ETest {

    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new AgentCardJacksonModule());

    private final List<EmbeddedA2AServer> servers = new ArrayList<>();
    private DefaultWorkflowEngineClient client;
    private int port1;
    private int port2;

    private static AgentCard cardFor(String name, int port) {
        Map<String, Object> card = Map.of(
                "name", name,
                "description", "test",
                "provider", Map.of("organization", "test", "url", ""),
                "version", "1.0.0",
                "capabilities", Map.of("streaming", true, "pushNotifications", false,
                        "extendedAgentCard", false, "extensions", List.of()),
                "defaultInputModes", List.of("text/plain"),
                "defaultOutputModes", List.of("text/plain"),
                "skills", List.of(Map.of("id", "test", "name", "test",
                        "description", "test", "tags", List.of())),
                "supportedInterfaces", List.of(Map.of(
                        "protocolBinding", "HTTP+JSON", "protocolVersion", "1.0",
                        "url", "https://127.0.0.1:" + port, "tenant", "")));
        return mapper.convertValue(card, AgentCard.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("a2at.llm.disabled", "true");
        port1 = 28200 + (int) (Math.random() * 500);
        port2 = port1 + 1;
        AgentCard c1 = cardFor("SPN Domain Agent City1", port1);
        AgentCard c2 = cardFor("SPN Domain Agent City2", port2);
        EmbeddedA2AServer s1 = new EmbeddedA2AServer("127.0.0.1", port1,
                mapper.convertValue(c1, Map.class), new SpnDomainAgentCity1Executor());
        EmbeddedA2AServer s2 = new EmbeddedA2AServer("127.0.0.1", port2,
                mapper.convertValue(c2, Map.class), new SpnDomainAgentCity2Executor());
        s1.start();
        s2.start();
        servers.add(s1);
        servers.add(s2);
        Thread.sleep(600);
        client = new DefaultWorkflowEngineClient(List.of(c1, c2), null,
                WorkflowEngineClientConfig.builder().sslVerify(false).build());
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
        servers.forEach(s -> {
            try { s.close(); } catch (Exception ignored) {}
        });
    }

    private Workflow crossCityWorkflow() {
        Task t1 = Task.builder().agent("SPN Domain Agent City1")
                .description("SPN专线故障诊断").build();
        Task t2 = Task.builder().agent("SPN Domain Agent City2")
                .description("SPN专线故障诊断").build();
        Task merge = Task.builder().agent("Workbench")
                .description("汇总两地市OMC诊断结论").build();
        WorkflowStep s1 = WorkflowStep.builder().name("diagnosis_city1").layer(0)
                .subtasks(List.of(t1))
                .next(List.of(JumpCondition.builder().step("merge_analysis").condition("").build()))
                .build();
        WorkflowStep s2 = WorkflowStep.builder().name("diagnosis_city2").layer(0)
                .subtasks(List.of(t2))
                .next(List.of(JumpCondition.builder().step("merge_analysis").condition("").build()))
                .build();
        WorkflowStep s3 = WorkflowStep.builder().name("merge_analysis").layer(1)
                .stepType(StepType.SELF_LOOP)
                .contextFrom(List.of("diagnosis_city1", "diagnosis_city2"))
                .subtasks(List.of(merge))
                .next(List.of(JumpCondition.builder().step("endNode").condition("").build()))
                .build();
        return Workflow.builder().name("spn-e2e")
                .steps(List.of(s1, s2, s3)).build();
    }

    @Test
    void fullBusinessPathNegotiationDiagnosisRecoveryAndSelfLoopMerge() {
        // Pre-position Authorization-T + Notification-T to both SPN agents
        client.sendAuthorization("SPN Domain Agent City1", "下发授权放行策略",
                "任务类型新增授权，操作名称业务抢通").join();
        client.sendNotification("SPN Domain Agent City1", "订阅业务抢通结果通知",
                "通知主题为service-recovery-execution-result").join();
        client.sendAuthorization("SPN Domain Agent City2", "下发授权放行策略",
                "任务类型新增授权，操作名称业务抢通").join();
        client.sendNotification("SPN Domain Agent City2", "订阅业务抢通结果通知",
                "通知主题为service-recovery-execution-result").join();

        Map<String, Object> allOutputs = new ConcurrentHashMap<>();
        AtomicBoolean sawRecovery = new AtomicBoolean(false);
        AtomicBoolean sawSelfLoop = new AtomicBoolean(false);
        EventCallback cb = new EventCallback() {
            @Override
            public void onEvent(String type, Map<String, Object> data) {
                if (EventType.AGENT_ARTIFACT_UPDATE.equals(type)) {
                    String text = data.get("text") != null ? String.valueOf(data.get("text")) : "";
                    if (text.contains("抢通") || text.contains("恢复") || text.contains("Notification")) {
                        sawRecovery.set(true);
                    }
                }
                if (EventType.TASK_RESPONSE.equals(type) && "Workbench".equals(data.get("agent"))) {
                    sawSelfLoop.set(true);
                }
                allOutputs.put(type + ":" + data.get("agent"), data);
            }
        };

        WorkbenchControlPoint cp = new WorkbenchControlPoint(null);
        ExecutionResult result = ExecutePsop.builder()
                .psop(crossCityWorkflow())
                .controlPoint(cp)
                .engineClient(client)
                .runtimeIntent("SPN跨城专线故障诊断与抢通：客户A上海-广州间SPN专线中断")
                .lang("zh")
                .sslVerify(false)
                .eventCallback(cb)
                .execute()
                .join();

        assertTrue(result.isSuccess(), "Workflow must succeed: " + result.getError());
        assertFalse(result.getHistory().isEmpty());
        // Self-loop merge step executed locally (no A2A-T to self)
        assertTrue(sawSelfLoop.get(), "Self-loop merge must run via onSelfTask");
        // SPN agents reported recovery via Notification-T channel
        assertTrue(sawRecovery.get(), "SPN must self-trigger recovery and report via Notification-T");
        // Merge output contains fault localization
        Map<String, Object> mergeOut = result.getStepOutputs().get("merge_analysis");
        assertNotNull(mergeOut, "merge_analysis output must exist");
        String mergeText = String.valueOf(mergeOut.values().iterator().next());
        assertTrue(mergeText.contains("粤东"), "Merge must locate fault in Yuedong: " + mergeText);
    }
}
