package com.chatflow.aisummary.service;

import com.chatflow.aisummary.client.ChatModelClient;
import com.chatflow.common.dto.ChatMessage;
import com.chatflow.common.dto.KafkaTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AiSummaryService}.
 *
 * <p>Mocking strategy:
 * <ul>
 *   <li>{@code StringRedisTemplate} — mocked, with its {@code opsForList()},
 *       {@code opsForSet()}, and {@code opsForValue()} returning dedicated mocks.</li>
 *   <li>{@code ChatModelClient} — mocked to return canned summaries.</li>
 *   <li>{@code KafkaTemplate} — mocked; {@code send()} returns a completed future.</li>
 *   <li>{@code ObjectMapper} — real instance with JavaTimeModule (mirrors prod config).</li>
 *   <li>{@code Executor} — synchronous ({@code Runnable::run}) so async paths execute inline.</li>
 * </ul>
 *
 * <p>Coverage targets: buffering, skip-guards, count-based trigger, time-based
 * trigger, getSummaries, requestSummary, answerQuestion, generateShiftReport,
 * malformed JSON handling.
 *
 * <p>Deliberately skipped:
 * <ul>
 *   <li>Rate-limiter exhaustion in generateSummary — the internal {@code Bucket} is
 *       final and constructed in-line; exhausting it requires consuming all 10 tokens
 *       which couples to the Bucket4j refill timing. Covered partially via
 *       answerQuestion/generateShiftReport rate-limit tests.</li>
 *   <li>consumeBuffer Lua script — the {@code redisTemplate.execute(RedisScript, ...)}
 *       is stubbed; the Lua itself is tested implicitly via integration tests.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AiSummaryServiceTest {

    @Mock private ChatModelClient chatModelClient;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ListOperations<String, String> listOps;
    @Mock private SetOperations<String, String> setOps;
    @Mock private ValueOperations<String, String> valueOps;

    // Inline executor runs tasks synchronously in tests
    private final Executor syncExecutor = Runnable::run;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private AiSummaryService service;

    private static final String BUFFER_PREFIX = "chatflow:buffer:";
    private static final String BUFFER_TIME_PREFIX = "chatflow:buffer-time:";
    private static final String ACTIVE_ROOMS_KEY = "chatflow:active-rooms";
    private static final String SUMMARY_PREFIX = RedisKeys.SUMMARY_LIST_PREFIX;
    private static final String HASH_PREFIX = "chatflow:summary-hash:";

    @BeforeEach
    void setUp() {
        service = new AiSummaryService(chatModelClient, kafkaTemplate, redisTemplate,
                objectMapper, syncExecutor);
    }

    // -----------------------------------------------------------------------
    // Helper: build a ChatMessage and serialize it
    // -----------------------------------------------------------------------
    private ChatMessage chatMessage(String roomId, String msgId, String content) {
        return ChatMessage.builder()
                .messageId(msgId)
                .chatRoomId(roomId)
                .userId("u1")
                .username("alice")
                .content(content)
                .type(ChatMessage.MessageType.CHAT)
                .timestamp(LocalDateTime.of(2026, 7, 4, 12, 0, 0))
                .build();
    }

    private String toJson(ChatMessage msg) throws Exception {
        return objectMapper.writeValueAsString(msg);
    }

    /**
     * Stubs the common Redis operations so that a normal CHAT message can be
     * buffered without NPE. Returns the buffer size to return from
     * {@code listOps.size()}.
     */
    private void stubRedisForBuffering(long bufferSizeAfterPush) {
        lenient().when(redisTemplate.opsForList()).thenReturn(listOps);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(listOps.rightPush(anyString(), anyString())).thenReturn(bufferSizeAfterPush);
        lenient().when(listOps.size(anyString())).thenReturn(bufferSizeAfterPush);
        lenient().when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);
        lenient().when(setOps.add(anyString(), anyString())).thenReturn(1L);
    }

    // =======================================================================
    // Skip-guard tests
    // =======================================================================
    @Nested
    @DisplayName("Skip-guard: messages that must NOT reach the Redis buffer")
    class SkipGuardTests {

        @ParameterizedTest(name = "type={0} should be skipped")
        @EnumSource(value = ChatMessage.MessageType.class,
                names = {"JOIN", "LEAVE", "SYSTEM", "AI_SUMMARY", "PATIENT_CARD", "FILE"})
        void non_chat_types_are_skipped(ChatMessage.MessageType type) throws Exception {
            ChatMessage msg = ChatMessage.builder()
                    .chatRoomId("room-skip").userId("u1").username("alice")
                    .content("some content").type(type)
                    .timestamp(LocalDateTime.now())
                    .build();

            service.handleChatMessage(toJson(msg));

            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("isDeleted=true CHAT message is NOT buffered")
        void deleted_message_skipped() throws Exception {
            ChatMessage deleted = ChatMessage.builder()
                    .chatRoomId("room-del").userId("u1").username("alice")
                    .content("deleted").type(ChatMessage.MessageType.CHAT)
                    .timestamp(LocalDateTime.now())
                    .isDeleted(true)
                    .build();

            service.handleChatMessage(toJson(deleted));

            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("edited=true CHAT message is NOT buffered")
        void edited_message_skipped() throws Exception {
            ChatMessage edited = ChatMessage.builder()
                    .chatRoomId("room-edit").userId("u1").username("alice")
                    .content("edited").type(ChatMessage.MessageType.CHAT)
                    .timestamp(LocalDateTime.now())
                    .edited(true)
                    .build();

            service.handleChatMessage(toJson(edited));

            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("deleted AND edited CHAT message is NOT buffered")
        void deleted_and_edited_message_skipped() throws Exception {
            ChatMessage both = ChatMessage.builder()
                    .chatRoomId("room-both").userId("u1").username("alice")
                    .content("both flags").type(ChatMessage.MessageType.CHAT)
                    .timestamp(LocalDateTime.now())
                    .isDeleted(true)
                    .edited(true)
                    .build();

            service.handleChatMessage(toJson(both));

            verifyNoInteractions(redisTemplate);
        }
    }

    // =======================================================================
    // Buffering tests
    // =======================================================================
    @Nested
    @DisplayName("Buffering: normal CHAT messages are pushed to Redis")
    class BufferingTests {

        @Test
        @DisplayName("normal CHAT message is pushed to Redis buffer with correct key")
        void normal_chat_message_buffered() throws Exception {
            stubRedisForBuffering(1L);

            ChatMessage msg = chatMessage("room-A", "msg-1", "hello");
            service.handleChatMessage(toJson(msg));

            verify(listOps).rightPush(eq(BUFFER_PREFIX + "room-A"), anyString());
        }

        @Test
        @DisplayName("buffer expire is set after push")
        void buffer_expire_set() throws Exception {
            stubRedisForBuffering(1L);

            ChatMessage msg = chatMessage("room-A", "msg-1", "hello");
            service.handleChatMessage(toJson(msg));

            verify(redisTemplate).expire(eq(BUFFER_PREFIX + "room-A"), eq(Duration.ofHours(1)));
        }

        @Test
        @DisplayName("roomId is added to active-rooms set")
        void room_added_to_active_set() throws Exception {
            stubRedisForBuffering(1L);

            ChatMessage msg = chatMessage("room-A", "msg-1", "hello");
            service.handleChatMessage(toJson(msg));

            verify(setOps).add(eq(ACTIVE_ROOMS_KEY), eq("room-A"));
        }

        @Test
        @DisplayName("buffer-time key is set with last message timestamp")
        void buffer_time_key_set() throws Exception {
            stubRedisForBuffering(1L);

            ChatMessage msg = chatMessage("room-A", "msg-1", "hello");
            service.handleChatMessage(toJson(msg));

            verify(valueOps).set(eq(BUFFER_TIME_PREFIX + "room-A"), anyString(), eq(Duration.ofHours(1)));
        }

        @Test
        @DisplayName("oversized buffer (>100) is trimmed")
        void oversized_buffer_trimmed() throws Exception {
            long oversized = 105L;
            stubRedisForBuffering(oversized);

            ChatMessage msg = chatMessage("room-trim", "msg-1", "hello");
            service.handleChatMessage(toJson(msg));

            // trim(bufferSize - MAX_MESSAGES_PER_ROOM, -1) = trim(5, -1)
            verify(listOps).trim(eq(BUFFER_PREFIX + "room-trim"), eq(5L), eq(-1L));
        }

        @Test
        @DisplayName("buffer at exactly 100 is NOT trimmed")
        void buffer_at_max_not_trimmed() throws Exception {
            stubRedisForBuffering(100L);

            ChatMessage msg = chatMessage("room-exact", "msg-1", "hello");
            service.handleChatMessage(toJson(msg));

            verify(listOps, never()).trim(anyString(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("buffer below trigger count (< 10) does NOT invoke chatModelClient")
        void below_trigger_count_no_summary() throws Exception {
            stubRedisForBuffering(5L);

            ChatMessage msg = chatMessage("room-low", "msg-1", "hello");
            service.handleChatMessage(toJson(msg));

            verifyNoInteractions(chatModelClient);
        }

        @Test
        @DisplayName("serialized JSON pushed to Redis is deserializable back to ChatMessage")
        void pushed_json_is_valid() throws Exception {
            stubRedisForBuffering(1L);

            ChatMessage msg = chatMessage("room-json", "msg-1", "hello world");
            service.handleChatMessage(toJson(msg));

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(listOps).rightPush(eq(BUFFER_PREFIX + "room-json"), jsonCaptor.capture());

            // Verify the captured JSON can be deserialized back
            ChatMessage roundTripped = objectMapper.readValue(jsonCaptor.getValue(), ChatMessage.class);
            assertThat(roundTripped.getMessageId()).isEqualTo("msg-1");
            assertThat(roundTripped.getContent()).isEqualTo("hello world");
            assertThat(roundTripped.getChatRoomId()).isEqualTo("room-json");
        }
    }

    // =======================================================================
    // Count-based trigger tests
    // =======================================================================
    @Nested
    @DisplayName("Count-based trigger: buffer >= 10 triggers summary generation")
    class CountBasedTriggerTests {

        @Test
        @DisplayName("buffer reaching 10 consumes buffer and invokes chatModelClient")
        void trigger_at_10_generates_summary() throws Exception {
            stubRedisForBuffering(10L);

            // consumeBuffer uses redisTemplate.execute(RedisScript, keys)
            // Stub to return a list of serialized ChatMessages
            List<String> bufferedJsons = List.of(
                    toJson(chatMessage("room-trigger", "m1", "msg1")),
                    toJson(chatMessage("room-trigger", "m2", "msg2")),
                    toJson(chatMessage("room-trigger", "m3", "msg3"))
            );
            when(redisTemplate.execute(any(RedisScript.class), anyList()))
                    .thenReturn(bufferedJsons);

            // Stub hash check — no previous hash
            when(valueOps.get(eq(HASH_PREFIX + "room-trigger"))).thenReturn(null);

            // Stub chatModelClient to return a summary
            when(chatModelClient.generate(anyString())).thenReturn("AI summary result");

            // Stub getSummaries path — no existing summaries

            // Stub kafkaTemplate.send
            when(kafkaTemplate.send(anyString(), anyString(), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            ChatMessage msg = chatMessage("room-trigger", "msg-trigger", "hello");
            service.handleChatMessage(toJson(msg));

            // Verify the summary generation path ran
            verify(chatModelClient).generate(anyString());
            verify(kafkaTemplate).send(eq(KafkaTopics.AI_SUMMARIES), eq("room-trigger"), any(ChatMessage.class));
        }

        @Test
        @DisplayName("trigger clears time key and removes room from active set")
        void trigger_clears_time_and_active_room() throws Exception {
            stubRedisForBuffering(10L);

            List<String> bufferedJsons = List.of(
                    toJson(chatMessage("room-cleanup", "m1", "msg1"))
            );
            when(redisTemplate.execute(any(RedisScript.class), anyList()))
                    .thenReturn(bufferedJsons);
            when(valueOps.get(eq(HASH_PREFIX + "room-cleanup"))).thenReturn(null);
            when(chatModelClient.generate(anyString())).thenReturn("summary");
            when(kafkaTemplate.send(anyString(), anyString(), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            ChatMessage msg = chatMessage("room-cleanup", "msg-trigger", "hello");
            service.handleChatMessage(toJson(msg));

            verify(redisTemplate).delete(eq(BUFFER_TIME_PREFIX + "room-cleanup"));
            verify(setOps).remove(eq(ACTIVE_ROOMS_KEY), eq("room-cleanup"));
        }

        @Test
        @DisplayName("empty consumeBuffer result does NOT invoke chatModelClient")
        void empty_consume_skips_summary() throws Exception {
            stubRedisForBuffering(10L);

            // consumeBuffer returns empty list
            when(redisTemplate.execute(any(RedisScript.class), anyList()))
                    .thenReturn(List.of());

            ChatMessage msg = chatMessage("room-empty", "msg-1", "hello");
            service.handleChatMessage(toJson(msg));

            verifyNoInteractions(chatModelClient);
        }

        @Test
        @DisplayName("null consumeBuffer result does NOT invoke chatModelClient")
        void null_consume_skips_summary() throws Exception {
            stubRedisForBuffering(10L);

            when(redisTemplate.execute(any(RedisScript.class), anyList()))
                    .thenReturn(null);

            ChatMessage msg = chatMessage("room-null", "msg-1", "hello");
            service.handleChatMessage(toJson(msg));

            verifyNoInteractions(chatModelClient);
        }

        @Test
        @DisplayName("duplicate hash skips summary generation")
        void duplicate_hash_skips_summary() throws Exception {
            stubRedisForBuffering(10L);

            ChatMessage m1 = chatMessage("room-dup", "m1", "msg1");
            List<String> bufferedJsons = List.of(toJson(m1));
            when(redisTemplate.execute(any(RedisScript.class), anyList()))
                    .thenReturn(bufferedJsons);

            // Compute the expected hash for [m1] and stub the previous hash to match
            // The hash is SHA-256 of sorted messageIds joined by ","
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest("m1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String expectedHash = java.util.HexFormat.of().formatHex(hashBytes);

            when(valueOps.get(eq(HASH_PREFIX + "room-dup"))).thenReturn(expectedHash);

            ChatMessage msg = chatMessage("room-dup", "msg-trigger", "hello");
            service.handleChatMessage(toJson(msg));

            // chatModelClient should NOT be called because the hash matches
            verifyNoInteractions(chatModelClient);
        }

        @Test
        @DisplayName("summary message sent to Kafka has correct fields")
        void summary_message_kafka_fields() throws Exception {
            stubRedisForBuffering(10L);

            List<String> bufferedJsons = List.of(
                    toJson(chatMessage("room-fields", "m1", "hello world"))
            );
            when(redisTemplate.execute(any(RedisScript.class), anyList()))
                    .thenReturn(bufferedJsons);
            when(valueOps.get(eq(HASH_PREFIX + "room-fields"))).thenReturn(null);
            when(chatModelClient.generate(anyString())).thenReturn("Test summary content");
            when(kafkaTemplate.send(anyString(), anyString(), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            ChatMessage msg = chatMessage("room-fields", "msg-trigger", "hello");
            service.handleChatMessage(toJson(msg));

            ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
            verify(kafkaTemplate).send(eq(KafkaTopics.AI_SUMMARIES), eq("room-fields"), captor.capture());

            ChatMessage summaryMsg = captor.getValue();
            assertThat(summaryMsg.getChatRoomId()).isEqualTo("room-fields");
            assertThat(summaryMsg.getUserId()).isEqualTo("ai-system");
            assertThat(summaryMsg.getUsername()).isEqualTo("AI 요약봇");
            assertThat(summaryMsg.getContent()).isEqualTo("Test summary content");
            assertThat(summaryMsg.getType()).isEqualTo(ChatMessage.MessageType.AI_SUMMARY);
            assertThat(summaryMsg.isAiGenerated()).isTrue();
            assertThat(summaryMsg.getMessageId()).isNotNull();
        }

        @Test
        @DisplayName("HANDOFF roomType triggers SOAP prompt instead of summary prompt")
        void handoff_room_uses_soap_prompt() throws Exception {
            stubRedisForBuffering(10L);

            ChatMessage handoffMsg = ChatMessage.builder()
                    .messageId("m1")
                    .chatRoomId("room-handoff")
                    .userId("u1")
                    .username("doctor")
                    .content("patient update")
                    .type(ChatMessage.MessageType.CHAT)
                    .timestamp(LocalDateTime.of(2026, 7, 4, 12, 0, 0))
                    .roomType("HANDOFF")
                    .build();

            List<String> bufferedJsons = List.of(toJson(handoffMsg));
            when(redisTemplate.execute(any(RedisScript.class), anyList()))
                    .thenReturn(bufferedJsons);
            when(valueOps.get(eq(HASH_PREFIX + "room-handoff"))).thenReturn(null);
            when(chatModelClient.generate(anyString())).thenReturn("SOAP note");
            when(kafkaTemplate.send(anyString(), anyString(), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            ChatMessage triggerMsg = ChatMessage.builder()
                    .messageId("trigger-msg")
                    .chatRoomId("room-handoff")
                    .userId("u1")
                    .username("doctor")
                    .content("trigger")
                    .type(ChatMessage.MessageType.CHAT)
                    .timestamp(LocalDateTime.of(2026, 7, 4, 12, 0, 0))
                    .build();
            service.handleChatMessage(toJson(triggerMsg));

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(chatModelClient).generate(promptCaptor.capture());
            assertThat(promptCaptor.getValue()).contains("SOAP");
        }
    }

    // =======================================================================
    // Malformed JSON handling
    // =======================================================================
    @Nested
    @DisplayName("Malformed JSON handling")
    class MalformedJsonTests {

        @Test
        @DisplayName("malformed JSON is rethrown (-> DLT) and does not touch Redis")
        void malformed_json_rethrown() {
            assertThatThrownBy(() -> service.handleChatMessage("{ not valid json !!!"))
                    .isInstanceOf(org.apache.kafka.common.errors.SerializationException.class);

            verifyNoInteractions(redisTemplate);
            verifyNoInteractions(chatModelClient);
        }

        @Test
        @DisplayName("empty string is rethrown (-> DLT)")
        void empty_string_rethrown() {
            assertThatThrownBy(() -> service.handleChatMessage(""))
                    .isInstanceOf(org.apache.kafka.common.errors.SerializationException.class);

            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("JSON with missing required fields does not throw")
        void missing_fields_json_handled() {
            // A JSON object that deserializes to a ChatMessage with null type
            // — the guard `message.getType() != CHAT` handles it (null != CHAT → true → return)
            service.handleChatMessage("{\"chatRoomId\":\"room-x\",\"content\":\"hello\"}");

            verifyNoInteractions(chatModelClient);
        }
    }

    // =======================================================================
    // getSummaries tests
    // =======================================================================
    @Nested
    @DisplayName("getSummaries: retrieve cached summaries from Redis")
    class GetSummariesTests {

        private ChatMessage summary(String id, String content) {
            return ChatMessage.builder()
                    .messageId(id).chatRoomId("room-cached").userId("ai-system")
                    .username("AI 요약봇").content(content)
                    .type(ChatMessage.MessageType.AI_SUMMARY)
                    .timestamp(LocalDateTime.of(2026, 7, 4, 12, 0, 0))
                    .isAiGenerated(true).build();
        }

        @Test
        @DisplayName("returns empty list when no cached summary")
        void no_cache_returns_empty() {
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(listOps.range(eq(SUMMARY_PREFIX + "room-nocache"), eq(0L), eq(-1L))).thenReturn(List.of());

            assertThat(service.getSummaries("room-nocache")).isEmpty();
        }

        @Test
        @DisplayName("returns deserialized summaries in order when cache exists")
        void cache_hit_returns_summaries() throws Exception {
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(listOps.range(eq(SUMMARY_PREFIX + "room-cached"), eq(0L), eq(-1L)))
                    .thenReturn(List.of(toJson(summary("s1", "first")), toJson(summary("s2", "second"))));

            List<ChatMessage> result = service.getSummaries("room-cached");

            assertThat(result).extracting(ChatMessage::getContent).containsExactly("first", "second");
        }

        @Test
        @DisplayName("skips malformed entries")
        void malformed_entry_skipped() throws Exception {
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(listOps.range(eq(SUMMARY_PREFIX + "room-bad"), eq(0L), eq(-1L)))
                    .thenReturn(List.of("not json", toJson(summary("s1", "ok"))));

            assertThat(service.getSummaries("room-bad")).extracting(ChatMessage::getContent).containsExactly("ok");
        }
    }

    // =======================================================================
    // requestSummary tests
    // =======================================================================
    @Nested
    @DisplayName("requestSummary: on-demand summary generation")
    class RequestSummaryTests {

        @Test
        @DisplayName("returns false when buffer is empty")
        void empty_buffer_returns_false() {
            when(redisTemplate.execute(any(RedisScript.class), anyList()))
                    .thenReturn(List.of());

            boolean result = service.requestSummary("room-empty");

            assertThat(result).isFalse();
            verifyNoInteractions(chatModelClient);
        }

        @Test
        @DisplayName("returns true and generates summary when buffer has messages")
        void non_empty_buffer_generates_summary() throws Exception {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(redisTemplate.opsForList()).thenReturn(listOps);

            List<String> bufferedJsons = List.of(
                    toJson(chatMessage("room-req", "m1", "hello"))
            );
            when(redisTemplate.execute(any(RedisScript.class), anyList()))
                    .thenReturn(bufferedJsons);
            // requestSummary calls generateSummary directly (no hash check),
            // but generateSummary calls cacheSummary → getSummaries → valueOps.get(SUMMARY_PREFIX)
            // and then valueOps.set(HASH_PREFIX, ...) to store the hash.
            when(chatModelClient.generate(anyString())).thenReturn("On-demand summary");
            when(kafkaTemplate.send(anyString(), anyString(), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            boolean result = service.requestSummary("room-req");

            assertThat(result).isTrue();
            verify(chatModelClient).generate(anyString());
            verify(redisTemplate).delete(eq(BUFFER_TIME_PREFIX + "room-req"));
            verify(setOps).remove(eq(ACTIVE_ROOMS_KEY), eq("room-req"));
        }
    }

    // =======================================================================
    // checkTimeBasedTrigger tests
    // =======================================================================
    @Nested
    @DisplayName("checkTimeBasedTrigger: scheduled time-based summary trigger")
    class TimeBasedTriggerTests {

        @Test
        @DisplayName("no active rooms — returns immediately")
        void no_active_rooms_noop() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.members(eq(ACTIVE_ROOMS_KEY))).thenReturn(null);

            service.checkTimeBasedTrigger();

            verifyNoInteractions(chatModelClient);
            verify(redisTemplate, never()).opsForList();
        }

        @Test
        @DisplayName("empty active rooms set — returns immediately")
        void empty_active_rooms_noop() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.members(eq(ACTIVE_ROOMS_KEY))).thenReturn(Set.of());

            service.checkTimeBasedTrigger();

            verifyNoInteractions(chatModelClient);
        }

        @Test
        @DisplayName("active room with no time key — skipped")
        void no_time_key_skipped() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(setOps.members(eq(ACTIVE_ROOMS_KEY))).thenReturn(Set.of("room-notime"));
            when(valueOps.get(eq(BUFFER_TIME_PREFIX + "room-notime"))).thenReturn(null);

            service.checkTimeBasedTrigger();

            verifyNoInteractions(chatModelClient);
        }

        @Test
        @DisplayName("active room within 5-minute window — NOT triggered")
        void recent_room_not_triggered() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(setOps.members(eq(ACTIVE_ROOMS_KEY))).thenReturn(Set.of("room-recent"));
            // Set the time to 1 minute ago (within the 5-minute window)
            when(valueOps.get(eq(BUFFER_TIME_PREFIX + "room-recent")))
                    .thenReturn(LocalDateTime.now().minusMinutes(1).toString());

            service.checkTimeBasedTrigger();

            verifyNoInteractions(chatModelClient);
        }

        @Test
        @DisplayName("active room past 5-minute window with >= 3 messages — triggers summary")
        void stale_room_with_enough_messages_triggers() throws Exception {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(setOps.members(eq(ACTIVE_ROOMS_KEY))).thenReturn(Set.of("room-stale"));
            // Set the time to 10 minutes ago (past the 5-minute window)
            when(valueOps.get(eq(BUFFER_TIME_PREFIX + "room-stale")))
                    .thenReturn(LocalDateTime.now().minusMinutes(10).toString());
            when(listOps.size(eq(BUFFER_PREFIX + "room-stale"))).thenReturn(5L);

            List<String> bufferedJsons = List.of(
                    toJson(chatMessage("room-stale", "m1", "msg1")),
                    toJson(chatMessage("room-stale", "m2", "msg2")),
                    toJson(chatMessage("room-stale", "m3", "msg3"))
            );
            when(redisTemplate.execute(any(RedisScript.class), anyList()))
                    .thenReturn(bufferedJsons);
            when(valueOps.get(eq(HASH_PREFIX + "room-stale"))).thenReturn(null);
            when(chatModelClient.generate(anyString())).thenReturn("Time-based summary");
            when(kafkaTemplate.send(anyString(), anyString(), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            service.checkTimeBasedTrigger();

            verify(chatModelClient).generate(anyString());
            verify(redisTemplate).delete(eq(BUFFER_TIME_PREFIX + "room-stale"));
            verify(setOps).remove(eq(ACTIVE_ROOMS_KEY), eq("room-stale"));
        }

        @Test
        @DisplayName("active room past 5-minute window with < 3 messages — NOT triggered")
        void stale_room_with_few_messages_not_triggered() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(setOps.members(eq(ACTIVE_ROOMS_KEY))).thenReturn(Set.of("room-few"));
            when(valueOps.get(eq(BUFFER_TIME_PREFIX + "room-few")))
                    .thenReturn(LocalDateTime.now().minusMinutes(10).toString());
            when(listOps.size(eq(BUFFER_PREFIX + "room-few"))).thenReturn(2L);

            service.checkTimeBasedTrigger();

            verifyNoInteractions(chatModelClient);
        }

        @Test
        @DisplayName("empty consumeBuffer in time trigger — chatModelClient NOT called")
        void empty_consume_in_time_trigger() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(setOps.members(eq(ACTIVE_ROOMS_KEY))).thenReturn(Set.of("room-empty-t"));
            when(valueOps.get(eq(BUFFER_TIME_PREFIX + "room-empty-t")))
                    .thenReturn(LocalDateTime.now().minusMinutes(10).toString());
            when(listOps.size(eq(BUFFER_PREFIX + "room-empty-t"))).thenReturn(5L);
            when(redisTemplate.execute(any(RedisScript.class), anyList()))
                    .thenReturn(List.of());

            service.checkTimeBasedTrigger();

            verifyNoInteractions(chatModelClient);
        }
    }

    // =======================================================================
    // answerQuestion tests
    // =======================================================================
    @Nested
    @DisplayName("answerQuestion: AI Q&A with context")
    class AnswerQuestionTests {

        @Test
        @DisplayName("generates answer with conversation context")
        void answer_with_context() throws Exception {
            when(redisTemplate.opsForList()).thenReturn(listOps);

            List<String> rawMessages = List.of(
                    toJson(chatMessage("room-qa", "m1", "hello doctor"))
            );
            when(listOps.range(eq(BUFFER_PREFIX + "room-qa"), eq(0L), eq(-1L)))
                    .thenReturn(rawMessages);
            when(chatModelClient.generate(anyString())).thenReturn("AI answer");
            when(kafkaTemplate.send(anyString(), anyString(), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            ChatMessage result = service.answerQuestion("room-qa", "What happened?");

            assertThat(result.getContent()).isEqualTo("AI answer");
            assertThat(result.getUserId()).isEqualTo("ai-system");
            assertThat(result.getUsername()).isEqualTo("ChatFlow AI");
            assertThat(result.getType()).isEqualTo(ChatMessage.MessageType.AI_SUMMARY);
            assertThat(result.isAiGenerated()).isTrue();
            verify(kafkaTemplate).send(eq(KafkaTopics.AI_SUMMARIES), eq("room-qa"), any(ChatMessage.class));
        }

        @Test
        @DisplayName("generates answer without context (empty buffer)")
        void answer_without_context() {
            when(redisTemplate.opsForList()).thenReturn(listOps);

            when(listOps.range(eq(BUFFER_PREFIX + "room-empty-qa"), eq(0L), eq(-1L)))
                    .thenReturn(null);
            when(chatModelClient.generate(anyString())).thenReturn("General answer");
            when(kafkaTemplate.send(anyString(), anyString(), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            ChatMessage result = service.answerQuestion("room-empty-qa", "General question?");

            assertThat(result.getContent()).isEqualTo("General answer");
            // Verify prompt includes "현재 대화 내역이 없습니다."
            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(chatModelClient).generate(promptCaptor.capture());
            assertThat(promptCaptor.getValue()).contains("현재 대화 내역이 없습니다.");
        }
    }

    // =======================================================================
    // generateShiftReport tests
    // =======================================================================
    @Nested
    @DisplayName("generateShiftReport: shift handover report generation")
    class ShiftReportTests {

        @Test
        @DisplayName("generates shift report from buffer context")
        void generates_report() throws Exception {
            when(redisTemplate.opsForList()).thenReturn(listOps);

            List<String> rawMessages = List.of(
                    toJson(chatMessage("room-shift", "m1", "patient stable"))
            );
            when(listOps.range(eq(BUFFER_PREFIX + "room-shift"), eq(0L), eq(-1L)))
                    .thenReturn(rawMessages);
            when(chatModelClient.generate(anyString())).thenReturn("Shift report content");
            when(kafkaTemplate.send(anyString(), anyString(), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            ChatMessage result = service.generateShiftReport("room-shift");

            assertThat(result.getContent()).isEqualTo("Shift report content");
            assertThat(result.getUsername()).isEqualTo("AI 교대봇");
            assertThat(result.isAiGenerated()).isTrue();

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(chatModelClient).generate(promptCaptor.capture());
            assertThat(promptCaptor.getValue()).contains("교대 인수인계 보고서");
        }

        @Test
        @DisplayName("generates shift report with empty context")
        void generates_report_empty_context() {
            when(redisTemplate.opsForList()).thenReturn(listOps);

            when(listOps.range(eq(BUFFER_PREFIX + "room-shift-e"), eq(0L), eq(-1L)))
                    .thenReturn(null);
            when(chatModelClient.generate(anyString())).thenReturn("Empty shift report");
            when(kafkaTemplate.send(anyString(), anyString(), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            ChatMessage result = service.generateShiftReport("room-shift-e");

            assertThat(result.getContent()).isEqualTo("Empty shift report");
            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(chatModelClient).generate(promptCaptor.capture());
            assertThat(promptCaptor.getValue()).contains("현재 대화 내역이 없습니다.");
        }
    }

    // =======================================================================
    // cacheSummary integration (via generateSummary path)
    // =======================================================================
    @Nested
    @DisplayName("cacheSummary: atomic append to the cached summary list")
    class CacheSummaryTests {

        @Test
        @DisplayName("cacheSummary_concurrentAppends_bothKept: appends via RPUSH + LTRIM + EXPIRE, never read-modify-write")
        void cacheSummary_concurrentAppends_bothKept() throws Exception {
            stubRedisForBuffering(10L);
            when(redisTemplate.execute(any(RedisScript.class), anyList()))
                    .thenReturn(List.of(toJson(chatMessage("room-append", "m1", "msg1"))));
            when(valueOps.get(eq(HASH_PREFIX + "room-append"))).thenReturn(null);
            when(chatModelClient.generate(anyString())).thenReturn("new summary");
            when(kafkaTemplate.send(anyString(), anyString(), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            service.handleChatMessage(toJson(chatMessage("room-append", "msg-trigger", "hello")));

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(listOps).rightPush(eq(SUMMARY_PREFIX + "room-append"), jsonCaptor.capture());
            verify(listOps).trim(SUMMARY_PREFIX + "room-append", -50L, -1L);
            verify(redisTemplate).expire(SUMMARY_PREFIX + "room-append", Duration.ofHours(24));
            // no whole-list overwrite of the summary key
            verify(valueOps, never()).set(eq(SUMMARY_PREFIX + "room-append"), anyString(), any(Duration.class));
            assertThat(objectMapper.readValue(jsonCaptor.getValue(), ChatMessage.class).getContent())
                    .isEqualTo("new summary");
        }
    }

    // =======================================================================
    // B-9 / B-10 / B-11 regression tests
    // =======================================================================
    @Nested
    @DisplayName("Audit remediation: idempotent buffer, honest rate-limit, DLT rethrow")
    class AuditRemediationTests {

        @Test
        @DisplayName("buffer_duplicateMessageId_notCountedTwice")
        void buffer_duplicateMessageId_notCountedTwice() throws Exception {
            stubRedisForBuffering(1L);
            String idsKey = RedisKeys.BUFFER_IDS_PREFIX + "room-dup";
            when(setOps.add(idsKey, "m-dup")).thenReturn(1L).thenReturn(0L);

            String json = toJson(chatMessage("room-dup", "m-dup", "hello"));
            service.handleChatMessage(json);
            service.handleChatMessage(json);

            verify(listOps, times(1)).rightPush(eq(BUFFER_PREFIX + "room-dup"), anyString());
        }

        @Test
        @DisplayName("requestSummary_rateLimited_returnsFalse_andTimerReRegistered")
        void requestSummary_rateLimited_returnsFalse_andTimerReRegistered() throws Exception {
            stubRedisForBuffering(0L);
            when(chatModelClient.generate(anyString())).thenReturn("s");
            when(kafkaTemplate.send(anyString(), anyString(), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            when(redisTemplate.execute(any(RedisScript.class), anyList())).thenReturn(
                    List.of(toJson(chatMessage("room-rl", "m1", "one")), toJson(chatMessage("room-rl", "m2", "two"))));
            // drain the 10-token bucket
            for (int i = 0; i < 10; i++) {
                assertThat(service.requestSummary("room-rl")).isTrue();
            }
            clearInvocations(listOps, setOps, valueOps, redisTemplate);
            stubRedisForBuffering(0L);

            assertThat(service.requestSummary("room-rl")).isFalse();
            assertThat(service.requestSummaryOutcome("room-rl"))
                    .isEqualTo(AiSummaryService.SummaryOutcome.RATE_LIMITED);

            // re-queued at the front in original order (m2 pushed first, m1 last)
            org.mockito.InOrder order = inOrder(listOps);
            order.verify(listOps).leftPush(eq(BUFFER_PREFIX + "room-rl"), contains("\"m2\""));
            order.verify(listOps).leftPush(eq(BUFFER_PREFIX + "room-rl"), contains("\"m1\""));
            // timer + active-room registration restored
            verify(valueOps, atLeastOnce()).set(eq(BUFFER_TIME_PREFIX + "room-rl"), anyString(), any(Duration.class));
            verify(setOps, atLeastOnce()).add(ACTIVE_ROOMS_KEY, "room-rl");
        }

        @Test
        @DisplayName("listener_badJson_throws")
        void listener_badJson_throws() {
            assertThatThrownBy(() -> service.handleChatMessage("{ not valid json !!!"))
                    .isInstanceOf(org.apache.kafka.common.errors.SerializationException.class);
            verifyNoInteractions(redisTemplate);
        }
    }
}
