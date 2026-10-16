package com.chatflow.chat.game.repository;

import com.chatflow.chat.game.entity.GameQuestion;
import com.chatflow.chat.game.entity.GameQuestionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public interface GameQuestionRepository extends JpaRepository<GameQuestion, Long> {

    boolean existsByContentHash(String contentHash);

    Optional<GameQuestion> findByContentHash(String contentHash);

    Page<GameQuestion> findByStatusOrderByIdAsc(GameQuestionStatus status, Pageable pageable);

    /** Projection row for {@link #countApprovedByDungeon()}. */
    interface DungeonCount {
        String getDungeonId();

        long getQuestionCount();
    }

    @Query("SELECT t.dungeonId AS dungeonId, COUNT(q) AS questionCount FROM GameQuestion q, GameTopic t "
            + "WHERE q.topicId = t.id AND q.status = com.chatflow.chat.game.entity.GameQuestionStatus.APPROVED "
            + "GROUP BY t.dungeonId")
    List<DungeonCount> countApprovedGroupedByDungeon();

    /** dungeonId → number of APPROVED questions. Dungeons without any are absent. */
    default Map<String, Long> countApprovedByDungeon() {
        return countApprovedGroupedByDungeon().stream()
                .collect(Collectors.toMap(DungeonCount::getDungeonId, DungeonCount::getQuestionCount));
    }

    @Query("SELECT q.id FROM GameQuestion q, GameTopic t "
            + "WHERE q.topicId = t.id AND t.dungeonId = :dungeonId "
            + "AND q.status = com.chatflow.chat.game.entity.GameQuestionStatus.APPROVED")
    List<Long> findApprovedIdsByDungeon(@Param("dungeonId") String dungeonId);
}
