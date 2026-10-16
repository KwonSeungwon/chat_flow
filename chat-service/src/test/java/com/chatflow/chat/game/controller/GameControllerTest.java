package com.chatflow.chat.game.controller;

import com.chatflow.chat.auth.AuthInterceptor;
import com.chatflow.chat.auth.AuthenticatedUserResolver;
import com.chatflow.chat.controller.RoomMembershipGuard;
import com.chatflow.chat.exception.GlobalExceptionHandler;
import com.chatflow.chat.game.dto.AnswerRequest;
import com.chatflow.chat.game.dto.AnswerResult;
import com.chatflow.chat.game.dto.DungeonSummary;
import com.chatflow.chat.game.dto.GameMeResponse;
import com.chatflow.chat.game.dto.LeaderboardEntry;
import com.chatflow.chat.game.dto.NextEncounter;
import com.chatflow.chat.game.dto.RunStartResponse;
import com.chatflow.chat.game.exception.GameException;
import com.chatflow.chat.game.service.GameDungeonQueryService;
import com.chatflow.chat.game.service.GameRunService;
import com.chatflow.chat.game.service.LeaderboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class GameControllerTest {

    private static final String USER = "user-1";

    @Mock
    private GameRunService runService;
    @Mock
    private GameDungeonQueryService dungeonQueryService;
    @Mock
    private LeaderboardService leaderboardService;
    @Mock
    private RoomMembershipGuard membershipGuard;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        GameController controller = new GameController(runService, dungeonQueryService, leaderboardService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticatedUserResolver())
                .addInterceptors(new AuthInterceptor(membershipGuard))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void missingUserHeader_returns401_onEveryEndpoint() throws Exception {
        mockMvc.perform(get("/api/game/dungeons")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/game/dungeons/jvm/runs")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/game/runs/r1/next")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/game/runs/r1/answers")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seq\":1,\"choiceIndex\":0}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/game/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/game/leaderboard")).andExpect(status().isUnauthorized());
        verifyNoInteractions(runService, dungeonQueryService, leaderboardService);
    }

    @Test
    void next_hasNoAnswerIndex() throws Exception {
        when(runService.next(USER, "r1")).thenReturn(new NextEncounter(3,
                new NextEncounter.Monster("OOM 슬라임", "monster_jvm", false),
                new NextEncounter.Question(11L, "stem", List.of("a", "b", "c", "d"), "GC")));

        mockMvc.perform(get("/api/game/runs/r1/next").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seq").value(3))
                .andExpect(jsonPath("$.data.monster.isBoss").value(false))
                .andExpect(jsonPath("$.data.monster.spriteKey").value("monster_jvm"))
                .andExpect(jsonPath("$.data.question.choices[3]").value("d"))
                .andExpect(jsonPath("$.data.question.topicTitle").value("GC"))
                .andExpect(jsonPath("$..answerIndex").doesNotExist())
                .andExpect(jsonPath("$..answer_index").doesNotExist());
    }

    @Test
    void start_closedDungeon_returns409() throws Exception {
        when(runService.start(USER, "jvm"))
                .thenThrow(new GameException(409, "DUNGEON_CLOSED", "공사 중인 건물입니다."));

        mockMvc.perform(post("/api/game/dungeons/jvm/runs").header("X-User-Id", USER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUNGEON_CLOSED"));
    }

    @Test
    void start_returnsRun() throws Exception {
        when(runService.start(USER, "jvm")).thenReturn(new RunStartResponse("r1", 3, 0, 5));

        mockMvc.perform(post("/api/game/dungeons/jvm/runs").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("r1"))
                .andExpect(jsonPath("$.data.hearts").value(3))
                .andExpect(jsonPath("$.data.killsToBoss").value(5));
    }

    @Test
    void answer_passesUsernameHeader() throws Exception {
        when(runService.answer(eq(USER), eq("앨리스"), eq("r1"), any(AnswerRequest.class)))
                .thenReturn(new AnswerResult(true, 2, "exp", "GC", "0. Wiki", 3, 1, false, false, false));

        mockMvc.perform(post("/api/game/runs/r1/answers").header("X-User-Id", USER).header("X-Username", "앨리스")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seq\":1,\"choiceIndex\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.correct").value(true))
                .andExpect(jsonPath("$.data.correctIndex").value(2))
                .andExpect(jsonPath("$.data.bossUnlocked").value(false));
    }

    @Test
    void dungeonsMeAndLeaderboard() throws Exception {
        when(dungeonQueryService.dungeons(USER)).thenReturn(List.of(
                new DungeonSummary("jvm", "JVM 대장간", "jvm", true, false, 4)));
        when(dungeonQueryService.me(USER)).thenReturn(new GameMeResponse(1, 8, false));
        when(leaderboardService.top()).thenReturn(List.of(new LeaderboardEntry("u1", "앨리스", 2, 30)));

        mockMvc.perform(get("/api/game/dungeons").header("X-User-Id", USER))
                .andExpect(jsonPath("$.data[0].open").value(true))
                .andExpect(jsonPath("$.data[0].clearedByMe").value(false))
                .andExpect(jsonPath("$.data[0].clearCount").value(4));
        mockMvc.perform(get("/api/game/me").header("X-User-Id", USER))
                .andExpect(jsonPath("$.data.total").value(8))
                .andExpect(jsonPath("$.data.graduated").value(false));
        mockMvc.perform(get("/api/game/leaderboard").header("X-User-Id", USER))
                .andExpect(jsonPath("$.data[0].username").value("앨리스"))
                .andExpect(jsonPath("$.data[0].correctCount").value(30));
    }
}
