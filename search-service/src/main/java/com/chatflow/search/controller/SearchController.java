package com.chatflow.search.controller;

import com.chatflow.search.document.ChatMessageDocument;
import com.chatflow.search.service.SearchService;
import com.chatflow.search.service.KoreanSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;
    private final KoreanSearchService koreanSearchService;

    private static final int MAX_PAGE_SIZE = 100;

    @GetMapping("/messages")
    public ResponseEntity<Page<ChatMessageDocument>> searchMessages(
            @RequestParam String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        validateSearchParams(query, page, size);
        Page<ChatMessageDocument> results = searchService.searchByContent(query, page, Math.min(size, MAX_PAGE_SIZE));
        return ResponseEntity.ok(results);
    }

    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<Page<ChatMessageDocument>> searchInRoom(
            @PathVariable String roomId,
            @RequestParam String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        validateSearchParams(query, page, size);
        Page<ChatMessageDocument> results = searchService.searchInChatRoom(roomId, query, page, Math.min(size, MAX_PAGE_SIZE));
        return ResponseEntity.ok(results);
    }

    @GetMapping("/rooms/{roomId}/users")
    public ResponseEntity<Page<ChatMessageDocument>> searchByUser(
            @PathVariable String roomId,
            @RequestParam String username,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username은 필수입니다.");
        }
        Page<ChatMessageDocument> results = searchService.searchByUsername(roomId, username, page, Math.min(size, MAX_PAGE_SIZE));
        return ResponseEntity.ok(results);
    }

    @GetMapping("/rooms/{roomId}/time-range")
    public ResponseEntity<Page<ChatMessageDocument>> searchByTimeRange(
            @PathVariable String roomId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        if (start.isAfter(end)) {
            throw new IllegalArgumentException("시작 시간은 종료 시간보다 이전이어야 합니다.");
        }
        Page<ChatMessageDocument> results = searchService.searchByTimeRange(roomId, start, end, page, Math.min(size, MAX_PAGE_SIZE));
        return ResponseEntity.ok(results);
    }

    @GetMapping("/korean")
    public ResponseEntity<Page<ChatMessageDocument>> searchKorean(
            @RequestParam String query,
            @RequestParam(required = false) String roomId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        validateSearchParams(query, page, size);
        Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
        Page<ChatMessageDocument> results = koreanSearchService.searchKoreanContent(query, roomId, pageable);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/ngram")
    public ResponseEntity<Page<ChatMessageDocument>> searchNgram(
            @RequestParam String query,
            @RequestParam(required = false) String roomId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        validateSearchParams(query, page, size);
        Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
        Page<ChatMessageDocument> results = koreanSearchService.searchWithNgram(query, roomId, pageable);
        return ResponseEntity.ok(results);
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
