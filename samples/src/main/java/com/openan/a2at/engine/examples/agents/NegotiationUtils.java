package com.openan.a2at.engine.examples.agents;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Constants and helpers for the server-side negotiation flow, mirroring the
 * Python reference in orchestration-center/common/negotiation_utils.py.
 *
 * <p>Markers and key names align with the a2a-t-sdk-java negotiation payload
 * mapper (NEGOTIATION_CONTEXT_KEY / NEGOTIATION_TEXT_KEY) and the engine-side
 * NegotiationTHandler / autoNegotiate follow-up format.
 */
final class NegotiationUtils {

    static final String NEGOTIATION_RESOLUTION_MARKER = "[NEGOTIATION_RESOLUTION]";
    static final String NEGOTIATION_REQUEST_MARKER = "[NEGOTIATION_REQUEST]";
    static final String NEGOTIATION_CONTEXT_MARKER = "[NEGOTIATION_CONTEXT]";
    static final String NEGOTIATION_CONCERN_KEY = "negotiationConcern";

    static final String NEGOTIATION_CONTEXT_KEY =
            "https://projects.tmforum.org/a2aproject/telecommunication/extensions/DATA-NEGOTIATION-T/v1";
    static final String NEGOTIATION_TEXT_KEY =
            "https://projects.tmforum.org/a2aproject/telecommunication/extensions/NEGOTIATION-T";
    static final String TASK_PROMPT_KEY =
            "https://projects.tmforum.org/a2aproject/telecommunication/extensions/Task-T/v1";

    private NegotiationUtils() {}

    static boolean isFollowUpTask(String text) {
        return text != null && text.contains(NEGOTIATION_RESOLUTION_MARKER);
    }

    /** Extract the original task text from a [NEGOTIATION_RESOLUTION] follow-up message. */
    static String extractOriginalTask(String text) {
        if (text == null || !text.contains(NEGOTIATION_RESOLUTION_MARKER)) {
            return text != null ? text : "";
        }
        int idx = text.indexOf("Original Task:");
        if (idx < 0) {
            return "";
        }
        String after = text.substring(idx + "Original Task:".length()).trim();
        int end = after.indexOf("\n\nPlease re-execute the task based on the clarification above.");
        if (end >= 0) {
            after = after.substring(0, end).trim();
        }
        return after;
    }

    /** Strip negotiation markers to recover a clean task text for re-execution. */
    static String cleanupResolutionMarker(String text) {
        if (text == null) {
            return "";
        }
        if (!text.contains(NEGOTIATION_RESOLUTION_MARKER)) {
            return text;
        }
        String original = extractOriginalTask(text);
        return original.isEmpty() ? text : original;
    }

    /** Build the negotiation response metadata placed on an INPUT_REQUIRED task. */
    static Map<String, Object> negotiationResponseMetadata(
            Map<String, Object> contextData, String negotiationText, String concern) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (contextData != null && !contextData.isEmpty()) {
            metadata.put(NEGOTIATION_CONTEXT_KEY, contextData);
        }
        if (negotiationText != null && !negotiationText.isEmpty()) {
            metadata.put(NEGOTIATION_TEXT_KEY, negotiationText);
        }
        if (concern != null && !concern.isEmpty()) {
            metadata.put(NEGOTIATION_CONCERN_KEY, concern);
        }
        return metadata;
    }

    /** Build the [NEGOTIATION_RESOLUTION] follow-up text the client resends. */
    static String buildResolutionMessage(String originalTask, String resolutionText) {
        return NEGOTIATION_RESOLUTION_MARKER + "\n"
                + "The engine has reviewed your negotiation request and provides the following clarification:\n\n"
                + resolutionText + "\n\n---\nOriginal Task:\n" + originalTask
                + "\n\nPlease re-execute the task based on the clarification above.";
    }
}
