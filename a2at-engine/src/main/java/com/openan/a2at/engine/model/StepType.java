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

package com.openan.a2at.engine.model;

public enum StepType {
    ALL_SUCCESS("AllSuccess"),
    ANY_SUCCESS("AnySuccess"),
    SELF_LOOP("SelfLoop");

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
