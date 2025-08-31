package com.chatflow.search.controller;

import com.chatflow.search.document.ChatMessageDocument;
import com.chatflow.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @GetMapping("/messages")
    public ResponseEntity<Page<ChatMessageDocument>> searchMessages(
            @RequestParam String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Page<ChatMessageDocument> results = searchService.searchByContent(query, page, size);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<Page<ChatMessageDocument>> searchInRoom(
            @PathVariable String roomId,
            @RequestParam String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Page<ChatMessageDocument> results = searchService.searchInChatRoom(roomId, query, page, size);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/rooms/{roomId}/users")
    public ResponseEntity<Page<ChatMessageDocument>> searchByUser(
            @PathVariable String roomId,
            @RequestParam String username,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Page<ChatMessageDocument> results = searchService.searchByUsername(roomId, username, page, size);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/rooms/{roomId}/time-range")
    public ResponseEntity<Page<ChatMessageDocument>> searchByTimeRange(
            @PathVariable String roomId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Page<ChatMessageDocument> results = searchService.searchByTimeRange(roomId, start, end, page, size);
        return ResponseEntity.ok(results);
    }
}