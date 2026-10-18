package com.gossiplog.chat.service.presence;

import com.gossiplog.chat.config.RedisHealthTracker;
import com.gossiplog.chat.entity.ChatRoom;
import com.gossiplog.chat.event.RoomMemberRemovedEvent;
import com.gossiplog.chat.repository.ChatRoomRepository;
import com.gossiplog.chat.repository.RepositoryTestConfig;
import com.gossiplog.chat.service.room.ParticipantService;
import com.gossiplog.chat.service.room.RoomAccessPolicy;
import com.gossiplog.chat.service.room.RoomCacheEvictor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The kick/ban listener runs after commit; its participant_count write must still be persisted. */
@DataJpaTest
@ContextConfiguration(classes = RepositoryTestConfig.class)
@Import({RoomSubscriptionRevoker.class, ParticipantRegistryService.class, ParticipantService.class})
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RoomSubscriptionRevokerPersistenceTest {

    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ChatRoomRepository chatRoomRepository;
    @MockBean private StringRedisTemplate redisTemplate;
    @MockBean private RedisHealthTracker redisHealthTracker;
    @MockBean private RoomCacheEvictor roomCacheEvictor;
    @MockBean private RoomAccessPolicy roomAccessPolicy;
    @MockBean private SimpUserRegistry userRegistry;
    @MockBean(name = "brokerChannel") private MessageChannel brokerChannel;

    @AfterEach
    void cleanUp() {
        chatRoomRepository.deleteAll();
    }

    @Test
    @SuppressWarnings("unchecked")
    void kick_updatesParticipantCountInTheDatabase() {
        chatRoomRepository.save(ChatRoom.builder().id("r-1").name("room").participantCount(2)
                .maxParticipants(10).createdAt(LocalDateTime.now()).build());
        SetOperations<String, String> set = mock(SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(set);
        when(set.members("gossiplog:room:participants:r-1"))
                .thenReturn(Set.of("u1:s1:alice", "u2:s2:bob"), Set.of("u2:s2:bob"));

        new TransactionTemplate(transactionManager).executeWithoutResult(s -> publisher.publishEvent(
                new RoomMemberRemovedEvent("r-1", "u1", RoomMemberRemovedEvent.Reason.KICKED)));

        assertThat(chatRoomRepository.findById("r-1").orElseThrow().getParticipantCount()).isEqualTo(1);
    }
}
