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

package com.openan.a2at.engine.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM credential encryption/decryption utility.
 *
 * <p>Supports encrypted values in credential config files using the
 * {@code enc:} prefix. The encryption key is read from the
 * {@code A2AT_CRED_KEY} environment variable (32-byte hex string).
 *
 * <p>Usage in credentials JSON:
 * <pre>{@code
 * {
 *   "value": "enc:<base64-iv>:<base64-ciphertext>"
 * }
 * }</pre>
 *
 * <p>To generate an encrypted value, use the {@link #encrypt} method
 * with the same key. Plaintext values (no {@code enc:} prefix) are
 * returned as-is for backward compatibility.
 */
public final class CredentialCrypto {

    private static final Logger log = LoggerFactory.getLogger(CredentialCrypto.class);
    private static final String ENV_KEY = "A2AT_CRED_KEY";
    private static final String PREFIX = "enc:";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;

    private CredentialCrypto() {
    }

    /**
     * Decrypt a credential value if it has the {@code enc:} prefix.
     * Values without the prefix are returned as-is (plaintext fallback).
     *
     * @param value the raw value from config (may be {@code enc:...} or plaintext)
     * @return decrypted plaintext, or the original value if not encrypted
     */
    public static String decryptIfNeeded(String value) {
        if (value == null || !value.startsWith(PREFIX)) {
            return value;
        }
        String keyHex = System.getenv(ENV_KEY);
        if (keyHex == null || keyHex.isBlank()) {
            log.warn("[CredentialCrypto] Encrypted value found but {} env var not set, using as-is", ENV_KEY);
            return value;
        }
        try {
            String encoded = value.substring(PREFIX.length());
            String[] parts = encoded.split(":", 2);
            if (parts.length != 2) {
                log.error("[CredentialCrypto] Invalid encrypted format, expected enc:<iv>:<ciphertext>");
                return value;
            }
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] cipherText = Base64.getDecoder().decode(parts[1]);
            byte[] keyBytes = hexToBytes(keyHex);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] plainBytes = cipher.doFinal(cipherText);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("[CredentialCrypto] Decryption failed: {}", e.getMessage());
            return value;
        }
    }

    /**
     * Encrypt a plaintext value using AES-GCM with the key from
     * the {@code A2AT_CRED_KEY} environment variable.
     *
     * @param plaintext the value to encrypt
     * @return encrypted string in format {@code enc:<base64-iv>:<base64-ciphertext>}
     * @throws IllegalStateException if the key env var is not set
     */
    public static String encrypt(String plaintext) {
        String keyHex = System.getenv(ENV_KEY);
        if (keyHex == null || keyHex.isBlank()) {
            throw new IllegalStateException(ENV_KEY + " environment variable not set");
        }
        try {
            byte[] keyBytes = hexToBytes(keyHex);
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(iv)
                    + ":" + Base64.getEncoder().encodeToString(cipherText);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed: " + e.getMessage(), e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
