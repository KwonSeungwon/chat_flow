package com.chatflow.gateway.repository;

import com.chatflow.gateway.entity.UserEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserRepository extends ReactiveCrudRepository<UserEntity, Long> {
    Mono<UserEntity> findByUsername(String username);
    Mono<UserEntity> findByUserId(String userId);
    Mono<Boolean> existsByUsername(String username);
    Mono<Boolean> existsByUsernameIgnoreCase(String username);
    Flux<UserEntity> findByUsernameContainingIgnoreCaseOrderByUsernameAsc(String query);

    // 엔티티 전체 save는 읽은 뒤 쓰기 사이의 다른 컬럼 변경을 덮어쓴다(lost update). 바꿀 컬럼만 UPDATE한다.
    @Modifying
    @Query("UPDATE users SET encoded_password = :value WHERE user_id = :userId")
    Mono<Integer> updateEncodedPassword(@Param("userId") String userId, @Param("value") String value);

    @Modifying
    @Query("UPDATE users SET profile_image_url = :value WHERE user_id = :userId")
    Mono<Integer> updateProfileImageUrl(@Param("userId") String userId, @Param("value") String value);

    @Modifying
    @Query("UPDATE users SET status_message = :value WHERE user_id = :userId")
    Mono<Integer> updateStatusMessage(@Param("userId") String userId, @Param("value") String value);

    @Modifying
    @Query("UPDATE users SET bio = :value WHERE user_id = :userId")
    Mono<Integer> updateBio(@Param("userId") String userId, @Param("value") String value);
}
