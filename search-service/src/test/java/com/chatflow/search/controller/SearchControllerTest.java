package com.chatflow.search.controller;

import com.chatflow.search.client.MembershipCheckUnavailableException;
import com.chatflow.search.client.RoomMembershipClient;
import com.chatflow.search.document.ChatMessageDocument;
import com.chatflow.search.service.KoreanSearchService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Room-authorization tests for {@link SearchController}: identity comes from
 * the gateway-injected {@code X-User-Id}; membership comes from chat-service.
 */
@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=" +
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
        "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration," +
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SearchControllerTest {

    private static final String USER = "u1";

    @Autowired private MockMvc mockMvc;
    @MockBean private KoreanSearchService koreanSearchService;
    @MockBean private RoomMembershipClient roomMembershipClient;
    @MockBean @SuppressWarnings("rawtypes") private KafkaTemplate kafkaTemplate;

    @TestConfiguration
    static class TestConfig {
        @Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
    }

    private static Page<ChatMessageDocument> emptyPage() {
        return new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
    }

    @Test
    void globalSearch_onlyMemberRooms() throws Exception {
        when(roomMembershipClient.memberRoomIds(USER)).thenReturn(List.of("r1", "r2"));
        when(koreanSearchService.searchKoreanContentInRooms(eq("hi"), eq(List.of("r1", "r2")), any(Pageable.class)))
                .thenReturn(emptyPage());

        mockMvc.perform(get("/api/search/messages").param("query", "hi").header("X-User-Id", USER))
                .andExpect(status().isOk());

        verify(koreanSearchService).searchKoreanContentInRooms(eq("hi"), eq(List.of("r1", "r2")), any(Pageable.class));
    }

    @Test
    void globalKoreanAndNgram_withoutRoom_onlyMemberRooms() throws Exception {
        when(roomMembershipClient.memberRoomIds(USER)).thenReturn(List.of("r1"));
        when(koreanSearchService.searchKoreanContentInRooms(eq("hi"), eq(List.of("r1")), any(Pageable.class)))
                .thenReturn(emptyPage());
        when(koreanSearchService.searchWithNgramInRooms(eq("hi"), eq(List.of("r1")), any(Pageable.class)))
                .thenReturn(emptyPage());

        mockMvc.perform(get("/api/search/korean").param("query", "hi").header("X-User-Id", USER))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/search/ngram").param("query", "hi").header("X-User-Id", USER))
                .andExpect(status().isOk());

        verify(koreanSearchService).searchKoreanContentInRooms(eq("hi"), eq(List.of("r1")), any(Pageable.class));
        verify(koreanSearchService).searchWithNgramInRooms(eq("hi"), eq(List.of("r1")), any(Pageable.class));
    }

    @Test
    void globalSearch_noMemberRooms_emptyWithoutQueryingIndex() throws Exception {
        when(roomMembershipClient.memberRoomIds(USER)).thenReturn(List.of());

        mockMvc.perform(get("/api/search/messages").param("query", "hi").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        verifyNoInteractions(koreanSearchService);
    }

    @Test
    void roomSearch_nonMember_403() throws Exception {
        when(roomMembershipClient.isMember("r9", USER)).thenReturn(false);

        mockMvc.perform(get("/api/search/rooms/r9/messages").param("query", "hi").header("X-User-Id", USER))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/search/rooms/r9/users").param("username", "alice").header("X-User-Id", USER))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/search/rooms/r9/time-range")
                        .param("start", "2026-01-01T00:00:00").param("end", "2026-02-01T00:00:00")
                        .header("X-User-Id", USER))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/search/rooms/r9/filter").param("query", "hi").header("X-User-Id", USER))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/search/korean").param("query", "hi").param("roomId", "r9").header("X-User-Id", USER))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/search/ngram").param("query", "hi").param("roomId", "r9").header("X-User-Id", USER))
                .andExpect(status().isForbidden());

        verifyNoInteractions(koreanSearchService);
    }

    @Test
    void roomSearch_member_delegatesWithRoom() throws Exception {
        when(roomMembershipClient.isMember("r1", USER)).thenReturn(true);
        when(koreanSearchService.searchKoreanContent(eq("hi"), eq("r1"), any(Pageable.class))).thenReturn(emptyPage());

        mockMvc.perform(get("/api/search/rooms/r1/messages").param("query", "hi").header("X-User-Id", USER))
                .andExpect(status().isOk());

        verify(koreanSearchService).searchKoreanContent(eq("hi"), eq("r1"), any(Pageable.class));
    }

    @Test
    void missingUserId_401() throws Exception {
        mockMvc.perform(get("/api/search/messages").param("query", "hi"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/search/rooms/r1/messages").param("query", "hi"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(roomMembershipClient, koreanSearchService);
    }

    @Test
    void membershipServiceDown_503_neverFailOpen() throws Exception {
        when(roomMembershipClient.isMember(anyString(), anyString()))
                .thenThrow(new MembershipCheckUnavailableException("down", null));
        when(roomMembershipClient.memberRoomIds(anyString()))
                .thenThrow(new MembershipCheckUnavailableException("down", null));

        mockMvc.perform(get("/api/search/rooms/r1/messages").param("query", "hi").header("X-User-Id", USER))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/api/search/messages").param("query", "hi").header("X-User-Id", USER))
                .andExpect(status().isServiceUnavailable());

        verifyNoInteractions(koreanSearchService);
    }
}
