/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.openan.a2at.engine.client;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SSL context factory for outbound HTTPS calls.
 *
 * <p>Mirrors the Python SDK's {@code ssl_context.create_ssl_context()}.
 * Returns an {@link SSLContext} configured with the given CA trust store,
 * or {@code Optional#empty()} when server verification is disabled.
 */
public final class SslContextFactory {

    private static final Logger log = LoggerFactory.getLogger(SslContextFactory.class);

    private SslContextFactory() {
    }

    /**
     * Build an SSLContext for outbound HTTPS.
     *
     * @param verifyServer whether to verify remote server certificates
     * @param caCertsPath  optional path to a PEM CA trust store file
     * @return SSLContext, or empty when verification is disabled
     */
    public static Optional<SSLContext> create(boolean verifyServer, String caCertsPath) {
        if (!verifyServer) {
            log.warn("Outbound TLS verification disabled. Insecure for production.");
            return Optional.empty();
        }
        try {
            X509TrustManager trustManager = createTrustManager(caCertsPath);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{trustManager}, null);
            log.info("Client SSL: context initialized (ca_certs={})", caCertsPath);
            return Optional.of(ctx);
        } catch (Exception e) {
            log.error("Failed to build SSL context: {}. Falling back to no verification.", e.getMessage());
            return Optional.empty();
        }
    }

    private static X509TrustManager createTrustManager(String caCertsPath) throws Exception {
        if (caCertsPath != null && !caCertsPath.isEmpty()) {
            try (FileInputStream fis = new FileInputStream(caCertsPath)) {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
                ks.load(null, null);
                int i = 0;
                for (X509Certificate cert : cf.generateCertificates(fis).toArray(new X509Certificate[0])) {
                    ks.setCertificateEntry("ca-" + i, cert);
                    i++;
                }
                log.info("Client SSL: loaded CA trust store from {}", caCertsPath);
                javax.net.ssl.TrustManagerFactory tmf =
                        javax.net.ssl.TrustManagerFactory.getInstance(
                                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ks);
                return (X509TrustManager) tmf.getTrustManagers()[0];
            }
        }
        javax.net.ssl.TrustManagerFactory tmf =
                javax.net.ssl.TrustManagerFactory.getInstance(
                        javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        return (X509TrustManager) tmf.getTrustManagers()[0];
    }
}
