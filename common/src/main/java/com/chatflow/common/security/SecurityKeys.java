package com.chatflow.common.security;

/**
 * 보안 관련 Redis key 상수. 여러 서비스에서 공유.
 * 변경 시 모든 의존 서비스 동시 배포 필수.
 */
public final class SecurityKeys {
    /** JTI blacklist key prefix -- gateway가 SET, chat-service가 hasKey. */
    public static final String BLACKLIST_PREFIX = "chatflow:blacklist:";

    /** 사용자별 활성 jti tracking -- gateway만 사용. */
    public static final String ACTIVE_JTI_PREFIX = "chatflow:user:active_jti:";

    private SecurityKeys() {}
}
