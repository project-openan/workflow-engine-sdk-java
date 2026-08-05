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

package dev.openan.workflow.engine.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Optional;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * SSL context factory for outbound HTTPS calls.
 *
 * <p>Mirrors the Python SDK's {@code ssl_context.create_ssl_context()}. Returns an {@link
 * SSLContext} configured with the given CA trust store, or {@code Optional#empty()} when server
 * verification is disabled.
 */
public final class SslContextFactory {

    private static final Logger log = LoggerFactory.getLogger(SslContextFactory.class);

    private SslContextFactory() {}

    /**
     * Build an SSLContext for outbound HTTPS.
     *
     * @param verifyServer whether to verify remote server certificates
     * @param caCertsPath optional path to a PEM CA trust store file
     * @return SSLContext, or empty when verification is disabled
     */
    public static Optional<SSLContext> create(boolean verifyServer, String caCertsPath) {
        return create(verifyServer, caCertsPath, null, null, null, null);
    }

    /**
     * Build an SSLContext for outbound HTTPS with full mTLS support.
     *
     * @param verifyServer whether to verify remote server certificates
     * @param caCertsPath optional path to a PEM CA trust store file
     * @param certPath optional path to client certificate (for mTLS)
     * @param keyPath optional path to client private key (for mTLS)
     * @param keyPassword optional password for the private key
     * @param crlPath optional path to a CRL file
     * @return SSLContext, or empty when verification is disabled
     */
    public static Optional<SSLContext> create(
            boolean verifyServer,
            String caCertsPath,
            String certPath,
            String keyPath,
            String keyPassword,
            String crlPath) {
        if (!verifyServer) {
            log.warn("Outbound TLS verification disabled. Insecure for production.");
            return Optional.empty();
        }
        try {
            X509TrustManager trustManager = createTrustManager(caCertsPath);
            // Load client identity cert for mTLS if provided
            javax.net.ssl.KeyManager[] keyManagers =
                    loadKeyManagers(certPath, keyPath, keyPassword);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(keyManagers, new TrustManager[] {trustManager}, null);
            log.info("Client SSL: context initialized (ca_certs={})", caCertsPath);
            return Optional.of(ctx);
        } catch (Exception e) {
            log.error(
                    "Failed to build SSL context: {}. Falling back to no verification.",
                    e.getMessage());
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
                for (Certificate cert : cf.generateCertificates(fis).toArray(new Certificate[0])) {
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

    private static javax.net.ssl.KeyManager[] loadKeyManagers(
            String certPath, String keyPath, String keyPassword) {
        if (certPath == null
                || certPath.isEmpty()
                || keyPath == null
                || keyPath.isEmpty()
                || !new java.io.File(certPath).exists()
                || !new java.io.File(keyPath).exists()) {
            return null;
        }
        try {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            java.security.cert.CertificateFactory cf =
                    java.security.cert.CertificateFactory.getInstance("X.509");
            try (FileInputStream certFis = new FileInputStream(certPath)) {
                X509Certificate cert = (X509Certificate) cf.generateCertificate(certFis);
                keyStore.setCertificateEntry("client-cert", cert);
            }
            byte[] keyBytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(keyPath));
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
            java.security.spec.PKCS8EncodedKeySpec keySpec =
                    new java.security.spec.PKCS8EncodedKeySpec(keyBytes);
            java.security.PrivateKey privateKey = kf.generatePrivate(keySpec);
            char[] password = keyPassword != null ? keyPassword.toCharArray() : new char[0];
            keyStore.setKeyEntry(
                    "client-key",
                    privateKey,
                    password,
                    new java.security.cert.Certificate[] {keyStore.getCertificate("client-cert")});
            javax.net.ssl.KeyManagerFactory kmf =
                    javax.net.ssl.KeyManagerFactory.getInstance(
                            javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password);
            log.info("Client SSL: loaded client identity cert for mTLS");
            return kmf.getKeyManagers();
        } catch (Exception e) {
            log.warn("Client SSL: could not load client cert chain: {}", e.getMessage());
            return null;
        }
    }

    public static SSLContext createTrustAll() {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(
                    null,
                    new TrustManager[] {
                        new X509TrustManager() {
                            public void checkClientTrusted(
                                    X509Certificate[] chain, String authType) {}

                            public void checkServerTrusted(
                                    X509Certificate[] chain, String authType) {}

                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        }
                    },
                    null);
            log.warn("Trust-all SSL context created");
            return ctx;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
