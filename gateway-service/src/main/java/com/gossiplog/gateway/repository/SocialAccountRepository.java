package com.gossiplog.gateway.repository;

import com.gossiplog.gateway.entity.SocialAccountEntity;
import com.gossiplog.gateway.entity.SocialProvider;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface SocialAccountRepository extends ReactiveCrudRepository<SocialAccountEntity, Long> {
    Mono<SocialAccountEntity> findByProviderAndProviderUserId(SocialProvider provider, String providerUserId);
    Flux<SocialAccountEntity> findByUserId(String userId);

    @Modifying
    @Query("UPDATE user_social_accounts SET last_login_at = :at "
            + "WHERE provider = :#{#provider.name()} AND provider_user_id = :providerUserId")
    Mono<Integer> touchLastLogin(@Param("provider") SocialProvider provider,
                                 @Param("providerUserId") String providerUserId,
                                 @Param("at") LocalDateTime at);
}
