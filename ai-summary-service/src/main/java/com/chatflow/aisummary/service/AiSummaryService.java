package com.chatflow.aisummary.service;

import com.chatflow.aisummary.client.ChatModelClient;
import com.chatflow.common.dto.ChatMessage;
import com.chatflow.common.dto.KafkaTopics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bucket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import org.springframework.beans.factory.annotation.Qualifier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AiSummaryService {

    private final ChatModelClient chatModelClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Executor geminiExecutor;

    private static final int SUMMARY_TRIGGER_COUNT = 10;
    private static final int MAX_MESSAGES_PER_ROOM = 100;
    private static final int MIN_MESSAGES_FOR_TIME_TRIGGER = 3;
    private static final Duration TIME_TRIGGER_DELAY = Duration.ofMinutes(5);

    // Redis 키 프리픽스
    private static final String REDIS_SUMMARY_PREFIX = "chatflow:summary:";
    private static final String REDIS_HASH_PREFIX = "chatflow:summary-hash:";
    private static final String REDIS_BUFFER_PREFIX = "chatflow:buffer:";
    private static final String REDIS_BUFFER_TIME_PREFIX = "chatflow:buffer-time:";
    private static final String REDIS_ACTIVE_ROOMS_KEY = "chatflow:active-rooms";
    private static final Duration REDIS_SUMMARY_TTL = Duration.ofHours(24);
    private static final Duration REDIS_BUFFER_TTL = Duration.ofHours(1);

    // Rate Limiter: 분당 10회 Gemini API 호출 제한
    private final Bucket rateLimiter = Bucket.builder()
            .addLimit(limit -> limit.capacity(10).refillGreedy(10, Duration.ofMinutes(1)))
            .build();

    public AiSummaryService(ChatModelClient chatModelClient,
                            KafkaTemplate<String, Object> kafkaTemplate,
                            StringRedisTemplate redisTemplate,
                            ObjectMapper objectMapper,
                            @Qualifier("geminiExecutor") Executor geminiExecutor) {
        this.chatModelClient = chatModelClient;
        this.kafkaTemplate = kafkaTemplate;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.geminiExecutor = geminiExecutor;
    }

    // The AI_SUMMARY_REQUESTS topic listener was removed — MessageSenderService
    // publishes long CHAT messages to BOTH chat-messages and ai-summary-requests,
    // so listening on both buffered every long message twice (the 10-message
    // trigger fired after 5 real messages, summary prompt had duplicates).
    // chat-messages alone covers every CHAT message.

    @KafkaListener(topics = KafkaTopics.CHAT_MESSAGES)
    public void handleChatMessage(String messageJson) {
        try {
            ChatMessage message = objectMapper.readValue(messageJson, ChatMessage.class);
            log.debug("Storing message for potential summary: {}", message.getMessageId());
            addMessageAndCheckTrigger(message);
        } catch (JsonProcessingException e) {
            log.error("채팅 메시지 역직렬화 실패", e);
        }
    }

    private void addMessageAndCheckTrigger(ChatMessage message) {
        // Only real user CHAT messages contribute to the summary buffer.
        // JOIN/LEAVE/SYSTEM/FILE/AI_SUMMARY would inflate the 10-msg trigger
        // and pollute the summary content.
        if (message.getType() != ChatMessage.MessageType.CHAT) {
            return;
        }

        String roomId = message.getChatRoomId();
        String bufferKey = REDIS_BUFFER_PREFIX + roomId;
        String timeKey = REDIS_BUFFER_TIME_PREFIX + roomId;

        try {
            String messageJson = objectMapper.writeValueAsString(message);
            redisTemplate.opsForList().rightPush(bufferKey, messageJson);
            redisTemplate.expire(bufferKey, REDIS_BUFFER_TTL);

            redisTemplate.opsForValue().set(timeKey, LocalDateTime.now().toString(), REDIS_BUFFER_TTL);

            // 활성 방 Set에 등록 (SCAN 대체)
            redisTemplate.opsForSet().add(REDIS_ACTIVE_ROOMS_KEY, roomId);

            // 버퍼 최대 크기 유지
            Long bufferSize = redisTemplate.opsForList().size(bufferKey);
            if (bufferSize != null && bufferSize > MAX_MESSAGES_PER_ROOM) {
                redisTemplate.opsForList().trim(bufferKey, bufferSize - MAX_MESSAGES_PER_ROOM, -1);
            }

            // 메시지 카운트 기반 트리거 — Gemini 호출을 전용 Executor로 오프로드
            if (bufferSize != null && bufferSize >= SUMMARY_TRIGGER_COUNT) {
                List<ChatMessage> snapshot = consumeBuffer(bufferKey);
                if (!snapshot.isEmpty()) {
                    redisTemplate.delete(timeKey);
                    redisTemplate.opsForSet().remove(REDIS_ACTIVE_ROOMS_KEY, roomId);
                    final String rid = roomId;
                    final List<ChatMessage> msgs = snapshot;
                    geminiExecutor.execute(() -> {
                        try {
                            generateSummaryIfNeeded(rid, msgs);
                        } catch (Exception e) {
                            log.error("Gemini executor task failed for room {}", rid, e);
                        }
                    });
                }
            }
        } catch (JsonProcessingException e) {
            log.error("메시지 직렬화 실패: room={}", roomId, e);
        }
    }

    /**
     * 시간 기반 트리거: 마지막 메시지로부터 5분 경과 시 자동 요약
     * active_rooms Set을 조회하여 활성 방만 체크 (SCAN 대비 O(활성방수))
     */
    @Scheduled(fixedRate = 60000)
    public void checkTimeBasedTrigger() {
        LocalDateTime now = LocalDateTime.now();
        Set<String> activeRooms = redisTemplate.opsForSet().members(REDIS_ACTIVE_ROOMS_KEY);

        if (activeRooms == null || activeRooms.isEmpty()) return;

        for (String roomId : activeRooms) {
            String timeKey = REDIS_BUFFER_TIME_PREFIX + roomId;
            String lastTimeStr = redisTemplate.opsForValue().get(timeKey);
            if (lastTimeStr == null) continue;

            try {
                LocalDateTime lastTime = LocalDateTime.parse(lastTimeStr);
                if (Duration.between(lastTime, now).compareTo(TIME_TRIGGER_DELAY) >= 0) {
                    String bufferKey = REDIS_BUFFER_PREFIX + roomId;
                    Long size = redisTemplate.opsForList().size(bufferKey);

                    if (size != null && size >= MIN_MESSAGES_FOR_TIME_TRIGGER) {
                        List<ChatMessage> snapshot = consumeBuffer(bufferKey);
                        redisTemplate.delete(timeKey);
                        redisTemplate.opsForSet().remove(REDIS_ACTIVE_ROOMS_KEY, roomId);

                        if (!snapshot.isEmpty()) {
                            log.info("시간 기반 트리거 발동: room={}, messages={}", roomId, snapshot.size());
                            final String rid = roomId;
                            final List<ChatMessage> msgs = snapshot;
                            geminiExecutor.execute(() -> {
                                try {
                                    generateSummaryIfNeeded(rid, msgs);
                                } catch (Exception e) {
                                    log.error("Gemini executor task failed for room {}", rid, e);
                                }
                            });
                        }
                    }
                }
            } catch (Exception e) {
                log.error("시간 기반 트리거 처리 실패: room={}", roomId, e);
            }
        }
    }

    /**
     * Redis 버퍼를 원자적으로 읽고 삭제 (Lua 스크립트).
     * range() + delete() 사이에 새 메시지가 유실되는 race condition 방지.
     */
    private List<ChatMessage> consumeBuffer(String bufferKey) {
        String luaScript = "local items = redis.call('LRANGE', KEYS[1], 0, -1); " +
                "redis.call('DEL', KEYS[1]); " +
                "return items;";

        @SuppressWarnings("unchecked")
        List<String> jsonList = redisTemplate.execute(
                new org.springframework.data.redis.core.script.DefaultRedisScript<>(luaScript, List.class),
                List.of(bufferKey));

        if (jsonList == null || jsonList.isEmpty()) {
            return List.of();
        }

        return jsonList.stream()
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, ChatMessage.class);
                    } catch (JsonProcessingException e) {
                        log.error("메시지 역직렬화 실패", e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private void generateSummaryIfNeeded(String roomId, List<ChatMessage> messages) {
        String hash = computeMessageHash(messages);
        String hashKey = REDIS_HASH_PREFIX + roomId;

        String previousHash = redisTemplate.opsForValue().get(hashKey);
        if (hash.equals(previousHash)) {
            log.info("동일 메시지셋 감지, 요약 생성 스킵: room={}", roomId);
            return;
        }

        generateSummary(roomId, messages, hash);
    }

    private void generateSummary(String roomId, List<ChatMessage> messages, String hash) {
        if (!rateLimiter.tryConsume(1)) {
            log.warn("Rate limit 초과, 요약 생성 보류: room={}", roomId);
            // 메시지를 다시 Redis 버퍼로 재큐잉
            String bufferKey = REDIS_BUFFER_PREFIX + roomId;
            for (ChatMessage msg : messages) {
                try {
                    String json = objectMapper.writeValueAsString(msg);
                    redisTemplate.opsForList().rightPush(bufferKey, json);
                } catch (JsonProcessingException e) {
                    log.error("메시지 재큐잉 직렬화 실패", e);
                }
            }
            redisTemplate.expire(bufferKey, REDIS_BUFFER_TTL);
            return;
        }

        try {
            log.info("Generating AI summary for room: {} with {} messages", roomId, messages.size());

            String conversationText = buildConversationText(messages);
            boolean isHandoff = messages.stream()
                    .anyMatch(m -> "HANDOFF".equals(m.getRoomType()));
            String summaryPrompt = isHandoff
                    ? buildSoapPrompt(conversationText)
                    : buildSummaryPrompt(conversationText);

            String summary = chatModelClient.generate(summaryPrompt);

            ChatMessage summaryMessage = ChatMessage.builder()
                    .messageId(UUID.randomUUID().toString())
                    .chatRoomId(roomId)
                    .userId("ai-system")
                    .username("AI 요약봇")
                    .content(summary)
                    .type(ChatMessage.MessageType.AI_SUMMARY)
                    .timestamp(LocalDateTime.now())
                    .isAiGenerated(true)
                    .build();

            cacheSummary(roomId, summaryMessage);
            redisTemplate.opsForValue().set(REDIS_HASH_PREFIX + roomId, hash, REDIS_SUMMARY_TTL);

            kafkaTemplate.send(KafkaTopics.AI_SUMMARIES, roomId, summaryMessage)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to send summary to Kafka for room: {}", roomId, ex);
                        }
                    });

            log.info("AI summary generated and sent for room: {}", roomId);

        } catch (Exception e) {
            log.error("Error generating AI summary for room: {}", roomId, e);
        }
    }

    public List<ChatMessage> getSummaries(String roomId) {
        String key = REDIS_SUMMARY_PREFIX + roomId;
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize summaries for room: {}", roomId, e);
            return List.of();
        }
    }

    public boolean requestSummary(String roomId) {
        String bufferKey = REDIS_BUFFER_PREFIX + roomId;
        List<ChatMessage> snapshot = consumeBuffer(bufferKey);

        if (!snapshot.isEmpty()) {
            redisTemplate.delete(REDIS_BUFFER_TIME_PREFIX + roomId);
            redisTemplate.opsForSet().remove(REDIS_ACTIVE_ROOMS_KEY, roomId);
            String hash = computeMessageHash(snapshot);
            generateSummary(roomId, snapshot, hash);
            return true;
        } else {
            log.info("No messages to summarize for room: {}", roomId);
            return false;
        }
    }

    /**
     * AI Q&A: 최근 대화 맥락을 참고해 질문에 답변한다.
     * 응답은 Kafka ai-summaries 토픽으로 브로드캐스트되어 방 전체에 표시된다.
     */
    public ChatMessage answerQuestion(String roomId, String question) {
        if (!rateLimiter.tryConsume(1)) {
            throw new IllegalStateException("AI 호출 한도를 초과했습니다. 잠시 후 다시 시도해 주세요.");
        }

        String bufferKey = REDIS_BUFFER_PREFIX + roomId;
        List<String> rawMessages = redisTemplate.opsForList().range(bufferKey, 0, -1);
        List<ChatMessage> recentMessages = new ArrayList<>();
        if (rawMessages != null) {
            for (String json : rawMessages) {
                try {
                    recentMessages.add(objectMapper.readValue(json, ChatMessage.class));
                } catch (JsonProcessingException e) {
                    log.warn("Q&A 컨텍스트 메시지 역직렬화 실패", e);
                }
            }
        }

        String contextText = recentMessages.isEmpty()
                ? "현재 대화 내역이 없습니다."
                : buildConversationText(recentMessages);

        String prompt = String.format("""
                채팅방의 최근 대화 내용을 참고하여 사용자의 질문에 답해주세요.

                최근 대화:
                %s

                질문: %s

                답변 규칙:
                1. 한국어로 답변하세요
                2. 대화 맥락이 있으면 활용하고, 없으면 일반 지식으로 답변하세요
                3. 간결하고 명확하게 답변하세요
                4. 존댓말을 사용하세요
                """, contextText, question);

        String answer = chatModelClient.generate(prompt);

        ChatMessage response = ChatMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .chatRoomId(roomId)
                .userId("ai-system")
                .username("ChatFlow AI")
                .content(answer)
                .type(ChatMessage.MessageType.AI_SUMMARY)
                .timestamp(LocalDateTime.now())
                .isAiGenerated(true)
                .build();

        cacheSummary(roomId, response);
        kafkaTemplate.send(KafkaTopics.AI_SUMMARIES, roomId, response);

        return response;
    }

    private void cacheSummary(String roomId, ChatMessage summaryMessage) {
        String key = REDIS_SUMMARY_PREFIX + roomId;
        try {
            List<ChatMessage> existing = getSummaries(roomId);
            List<ChatMessage> updated = new ArrayList<>(existing);
            updated.add(summaryMessage);
            String json = objectMapper.writeValueAsString(updated);
            redisTemplate.opsForValue().set(key, json, REDIS_SUMMARY_TTL);
        } catch (JsonProcessingException e) {
            log.error("Failed to cache summary for room: {}", roomId, e);
        }
    }

    private String computeMessageHash(List<ChatMessage> messages) {
        String ids = messages.stream()
                .map(ChatMessage::getMessageId)
                .filter(Objects::nonNull)
                .sorted()
                .collect(Collectors.joining(","));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(ids.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return ids.hashCode() + "";
        }
    }

    private String buildConversationText(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message.getType() == ChatMessage.MessageType.CHAT) {
                sb.append(message.getUsername())
                  .append(": ")
                  .append(message.getContent())
                  .append("\n");
            }
        }
        return sb.toString();
    }

    private String buildSummaryPrompt(String conversationText) {
        return String.format("""
            다음 채팅 대화를 요약해주세요. 주요 내용과 결론을 포함하여 간결하게 요약해주세요.

            대화 내용:
            %s

            요약 규칙:
            1. 주요 토픽과 결론을 중심으로 요약
            2. 3-5문장으로 간결하게 작성
            3. 한국어로 작성
            4. 존댓말 사용
            """, conversationText);
    }

    public ChatMessage generateShiftReport(String roomId) {
        if (!rateLimiter.tryConsume(1)) {
            throw new IllegalStateException("AI 호출 한도를 초과했습니다. 잠시 후 다시 시도해 주세요.");
        }

        String bufferKey = REDIS_BUFFER_PREFIX + roomId;
        List<String> rawMessages = redisTemplate.opsForList().range(bufferKey, 0, -1);
        List<ChatMessage> recentMessages = new ArrayList<>();
        if (rawMessages != null) {
            for (String json : rawMessages) {
                try {
                    recentMessages.add(objectMapper.readValue(json, ChatMessage.class));
                } catch (JsonProcessingException e) {
                    log.warn("교대 보고서 컨텍스트 메시지 역직렬화 실패", e);
                }
            }
        }

        String contextText = recentMessages.isEmpty()
                ? "현재 대화 내역이 없습니다."
                : buildConversationText(recentMessages);

        String prompt = buildShiftReportPrompt(contextText);
        String report = chatModelClient.generate(prompt);

        ChatMessage response = ChatMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .chatRoomId(roomId)
                .userId("ai-system")
                .username("AI 교대봇")
                .content(report)
                .type(ChatMessage.MessageType.AI_SUMMARY)
                .timestamp(LocalDateTime.now())
                .isAiGenerated(true)
                .build();

        cacheSummary(roomId, response);
        kafkaTemplate.send(KafkaTopics.AI_SUMMARIES, roomId, response);
        return response;
    }

    private String buildShiftReportPrompt(String conversationText) {
        return String.format("""
            다음은 의료진 채팅방의 대화 내용입니다.
            의료진 교대 인수인계 보고서 형식으로 요약해주세요.

            대화 내용:
            %s

            교대 인수인계 보고서 형식으로 작성:
            [시간대별 주요 이벤트]
            - 각 시간대의 주요 사건과 처치 내용 기록

            [환자 상태 변화]
            - 활력징후 변화, 증상 변화, 투약 변경 사항

            [다음 근무조 주의사항]
            - 지속 모니터링 필요 사항
            - 예정된 처치 및 검사
            - 특이사항 및 주의 환자

            작성 규칙:
            1. 대화에서 추출 가능한 임상 정보만 기록
            2. 시간 순서대로 정리
            3. 한국어로 작성
            4. 대화에 없는 내용은 '언급 없음'으로 표기
            """, conversationText);
    }

    private String buildSoapPrompt(String conversationText) {
        return String.format("""
            다음은 의료진 인수인계 채팅방의 대화 내용입니다.
            SOAP 형식으로 임상 노트를 작성해주세요.

            대화 내용:
            %s

            SOAP 형식으로 작성:
            [S] Subjective (주관적 소견): 환자 호소, 증상, 불편감
            [O] Objective (객관적 소견): 활력징후, 검사결과, 관찰 소견
            [A] Assessment (평가): 현재 상태 평가, 진단명, 주요 문제
            [P] Plan (계획): 치료계획, 투약, 모니터링, 다음 조치

            작성 규칙:
            1. 대화에서 추출 가능한 임상 정보만 기록
            2. 각 섹션은 핵심만 간결하게 작성
            3. 한국어로 작성
            4. 대화에 없는 내용은 '언급 없음'으로 표기
            """, conversationText);
    }
}
