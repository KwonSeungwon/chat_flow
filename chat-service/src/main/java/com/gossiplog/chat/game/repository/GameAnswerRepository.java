package com.gossiplog.chat.game.repository;

import com.gossiplog.chat.game.entity.GameAnswer;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface GameAnswerRepository extends JpaRepository<GameAnswer, Long> {

    /**
     * Approved questions of a dungeon whose latest answer by this user is wrong and due,
     * oldest due first. "Latest" = the highest answer id per (user, question).
     */
    @Query("SELECT a.questionId FROM GameAnswer a "
            + "WHERE a.userId = :userId AND a.correct = false AND a.nextDueAt <= :now "
            + "AND a.id = (SELECT MAX(a2.id) FROM GameAnswer a2 "
            + "            WHERE a2.userId = a.userId AND a2.questionId = a.questionId) "
            + "AND a.questionId IN (SELECT q.id FROM GameQuestion q, GameTopic t "
            + "    WHERE q.topicId = t.id AND t.dungeonId = :dungeonId "
            + "    AND q.status = com.gossiplog.chat.game.entity.GameQuestionStatus.APPROVED) "
            + "ORDER BY a.nextDueAt ASC, a.id ASC")
    List<Long> findDueWrongQuestionIds(@Param("userId") String userId,
                                       @Param("dungeonId") String dungeonId,
                                       @Param("now") LocalDateTime now,
                                       Pageable pageable);

    /** The two most recent answers — enough to know a previous correct streak of 0, 1 or ≥2. */
    List<GameAnswer> findTop2ByUserIdAndQuestionIdOrderByIdDesc(String userId, Long questionId);

    /** Projection row for {@link #countCorrectGroupedByUser()}. */
    interface UserCorrectCount {
        String getUserId();

        long getCorrectCount();
    }

    /** Distinct questions answered correctly — replaying a question does not raise it. */
    @Query("SELECT a.userId AS userId, COUNT(DISTINCT a.questionId) AS correctCount FROM GameAnswer a "
            + "WHERE a.correct = true GROUP BY a.userId")
    List<UserCorrectCount> countCorrectGroupedByUser();
}
