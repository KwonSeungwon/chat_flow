package com.gossiplog.gateway.security.oauth;

import com.gossiplog.gateway.entity.SocialAccountEntity;
import com.gossiplog.gateway.entity.UserEntity;
import com.gossiplog.gateway.repository.SocialAccountRepository;
import com.gossiplog.gateway.repository.UserRepository;
import com.gossiplog.gateway.security.AuthService;
import com.gossiplog.gateway.security.AuthService.AuthResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 소셜 계정으로 로그인한다. 연결된 사용자가 있으면 그 사용자로, 없으면 비밀번호 없는 사용자를 새로 만들어 연결한다.
 * 이메일 등으로 기존 계정에 자동 연결하지 않는다.
 */
@Slf4j
@Service
public class SocialLoginService {

    private static final int USERNAME_ATTEMPTS = 5;

    private final SocialAccountRepository accounts;
    private final UserRepository users;
    private final AuthService authService;

    public SocialLoginService(SocialAccountRepository accounts, UserRepository users, AuthService authService) {
        this.accounts = accounts;
        this.users = users;
        this.authService = authService;
    }

    public Mono<AuthResponse> login(SocialProfile profile) {
        return accounts.findByProviderAndProviderUserId(profile.provider(), profile.providerUserId())
                .flatMap(this::existingUser)
                .switchIfEmpty(Mono.defer(() -> register(profile)))
                .flatMap(authService::issueSession);
    }

    private Mono<UserEntity> existingUser(SocialAccountEntity account) {
        return accounts.touchLastLogin(account.getProvider(), account.getProviderUserId(), LocalDateTime.now())
                .then(users.findByUserId(account.getUserId()))
                .switchIfEmpty(Mono.error(() -> new IllegalStateException(
                        "소셜 계정에 연결된 사용자가 없다: " + account.getProvider())));
    }

    private Mono<UserEntity> register(SocialProfile profile) {
        String base = SocialUsernames.base(profile.displayName());
        return createUser(base, 0)
                .flatMap(user -> accounts.save(SocialAccountEntity.builder()
                                .userId(user.getUserId())
                                .provider(profile.provider())
                                .providerUserId(profile.providerUserId())
                                .createdAt(LocalDateTime.now())
                                .lastLoginAt(LocalDateTime.now())
                                .build())
                        .thenReturn(user)
                        // 같은 제공자 계정의 첫 로그인이 동시에 들어와 연결이 UNIQUE에 걸리면, 방금 만든 사용자를 지우고 먼저 연결된 쪽으로 로그인한다.
                        .onErrorResume(DataIntegrityViolationException.class, e -> users.deleteByUserId(user.getUserId())
                                .then(accounts.findByProviderAndProviderUserId(profile.provider(), profile.providerUserId()))
                                .flatMap(this::existingUser)));
    }

    private Mono<UserEntity> createUser(String base, int attempt) {
        if (attempt >= USERNAME_ATTEMPTS) {
            return Mono.error(new IllegalStateException("소셜 가입 아이디를 정하지 못했다: " + base));
        }
        String username = SocialUsernames.candidate(base, attempt);
        return users.existsByUsernameIgnoreCase(username)
                .flatMap(taken -> taken
                        ? createUser(base, attempt + 1)
                        : users.save(UserEntity.builder()
                                        .userId(UUID.randomUUID().toString())
                                        .username(username)
                                        .createdAt(LocalDateTime.now())
                                        .build())
                                // 확인과 저장 사이에 같은 이름이 들어오면 다음 후보로 넘어간다.
                                .onErrorResume(DataIntegrityViolationException.class, e -> createUser(base, attempt + 1)));
    }
}
