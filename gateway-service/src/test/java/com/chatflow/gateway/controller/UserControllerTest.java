package com.chatflow.gateway.controller;

import com.chatflow.gateway.entity.UserEntity;
import com.chatflow.gateway.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit test for UserController.
 *
 * Verifies:
 * - /api/users/search happy path: returns user list with correct shape (userId, username, profileImageUrl)
 * - Short query (< 2 chars) returns empty list, no DB call
 * - Blank/empty query returns empty list, no DB call
 * - Results are capped at 10
 * - Null fields in UserEntity are replaced with empty strings in response
 */
@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserController controller;

    private UserEntity user1;
    private UserEntity user2;

    @BeforeEach
    void setUp() {
        user1 = UserEntity.builder()
                .seq(1L)
                .userId("user-1")
                .username("alice")
                .profileImageUrl("https://cdn/alice.png")
                .createdAt(LocalDateTime.now())
                .build();

        user2 = UserEntity.builder()
                .seq(2L)
                .userId("user-2")
                .username("alicia")
                .profileImageUrl(null)     // null → should become ""
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ── Happy path: search returns matching users ────────────────

    @Test
    void search_validQuery_returnsMatchingUsers() {
        when(userRepository.findByUsernameContainingIgnoreCaseOrderByUsernameAsc("ali"))
                .thenReturn(Flux.just(user1, user2));

        StepVerifier.create(controller.searchUsers("ali"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> data = (List<Map<String, Object>>) response.getBody().get("data");
                    assertEquals(2, data.size());

                    // First user — all fields populated
                    assertEquals("user-1", data.get(0).get("userId"));
                    assertEquals("alice", data.get(0).get("username"));
                    assertEquals("https://cdn/alice.png", data.get(0).get("profileImageUrl"));

                    // Second user — null profileImageUrl → empty string
                    assertEquals("user-2", data.get(1).get("userId"));
                    assertEquals("alicia", data.get(1).get("username"));
                    assertEquals("", data.get(1).get("profileImageUrl"));
                })
                .verifyComplete();
    }

    // ── Short query (< 2 chars) → empty list, no DB call ────────

    @Test
    void search_singleChar_returnsEmptyList() {
        StepVerifier.create(controller.searchUsers("a"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> data = (List<Map<String, Object>>) response.getBody().get("data");
                    assertTrue(data.isEmpty());
                })
                .verifyComplete();

        verifyNoInteractions(userRepository);
    }

    // ── Blank query → empty list, no DB call ────────────────────

    @Test
    void search_blankQuery_returnsEmptyList() {
        StepVerifier.create(controller.searchUsers(""))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> data = (List<Map<String, Object>>) response.getBody().get("data");
                    assertTrue(data.isEmpty());
                })
                .verifyComplete();

        verifyNoInteractions(userRepository);
    }

    @Test
    void search_whitespaceOnly_returnsEmptyList() {
        StepVerifier.create(controller.searchUsers("   "))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> data = (List<Map<String, Object>>) response.getBody().get("data");
                    assertTrue(data.isEmpty());
                })
                .verifyComplete();

        verifyNoInteractions(userRepository);
    }

    // ── Results capped at 10 ────────────────────────────────────

    @Test
    void search_moreThan10Results_takesOnly10() {
        // Repository returns 15 users
        Flux<UserEntity> fifteenUsers = Flux.range(1, 15)
                .map(i -> UserEntity.builder()
                        .seq((long) i)
                        .userId("user-" + i)
                        .username("testuser" + i)
                        .profileImageUrl(null)
                        .build());

        when(userRepository.findByUsernameContainingIgnoreCaseOrderByUsernameAsc("test"))
                .thenReturn(fifteenUsers);

        StepVerifier.create(controller.searchUsers("test"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> data = (List<Map<String, Object>>) response.getBody().get("data");
                    assertEquals(10, data.size());
                })
                .verifyComplete();
    }

    // ── No results → empty data list ────────────────────────────

    @Test
    void search_noMatch_returnsEmptyDataList() {
        when(userRepository.findByUsernameContainingIgnoreCaseOrderByUsernameAsc("zzz"))
                .thenReturn(Flux.empty());

        StepVerifier.create(controller.searchUsers("zzz"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> data = (List<Map<String, Object>>) response.getBody().get("data");
                    assertTrue(data.isEmpty());
                })
                .verifyComplete();
    }

    // ── Null fields in entity → empty strings in response ───────

    @Test
    void search_nullFields_mappedToEmptyStrings() {
        UserEntity nullFieldUser = UserEntity.builder()
                .seq(99L)
                .userId(null)          // null userId
                .username(null)        // null username
                .profileImageUrl(null) // null profileImageUrl
                .build();

        when(userRepository.findByUsernameContainingIgnoreCaseOrderByUsernameAsc("nu"))
                .thenReturn(Flux.just(nullFieldUser));

        StepVerifier.create(controller.searchUsers("nu"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> data = (List<Map<String, Object>>) response.getBody().get("data");
                    assertEquals(1, data.size());
                    assertEquals("", data.get(0).get("userId"));
                    assertEquals("", data.get(0).get("username"));
                    assertEquals("", data.get(0).get("profileImageUrl"));
                })
                .verifyComplete();
    }

    // ── Query is trimmed before passing to repository ───────────

    @Test
    void search_queryWithLeadingTrailingSpaces_isTrimmed() {
        when(userRepository.findByUsernameContainingIgnoreCaseOrderByUsernameAsc("alice"))
                .thenReturn(Flux.just(user1));

        StepVerifier.create(controller.searchUsers("  alice  "))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> data = (List<Map<String, Object>>) response.getBody().get("data");
                    assertEquals(1, data.size());
                    assertEquals("alice", data.get(0).get("username"));
                })
                .verifyComplete();

        // Verify the trimmed query was sent to repository
        verify(userRepository).findByUsernameContainingIgnoreCaseOrderByUsernameAsc("alice");
    }
}
