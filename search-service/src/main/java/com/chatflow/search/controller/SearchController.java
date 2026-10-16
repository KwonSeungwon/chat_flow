package com.chatflow.search.controller;

import com.chatflow.search.client.RoomMembershipClient;
import com.chatflow.search.document.ChatMessageDocument;
import com.chatflow.search.exception.RoomAccessDeniedException;
import com.chatflow.search.exception.UnauthenticatedException;
import com.chatflow.search.service.KoreanSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Chat message search. Caller identity is the gateway-injected
 * {@code X-User-Id}; room-scoped endpoints require membership (403) and
 * global search is restricted to the caller's member rooms.
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final KoreanSearchService koreanSearchService;
    private final RoomMembershipClient roomMembershipClient;

    private static final int MAX_PAGE_SIZE = 100;

    @GetMapping("/messages")
    public ResponseEntity<Page<ChatMessageDocument>> searchMessages(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestParam String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        requireUser(userId);
        validateSearchParams(query, page, size);
        Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
        return ResponseEntity.ok(searchKoreanScoped(userId, query, null, pageable));
    }

    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<Page<ChatMessageDocument>> searchInRoom(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @PathVariable String roomId,
            @RequestParam String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        requireUser(userId);
        validateSearchParams(query, page, size);
        requireMember(roomId, userId);
        Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
        Page<ChatMessageDocument> results = koreanSearchService.searchKoreanContent(query, roomId, pageable);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/rooms/{roomId}/users")
    public ResponseEntity<Page<ChatMessageDocument>> searchByUser(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @PathVariable String roomId,
            @RequestParam String username,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        requireUser(userId);
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username은 필수입니다.");
        }
        requireMember(roomId, userId);
        Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
        String q = (query != null && !query.isBlank()) ? query : null;
        Page<ChatMessageDocument> results = koreanSearchService.searchWithFilters(roomId, q, username, null, null, null, pageable);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/rooms/{roomId}/time-range")
    public ResponseEntity<Page<ChatMessageDocument>> searchByTimeRange(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @PathVariable String roomId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        requireUser(userId);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("시작 시간은 종료 시간보다 이전이어야 합니다.");
        }
        requireMember(roomId, userId);
        Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
        String q = (query != null && !query.isBlank()) ? query : null;
        String u = (username != null && !username.isBlank()) ? username : null;
        Page<ChatMessageDocument> results = koreanSearchService.searchWithFilters(roomId, q, u, start, end, null, pageable);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/korean")
    public ResponseEntity<Page<ChatMessageDocument>> searchKorean(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestParam String query,
            @RequestParam(required = false) String roomId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        requireUser(userId);
        validateSearchParams(query, page, size);
        Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
        return ResponseEntity.ok(searchKoreanScoped(userId, query, roomId, pageable));
    }

    @GetMapping("/ngram")
    public ResponseEntity<Page<ChatMessageDocument>> searchNgram(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestParam String query,
            @RequestParam(required = false) String roomId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        requireUser(userId);
        validateSearchParams(query, page, size);
        Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
        if (hasRoom(roomId)) {
            requireMember(roomId, userId);
            return ResponseEntity.ok(koreanSearchService.searchWithNgram(query, roomId, pageable));
        }
        List<String> roomIds = roomMembershipClient.memberRoomIds(userId);
        if (roomIds.isEmpty()) {
            return ResponseEntity.ok(Page.empty(pageable));
        }
        return ResponseEntity.ok(koreanSearchService.searchWithNgramInRooms(query, roomIds, pageable));
    }

    @GetMapping("/rooms/{roomId}/filter")
    public ResponseEntity<Page<ChatMessageDocument>> filterSearch(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @PathVariable String roomId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String username,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(required = false) String messageType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        requireUser(userId);
        boolean hasAny = (query != null && !query.isBlank())
                || (username != null && !username.isBlank())
                || startDate != null
                || endDate != null
                || (messageType != null && !messageType.isBlank());
        if (!hasAny) {
            throw new IllegalArgumentException("최소 하나의 검색 조건이 필요합니다.");
        }
        if ((startDate != null) != (endDate != null)) {
            throw new IllegalArgumentException("startDate와 endDate는 함께 제공되어야 합니다.");
        }
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("시작 시간은 종료 시간보다 이전이어야 합니다.");
        }
        requireMember(roomId, userId);

        Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
        Page<ChatMessageDocument> results = koreanSearchService.searchWithFilters(
                roomId, query, username, startDate, endDate, messageType, pageable);
        return ResponseEntity.ok(results);
    }

    /**
     * Korean search scoped to one room (membership required) or, without a
     * room, to all of the caller's member rooms.
     */
    private Page<ChatMessageDocument> searchKoreanScoped(
            String userId, String query, String roomId, Pageable pageable) {
        if (hasRoom(roomId)) {
            requireMember(roomId, userId);
            return koreanSearchService.searchKoreanContent(query, roomId, pageable);
        }
        List<String> roomIds = roomMembershipClient.memberRoomIds(userId);
        if (roomIds.isEmpty()) {
            return Page.empty(pageable);
        }
        return koreanSearchService.searchKoreanContentInRooms(query, roomIds, pageable);
    }

    private static boolean hasRoom(String roomId) {
        return roomId != null && !roomId.isEmpty();
    }

    private static void requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new UnauthenticatedException("X-User-Id 헤더 누락");
        }
    }

    private void requireMember(String roomId, String userId) {
        if (!roomMembershipClient.isMember(roomId, userId)) {
            throw new RoomAccessDeniedException("user " + userId + " is not a member of room " + roomId);
        }
    }

    private void validateSearchParams(String query, int page, int size) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("검색어는 필수입니다.");
        }
        if (page < 0) {
            throw new IllegalArgumentException("페이지 번호는 0 이상이어야 합니다.");
        }
        if (size < 1) {
            throw new IllegalArgumentException("페이지 크기는 1 이상이어야 합니다.");
        }
    }
}
