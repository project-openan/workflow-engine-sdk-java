package com.openan.a2at.engine.model;

public enum StepType {
    ALL_SUCCESS("AllSuccess"),
    ANY_SUCCESS("AnySuccess");

    private final String value;
    StepType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static StepType fromValue(String v) {
        if (v == null) {
            return ALL_SUCCESS;
        }
        for (StepType t : values()) {
            if (t.value.equalsIgnoreCase(v) || t.name().equalsIgnoreCase(v)) {
                return t;
            }
        }
        return ALL_SUCCESS;
    }
}
