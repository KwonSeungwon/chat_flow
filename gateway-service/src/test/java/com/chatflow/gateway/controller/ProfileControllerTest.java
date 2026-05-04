package com.chatflow.gateway.controller;

import com.chatflow.gateway.dto.ProfileResponse;
import com.chatflow.gateway.dto.ProfileUpdateRequest;
import com.chatflow.gateway.entity.UserEntity;
import com.chatflow.gateway.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProfileControllerTest {

    private static final String USER_ID = "user-1";
    private static final String USERNAME = "alice";

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ProfileController controller;

    private UserEntity baseUser;

    @BeforeEach
    void setUp() {
        baseUser = UserEntity.builder()
                .seq(1L)
                .userId(USER_ID)
                .username(USERNAME)
                .role("NURSE")
                .profileImageUrl("https://cdn/avatar.png")
                .statusMessage(null)
                .bio(null)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private ServerHttpRequest reqWithUserId(String userId) {
        MockServerHttpRequest.BaseBuilder<?> b = MockServerHttpRequest.get("/api/users/me");
        if (userId != null) b.header("X-User-Id", userId);
        return b.build();
    }

    @Test
    void getMe_authenticated_returnsProfile() {
        when(userRepository.findByUserId(USER_ID)).thenReturn(Mono.just(baseUser));

        StepVerifier.create(controller.getMe(reqWithUserId(USER_ID)))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    ProfileResponse body = response.getBody();
                    assertEquals(USER_ID, body.userId());
                    assertEquals("https://cdn/avatar.png", body.profileImageUrl());
                })
                .verifyComplete();
    }

    @Test
    void getMe_missingHeader_returns401() {
        StepVerifier.create(controller.getMe(reqWithUserId(null)))
                .assertNext(response -> assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void getMe_userNotFound_returns404() {
        when(userRepository.findByUserId(USER_ID)).thenReturn(Mono.empty());

        StepVerifier.create(controller.getMe(reqWithUserId(USER_ID)))
                .assertNext(response -> assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void updateMe_partialUpdate_appliesOnlyProvidedFields() {
        when(userRepository.findByUserId(USER_ID)).thenReturn(Mono.just(baseUser));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        ProfileUpdateRequest body = new ProfileUpdateRequest(null, "in a meeting", null);

        StepVerifier.create(controller.updateMe(reqWithUserId(USER_ID), body))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("in a meeting", response.getBody().statusMessage());
                    assertEquals("https://cdn/avatar.png", response.getBody().profileImageUrl());
                })
                .verifyComplete();

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());
        assertEquals("in a meeting", captor.getValue().getStatusMessage());
        assertEquals("https://cdn/avatar.png", captor.getValue().getProfileImageUrl());
    }

    @Test
    void updateMe_emptyString_clearsField() {
        baseUser.setStatusMessage("old");
        when(userRepository.findByUserId(USER_ID)).thenReturn(Mono.just(baseUser));
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        ProfileUpdateRequest body = new ProfileUpdateRequest(null, "", null);

        StepVerifier.create(controller.updateMe(reqWithUserId(USER_ID), body))
                .assertNext(response -> assertNull(response.getBody().statusMessage()))
                .verifyComplete();
    }

    @Test
    void updateMe_validationFails_returns400() {
        String tooLong = "x".repeat(101);
        ProfileUpdateRequest body = new ProfileUpdateRequest(null, tooLong, null);

        StepVerifier.create(controller.updateMe(reqWithUserId(USER_ID), body))
                .assertNext(response -> assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode()))
                .verifyComplete();

        verify(userRepository, never()).save(any());
    }

    @Test
    void updateMe_invalidUrl_returns400() {
        ProfileUpdateRequest body = new ProfileUpdateRequest("javascript:alert(1)", null, null);

        StepVerifier.create(controller.updateMe(reqWithUserId(USER_ID), body))
                .assertNext(response -> assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void updateMe_missingHeader_returns401() {
        ProfileUpdateRequest body = new ProfileUpdateRequest(null, "msg", null);

        StepVerifier.create(controller.updateMe(reqWithUserId(null), body))
                .assertNext(response -> assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void getById_authenticatedCaller_returnsTargetProfile() {
        when(userRepository.findByUserId("target-user")).thenReturn(Mono.just(baseUser));

        StepVerifier.create(controller.getById("target-user", reqWithUserId("caller-id")))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals(USER_ID, response.getBody().userId());
                })
                .verifyComplete();
    }

    @Test
    void getById_missingCallerHeader_returns401() {
        StepVerifier.create(controller.getById("target-user", reqWithUserId(null)))
                .assertNext(response -> assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode()))
                .verifyComplete();
    }
}
