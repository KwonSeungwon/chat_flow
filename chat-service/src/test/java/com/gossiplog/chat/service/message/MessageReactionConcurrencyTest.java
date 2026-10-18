package com.gossiplog.chat.service.message;

import com.gossiplog.chat.entity.ChatMessageEntity;
import com.gossiplog.chat.repository.ChatMessageRepository;
import com.gossiplog.chat.repository.RepositoryTestConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Concurrent toggles on one message must not overwrite each other's reaction. */
@DataJpaTest
@ContextConfiguration(classes = RepositoryTestConfig.class)
@Import({MessageReactionService.class, ObjectMapper.class})
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MessageReactionConcurrencyTest {

    @Autowired private MessageReactionService service;
    @Autowired private ChatMessageRepository repository;
    @MockBean private SimpMessagingTemplate messagingTemplate;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    void concurrentReactionsByDifferentUsers_areAllKept() throws Exception {
        repository.save(ChatMessageEntity.builder()
                .messageId("m-1").chatRoomId("r-1").userId("author").username("author")
                .content("hi").type("CHAT").timestamp(LocalDateTime.now())
                .deleted(false).pinned(false).build());

        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String user = "u" + i;
            futures.add(pool.submit(() -> {
                start.await();
                return service.toggleReaction("r-1", "m-1", "👍", user);
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        String json = repository.findById("m-1").orElseThrow().getReactions();
        Map<String, List<String>> reactions = new ObjectMapper().readValue(json, new TypeReference<>() {});
        assertThat(reactions.get("👍")).hasSize(n);
    }
}
