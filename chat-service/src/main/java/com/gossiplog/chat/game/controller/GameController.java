package com.gossiplog.chat.game.controller;

import com.gossiplog.chat.auth.AuthHeaders;
import com.gossiplog.chat.auth.AuthenticatedUser;
import com.gossiplog.chat.auth.RequireAuth;
import com.gossiplog.chat.game.dto.AnswerRequest;
import com.gossiplog.chat.game.dto.AnswerResult;
import com.gossiplog.chat.game.dto.DungeonSummary;
import com.gossiplog.chat.game.dto.GameMeResponse;
import com.gossiplog.chat.game.dto.LeaderboardEntry;
import com.gossiplog.chat.game.dto.NextEncounter;
import com.gossiplog.chat.game.dto.RunStartResponse;
import com.gossiplog.chat.game.service.GameDungeonQueryService;
import com.gossiplog.chat.game.service.GameRunService;
import com.gossiplog.chat.game.service.LeaderboardService;
import com.gossiplog.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Player API for Wiki Dungeon. */
@RestController
@RequestMapping("/api/game")
@RequiredArgsConstructor
public class GameController {

    private final GameRunService runService;
    private final GameDungeonQueryService dungeonQueryService;
    private final LeaderboardService leaderboardService;

    @RequireAuth
    @GetMapping("/dungeons")
    public ResponseEntity<ApiResponse<List<DungeonSummary>>> dungeons(@AuthenticatedUser String userId) {
        return ResponseEntity.ok(ApiResponse.ok(dungeonQueryService.dungeons(userId)));
    }

    @RequireAuth
    @PostMapping("/dungeons/{id}/runs")
    public ResponseEntity<ApiResponse<RunStartResponse>> start(@AuthenticatedUser String userId,
                                                               @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(runService.start(userId, id)));
    }

    @RequireAuth
    @GetMapping("/runs/{runId}/next")
    public ResponseEntity<ApiResponse<NextEncounter>> next(@AuthenticatedUser String userId,
                                                           @PathVariable String runId) {
        return ResponseEntity.ok(ApiResponse.ok(runService.next(userId, runId)));
    }

    @RequireAuth
    @PostMapping("/runs/{runId}/answers")
    public ResponseEntity<ApiResponse<AnswerResult>> answer(
            @AuthenticatedUser String userId,
            @RequestHeader(value = AuthHeaders.X_USERNAME, required = false) String username,
            @PathVariable String runId,
            @RequestBody AnswerRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(runService.answer(userId, username, runId, request)));
    }

    @RequireAuth
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<GameMeResponse>> me(@AuthenticatedUser String userId) {
        return ResponseEntity.ok(ApiResponse.ok(dungeonQueryService.me(userId)));
    }

    @RequireAuth
    @GetMapping("/leaderboard")
    public ResponseEntity<ApiResponse<List<LeaderboardEntry>>> leaderboard(@AuthenticatedUser String userId) {
        return ResponseEntity.ok(ApiResponse.ok(leaderboardService.top()));
    }
}
