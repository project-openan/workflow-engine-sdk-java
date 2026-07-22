package com.openan.a2at.engine.examples;

import com.openan.a2at.engine.examples.agents.SpnDomainAgentCity2Executor;
import com.openan.a2at.engine.examples.agents.SpnDomainAgentExecutor;
import com.openan.a2at.engine.examples.agents.TransportWorkbenchAgentExecutor;
import com.openan.a2at.engine.examples.server.EmbeddedA2AServer;
import com.openan.a2at.engine.registry.RegistryClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Starts all sample A2A agents from agentcard JSON config files.
 *
 * <p>Each agent is an independent A2A server (client + server).
 * Agents register their AgentCards in the registry center on startup.
 *
 * <p>Implements {@link Runnable} so it can be started in a background
 * thread by the demo, or run standalone via {@link #main}.
 *
 * <p>Mirrors Python: samples/start_agents_server.py
 */
public class StartAgentsServer implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(StartAgentsServer.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    // Must be set before any HttpClient is created: the JDK caches this
    // property in a static final field at class-load time. If a2a-java SDK
    // or any other HttpClient is initialized first, setting it later has
    // no effect and self-signed cert hostname verification will fail.
    static {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
    }

    static final String REGISTRY_URL = "https://127.0.0.1:5000";
    static final String ORCH_URL = "https://127.0.0.1:5001";
    static final String CRED_FILE = "spn_agent_credentials.json";
    static final String A2AT_ENV_FILE = ".env";

    private final List<EmbeddedA2AServer> servers = new ArrayList<>();
    private volatile boolean running = true;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    @Override
    public void run() {
        try {
            start();
        } catch (Exception e) {
            log.error("Failed to start agents: {}", e.getMessage(), e);
        }
    }

    public void start() throws Exception {
        boolean sslVerify = false;
        String credPath = getClass().getClassLoader().getResource(CRED_FILE).getPath();
        String envPath = resolveEnvPath();
        log.info("A2AT env file: {}", envPath != null ? envPath : "(not found, A2A-T extensions disabled)");

        List<AgentEntry> agents = List.of(
                loadAgent("agentcard/spn_domain_agent.json", new SpnDomainAgentExecutor()),
                loadAgent("agentcard/spn_domain_agent_city2.json", new SpnDomainAgentCity2Executor()),
                loadAgent("agentcard/transport_workbench_agent.json",
                        new TransportWorkbenchAgentExecutor(REGISTRY_URL, ORCH_URL, credPath, sslVerify, envPath))
        );

        for (AgentEntry entry : agents) {
            try {
                EmbeddedA2AServer server = new EmbeddedA2AServer(
                        entry.host, entry.port, entry.card, entry.executor);
                server.start();
                servers.add(server);
                registerAgent(entry.card, sslVerify);
                log.info("Started agent: {} on http://{}:{}/", entry.card.get("name"), entry.host, entry.port);
            } catch (Exception e) {
                log.error("Failed to start agent {}: {}", entry.card.get("name"), e.getMessage(), e);
            }
        }

        log.info("=== All agents started. Press Ctrl+C to stop. ===");
        shutdownLatch.await();
    }

    public void stop() {
        running = false;
        log.info("Shutting down all agents...");
        servers.forEach(s -> {
            try { s.close(); } catch (Exception ignored) {}
        });
        shutdownLatch.countDown();
    }

    @SuppressWarnings("unchecked")
    private static AgentEntry loadAgent(String resourcePath, AgentExecutor executor) throws Exception {
        String path = StartAgentsServer.class.getClassLoader().getResource(resourcePath).getPath();
        Map<String, Object> card = mapper.readValue(new java.io.File(path), Map.class);
        List<Map<String, Object>> ifaces =
                (List<Map<String, Object>>) card.getOrDefault("supportedInterfaces", List.of());
        String url = ifaces.isEmpty() ? "http://127.0.0.1:0" : String.valueOf(ifaces.get(0).get("url"));
        URI uri = URI.create(url);
        String host = uri.getHost() != null ? uri.getHost() : "127.0.0.1";
        int port = uri.getPort() > 0 ? uri.getPort() : 0;
        return new AgentEntry(host, port, card, executor);
    }

    private static void registerAgent(Map<String, Object> agentCard, boolean sslVerify) {
        try {
            RegistryClient registry = new RegistryClient(REGISTRY_URL, sslVerify);
            registry.registerAgentCard(agentCard);
            log.info("Registered agent: {}", agentCard.get("name"));
        } catch (Exception e) {
            log.warn("Registration failed for {}: {}", agentCard.get("name"), e.getMessage());
        }
    }

    private record AgentEntry(String host, int port, Map<String, Object> card, AgentExecutor executor) {}

    /**
     * Resolve the A2AT {@code .env} file path. Searches classpath, then the
     * project root (two levels up from the samples target/classes dir), then
     * the current working directory. Returns null if not found.
     */
    public static String resolveEnvPath() {
        var url = StartAgentsServer.class.getClassLoader().getResource(A2AT_ENV_FILE);
        if (url != null && "file".equals(url.getProtocol())) {
            return new java.io.File(url.getPath()).getAbsolutePath();
        }
        java.nio.file.Path cwd = java.nio.file.Paths.get(System.getProperty("user.dir"));
        for (java.nio.file.Path dir = cwd; dir != null; dir = dir.getParent()) {
            java.io.File candidate = dir.resolve(A2AT_ENV_FILE).toFile();
            if (candidate.exists()) {
                return candidate.getAbsolutePath();
            }
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        StartAgentsServer server = new StartAgentsServer();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
    }
}
