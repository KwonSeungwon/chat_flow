package com.chatflow.gateway.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.relational.core.mapping.Column;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("users")
public class UserEntity {
    @Id
    private Long seq;

    @Column("user_id")
    private String userId;

    @Column("username")
    private String username;

    @Column("encoded_password")
    private String encodedPassword;

    @Column("role")
    private String role;

    @Column("profile_image_url")
    private String profileImageUrl;

    @Column("status_message")
    private String statusMessage;

    @Column("bio")
    private String bio;

    @Column("created_at")
    private LocalDateTime createdAt;
}
