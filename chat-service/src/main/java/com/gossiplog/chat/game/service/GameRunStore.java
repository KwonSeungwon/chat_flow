package com.gossiplog.chat.game.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gossiplog.chat.game.exception.GameException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

/** Valkey persistence of {@link RunState}: JSON per run, a 30-minute TTL and per-seq answer claims. */
@Slf4j
class GameRunStore {

    private static final String RUN_KEY_PREFIX = "gossiplog:game:run:";
    private static final Duration RUN_TTL = Duration.ofMinutes(30);
    private static final Duration SEQ_CLAIM_TTL = Duration.ofSeconds(10);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    GameRunStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }


    RunState load(String userId, String runId) {
        String json;
        try {
            json = redis.opsForValue().get(RUN_KEY_PREFIX + runId);
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
        RunState state = null;
        if (json != null) {
            try {
                state = objectMapper.readValue(json, RunState.class);
            } catch (JsonProcessingException e) {
                log.warn("Unreadable run state {}", runId, e);
            }
        }
        if (state == null || !userId.equals(state.getUserId())) {
            throw runExpired();
        }
        return state;
    }

    static GameException runExpired() {
        return new GameException(404, "RUN_EXPIRED", "던전이 무너졌습니다.");
    }

    String json(RunState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize run state", e);
        }
    }

    void save(RunState state) {
        String json = json(state);
        try {
            redis.opsForValue().set(RUN_KEY_PREFIX + state.getRunId(), json, RUN_TTL);
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    /**
     * The run state is written inside the answer transaction (a Valkey outage then rolls the
     * answer back). If the transaction itself rolls back afterwards, put the state and the seq
     * claim back so Valkey never runs ahead of the database.
     */
    void restoreOnRollback(String runId, int seq, String previousJson) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) return;
                try {
                    redis.opsForValue().set(RUN_KEY_PREFIX + runId, previousJson, RUN_TTL);
                } catch (DataAccessException e) {
                    log.warn("Could not restore run state {} after rollback: {}", runId, e.getMessage());
                }
                releaseSeq(runId, seq);
            }
        });
    }

    void touch(String runId) {
        try {
            redis.expire(RUN_KEY_PREFIX + runId, RUN_TTL);
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    /** {@code SET ...:seq:{seq} NX EX 10}; false if another request already holds this seq. */
    boolean claimSeq(String runId, int seq) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue()
                    .setIfAbsent(seqClaimKey(runId, seq), "1", SEQ_CLAIM_TTL));
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    void releaseSeq(String runId, int seq) {
        try {
            redis.delete(seqClaimKey(runId, seq));
        } catch (DataAccessException e) {
            log.warn("Could not release seq claim {}:{} (expires in {}s)", runId, seq, SEQ_CLAIM_TTL.toSeconds());
        }
    }

    private static String seqClaimKey(String runId, int seq) {
        return RUN_KEY_PREFIX + runId + ":seq:" + seq;
    }

    private static GameException unavailable(DataAccessException e) {
        log.warn("Valkey unavailable for game run: {}", e.getMessage());
        return new GameException(503, "GAME_UNAVAILABLE", "게임 서버를 잠시 사용할 수 없습니다.");
    }
}
