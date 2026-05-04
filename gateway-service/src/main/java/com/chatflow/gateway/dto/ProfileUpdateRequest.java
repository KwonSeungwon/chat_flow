package com.chatflow.gateway.dto;

/**
 * Partial update semantics:
 *  - null = 변경 없음 (기존 값 유지)
 *  - 빈 문자열 ""  = 명시적 비우기 (DB NULL 저장)
 */
public record ProfileUpdateRequest(
        String profileImageUrl,
        String statusMessage,
        String bio
) {
    public static final int MAX_PROFILE_IMAGE_URL_LENGTH = 512;
    public static final int MAX_STATUS_MESSAGE_LENGTH = 100;
    public static final int MAX_BIO_LENGTH = 300;

    /**
     * 검증 — 위반 시 IllegalArgumentException.
     * null은 통과 (변경 없음). 빈 문자열도 통과 (명시적 비우기).
     */
    public void validate() {
        if (profileImageUrl != null && !profileImageUrl.isEmpty()) {
            if (profileImageUrl.length() > MAX_PROFILE_IMAGE_URL_LENGTH) {
                throw new IllegalArgumentException("profileImageUrl too long (max " + MAX_PROFILE_IMAGE_URL_LENGTH + ")");
            }
            if (!profileImageUrl.startsWith("http://") && !profileImageUrl.startsWith("https://") && !profileImageUrl.startsWith("/")) {
                throw new IllegalArgumentException("profileImageUrl must be http(s) URL or absolute path");
            }
        }
        if (statusMessage != null && statusMessage.length() > MAX_STATUS_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("statusMessage too long (max " + MAX_STATUS_MESSAGE_LENGTH + ")");
        }
        if (bio != null && bio.length() > MAX_BIO_LENGTH) {
            throw new IllegalArgumentException("bio too long (max " + MAX_BIO_LENGTH + ")");
        }
    }
}
