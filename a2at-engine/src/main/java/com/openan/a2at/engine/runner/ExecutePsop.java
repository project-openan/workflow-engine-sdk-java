package com.openan.a2at.engine.runner;

import com.openan.a2at.engine.client.DefaultWorkflowEngineClient;
import com.openan.a2at.engine.client.WorkflowEngineClientConfig;
import com.openan.a2at.engine.client.WorkflowEngineClient;
import com.openan.a2at.engine.control.ControlPoint;
import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.control.EventType;
import com.openan.a2at.engine.core.WorkflowExecutor;
import com.openan.a2at.engine.model.ExecutionResult;
import com.openan.a2at.engine.model.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * High-level PSOP runner -- execute + emit events + persistence hook.
 * <p>
 * Java equivalent of Python's execute_psop(). Returns a CompletableFuture
 * that completes when the workflow ends. Events are emitted via EventCallback
 * during execution. The onFinish hook is called with (result, collectedEvents).
 */
public class ExecutePsop {
    private static final Logger log = LoggerFactory.getLogger(ExecutePsop.class);

    /**
     * Execute a PSOP workflow end-to-end.
     *
     * @param psop             workflow as Map or Workflow object
     * @param agentCards       list of AgentCard objects
     * @param controlPoint     user's decision callbacks
     * @param engineClient     optional pre-created client (null = auto-create)
     * @param runtimeIntent    original user intent
     * @param lang             language hint (zh/en)
     * @param a2aClientRuntime the A2A client runtime from a2a-java-sdk
     * @param eventCallback    optional event callback (null = no-op)
     * @param onFinish         optional persistence hook: (result, collectedEvents) -> CompletableFuture<Void> (async)
     * @param onEvent          optional event transformer: event -> event | null | List<event>
     * @return CompletableFuture<ExecutionResult>
     */
    public static CompletableFuture<ExecutionResult> execute(
            Object psop,
            List<?> agentCards,
            ControlPoint controlPoint,
            WorkflowEngineClient engineClient,
            String runtimeIntent,
            String lang,
            String a2atEnvPath,
            String credentialsConfigPath,
            boolean sslVerify,
            String caCertsPath,
            Object a2aClientRuntime,
            EventCallback eventCallback,
            BiFunction<ExecutionResult, List<Map<String, Object>>, CompletableFuture<Void>> onFinish,
            Function<Map<String, Object>, Object> onEvent) {

        Workflow workflow = psop instanceof Workflow ? (Workflow) psop : Workflow.fromMap((Map<String, Object>) psop);
        EventCallback emitter = eventCallback != null ? eventCallback : new EventCallback();
        List<Map<String, Object>> collected = Collections.synchronizedList(new ArrayList<>());

        // Wrap the callback to collect events + apply on_event transformer
        EventCallback collectingCallback = new EventCallback() {
            @Override
            public void onEvent(String eventType, Map<String, Object> data) {
                Map<String, Object> event = new HashMap<>();
                event.put("type", eventType);
                event.put("data", serialize(data));
                event.put("timestamp", System.currentTimeMillis() / 1000.0);
                // Apply on_event transformer (same semantics as Python):
                // null = skip, single event = replace, list = inject multiple
                Object transformed = event;
                if (onEvent != null) {
                    try {
                        transformed = onEvent.apply(event);
                    } catch (Exception e) {
                        log.warn("[execute_psop] on_event raised: {}", e.getMessage());
                        transformed = event;
                    }
                }
                if (transformed == null) {
                    return;
                }
                if (transformed instanceof List<?> list) {
                    for (Object e : list) {
                        if (e instanceof Map<?, ?> m) {
                            collected.add((Map<String, Object>) m);
                            Object typeObj = m.get("type");
                            Object dataObj = m.get("data");
                            emitter.onEvent(
                                    typeObj != null ? typeObj.toString() : eventType,
                                    dataObj instanceof Map ? (Map<String, Object>) dataObj : Map.of());
                        }
                    }
                } else if (transformed instanceof Map<?, ?> m) {
                    collected.add((Map<String, Object>) m);
                    Object typeObj = m.get("type");
                    Object dataObj = m.get("data");
                    emitter.onEvent(
                            typeObj != null ? typeObj.toString() : eventType,
                            dataObj instanceof Map ? (Map<String, Object>) dataObj : Map.of());
                }
            }
        };

        // Create engine client if not provided
        WorkflowEngineClient client = engineClient != null ? engineClient
                : new DefaultWorkflowEngineClient(agentCards, a2aClientRuntime,
                WorkflowEngineClientConfig.builder()
                        .sslVerify(sslVerify)
                        .caCertsPath(caCertsPath)
                        .credentialsConfigPath(credentialsConfigPath)
                        .a2atEnvPath(a2atEnvPath)
                        .build());
        client.setEventCallback(collectingCallback);

        // Create executor
        WorkflowExecutor executor = new WorkflowExecutor(
                workflow, controlPoint, client, collectingCallback,
                runtimeIntent != null ? runtimeIntent : "", lang != null ? lang : "zh");

        // Emit start
        collectingCallback.onEvent(EventType.START, Map.of("workflow", workflow.getName(), "steps", workflow.getSteps().size()));

        // Run + finalize
        return executor.run()
                .exceptionally(error -> {
                    log.error("[execute_psop] Execution failed: {}", error.getMessage());
                    return ExecutionResult.builder()
                            .success(false)
                            .error(error.getMessage())
                            .history(List.of())
                            .stepOutputs(Map.of())
                            .build();
                })
                .thenCompose(result -> {
                    // Emit complete or error
                    if (result.isSuccess()) {
                        collectingCallback.onEvent(EventType.COMPLETE, Map.of(
                                "history", result.getHistory(),
                                "step_outputs", result.getStepOutputs()));
                    } else {
                        collectingCallback.onEvent(EventType.ERROR, Map.of(
                                "error", result.getError() != null ? result.getError() : "Execution failed",
                                "history", result.getHistory(),
                                "step_outputs", result.getStepOutputs()));
                    }
                    // on_finish hook (async)
                    CompletableFuture<Void> finishFuture;
                    if (onFinish != null) {
                        finishFuture = onFinish.apply(result, new ArrayList<>(collected))
                                .exceptionally(e -> {
                                    log.error("[execute_psop] on_finish failed: {}", e.getMessage());
                                    return null;
                                });
                    } else {
                        finishFuture = CompletableFuture.completedFuture(null);
                    }
                    return finishFuture.thenApply(v -> {
                        // Emit close
                        collectingCallback.onEvent(EventType.CLOSE, Map.of());
                        // Close engine client
                        try {
                            client.close();
                        } catch (Exception ignored) {
                            // Close failures are non-fatal during shutdown
                        }
                        return result;
                    });
                });
    }

    /**
     * Simplified overload without SSL/auth/A2AT config (legacy compatibility).
     */
    public static CompletableFuture<ExecutionResult> execute(
            Object psop,
            List<?> agentCards,
            ControlPoint controlPoint,
            WorkflowEngineClient engineClient,
            String runtimeIntent,
            String lang,
            Object a2aClientRuntime,
            EventCallback eventCallback,
            BiConsumer<ExecutionResult, List<Map<String, Object>>> onFinish,
            Function<Map<String, Object>, Object> onEvent) {
        BiFunction<ExecutionResult, List<Map<String, Object>>, CompletableFuture<Void>> asyncOnFinish =
                onFinish != null ? (r, e) -> {
                    onFinish.accept(r, e);
                    return CompletableFuture.completedFuture(null);
                } : null;
        return execute(psop, agentCards, controlPoint, engineClient,
                runtimeIntent, lang,
                null, null, false, null,
                a2aClientRuntime, eventCallback, asyncOnFinish, onEvent);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Object psop;
        private List<?> agentCards = List.of();
        private ControlPoint controlPoint;
        private WorkflowEngineClient engineClient;
        private String runtimeIntent = "";
        private String lang = "zh";
        private String a2atEnvPath;
        private String credentialsConfigPath;
        private boolean sslVerify = true;
        private String caCertsPath;
        private Object a2aClientRuntime;
        private EventCallback eventCallback;
        private BiFunction<ExecutionResult, List<Map<String, Object>>, CompletableFuture<Void>> onFinish;
        private Function<Map<String, Object>, Object> onEvent;

        public Builder psop(Object psop) {
            this.psop = psop;
            return this;
        }

        public Builder agentCards(List<?> agentCards) {
            this.agentCards = agentCards;
            return this;
        }

        public Builder controlPoint(ControlPoint controlPoint) {
            this.controlPoint = controlPoint;
            return this;
        }

        public Builder engineClient(WorkflowEngineClient engineClient) {
            this.engineClient = engineClient;
            return this;
        }

        public Builder runtimeIntent(String runtimeIntent) {
            this.runtimeIntent = runtimeIntent;
            return this;
        }

        public Builder lang(String lang) {
            this.lang = lang;
            return this;
        }

        public Builder a2atEnvPath(String a2atEnvPath) {
            this.a2atEnvPath = a2atEnvPath;
            return this;
        }

        public Builder credentialsConfigPath(String credentialsConfigPath) {
            this.credentialsConfigPath = credentialsConfigPath;
            return this;
        }

        public Builder sslVerify(boolean sslVerify) {
            this.sslVerify = sslVerify;
            return this;
        }

        public Builder caCertsPath(String caCertsPath) {
            this.caCertsPath = caCertsPath;
            return this;
        }

        public Builder a2aClientRuntime(Object a2aClientRuntime) {
            this.a2aClientRuntime = a2aClientRuntime;
            return this;
        }

        public Builder eventCallback(EventCallback eventCallback) {
            this.eventCallback = eventCallback;
            return this;
        }

        public Builder onFinish(BiFunction<ExecutionResult, List<Map<String, Object>>, CompletableFuture<Void>> onFinish) {
            this.onFinish = onFinish;
            return this;
        }

        public Builder onFinish(BiConsumer<ExecutionResult, List<Map<String, Object>>> onFinish) {
            this.onFinish = onFinish != null ? (r, e) -> {
                onFinish.accept(r, e);
                return CompletableFuture.completedFuture(null);
            } : null;
            return this;
        }

        public Builder onEvent(Function<Map<String, Object>, Object> onEvent) {
            this.onEvent = onEvent;
            return this;
        }

        public CompletableFuture<ExecutionResult> execute() {
            if (psop == null) {
                throw new IllegalArgumentException("psop is required");
            }
            if (controlPoint == null) {
                throw new IllegalArgumentException("controlPoint is required");
            }
            return ExecutePsop.execute(psop, agentCards, controlPoint, engineClient,
                    runtimeIntent, lang, a2atEnvPath, credentialsConfigPath,
                    sslVerify, caCertsPath, a2aClientRuntime,
                    eventCallback, onFinish, onEvent);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object serialize(Object data) {
        if (data == null) {
            return null;
        }
        if (data instanceof String || data instanceof Number || data instanceof Boolean) {
            return data;
        }
        if (data instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : ((Map<String, Object>) data).entrySet()) {
                result.put(entry.getKey(), serialize(entry.getValue()));
            }
            return result;
        }
        if (data instanceof List) {
            return ((List<?>) data).stream().map(ExecutePsop::serialize).toList();
        }
        return data.toString();
    }
}
