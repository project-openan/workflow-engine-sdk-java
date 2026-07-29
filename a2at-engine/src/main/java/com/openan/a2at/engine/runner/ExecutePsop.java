/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the License); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an AS IS BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package com.openan.a2at.engine.runner;

import com.openan.a2at.engine.client.A2AJavaClientRuntime;
import com.openan.a2at.engine.client.A2ATransport;
import com.openan.a2at.engine.client.DefaultWorkflowEngineClient;
import com.openan.a2at.engine.client.WorkflowEngineClient;
import com.openan.a2at.engine.client.WorkflowEngineClientConfig;
import com.openan.a2at.engine.control.ControlPoint;
import com.openan.a2at.engine.control.EventCallback;
import com.openan.a2at.engine.control.EventType;
import com.openan.a2at.engine.core.WorkflowExecutor;
import com.openan.a2at.engine.model.ExecutionResult;
import com.openan.a2at.engine.model.Workflow;

import org.a2aproject.sdk.spec.AgentCard;
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
 *
 * <p>Java equivalent of Python's execute_psop(). Returns a CompletableFuture that completes when
 * the workflow ends. Events are emitted via EventCallback during execution. The onFinish hook is
 * called with (result, collectedEvents).
 */
public class ExecutePsop {
    private static final Logger log = LoggerFactory.getLogger(ExecutePsop.class);

    /**
     * Execute a PSOP workflow end-to-end.
     *
     * @param psop workflow object
     * @param agentCards list of AgentCard objects
     * @param controlPoint user's decision callbacks
     * @param engineClient optional pre-created client (null = auto-create)
     * @param runtimeIntent original user intent
     * @param lang language hint (zh/en)
     * @param a2atEnvPath path to A2A-T .env file
     * @param credentialsConfigPath path to credentials JSON
     * @param sslVerify whether to verify TLS certificates
     * @param caCertsPath path to CA trust store
     * @param a2aClientRuntime the A2A client runtime
     * @param eventCallback optional event callback (null = no-op)
     * @param onFinish optional persistence hook
     * @param onEvent optional event transformer
     * @return CompletableFuture<ExecutionResult>
     */
    public static CompletableFuture<ExecutionResult> execute(
            Workflow psop,
            List<AgentCard> agentCards,
            ControlPoint controlPoint,
            WorkflowEngineClient engineClient,
            String runtimeIntent,
            String lang,
            String a2atEnvPath,
            String credentialsConfigPath,
            boolean sslVerify,
            String caCertsPath,
            A2AJavaClientRuntime a2aClientRuntime,
            EventCallback eventCallback,
            BiFunction<ExecutionResult, List<Map<String, Object>>, CompletableFuture<Void>>
                    onFinish,
            Function<Map<String, Object>, Object> onEvent) {

        EventCallback emitter = eventCallback != null ? eventCallback : new EventCallback();
        List<Map<String, Object>> collected = Collections.synchronizedList(new ArrayList<>());
        EventCallback collectingCallback = createCollectingCallback(emitter, collected, onEvent);
        WorkflowEngineClient client =
                engineClient != null
                        ? engineClient
                        : createEngineClient(
                                agentCards,
                                a2aClientRuntime,
                                sslVerify,
                                caCertsPath,
                                credentialsConfigPath,
                                a2atEnvPath,
                                collectingCallback);
        WorkflowExecutor executor =
                new WorkflowExecutor(
                        psop,
                        controlPoint,
                        client,
                        collectingCallback,
                        runtimeIntent != null ? runtimeIntent : "",
                        lang != null ? lang : "zh");
        log.info(
                "[execute_psop] Starting: workflow={}, {} steps, intent={}",
                psop.getName(),
                psop.getSteps().size(),
                runtimeIntent);
        collectingCallback.onEvent(
                EventType.START,
                Map.of("workflow", psop.getName(), "steps", psop.getSteps().size()));
        return executor.run()
                .exceptionally(ExecutePsop::handleExecutionError)
                .thenCompose(
                        result ->
                                finalizeResult(
                                        result, client, collectingCallback, collected, onFinish));
    }

    /** Simplified overload without SSL/auth/A2AT config (legacy compatibility). */
    public static CompletableFuture<ExecutionResult> execute(
            Workflow psop,
            List<AgentCard> agentCards,
            ControlPoint controlPoint,
            WorkflowEngineClient engineClient,
            String runtimeIntent,
            String lang,
            A2AJavaClientRuntime a2aClientRuntime,
            EventCallback eventCallback,
            BiConsumer<ExecutionResult, List<Map<String, Object>>> onFinish,
            Function<Map<String, Object>, Object> onEvent) {
        BiFunction<ExecutionResult, List<Map<String, Object>>, CompletableFuture<Void>>
                asyncOnFinish =
                        onFinish != null
                                ? (r, e) -> {
                                    onFinish.accept(r, e);
                                    return CompletableFuture.completedFuture(null);
                                }
                                : null;
        return execute(
                psop,
                agentCards,
                controlPoint,
                engineClient,
                runtimeIntent,
                lang,
                null,
                null,
                false,
                null,
                a2aClientRuntime,
                eventCallback,
                asyncOnFinish,
                onEvent);
    }

    public static Builder builder() {
        return new Builder();
    }

    @SuppressWarnings("unchecked")
    private static EventCallback createCollectingCallback(
            EventCallback emitter,
            List<Map<String, Object>> collected,
            Function<Map<String, Object>, Object> onEvent) {
        return new EventCallback() {
            @Override
            public void onEvent(String eventType, Map<String, Object> data) {
                Map<String, Object> event = new HashMap<>();
                event.put("type", eventType);
                event.put("data", serialize(data));
                event.put("timestamp", System.currentTimeMillis() / 1000.0);
                Object transformed = transformEvent(event, onEvent);
                if (transformed == null) return;
                dispatchTransformed(transformed, eventType, emitter, collected);
            }
        };
    }

    private static Object transformEvent(
            Map<String, Object> event, Function<Map<String, Object>, Object> onEvent) {
        if (onEvent == null) return event;
        try {
            return onEvent.apply(event);
        } catch (Exception e) {
            log.warn("[execute_psop] on_event raised: {}", e.getMessage());
            return event;
        }
    }

    @SuppressWarnings("unchecked")
    private static void dispatchTransformed(
            Object transformed,
            String defaultType,
            EventCallback emitter,
            List<Map<String, Object>> collected) {
        if (transformed instanceof List<?> list) {
            for (Object e : list) {
                if (e instanceof Map<?, ?> m) {
                    emitEventMap((Map<String, Object>) m, defaultType, emitter, collected);
                }
            }
        } else if (transformed instanceof Map<?, ?> m) {
            emitEventMap((Map<String, Object>) m, defaultType, emitter, collected);
        }
    }

    @SuppressWarnings("unchecked")
    private static void emitEventMap(
            Map<String, Object> m,
            String defaultType,
            EventCallback emitter,
            List<Map<String, Object>> collected) {
        collected.add(m);
        Object typeObj = m.get("type");
        Object dataObj = m.get("data");
        emitter.onEvent(
                typeObj != null ? typeObj.toString() : defaultType,
                dataObj instanceof Map ? (Map<String, Object>) dataObj : Map.of());
    }

    private static WorkflowEngineClient createEngineClient(
            List<AgentCard> agentCards,
            A2AJavaClientRuntime a2aClientRuntime,
            boolean sslVerify,
            String caCertsPath,
            String credentialsConfigPath,
            String a2atEnvPath,
            EventCallback callback) {
        A2ATransport transport =
                new A2ATransport(
                        agentCards,
                        a2aClientRuntime,
                        WorkflowEngineClientConfig.builder()
                                .sslVerify(sslVerify)
                                .caCertsPath(caCertsPath)
                                .credentialsConfigPath(credentialsConfigPath)
                                .a2atEnvPath(a2atEnvPath)
                                .build());
        WorkflowEngineClient client = new DefaultWorkflowEngineClient(transport);
        client.setEventCallback(callback);
        return client;
    }

    private static ExecutionResult handleExecutionError(Throwable error) {
        log.error("[execute_psop] Execution failed: {}", error.getMessage());
        return ExecutionResult.builder()
                .success(false)
                .error(error.getMessage())
                .history(List.of())
                .stepOutputs(Map.of())
                .build();
    }

    private static CompletableFuture<ExecutionResult> finalizeResult(
            ExecutionResult result,
            WorkflowEngineClient client,
            EventCallback callback,
            List<Map<String, Object>> collected,
            BiFunction<ExecutionResult, List<Map<String, Object>>, CompletableFuture<Void>>
                    onFinish) {
        emitResultEvent(result, callback);
        CompletableFuture<Void> finishFuture = invokeOnFinish(result, collected, onFinish);
        return finishFuture.thenApply(
                v -> {
                    log.info(
                            "[execute_psop] Finished: workflow success={}, history={}",
                            result.isSuccess(),
                            result.getHistory() != null ? result.getHistory().size() : 0);
                    callback.onEvent(EventType.CLOSE, Map.of());
                    try {
                        client.close();
                    } catch (Exception ignored) {
                    }
                    return result;
                });
    }

    private static void emitResultEvent(ExecutionResult result, EventCallback callback) {
        if (result.isSuccess()) {
            callback.onEvent(
                    EventType.COMPLETE,
                    Map.of(
                            "history", result.getHistory(),
                            "step_outputs", result.getStepOutputs()));
        } else {
            callback.onEvent(
                    EventType.ERROR,
                    Map.of(
                            "error",
                                    result.getError() != null
                                            ? result.getError()
                                            : "Execution failed",
                            "history", result.getHistory(),
                            "step_outputs", result.getStepOutputs()));
        }
    }

    private static CompletableFuture<Void> invokeOnFinish(
            ExecutionResult result,
            List<Map<String, Object>> collected,
            BiFunction<ExecutionResult, List<Map<String, Object>>, CompletableFuture<Void>>
                    onFinish) {
        if (onFinish == null) {
            return CompletableFuture.completedFuture(null);
        }
        return onFinish.apply(result, new ArrayList<>(collected))
                .exceptionally(
                        e -> {
                            log.error("[execute_psop] on_finish failed: {}", e.getMessage());
                            return null;
                        });
    }

    @SuppressWarnings("unchecked")
    private static Object serialize(Object data) {
        if (data == null) return null;
        if (data instanceof String || data instanceof Number || data instanceof Boolean)
            return data;
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

    public static final class Builder {
        private Workflow psop;
        private List<AgentCard> agentCards = List.of();
        private ControlPoint controlPoint;
        private WorkflowEngineClient engineClient;
        private String runtimeIntent = "";
        private String lang = "zh";
        private String a2atEnvPath;
        private String credentialsConfigPath;
        private boolean sslVerify = true;
        private String caCertsPath;
        private A2AJavaClientRuntime a2aClientRuntime;
        private EventCallback eventCallback;
        private BiFunction<ExecutionResult, List<Map<String, Object>>, CompletableFuture<Void>>
                onFinish;
        private Function<Map<String, Object>, Object> onEvent;

        public Builder psop(Workflow psop) {
            this.psop = psop;
            return this;
        }

        public Builder agentCards(List<AgentCard> v) {
            this.agentCards = v;
            return this;
        }

        public Builder controlPoint(ControlPoint v) {
            this.controlPoint = v;
            return this;
        }

        public Builder engineClient(WorkflowEngineClient v) {
            this.engineClient = v;
            return this;
        }

        public Builder runtimeIntent(String v) {
            this.runtimeIntent = v;
            return this;
        }

        public Builder lang(String v) {
            this.lang = v;
            return this;
        }

        public Builder a2atEnvPath(String v) {
            this.a2atEnvPath = v;
            return this;
        }

        public Builder credentialsConfigPath(String v) {
            this.credentialsConfigPath = v;
            return this;
        }

        public Builder sslVerify(boolean v) {
            this.sslVerify = v;
            return this;
        }

        public Builder caCertsPath(String v) {
            this.caCertsPath = v;
            return this;
        }

        public Builder a2aClientRuntime(A2AJavaClientRuntime v) {
            this.a2aClientRuntime = v;
            return this;
        }

        public Builder eventCallback(EventCallback v) {
            this.eventCallback = v;
            return this;
        }

        public Builder onFinish(
                BiFunction<ExecutionResult, List<Map<String, Object>>, CompletableFuture<Void>> v) {
            this.onFinish = v;
            return this;
        }

        public Builder onFinish(BiConsumer<ExecutionResult, List<Map<String, Object>>> v) {
            this.onFinish =
                    v != null
                            ? (r, e) -> {
                                v.accept(r, e);
                                return CompletableFuture.completedFuture(null);
                            }
                            : null;
            return this;
        }

        public Builder onEvent(Function<Map<String, Object>, Object> v) {
            this.onEvent = v;
            return this;
        }

        public CompletableFuture<ExecutionResult> execute() {
            if (psop == null) throw new IllegalArgumentException("psop is required");
            if (controlPoint == null)
                throw new IllegalArgumentException("controlPoint is required");
            return ExecutePsop.execute(
                    psop,
                    agentCards,
                    controlPoint,
                    engineClient,
                    runtimeIntent,
                    lang,
                    a2atEnvPath,
                    credentialsConfigPath,
                    sslVerify,
                    caCertsPath,
                    a2aClientRuntime,
                    eventCallback,
                    onFinish,
                    onEvent);
        }
    }
}
