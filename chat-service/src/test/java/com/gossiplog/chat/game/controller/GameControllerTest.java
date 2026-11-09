package com.gossiplog.chat.game.controller;

import com.gossiplog.chat.auth.AuthInterceptor;
import com.gossiplog.chat.auth.AuthenticatedUserResolver;
import com.gossiplog.chat.auth.TestAuthFilter;
import com.gossiplog.chat.controller.RoomMembershipGuard;
import com.gossiplog.chat.exception.GlobalExceptionHandler;
import com.gossiplog.chat.game.dto.AnswerRequest;
import com.gossiplog.chat.game.dto.AnswerResult;
import com.gossiplog.chat.game.dto.CollectionEntry;
import com.gossiplog.chat.game.dto.DungeonSummary;
import com.gossiplog.chat.game.dto.GameMeResponse;
import com.gossiplog.chat.game.dto.LeaderboardEntry;
import com.gossiplog.chat.game.dto.NextEncounter;
import com.gossiplog.chat.game.dto.RunStartResponse;
import com.gossiplog.chat.game.exception.GameException;
import com.gossiplog.chat.game.service.GameCollectionService;
import com.gossiplog.chat.game.service.GameDungeonQueryService;
import com.gossiplog.chat.game.service.GamePlayerService;
import com.gossiplog.chat.game.service.GameRunService;
import com.gossiplog.chat.game.service.LeaderboardService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
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
    private GamePlayerService playerService;
    @Mock
    private GameCollectionService collectionService;
    @Mock
    private RoomMembershipGuard membershipGuard;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        GameController controller = new GameController(runService, dungeonQueryService, leaderboardService, playerService,
                collectionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticatedUserResolver())
                .addFilters(TestAuthFilter.fromUserIdHeader())
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
        mockMvc.perform(put("/api/game/me/avatar")
                .contentType(MediaType.APPLICATION_JSON).content("{\"avatar\":\"boy\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/game/collection")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/game/collection/7/runs")).andExpect(status().isUnauthorized());
        verifyNoInteractions(runService, dungeonQueryService, leaderboardService, playerService, collectionService);
    }

    @Test
    void next_hasNoAnswerIndex() throws Exception {
        when(runService.next(USER, "r1")).thenReturn(new NextEncounter(3,
                new NextEncounter.Monster("OOM 슬라임", "monster_jvm", false, 15, 30),
                new NextEncounter.Question(11L, "stem", List.of("a", "b", "c", "d"), "GC")));

        mockMvc.perform(get("/api/game/runs/r1/next").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seq").value(3))
                .andExpect(jsonPath("$.data.monster.isBoss").value(false))
                .andExpect(jsonPath("$.data.monster.spriteKey").value("monster_jvm"))
                .andExpect(jsonPath("$.data.monster.hp").value(15))
                .andExpect(jsonPath("$.data.monster.maxHp").value(30))
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
        when(runService.start(USER, "jvm")).thenReturn(new RunStartResponse("r1", 3, 0, 3, 100, 100, false, 0));

        mockMvc.perform(post("/api/game/dungeons/jvm/runs").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("r1"))
                .andExpect(jsonPath("$.data.hearts").value(3))
                .andExpect(jsonPath("$.data.killsToBoss").value(3))
                .andExpect(jsonPath("$.data.hp").value(100))
                .andExpect(jsonPath("$.data.maxHp").value(100))
                .andExpect(jsonPath("$.data.review").value(false))
                .andExpect(jsonPath("$.data.reviewTotal").value(0));
    }

    @Test
    void answer_passesUsernameHeader() throws Exception {
        when(runService.answer(eq(USER), eq("앨리스"), eq("r1"), any(AnswerRequest.class)))
                .thenReturn(new AnswerResult(true, 2, "exp", "GC", "0. Wiki", 3, 1, false, false, false,
                        100, 100, 30, true, 0, 30, true, false, 0, 0));

        mockMvc.perform(post("/api/game/runs/r1/answers").header("X-User-Id", USER).header("X-Username", "앨리스")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seq\":1,\"choiceIndex\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.correct").value(true))
                .andExpect(jsonPath("$.data.correctIndex").value(2))
                .andExpect(jsonPath("$.data.bossUnlocked").value(false))
                .andExpect(jsonPath("$.data.hp").value(100))
                .andExpect(jsonPath("$.data.maxHp").value(100))
                .andExpect(jsonPath("$.data.damage").value(30))
                .andExpect(jsonPath("$.data.critical").value(true))
                .andExpect(jsonPath("$.data.monsterHp").value(0))
                .andExpect(jsonPath("$.data.monsterMaxHp").value(30))
                .andExpect(jsonPath("$.data.monsterDefeated").value(true))
                .andExpect(jsonPath("$.data.review").value(false))
                .andExpect(jsonPath("$.data.reviewCorrect").value(0))
                .andExpect(jsonPath("$.data.reviewTotal").value(0));
    }

    @Test
    void dungeonsMeAndLeaderboard() throws Exception {
        when(dungeonQueryService.dungeons(USER)).thenReturn(List.of(
                new DungeonSummary("jvm", "JVM 대장간", "jvm", true, false, 4)));
        when(dungeonQueryService.me(USER)).thenReturn(new GameMeResponse(1, 8, false, true, "girl"));
        when(leaderboardService.top()).thenReturn(List.of(new LeaderboardEntry("앨리스", 2, 30)));

        mockMvc.perform(get("/api/game/dungeons").header("X-User-Id", USER))
                .andExpect(jsonPath("$.data[0].open").value(true))
                .andExpect(jsonPath("$.data[0].clearedByMe").value(false))
                .andExpect(jsonPath("$.data[0].clearCount").value(4));
        mockMvc.perform(get("/api/game/me").header("X-User-Id", USER))
                .andExpect(jsonPath("$.data.total").value(8))
                .andExpect(jsonPath("$.data.graduated").value(false))
                .andExpect(jsonPath("$.data.clearedCount").value(1))
                .andExpect(jsonPath("$.data.admin").value(true))
                .andExpect(jsonPath("$.data.avatar").value("girl"));
        mockMvc.perform(get("/api/game/leaderboard").header("X-User-Id", USER))
                .andExpect(jsonPath("$.data[0].username").value("앨리스"))
                .andExpect(jsonPath("$.data[0].correctCount").value(30))
                .andExpect(jsonPath("$.data[0].userId").doesNotExist());
    }

    @Test
    void collection_returnsEntries() throws Exception {
        when(collectionService.collection(USER)).thenReturn(List.of(
                new CollectionEntry(7L, "jvm", "JVM 대장간", "GC", "OOM 슬라임", "monster_jvm", true, 2, 5, 1),
                new CollectionEntry(8L, "jvm", "JVM 대장간", "Heap", null, "monster_jvm", false, 0, 3, 0)));

        mockMvc.perform(get("/api/game/collection").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].topicId").value(7))
                .andExpect(jsonPath("$.data[0].dungeonId").value("jvm"))
                .andExpect(jsonPath("$.data[0].dungeonName").value("JVM 대장간"))
                .andExpect(jsonPath("$.data[0].topicTitle").value("GC"))
                .andExpect(jsonPath("$.data[0].monsterName").value("OOM 슬라임"))
                .andExpect(jsonPath("$.data[0].spriteKey").value("monster_jvm"))
                .andExpect(jsonPath("$.data[0].caught").value(true))
                .andExpect(jsonPath("$.data[0].correctCount").value(2))
                .andExpect(jsonPath("$.data[0].questionCount").value(5))
                .andExpect(jsonPath("$.data[0].dueCount").value(1))
                .andExpect(jsonPath("$.data[1].caught").value(false))
                .andExpect(jsonPath("$.data[1].monsterName").doesNotExist());
    }

    @Test
    void startReview_returnsRun() throws Exception {
        when(runService.startReview(USER, 7L)).thenReturn(new RunStartResponse("r2", 3, 0, 3, 100, 100, true, 4));

        mockMvc.perform(post("/api/game/collection/7/runs").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("r2"))
                .andExpect(jsonPath("$.data.review").value(true))
                .andExpect(jsonPath("$.data.reviewTotal").value(4));
    }

    @Test
    void startReview_notCaught_returns409() throws Exception {
        when(runService.startReview(USER, 7L))
                .thenThrow(new GameException(409, "NOT_CAUGHT", "아직 잡지 못한 몬스터입니다."));

        mockMvc.perform(post("/api/game/collection/7/runs").header("X-User-Id", USER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_CAUGHT"));
    }

    @Test
    void putAvatar_returns204_andDelegates() throws Exception {
        mockMvc.perform(put("/api/game/me/avatar").header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"avatar\":\"boy\"}"))
                .andExpect(status().isNoContent());
        verify(playerService).setAvatar(USER, "boy");
    }

    @Test
    void putAvatar_invalid_returns400() throws Exception {
        doThrow(GameException.validation("avatar must be boy or girl"))
                .when(playerService).setAvatar(USER, "cat");

        mockMvc.perform(put("/api/game/me/avatar").header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"avatar\":\"cat\"}"))
                .andExpect(status().isBadRequest());
    }
}
