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
package dev.openan.workflow.engine.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Redacts credential-shaped values from diagnostics without reserializing business payloads. */
public final class SensitiveDataRedactor {
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper();
  private static final Pattern SECRET_FORM =
      Pattern.compile(
          "(?i)((?:password|passwd|pwd|[\\w-]*token|[\\w-]*secret|accessSession|api[-_]?key)=)[^&\\s]*");
  private static final Pattern AUTH_VALUE =
      Pattern.compile("(?i)(\\b(?:Bearer|Basic)[ \\t]+)[^\\s\"\\\\,;]+");

  private SensitiveDataRedactor() {}

  public static String redact(String text) {
    if (text == null) return "";
    StringBuilder result = new StringBuilder();
    int copied = 0;
    for (int cursor = 0; cursor < text.length(); cursor++) {
      if (text.charAt(cursor) != '"') continue;
      int endOfString = valueEnd(text, cursor);
      if (endOfString <= cursor || text.charAt(endOfString - 1) != '"') break;
      int colon = endOfString;
      while (colon < text.length() && Character.isWhitespace(text.charAt(colon))) colon++;
      if (colon == text.length() || text.charAt(colon) != ':') {
        cursor = endOfString - 1;
        continue;
      }
      String key;
      try {
        key = JSON.readValue(text.substring(cursor, endOfString), String.class);
      } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
        cursor = endOfString - 1;
        continue;
      }
      cursor = colon;
      if (!sensitive(key)) continue;
      int start = colon + 1;
      while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
      int end = valueEnd(text, start);
      result.append(text, copied, start).append("\"***\"");
      copied = end;
      cursor = end - 1;
    }
    result.append(text, copied, text.length());
    return AUTH_VALUE.matcher(SECRET_FORM.matcher(result).replaceAll("$1***")).replaceAll("$1***");
  }

  private static int valueEnd(String text, int start) {
    boolean quoted = false;
    boolean escaped = false;
    int depth = 0;
    for (int i = start; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quoted) {
        if (escaped) escaped = false;
        else if (c == '\\') escaped = true;
        else if (c == '"') {
          quoted = false;
          if (depth == 0) return i + 1;
        }
      } else if (c == '"') quoted = true;
      else if (c == '{' || c == '[') depth++;
      else if (c == '}' || c == ']') {
        if (depth == 0) return i;
        if (--depth == 0) return i + 1;
      } else if (depth == 0 && (c == ',' || Character.isWhitespace(c))) return i;
    }
    return text.length();
  }

  public static boolean sensitive(String name) {
    String key = name.toLowerCase(Locale.ROOT);
    if (key.contains("://")) return false;
    String normalized = key.replace("-", "").replace("_", "");
    return normalized.contains("authorization")
        || key.contains("cookie")
        || normalized.contains("token")
        || normalized.contains("secret")
        || normalized.contains("password")
        || normalized.equals("pwd")
        || normalized.equals("passwd")
        || normalized.equals("accesssession")
        || normalized.equals("xapikey")
        || normalized.equals("apikey");
  }

  public static Map<String, List<String>> safeHeaders(Map<String, List<String>> headers) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    headers.forEach(
        (key, values) -> result.put(key, sensitive(key) ? List.of("***") : List.copyOf(values)));
    return Collections.unmodifiableMap(result);
  }
}
