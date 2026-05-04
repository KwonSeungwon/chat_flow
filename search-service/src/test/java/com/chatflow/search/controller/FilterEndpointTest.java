package com.chatflow.search.controller;

import com.chatflow.search.document.ChatMessageDocument;
import com.chatflow.search.repository.ChatMessageSearchRepository;
import com.chatflow.search.service.KoreanSearchService;
import com.chatflow.search.service.SearchService;
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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=" +
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
        "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration," +
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FilterEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private KoreanSearchService koreanSearchService;

    @MockBean
    private SearchService searchService;

    @MockBean
    private ChatMessageSearchRepository chatMessageSearchRepository;

    @MockBean(name = "elasticsearchTemplate")
    private ElasticsearchOperations elasticsearchOperations;

    @MockBean
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    @TestConfiguration
    static class TestConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Test
    void filter_withQuery_returns200() throws Exception {
        Page<ChatMessageDocument> empty = new PageImpl<>(new ArrayList<>(), PageRequest.of(0, 50), 0);
        when(koreanSearchService.searchWithFilters(
                eq("room1"), eq("hello"), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
            .thenReturn(empty);

        mockMvc.perform(get("/api/search/rooms/room1/filter")
                .param("query", "hello"))
                .andExpect(status().isOk());

        verify(koreanSearchService).searchWithFilters(
                eq("room1"), eq("hello"), isNull(), isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void filter_withNoFilters_returns400() throws Exception {
        mockMvc.perform(get("/api/search/rooms/room1/filter"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void filter_withMessageType_passesTypeParam() throws Exception {
        Page<ChatMessageDocument> empty = new PageImpl<>(new ArrayList<>(), PageRequest.of(0, 50), 0);
        when(koreanSearchService.searchWithFilters(
                eq("room1"), isNull(), isNull(), isNull(), isNull(), eq("FILE"), any(Pageable.class)))
            .thenReturn(empty);

        mockMvc.perform(get("/api/search/rooms/room1/filter")
                .param("messageType", "FILE"))
                .andExpect(status().isOk());

        verify(koreanSearchService).searchWithFilters(
                eq("room1"), isNull(), isNull(), isNull(), isNull(), eq("FILE"), any(Pageable.class));
    }

    @Test
    void filter_invalidDateRange_returns400() throws Exception {
        mockMvc.perform(get("/api/search/rooms/room1/filter")
                .param("startDate", "2025-05-04T12:00:00")
                .param("endDate", "2025-05-01T12:00:00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void filter_withUsername_passesUsernameParam() throws Exception {
        Page<ChatMessageDocument> empty = new PageImpl<>(new ArrayList<>(), PageRequest.of(0, 50), 0);
        when(koreanSearchService.searchWithFilters(
                eq("room1"), isNull(), eq("홍길동"), isNull(), isNull(), isNull(), any(Pageable.class)))
            .thenReturn(empty);

        mockMvc.perform(get("/api/search/rooms/room1/filter")
                .param("username", "홍길동"))
                .andExpect(status().isOk());

        verify(koreanSearchService).searchWithFilters(
                eq("room1"), isNull(), eq("홍길동"), isNull(), isNull(), isNull(), any(Pageable.class));
    }
}
