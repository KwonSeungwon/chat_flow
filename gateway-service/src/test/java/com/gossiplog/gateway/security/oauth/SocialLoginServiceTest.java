package com.gossiplog.gateway.security.oauth;

import com.gossiplog.gateway.entity.SocialAccountEntity;
import com.gossiplog.gateway.entity.SocialProvider;
import com.gossiplog.gateway.entity.UserEntity;
import com.gossiplog.gateway.repository.SocialAccountRepository;
import com.gossiplog.gateway.repository.UserRepository;
import com.gossiplog.gateway.security.AuthService;
import com.gossiplog.gateway.security.AuthService.AuthResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SocialLoginServiceTest {

    @Mock private SocialAccountRepository accounts;
    @Mock private UserRepository users;
    @Mock private AuthService authService;
    private SocialLoginService service;

    private final SocialProfile kakao = new SocialProfile(SocialProvider.KAKAO, "4012", "홍길동");

    @BeforeEach
    void setUp() {
        service = new SocialLoginService(accounts, users, authService);
        lenient().when(authService.issueSession(any())).thenAnswer(inv -> {
            UserEntity u = inv.getArgument(0);
            return Mono.just(new AuthResponse("jwt-" + u.getUserId(), u.getUserId(), u.getUsername(), u.getProfileImageUrl()));
        });
        lenient().when(accounts.touchLastLogin(any(), anyString(), any())).thenReturn(Mono.just(1));
    }

    private static UserEntity user(String id, String name) {
        return UserEntity.builder().userId(id).username(name).createdAt(LocalDateTime.now()).build();
    }

    @Test
    void linkedAccount_logsInExistingUser_andTouchesLastLogin() {
        when(accounts.findByProviderAndProviderUserId(SocialProvider.KAKAO, "4012"))
                .thenReturn(Mono.just(SocialAccountEntity.builder().userId("u1").provider(SocialProvider.KAKAO).providerUserId("4012").build()));
        when(users.findByUserId("u1")).thenReturn(Mono.just(user("u1", "기존이름")));

        StepVerifier.create(service.login(kakao))
                .assertNext(r -> { assertEquals("u1", r.userId()); assertEquals("기존이름", r.username()); })
                .verifyComplete();
        verify(accounts).touchLastLogin(eq(SocialProvider.KAKAO), eq("4012"), any());
        verify(users, never()).save(any());
    }

    @Test
    void linkedAccountWithMissingUser_isAnError_notEmpty() {
        // 빈 결과로 끝나면 성공 핸들러가 리다이렉트 없이 끝난다. 에러로 바꿔 실패 경로를 타게 한다.
        when(accounts.findByProviderAndProviderUserId(SocialProvider.KAKAO, "4012"))
                .thenReturn(Mono.just(SocialAccountEntity.builder().userId("gone").provider(SocialProvider.KAKAO).providerUserId("4012").build()));
        when(users.findByUserId("gone")).thenReturn(Mono.empty());

        StepVerifier.create(service.login(kakao)).expectError(IllegalStateException.class).verify();
    }

    @Test
    void newAccount_createsPasswordlessUserFromNickname_andLinksIt() {
        when(accounts.findByProviderAndProviderUserId(SocialProvider.KAKAO, "4012")).thenReturn(Mono.empty());
        when(users.existsByUsernameIgnoreCase("홍길동")).thenReturn(Mono.just(false));
        when(users.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(accounts.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        ArgumentCaptor<UserEntity> saved = ArgumentCaptor.forClass(UserEntity.class);
        ArgumentCaptor<SocialAccountEntity> link = ArgumentCaptor.forClass(SocialAccountEntity.class);

        StepVerifier.create(service.login(kakao))
                .assertNext(r -> assertEquals("홍길동", r.username()))
                .verifyComplete();

        verify(users).save(saved.capture());
        verify(accounts).save(link.capture());
        assertNull(saved.getValue().getEncodedPassword());
        assertEquals(saved.getValue().getUserId(), link.getValue().getUserId());
        assertEquals(SocialProvider.KAKAO, link.getValue().getProvider());
        assertEquals("4012", link.getValue().getProviderUserId());
    }

    @Test
    void takenNickname_getsSuffix() {
        when(accounts.findByProviderAndProviderUserId(any(), anyString())).thenReturn(Mono.empty());
        when(users.existsByUsernameIgnoreCase("홍길동")).thenReturn(Mono.just(true));
        when(users.existsByUsernameIgnoreCase(org.mockito.ArgumentMatchers.startsWith("홍길동_"))).thenReturn(Mono.just(false));
        when(users.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(accounts.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.login(kakao))
                .assertNext(r -> assertTrue(r.username().matches("^홍길동_\\d{3}$"), r.username()))
                .verifyComplete();
    }

    @Test
    void concurrentFirstLogin_linkConflict_fallsBackToWinnerAccount() {
        // 같은 제공자 계정으로 두 요청이 동시에 처음 들어오면 한쪽의 연결 저장이 UNIQUE에 걸린다.
        when(accounts.findByProviderAndProviderUserId(SocialProvider.KAKAO, "4012"))
                .thenReturn(Mono.empty())
                .thenReturn(Mono.just(SocialAccountEntity.builder().userId("winner").provider(SocialProvider.KAKAO).providerUserId("4012").build()));
        when(users.existsByUsernameIgnoreCase(anyString())).thenReturn(Mono.just(false));
        when(users.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(accounts.save(any())).thenReturn(Mono.error(new DataIntegrityViolationException("uq_social_provider_account")));
        when(users.deleteByUserId(anyString())).thenReturn(Mono.just(1));
        when(users.findByUserId("winner")).thenReturn(Mono.just(user("winner", "홍길동")));

        StepVerifier.create(service.login(kakao))
                .assertNext(r -> assertEquals("winner", r.userId()))
                .verifyComplete();
        verify(users).deleteByUserId(anyString()); // 연결에 실패한 고아 사용자는 지운다
    }
}
