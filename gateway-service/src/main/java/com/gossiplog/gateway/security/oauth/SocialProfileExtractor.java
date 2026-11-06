package com.gossiplog.gateway.security.oauth;

import com.gossiplog.gateway.entity.SocialProvider;

import java.util.Map;

/**
 * 제공자별 사용자 정보 응답에서 고유 ID와 표시 이름을 꺼낸다.
 * google: OIDC sub/name. naver: response.id/response.nickname. kakao: id(Long)/kakao_account.profile.nickname.
 */
public final class SocialProfileExtractor {

    private SocialProfileExtractor() {}

    public static SocialProfile extract(String registrationId, Map<String, Object> attrs) {
        return switch (registrationId) {
            case "google" -> profile(SocialProvider.GOOGLE, attrs.get("sub"), attrs.get("name"));
            case "naver" -> {
                Map<?, ?> r = map(attrs.get("response"));
                yield profile(SocialProvider.NAVER, r.get("id"), r.get("nickname"));
            }
            case "kakao" -> {
                Object nickname = map(map(attrs.get("kakao_account")).get("profile")).get("nickname");
                if (nickname == null) nickname = map(attrs.get("properties")).get("nickname");
                yield profile(SocialProvider.KAKAO, attrs.get("id"), nickname);
            }
            default -> throw new IllegalArgumentException("지원하지 않는 소셜 로그인: " + registrationId);
        };
    }

    private static SocialProfile profile(SocialProvider provider, Object id, Object name) {
        String idText = id == null ? "" : String.valueOf(id).trim();
        if (idText.isEmpty()) {
            throw new IllegalArgumentException(provider + " 응답에 사용자 ID가 없다");
        }
        String nameText = name == null ? null : String.valueOf(name).trim();
        return new SocialProfile(provider, idText, nameText == null || nameText.isEmpty() ? null : nameText);
    }

    private static Map<?, ?> map(Object o) {
        return o instanceof Map<?, ?> m ? m : Map.of();
    }
}
