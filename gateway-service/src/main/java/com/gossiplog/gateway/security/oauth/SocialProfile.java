package com.gossiplog.gateway.security.oauth;

import com.gossiplog.gateway.entity.SocialProvider;

/** 제공자 응답에서 우리가 쓰는 값만 뽑은 것. displayName은 동의하지 않았으면 null이다. */
public record SocialProfile(SocialProvider provider, String providerUserId, String displayName) {}
