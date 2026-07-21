package com.openan.a2at.engine.control;

/** Execution event type constants. */
public final class EventType {
    public static final String STEP_START = "step_start";
    public static final String STEP_COMPLETE = "step_complete";
    public static final String TASK_REQUEST = "task_request";
    public static final String TASK_RESPONSE = "task_response";
    public static final String TASK_STATUS_CHANGED = "task_status_changed";
    public static final String ROUTE_DECISION = "route_decision";
    public static final String ERROR = "error";
    public static final String WORKFLOW_COMPLETE = "workflow_complete";
    public static final String AGENT_REQUEST = "agent_request";
    public static final String AGENT_RESPONSE = "agent_response";
    public static final String NEGOTIATION_REQUEST = "negotiation_request";
    public static final String NEGOTIATION_RESOLVED = "negotiation_resolved";
    public static final String NEGOTIATION_FAILED = "negotiation_failed";
    public static final String AUTHORIZATION_REQUEST = "authorization_request";
    public static final String AUTHORIZATION_RESOLVED = "authorization_resolved";
    public static final String NOTIFICATION = "notification";
    public static final String START = "start";
    public static final String COMPLETE = "complete";
    public static final String CLOSE = "close";

    private EventType() {}
}
