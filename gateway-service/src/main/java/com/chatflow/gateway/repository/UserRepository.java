package com.chatflow.gateway.repository;

import com.chatflow.gateway.entity.UserEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserRepository extends ReactiveCrudRepository<UserEntity, Long> {
    Mono<UserEntity> findByUsername(String username);
    Mono<Boolean> existsByUsername(String username);
    Flux<UserEntity> findByUsernameContainingIgnoreCaseOrderByUsernameAsc(String query);
}
