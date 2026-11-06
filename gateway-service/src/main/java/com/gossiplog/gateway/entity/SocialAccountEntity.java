package com.gossiplog.gateway.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import java.time.LocalDateTime;

/** 사용자에게 연결된 소셜 계정. 제공자가 주는 고유 ID만 저장한다. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("user_social_accounts")
public class SocialAccountEntity {
    @Id
    private Long id;

    @Column("user_id")
    private String userId;

    @Column("provider")
    private SocialProvider provider;

    @Column("provider_user_id")
    private String providerUserId;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("last_login_at")
    private LocalDateTime lastLoginAt;
}
