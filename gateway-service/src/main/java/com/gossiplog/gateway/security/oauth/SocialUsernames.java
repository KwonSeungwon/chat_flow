package com.gossiplog.gateway.security.oauth;

import java.util.concurrent.ThreadLocalRandom;

/** 소셜 표시 이름으로 가입 규칙([A-Za-z0-9가-힣_.-]{2,20})에 맞는 아이디 후보를 만든다. */
final class SocialUsernames {

    private static final int BASE_MAX = 16; // "_" + 3자리를 붙여도 20자 이내

    private SocialUsernames() {}

    static String base(String displayName) {
        String cleaned = displayName == null ? "" : displayName.replaceAll("[^A-Za-z0-9가-힣_.-]", "");
        if (cleaned.length() > BASE_MAX) cleaned = cleaned.substring(0, BASE_MAX);
        return cleaned.length() < 2 ? "user" : cleaned;
    }

    /** attempt 0은 base 그대로, 그 뒤는 base_NNN. */
    static String candidate(String base, int attempt) {
        if (attempt == 0) return base;
        return base + "_" + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
    }
}
