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

    /** 프로필 필드를 한 문장으로 갱신한다(부분 실패 없음). set* 가 false인 컬럼은 그대로 둔다. null 값은 NULL 저장. */
    @Modifying
    @Query("UPDATE users SET "
            + "profile_image_url = CASE WHEN :setImage THEN CAST(:image AS VARCHAR) ELSE profile_image_url END, "
            + "status_message = CASE WHEN :setStatus THEN CAST(:status AS VARCHAR) ELSE status_message END, "
            + "bio = CASE WHEN :setBio THEN CAST(:bio AS VARCHAR) ELSE bio END "
            + "WHERE user_id = :userId")
    Mono<Integer> updateProfile(@Param("userId") String userId,
                                @Param("setImage") boolean setImage, @Param("image") String image,
                                @Param("setStatus") boolean setStatus, @Param("status") String status,
                                @Param("setBio") boolean setBio, @Param("bio") String bio);
}
