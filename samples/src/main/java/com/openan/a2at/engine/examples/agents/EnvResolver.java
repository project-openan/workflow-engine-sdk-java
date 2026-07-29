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

package com.openan.a2at.engine.examples.agents;

import java.io.File;

/**
 * Shared utility for resolving the A2A-T {@code .env} file path.
 *
 * <p>Searches the classpath, then walks up from the working directory to find {@code .env}. Used by
 * both demo variants (embedded and Spring) and by shared agent executors that need the env path for
 * A2A-T SDK initialization.
 */
public final class EnvResolver {

    private EnvResolver() {}

    public static String resolveEnvPath() {
        var url = EnvResolver.class.getClassLoader().getResource(".env");
        if (url != null && "file".equals(url.getProtocol())) {
            return new File(url.getPath()).getAbsolutePath();
        }
        File cwd = new File(System.getProperty("user.dir"));
        for (File dir = cwd; dir != null; dir = dir.getParentFile()) {
            File candidate = new File(dir, ".env");
            if (candidate.exists()) {
                return candidate.getAbsolutePath();
            }
        }
        return null;
    }
}
