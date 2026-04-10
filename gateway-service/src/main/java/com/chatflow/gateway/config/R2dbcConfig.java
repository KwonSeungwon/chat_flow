package com.chatflow.gateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

@Configuration
@EnableR2dbcRepositories(basePackages = "com.chatflow.gateway.repository")
public class R2dbcConfig {
}
