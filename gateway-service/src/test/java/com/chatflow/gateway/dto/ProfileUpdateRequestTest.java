package com.chatflow.gateway.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProfileUpdateRequestTest {

    private static ProfileUpdateRequest img(String url) {
        return new ProfileUpdateRequest(url, null, null);
    }

    @Test
    void acceptsNullEmptyHttpsAndUploadPath() {
        assertDoesNotThrow(() -> img(null).validate());
        assertDoesNotThrow(() -> img("").validate());
        assertDoesNotThrow(() -> img("https://cdn/a.png").validate());
        assertDoesNotThrow(() -> img("/api/files/3f2b8c1e-aaaa").validate());
    }

    @Test
    void rejectsProtocolRelativeHttpAndForeignPaths() {
        for (String bad : new String[] {"//evil.com/a.png", "http://cdn/a.png", "/avatar.png", "javascript:x"}) {
            assertThrows(IllegalArgumentException.class, () -> img(bad).validate(), bad);
        }
    }
}
