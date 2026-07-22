package com.openan.a2at.engine.examples.agents;

import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;

import java.util.UUID;

/**
 * Shared base class for sample agent executors.
 *
 * <p>Provides common utilities for text extraction and status message
 * construction that all agent executors need.
 */
public abstract class BaseAgentExecutor implements AgentExecutor {

    protected static String extractText(Message message) {
        if (message == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Part<?> part : message.parts()) {
            if (part instanceof TextPart tp) {
                sb.append(tp.text());
            }
        }
        return sb.toString();
    }

    protected static Message buildStatusMessage(String contextId, String taskId, String text) {
        return Message.builder()
                .messageId(UUID.randomUUID().toString())
                .contextId(contextId)
                .taskId(taskId)
                .role(Message.Role.ROLE_AGENT)
                .parts(new TextPart(text))
                .build();
    }
}
