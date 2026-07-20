package com.chatflow.search.controller;

import com.chatflow.search.document.ChatMessageDocument;
import com.chatflow.search.repository.ChatMessageSearchRepository;
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
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=" +
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
        "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration," +
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SearchControllerLegacyDelegationTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private KoreanSearchService koreanSearchService;
    @MockBean private ChatMessageSearchRepository chatMessageSearchRepository;
    @MockBean(name = "elasticsearchTemplate") private ElasticsearchOperations elasticsearchOperations;
    @MockBean @SuppressWarnings("rawtypes") private KafkaTemplate kafkaTemplate;

    @TestConfiguration
    static class TestConfig {
        @Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
    }

    private static Page<ChatMessageDocument> emptyPage() {
        return new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
    }

    @Test
    void messages_delegatesToKoreanContentWithNullRoom() throws Exception {
        when(koreanSearchService.searchKoreanContent(eq("hi"), isNull(), any(Pageable.class))).thenReturn(emptyPage());
        mockMvc.perform(get("/api/search/messages").param("query", "hi")).andExpect(status().isOk());
        verify(koreanSearchService).searchKoreanContent(eq("hi"), isNull(), any(Pageable.class));
    }

    @Test
    void roomMessages_delegatesToKoreanContentWithRoom() throws Exception {
        when(koreanSearchService.searchKoreanContent(eq("hi"), eq("room1"), any(Pageable.class))).thenReturn(emptyPage());
        mockMvc.perform(get("/api/search/rooms/room1/messages").param("query", "hi")).andExpect(status().isOk());
        verify(koreanSearchService).searchKoreanContent(eq("hi"), eq("room1"), any(Pageable.class));
    }

    @Test
    void users_withoutQuery_delegatesToFiltersWithUsernameOnly() throws Exception {
        when(koreanSearchService.searchWithFilters(eq("room1"), isNull(), eq("alice"), isNull(), isNull(), isNull(), any(Pageable.class))).thenReturn(emptyPage());
        mockMvc.perform(get("/api/search/rooms/room1/users").param("username", "alice")).andExpect(status().isOk());
        verify(koreanSearchService).searchWithFilters(eq("room1"), isNull(), eq("alice"), isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void users_withQuery_delegatesToFiltersWithUsernameAndQuery() throws Exception {
        when(koreanSearchService.searchWithFilters(eq("room1"), eq("hi"), eq("alice"), isNull(), isNull(), isNull(), any(Pageable.class))).thenReturn(emptyPage());
        mockMvc.perform(get("/api/search/rooms/room1/users").param("username", "alice").param("query", "hi")).andExpect(status().isOk());
        verify(koreanSearchService).searchWithFilters(eq("room1"), eq("hi"), eq("alice"), isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void users_missingUsername_returns400() throws Exception {
        mockMvc.perform(get("/api/search/rooms/room1/users")).andExpect(status().isBadRequest());
    }

    @Test
    void timeRange_delegatesToFiltersWithDates() throws Exception {
        when(koreanSearchService.searchWithFilters(eq("room1"), isNull(), isNull(), any(LocalDateTime.class), any(LocalDateTime.class), isNull(), any(Pageable.class))).thenReturn(emptyPage());
        mockMvc.perform(get("/api/search/rooms/room1/time-range")
                .param("start", "2026-01-01T00:00:00").param("end", "2026-02-01T00:00:00"))
                .andExpect(status().isOk());
        verify(koreanSearchService).searchWithFilters(eq("room1"), isNull(), isNull(), any(LocalDateTime.class), any(LocalDateTime.class), isNull(), any(Pageable.class));
    }

    @Test
    void timeRange_startAfterEnd_returns400() throws Exception {
        mockMvc.perform(get("/api/search/rooms/room1/time-range")
                .param("start", "2026-02-01T00:00:00").param("end", "2026-01-01T00:00:00"))
                .andExpect(status().isBadRequest());
    }
}
