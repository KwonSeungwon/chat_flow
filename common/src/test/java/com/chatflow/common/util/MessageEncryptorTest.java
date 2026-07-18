package com.chatflow.common.util;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization tests for MessageEncryptor.
 *
 * Documents every behavior including the silent fail-open paths
 * (encrypt returns plaintext, decrypt returns ciphertext on any crypto
 * error) and the chatflow.encryption.failures Micrometer counter that
 * makes those events observable.
 */
class MessageEncryptorTest {

    /** 32 zero-bytes — a valid AES-256 key. */
    private static final String VALID_KEY_A =
            Base64.getEncoder().encodeToString(new byte[32]);

    /** 32 one-bytes — a DIFFERENT valid AES-256 key. */
    private static final String VALID_KEY_B;

    static {
        byte[] ones = new byte[32];
        java.util.Arrays.fill(ones, (byte) 1);
        VALID_KEY_B = Base64.getEncoder().encodeToString(ones);
    }

    // --- happy path ---

    @Test
    void enabled_roundTrip() {
        var registry = new SimpleMeterRegistry();
        var enc = new MessageEncryptor(VALID_KEY_A, registry);

        assertThat(enc.isEnabled()).isTrue();

        String plaintext = "Hello, ChatFlow!";
        String ciphertext = enc.encrypt(plaintext);
        assertThat(ciphertext).isNotEqualTo(plaintext);

        String decrypted = enc.decrypt(ciphertext);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void enabled_randomIv_differentCiphertextSamePlaintext() {
        var registry = new SimpleMeterRegistry();
        var enc = new MessageEncryptor(VALID_KEY_A, registry);

        String plaintext = "deterministic?";
        String ct1 = enc.encrypt(plaintext);
        String ct2 = enc.encrypt(plaintext);

        // Random IV means different ciphertexts every time
        assertThat(ct1).isNotEqualTo(ct2);

        // Both decrypt back to the same input
        assertThat(enc.decrypt(ct1)).isEqualTo(plaintext);
        assertThat(enc.decrypt(ct2)).isEqualTo(plaintext);
    }

    // --- disabled ---

    @Test
    void disabled_passthrough() {
        var registry = new SimpleMeterRegistry();
        var enc = new MessageEncryptor("", registry);

        assertThat(enc.isEnabled()).isFalse();
        assertThat(enc.encrypt("x")).isEqualTo("x");
        assertThat(enc.decrypt("x")).isEqualTo("x");
    }

    // --- null input ---

    @Test
    void nullInput_returnsNull() {
        var registry = new SimpleMeterRegistry();

        // enabled encryptor
        var enabled = new MessageEncryptor(VALID_KEY_A, registry);
        assertThat(enabled.encrypt(null)).isNull();
        assertThat(enabled.decrypt(null)).isNull();

        // disabled encryptor
        var disabled = new MessageEncryptor("", registry);
        assertThat(disabled.encrypt(null)).isNull();
        assertThat(disabled.decrypt(null)).isNull();
    }

    // --- fail-open: decrypt garbage ---

    @Test
    void decrypt_garbage_failsOpen_andCounts() {
        var registry = new SimpleMeterRegistry();
        var enc = new MessageEncryptor(VALID_KEY_A, registry);

        String garbage = "!!!not-base64!!!";
        String result = enc.decrypt(garbage);

        // fail-open: returns the input unchanged
        assertThat(result).isEqualTo(garbage);

        // counter incremented
        double count = registry.get("chatflow.encryption.failures")
                .tag("operation", "decrypt")
                .counter().count();
        assertThat(count).isEqualTo(1.0);
    }

    // --- fail-open: wrong key ---

    @Test
    void decrypt_wrongKey_failsOpen_andCounts() {
        var registryA = new SimpleMeterRegistry();
        var encA = new MessageEncryptor(VALID_KEY_A, registryA);

        var registryB = new SimpleMeterRegistry();
        var encB = new MessageEncryptor(VALID_KEY_B, registryB);

        String ciphertext = encA.encrypt("secret");

        // Decrypt with wrong key -> GCM tag mismatch -> fail-open
        String result = encB.decrypt(ciphertext);
        assertThat(result).isEqualTo(ciphertext);

        double count = registryB.get("chatflow.encryption.failures")
                .tag("operation", "decrypt")
                .counter().count();
        assertThat(count).isEqualTo(1.0);
    }

    // --- fail-open: invalid key length ---

    @Test
    void encrypt_invalidKeyLength_failsOpen_andCounts() {
        var registry = new SimpleMeterRegistry();
        // 5 bytes is not a valid AES key length (must be 16, 24, or 32)
        String badKey = Base64.getEncoder().encodeToString(new byte[5]);
        var enc = new MessageEncryptor(badKey, registry);

        // isEnabled is true (key is non-blank), but encrypt will fail
        assertThat(enc.isEnabled()).isTrue();

        String result = enc.encrypt("hello");
        // fail-open: returns plaintext
        assertThat(result).isEqualTo("hello");

        double count = registry.get("chatflow.encryption.failures")
                .tag("operation", "encrypt")
                .counter().count();
        assertThat(count).isEqualTo(1.0);
    }

    // --- null registry ---

    @Test
    void noRegistry_isNullSafe() {
        var enc = new MessageEncryptor(VALID_KEY_A, null);

        // Round-trip works without NPE
        String ct = enc.encrypt("test");
        assertThat(enc.decrypt(ct)).isEqualTo("test");

        // Fail-open path without NPE (no counter to increment)
        String garbage = "!!!garbage!!!";
        String result = enc.decrypt(garbage);
        assertThat(result).isEqualTo(garbage);
    }
}
