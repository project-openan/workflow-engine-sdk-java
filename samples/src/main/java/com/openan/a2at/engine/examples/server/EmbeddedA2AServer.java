package com.openan.a2at.engine.examples.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.a2aproject.sdk.grpc.SendMessageRequest;
import org.a2aproject.sdk.grpc.StreamResponse;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.server.AgentCardCacheMetadata;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.events.InMemoryQueueManager;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.BasePushNotificationSender;
import org.a2aproject.sdk.server.tasks.InMemoryPushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.PushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.PushNotificationSender;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentExtension;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.transport.rest.handler.RestHandler;
import com.google.protobuf.util.JsonFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class EmbeddedA2AServer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(EmbeddedA2AServer.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int THREAD_COUNT = 8;

    private final HttpServer server;
    private final ExecutorService executorService;
    private final ExecutorService agentExecutorService;
    private final Map<String, Object> agentCardMap;
   private final String agentName;
    private final String pathPrefix;

    @SuppressWarnings("unchecked")
    public EmbeddedA2AServer(String host, int port,
                             Map<String, Object> agentCard,
                             AgentExecutor agentExecutor) throws IOException {
        this.agentCardMap = agentCard;
        this.agentName = String.valueOf(agentCard.getOrDefault("name", "unknown"));
        this.pathPrefix = extractPathPrefix(agentCard);
        AgentCard typedCard = toTypedAgentCard(agentCard);

        this.agentExecutorService = new ThreadPoolExecutor(
                THREAD_COUNT, THREAD_COUNT, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), r -> {
                    Thread t = new Thread(r, "agent-" + agentName + "-" + UUID.randomUUID().toString().substring(0, 8));
                    t.setDaemon(true);
                    return t;
                });

        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        MainEventBus mainEventBus = new MainEventBus();
        InMemoryQueueManager queueManager = new InMemoryQueueManager(taskStore, mainEventBus);
        PushNotificationConfigStore pushStore = new InMemoryPushNotificationConfigStore();
        PushNotificationSender pushSender = new BasePushNotificationSender(pushStore);
        MainEventBusProcessor eventBusProc = new MainEventBusProcessor(mainEventBus, taskStore, pushSender, queueManager);
        startEventBus(eventBusProc);

        RequestHandler requestHandler = DefaultRequestHandler.create(
                agentExecutor, taskStore, queueManager, pushStore,
                eventBusProc, agentExecutorService, agentExecutorService);

        RestHandler restHandler = new RestHandler(
                typedCard, new AgentCardCacheMetadata(typedCard, null), requestHandler, Runnable::run);

        this.executorService = new ThreadPoolExecutor(
                THREAD_COUNT, THREAD_COUNT, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
       this.server.setExecutor(executorService);
        this.server.createContext(pathPrefix.isEmpty() ? "/" : pathPrefix,
                exchange -> handleExchange(exchange, restHandler));

        if (hasSecuritySchemes(agentCard)) {
            this.server.createContext("/rest/plat/smapp/v1/oauth/token", this::handleLogin);
            log.info("[{}] Auth login endpoint enabled", agentName);
        }
        log.info("[{}] A2A server started on http://{}:{}/", agentName, host, port);
    }

    public void start() { server.start(); }

    @Override
    public void close() {
        server.stop(0);
        executorService.shutdown();
        agentExecutorService.shutdown();
        log.info("[{}] A2A server stopped", agentName);
    }

    public Map<String, Object> getAgentCard() { return agentCardMap; }
    public String getAgentName() { return agentName; }

   private void handleExchange(HttpExchange exchange, RestHandler restHandler) throws IOException {
        String fullPath = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String path = pathPrefix.isEmpty() || !fullPath.startsWith(pathPrefix)
                ? fullPath : fullPath.substring(pathPrefix.length());
        if (path.isEmpty()) path = "/";
        try {
            if ("GET".equalsIgnoreCase(method) && "/".equals(path)) {
                sendJson(exchange, 200, restHandler.getAgentCard().getBody());
                return;
            }
            if ("POST".equalsIgnoreCase(method) && "/message:send".equals(path)) {
                var resp = restHandler.sendMessage(buildCallContext(exchange), "", readBody(exchange));
                sendJson(exchange, resp.getStatusCode(), resp.getBody());
                return;
            }
            if ("POST".equalsIgnoreCase(method) && "/message:stream".equals(path)) {
                handleStream(exchange, restHandler, readBody(exchange));
                return;
            }
            exchange.sendResponseHeaders(404, -1);
        } catch (Exception e) {
            log.error("[{}] Handler error: {}", agentName, e.getMessage(), e);
            sendJson(exchange, 500, Map.of("error", Map.of("code", 500, "message", e.getMessage())));
        } finally {
            exchange.close();
        }
    }

    @SuppressWarnings("unchecked")
    private void handleLogin(HttpExchange exchange) throws IOException {
        if (!"PUT".equalsIgnoreCase(exchange.getRequestMethod())
                && !"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        String body = readBody(exchange);
        String userName = "";
        String password = "";
        try {
            Map<String, Object> data = mapper.readValue(body, Map.class);
            userName = String.valueOf(data.getOrDefault("userName", data.getOrDefault("username", "")));
            password = String.valueOf(data.getOrDefault("value", data.getOrDefault("password", "")));
        } catch (Exception e) {
            sendJson(exchange, 400, Map.of("error", "Invalid request body"));
            return;
        }
        if ("admin".equals(userName) && "Admin@123".equals(password)) {
            log.info("[{}] Login succeeded, token issued", agentName);
            sendJson(exchange, 200, Map.of("accessSession", UUID.randomUUID().toString()));
        } else {
            log.warn("[{}] Login failed: bad credentials", agentName);
            sendJson(exchange, 401, Map.of("error", "Invalid credentials"));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleStream(HttpExchange exchange, RestHandler restHandler, String requestBody) throws IOException {
        try {
            RequestHandler inner = extractRequestHandler(restHandler);
            SendMessageRequest.Builder builder = SendMessageRequest.newBuilder();
            JsonFormat.parser().merge(requestBody, builder);
            var request = ProtoUtils.FromProto.messageSendParams(builder.build());
            inner.validateRequestedTask(request.message().taskId());
            Flow.Publisher<StreamingEventKind> publisher = inner.onMessageSendStream(request, buildCallContext(exchange));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            AtomicLong seq = new AtomicLong(0);
            CountDownLatch done = new CountDownLatch(1);
            OutputStream os = exchange.getResponseBody();
            publisher.subscribe(new Flow.Subscriber<>() {
                private Flow.Subscription sub;
                public void onSubscribe(Flow.Subscription s) { sub = s; s.request(1); }
                public void onNext(StreamingEventKind item) {
                    try {
                        String payload = JsonFormat.printer().print(toStreamResponse(item));
                        os.write(formatSse(seq.incrementAndGet(), payload).getBytes(StandardCharsets.UTF_8));
                        os.flush(); sub.request(1);
                    } catch (IOException e) { sub.cancel(); done.countDown(); }
                }
                public void onError(Throwable t) { done.countDown(); }
                public void onComplete() { done.countDown(); }
            });
            try { done.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { os.close(); }
        } catch (A2AError e) {
            sendJson(exchange, 500, Map.of("error", e.getMessage()));
        }
    }

    private static String formatSse(long seq, String payload) {
        String compact = payload == null ? "" : payload.replace("\r\n", "\n").replace('\r', '\n')
                .lines().map(String::trim).reduce("", String::concat);
        return String.format(Locale.ROOT, "id:%d%n", seq) + "data:" + compact + "\n\n";
    }

    private static StreamResponse toStreamResponse(StreamingEventKind event) {
        StreamResponse.Builder b = StreamResponse.newBuilder();
        if (event instanceof Message m) { b.setMessage(ProtoUtils.ToProto.message(m)); return b.build(); }
        if (event instanceof Task t) { b.setTask(ProtoUtils.ToProto.task(t)); return b.build(); }
        if (event instanceof TaskStatusUpdateEvent e) { b.setStatusUpdate(ProtoUtils.ToProto.taskStatusUpdateEvent(e)); return b.build(); }
        if (event instanceof TaskArtifactUpdateEvent e) { b.setArtifactUpdate(ProtoUtils.ToProto.taskArtifactUpdateEvent(e)); return b.build(); }
        throw new IllegalArgumentException("Unsupported event: " + event);
    }

    private static RequestHandler extractRequestHandler(RestHandler restHandler) {
        try {
            var f = RestHandler.class.getDeclaredField("requestHandler");
            f.setAccessible(true);
            return (RequestHandler) f.get(restHandler);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to access request handler", e);
        }
    }

    private static ServerCallContext buildCallContext(HttpExchange exchange) {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
        String ext = firstHeader(headers, "A2A-Extensions", "X-A2A-Extensions");
        Set<String> exts = ext.isBlank() ? Set.of() : Set.of(ext.split(","));
        return new ServerCallContext(null, Map.of("headers", headers), exts,
                firstNullableHeader(headers, "A2A-Protocol-Version", "A2A-Version"));
    }

    private static String firstHeader(Map<String, String> h, String a, String b) {
        String v = firstNullableHeader(h, a, b);
        return v == null ? "" : v;
    }

    private static String firstNullableHeader(Map<String, String> h, String a, String b) {
        String v = h.get(a.toLowerCase(Locale.ROOT));
        return v != null ? v : h.get(b.toLowerCase(Locale.ROOT));
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void sendJson(HttpExchange exchange, int status, Object data) throws IOException {
        String json = data instanceof String ? (String) data : mapper.writeValueAsString(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    @SuppressWarnings("unchecked")
    private static AgentCard toTypedAgentCard(Map<String, Object> card) {
        Map<String, Object> provider = (Map<String, Object>) card.get("provider");
        Map<String, Object> caps = (Map<String, Object>) card.get("capabilities");
        List<Map<String, Object>> exts = (List<Map<String, Object>>) caps.getOrDefault("extensions", List.of());
        List<Map<String, Object>> skills = (List<Map<String, Object>>) card.getOrDefault("skills", List.of());
        List<Map<String, Object>> ifaces = (List<Map<String, Object>>) card.getOrDefault("supportedInterfaces", List.of());
        return new AgentCard(
                str(card.get("name")), str(card.get("description")),
                provider == null ? null : new AgentProvider(str(provider.get("organization")), str(provider.get("url"))),
                str(card.get("version")), null,
                new AgentCapabilities(
                        Boolean.TRUE.equals(caps.get("streaming")),
                        Boolean.TRUE.equals(caps.get("pushNotifications")),
                        Boolean.TRUE.equals(caps.get("extendedAgentCard")),
                        exts.stream().map(e -> new AgentExtension(str(e.get("description")), Map.of(), false, str(e.get("uri")))).toList()),
                strList(card.get("defaultInputModes")), strList(card.get("defaultOutputModes")),
                skills.stream().map(s -> new AgentSkill(str(s.get("id")), str(s.get("name")), str(s.get("description")),
                        strList(s.get("tags")), List.of(), List.of(), List.of(), List.of())).toList(),
                Map.of(), List.of(), null,
                ifaces.stream().map(i -> new AgentInterface(str(i.get("protocolBinding")), str(i.get("url")), "", str(i.get("protocolVersion")))).toList(),
                List.of());
    }

    @SuppressWarnings("unchecked")
    private static boolean hasSecuritySchemes(Map<String, Object> card) {
        return card.containsKey("securitySchemes") && card.get("securitySchemes") instanceof Map
                && !((Map<?, ?>) card.get("securitySchemes")).isEmpty()
                && card.containsKey("securityRequirements");
    }

    private static List<String> strList(Object v) {
        return v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }

    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }

    @SuppressWarnings("unchecked")
    private static String extractPathPrefix(Map<String, Object> agentCard) {
        List<Map<String, Object>> interfaces =
                (List<Map<String, Object>>) agentCard.getOrDefault("supportedInterfaces", List.of());
        for (Map<String, Object> iface : interfaces) {
            if ("HTTP+JSON".equalsIgnoreCase(String.valueOf(iface.get("protocolBinding")))) {
                String url = String.valueOf(iface.getOrDefault("url", ""));
                try {
                    String path = java.net.URI.create(url).getPath();
                    if (path != null && !path.isEmpty() && !"/".equals(path)) {
                        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
                    }
                } catch (Exception ignored) {}
                break;
            }
        }
        return "";
    }

    private static void startEventBus(MainEventBusProcessor proc) {
        try {
            var m = MainEventBusProcessor.class.getDeclaredMethod("start");
            m.setAccessible(true);
            m.invoke(proc);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to start event bus", e);
        }
    }
}
