/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine.spring;

import com.google.protobuf.util.JsonFormat;

import jakarta.servlet.http.HttpServletRequest;

import org.a2aproject.sdk.grpc.SendMessageRequest;
import org.a2aproject.sdk.grpc.StreamResponse;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.transport.rest.handler.RestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spring MVC controller exposing A2A message endpoints.
 *
 * <p>Maps two endpoints (no AgentCard retrieval -- cards come from the registry center):
 *
 * <ul>
 *   <li>{@code POST /a2a/json/message:send} - synchronous message send
 *   <li>{@code POST /a2a/json/message:stream} - SSE streaming message send
 * </ul>
 *
 * <p>The path prefix is configurable via {@code a2at.server.path-prefix}. The controller delegates
 * to {@link RestHandler} (non-streaming) and {@link RequestHandler} (streaming). The {@link
 * ServerCallContext} is built from {@link HttpServletRequest} headers, preserving the
 * A2A-Extensions header.
 */
@RestController
public class A2AController {

    private static final Logger log = LoggerFactory.getLogger(A2AController.class);

    private final RestHandler restHandler;
    private final RequestHandler requestHandler;
    private final String pathPrefix;

    public A2AController(
            RestHandler restHandler, RequestHandler requestHandler, A2AProperties properties) {
        this.restHandler = restHandler;
        this.requestHandler = requestHandler;
        this.pathPrefix = properties.getPathPrefix();
    }

    @PostMapping("${a2at.server.path-prefix}/message:send")
    public ResponseEntity<String> sendMessage(HttpServletRequest req, @RequestBody String body) {
        var ctx = buildContext(req);
        var resp = restHandler.sendMessage(ctx, "", body);
        return ResponseEntity.status(resp.getStatusCode())
                .contentType(
                        MediaType.parseMediaType(
                                resp.getContentType() != null
                                        ? resp.getContentType()
                                        : MediaType.APPLICATION_JSON_VALUE))
                .body(resp.getBody());
    }

    @PostMapping(
            value = "${a2at.server.path-prefix}/message:stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseBodyEmitter streamMessage(HttpServletRequest req, @RequestBody String body) {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(0L);
        try {
            SendMessageRequest.Builder builder = SendMessageRequest.newBuilder();
            JsonFormat.parser().merge(body, builder);
            MessageSendParams params = ProtoUtils.FromProto.messageSendParams(builder.build());

            requestHandler.validateRequestedTask(params.message().taskId());
            var ctx = buildContext(req);
            Flow.Publisher<StreamingEventKind> publisher =
                    requestHandler.onMessageSendStream(params, ctx);

            final AtomicLong seq = new AtomicLong(0);
            publisher.subscribe(
                    new Flow.Subscriber<>() {
                        private Flow.Subscription sub;

                        @Override
                        public void onSubscribe(Flow.Subscription s) {
                            sub = s;
                            s.request(1);
                        }

                        @Override
                        public void onNext(StreamingEventKind item) {
                            try {
                                StreamResponse sr = ProtoUtils.ToProto.streamResponse(item);
                                String json = JsonFormat.printer().print(sr);
                                String compact =
                                        json.replace("\r\n", "\n")
                                                .replace('\r', '\n')
                                                .lines()
                                                .map(String::trim)
                                                .reduce("", String::concat);
                                String sse =
                                        String.format(Locale.ROOT, "id:%d%n", seq.incrementAndGet())
                                                + "data:"
                                                + compact
                                                + "\n\n";
                                emitter.send(sse);
                            } catch (Exception e) {
                                log.error("[SSE] Write failed: {}", e.getMessage());
                                sub.cancel();
                                emitter.completeWithError(e);
                                return;
                            }
                            sub.request(1);
                        }

                        @Override
                        public void onError(Throwable t) {
                            log.error("[SSE] Stream error: {}", t.getMessage());
                            emitter.completeWithError(t);
                        }

                        @Override
                        public void onComplete() {
                            emitter.complete();
                        }
                    });
        } catch (Exception e) {
            log.error("[SSE] Setup failed: {}", e.getMessage(), e);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private ServerCallContext buildContext(HttpServletRequest req) {
        Map<String, Object> state = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();
        java.util.Collections.list(req.getHeaderNames())
                .forEach(h -> headers.put(h.toLowerCase(Locale.ROOT), req.getHeader(h)));
        state.put("headers", headers);
        String ext = req.getHeader("A2A-Extensions");
        Set<String> exts = (ext == null || ext.isBlank()) ? Set.of() : Set.of(ext.split(","));
        String ver = req.getHeader("A2A-Protocol-Version");
        return new ServerCallContext(null, state, exts, ver);
    }
}
