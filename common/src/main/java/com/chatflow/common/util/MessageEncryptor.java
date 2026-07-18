package com.chatflow.common.util;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
@Component
public class MessageEncryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final SecretKey secretKey;
    private final boolean enabled;
    @Nullable private final Counter encryptFailures;
    @Nullable private final Counter decryptFailures;

    public MessageEncryptor(
            @Value("${chatflow.encryption.key:}") String encryptionKey,
            @Nullable MeterRegistry meterRegistry) {
        if (encryptionKey != null && !encryptionKey.isBlank()) {
            byte[] keyBytes = Base64.getDecoder().decode(encryptionKey);
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
            this.enabled = true;
            log.info("MessageEncryptor: AES-256-GCM 암호화 활성화");
        } else {
            this.secretKey = null;
            this.enabled = false;
            log.info("MessageEncryptor: CHATFLOW_ENCRYPTION_KEY 미설정 — 암호화 비활성화");
        }

        if (meterRegistry != null) {
            this.encryptFailures = meterRegistry.counter("chatflow.encryption.failures", "operation", "encrypt");
            this.decryptFailures = meterRegistry.counter("chatflow.encryption.failures", "operation", "decrypt");
        } else {
            this.encryptFailures = null;
            this.decryptFailures = null;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String encrypt(String plaintext) {
        if (!enabled || plaintext == null) return plaintext;
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Prepend IV to ciphertext, then Base64 encode
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("메시지 암호화 실패 — 평문으로 폴백(암호화 미적용 저장)", e);
            if (encryptFailures != null) encryptFailures.increment();
            return plaintext;
        }
    }

    public String decrypt(String ciphertext) {
        if (!enabled || ciphertext == null) return ciphertext;
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] encrypted = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] plaintext = cipher.doFinal(encrypted);
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("메시지 복호화 실패 — 원문 반환", e);
            if (decryptFailures != null) decryptFailures.increment();
            return ciphertext;
        }
    }
}
