package com.chatflow.chat.game.controller;

import com.chatflow.chat.auth.AuthenticatedUser;
import com.chatflow.chat.auth.RequireAuth;
import com.chatflow.chat.game.dto.AdminQuestionDto;
import com.chatflow.chat.game.dto.AdminQuestionPage;
import com.chatflow.chat.game.dto.BulkUploadRequest;
import com.chatflow.chat.game.dto.BulkUploadResult;
import com.chatflow.chat.game.dto.QuestionPatchRequest;
import com.chatflow.chat.game.service.GameAdminGuard;
import com.chatflow.chat.game.service.GameQuestionService;
import com.chatflow.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Question bank admin API. Only callers on GAME_ADMIN_USER_IDS are allowed. */
@RestController
@RequestMapping("/api/game/admin/questions")
@RequiredArgsConstructor
public class GameAdminController {

    private final GameAdminGuard adminGuard;
    private final GameQuestionService questionService;

    @RequireAuth
    @PostMapping("/bulk")
    public ResponseEntity<ApiResponse<BulkUploadResult>> bulkUpload(
            @AuthenticatedUser String userId,
            @RequestBody BulkUploadRequest request) {
        adminGuard.requireAdmin(userId);
        return ResponseEntity.ok(ApiResponse.ok(questionService.bulkUpload(request)));
    }

    @RequireAuth
    @GetMapping
    public ResponseEntity<ApiResponse<AdminQuestionPage>> list(
            @AuthenticatedUser String userId,
            @RequestParam(defaultValue = "DRAFT") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        adminGuard.requireAdmin(userId);
        return ResponseEntity.ok(ApiResponse.ok(questionService.list(status, page, size)));
    }

    @RequireAuth
    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<AdminQuestionDto>> patch(
            @AuthenticatedUser String userId,
            @PathVariable Long id,
            @RequestBody QuestionPatchRequest request) {
        adminGuard.requireAdmin(userId);
        return ResponseEntity.ok(ApiResponse.ok(questionService.patch(id, request)));
    }
}
