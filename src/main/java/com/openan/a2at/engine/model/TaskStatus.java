package com.openan.a2at.engine.model;

public enum TaskStatus {
    PENDING("pending"),
    RUNNING("running"),
    SUCCESS("success"),
    FAILED("failed");

    private final String value;

    TaskStatus(String value) {
        this.value = value;
    }

    /**
     * Returns the lowercase string value, matching the Python SDK.
     * Used in {@code task_status_changed} events for cross-SDK consistency.
     */
    public String getValue() {
        return value;
    }
}
