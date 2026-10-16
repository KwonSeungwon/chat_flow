package com.chatflow.chat.game.repository;

import com.chatflow.chat.game.entity.GameProgress;
import com.chatflow.chat.game.entity.GameProgressId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface GameProgressRepository extends JpaRepository<GameProgress, GameProgressId> {

    List<GameProgress> findByUserId(String userId);

    /** Projection row for {@link #countClearedGroupedByDungeon()}. */
    interface DungeonClearCount {
        String getDungeonId();

        long getClearCount();
    }

    @Query("SELECT p.dungeonId AS dungeonId, COUNT(p) AS clearCount FROM GameProgress p "
            + "WHERE p.clearedAt IS NOT NULL GROUP BY p.dungeonId")
    List<DungeonClearCount> countClearedGroupedByDungeon();

    /** Projection row for {@link #countClearedGroupedByUser()}. */
    interface UserClearCount {
        String getUserId();

        String getUsername();

        long getClearedCount();
    }

    /** Every user with a progress row; {@code clearedCount} counts only rows with {@code cleared_at}. */
    @Query("SELECT p.userId AS userId, MAX(p.username) AS username, "
            + "SUM(CASE WHEN p.clearedAt IS NOT NULL THEN 1 ELSE 0 END) AS clearedCount "
            + "FROM GameProgress p GROUP BY p.userId")
    List<UserClearCount> countClearedGroupedByUser();
}
