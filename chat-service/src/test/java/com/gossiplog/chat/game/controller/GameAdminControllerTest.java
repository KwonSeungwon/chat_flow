package com.gossiplog.chat.game.controller;

import com.gossiplog.chat.auth.AuthInterceptor;
import com.gossiplog.chat.auth.AuthenticatedUserResolver;
import com.gossiplog.chat.auth.TestAuthFilter;
import com.gossiplog.chat.controller.RoomMembershipGuard;
import com.gossiplog.chat.exception.GlobalExceptionHandler;
import com.gossiplog.chat.game.dto.AdminQuestionDto;
import com.gossiplog.chat.game.dto.AdminQuestionPage;
import com.gossiplog.chat.game.dto.BulkUploadRequest;
import com.gossiplog.chat.game.dto.BulkUploadResult;
import com.gossiplog.chat.game.dto.QuestionPatchRequest;
import com.gossiplog.chat.game.exception.GameException;
import com.gossiplog.chat.game.service.GameAdminGuard;
import com.gossiplog.chat.game.service.GameQuestionService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class GameAdminControllerTest {

    private static final String ADMIN = "admin-1";
    private static final String BULK_BODY = """
            {"topics":[{"dungeonId":"jvm","title":"GC","summary":"s","sourceRef":"0. Wiki/2. JVM·Spring",
              "monster":{"name":"OOM 슬라임","spriteKey":"monster_jvm"},
              "questions":[{"stem":"q","choices":["a","b","c","d"],"answerIndex":1,"explanation":"e"}]}]}
            """;

    @Mock
    private GameQuestionService questionService;
    @Mock
    private RoomMembershipGuard membershipGuard;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        GameAdminController controller = new GameAdminController(new GameAdminGuard(ADMIN), questionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticatedUserResolver())
                .addFilters(TestAuthFilter.fromUserIdHeader())
                .addInterceptors(new AuthInterceptor(membershipGuard))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void missingUserHeader_returns401() throws Exception {
        mockMvc.perform(post("/api/game/admin/questions/bulk")
                        .contentType(MediaType.APPLICATION_JSON).content(BULK_BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(questionService);
    }

    @Test
    void missingUserHeader_onListAndPatch_returns401() throws Exception {
        mockMvc.perform(get("/api/game/admin/questions"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/game/admin/questions/7")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(questionService);
    }

    @Test
    void nonAdmin_onPatch_returns403() throws Exception {
        mockMvc.perform(patch("/api/game/admin/questions/7").header("X-User-Id", "someone")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GAME_FORBIDDEN"));
        verifyNoInteractions(questionService);
    }

    @Test
    void nonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/game/admin/questions").header("X-User-Id", "someone"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GAME_FORBIDDEN"));
        mockMvc.perform(post("/api/game/admin/questions/bulk").header("X-User-Id", "someone")
                        .contentType(MediaType.APPLICATION_JSON).content(BULK_BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(questionService);
    }

    @Test
    void bulk_success_returnsInsertedAndSkipped() throws Exception {
        when(questionService.bulkUpload(any(BulkUploadRequest.class))).thenReturn(new BulkUploadResult(1, 0));

        mockMvc.perform(post("/api/game/admin/questions/bulk").header("X-User-Id", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(BULK_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.inserted").value(1))
                .andExpect(jsonPath("$.data.skipped").value(0));
    }

    @Test
    void bulk_validationFailure_returns400() throws Exception {
        when(questionService.bulkUpload(any(BulkUploadRequest.class)))
                .thenThrow(GameException.validation("choices는 정확히 4개여야 합니다."));

        mockMvc.perform(post("/api/game/admin/questions/bulk").header("X-User-Id", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(BULK_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void list_defaultsToDraft() throws Exception {
        AdminQuestionDto dto = new AdminQuestionDto(7L, "jvm", "GC", "q", List.of("a", "b", "c", "d"),
                1, "e", "DRAFT");
        when(questionService.list("DRAFT", 0, 20)).thenReturn(new AdminQuestionPage(List.of(dto), 1));

        mockMvc.perform(get("/api/game/admin/questions").header("X-User-Id", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(7))
                .andExpect(jsonPath("$.data.items[0].topicTitle").value("GC"))
                .andExpect(jsonPath("$.data.items[0].answerIndex").value(1));
    }

    @Test
    void patch_approve_returnsUpdatedQuestion() throws Exception {
        AdminQuestionDto dto = new AdminQuestionDto(7L, "jvm", "GC", "q", List.of("a", "b", "c", "d"),
                1, "e", "APPROVED");
        when(questionService.patch(eq(7L), eq(new QuestionPatchRequest(null, null, null, null, "APPROVED"))))
                .thenReturn(dto);

        mockMvc.perform(patch("/api/game/admin/questions/7").header("X-User-Id", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    void patch_duplicate_returns409() throws Exception {
        when(questionService.patch(eq(7L), any(QuestionPatchRequest.class)))
                .thenThrow(new GameException(409, "DUPLICATE_QUESTION", "dup"));

        mockMvc.perform(patch("/api/game/admin/questions/7").header("X-User-Id", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stem\":\"q\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_QUESTION"));
    }
}
