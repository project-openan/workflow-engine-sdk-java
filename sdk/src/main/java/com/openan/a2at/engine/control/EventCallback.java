package com.openan.a2at.engine.control;

import java.util.Map;

/** Optional callback for execution events. Instantiate directly as no-op sink. */
public class EventCallback {
    public void onEvent(String eventType, Map<String, Object> data) {}
}
