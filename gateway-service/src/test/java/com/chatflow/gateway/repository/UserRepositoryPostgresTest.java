package com.chatflow.gateway.repository;

import com.chatflow.gateway.entity.UserEntity;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 실제 PostgreSQL에서 schema.sql과 @Modifying UPDATE 쿼리를 검증한다.
 * 테스트 프로필은 R2DBC를 끄고 있어 모킹으로는 SQL·null 바인딩 타입 추론을 잡을 수 없다.
 */
@Testcontainers(disabledWithoutDocker = true)
class UserRepositoryPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("chatflow").withUsername("chatflow").withPassword("chatflow");

    private static DatabaseClient client;
    private static UserRepository repo;

    @BeforeAll
    static void setUp() throws Exception {
        ConnectionFactory cf = ConnectionFactories.get(String.format(
                "r2dbc:postgresql://chatflow:chatflow@%s:%d/chatflow",
                POSTGRES.getHost(), POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)));
        client = DatabaseClient.create(cf);
        String schema = new String(new ClassPathResource("schema.sql").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        for (String stmt : schema.split(";")) {
            String sql = stmt.replaceAll("(?m)^--.*$", "").trim();
            if (!sql.isEmpty()) {
                client.sql(sql).fetch().rowsUpdated().block();
            }
        }
        repo = new R2dbcRepositoryFactory(new R2dbcEntityTemplate(client, PostgresDialect.INSTANCE))
                .getRepository(UserRepository.class);
    }

    @BeforeEach
    void seed() {
        client.sql("DELETE FROM users").fetch().rowsUpdated().block();
        for (String id : new String[] {"a", "b"}) {
            repo.save(UserEntity.builder().userId(id).username("user-" + id).encodedPassword("pw-" + id)
                    .profileImageUrl("img-" + id).statusMessage("st-" + id).bio("bio-" + id)
                    .createdAt(LocalDateTime.now()).build()).block();
        }
    }

    private UserEntity get(String id) {
        return repo.findByUserId(id).block();
    }

    @Test
    void updateEncodedPassword_changesOnlyTargetRowAndColumn() {
        StepVerifier.create(repo.updateEncodedPassword("a", "new-pw")).expectNext(1).verifyComplete();

        assertEquals("new-pw", get("a").getEncodedPassword());
        assertEquals("img-a", get("a").getProfileImageUrl());
        assertEquals("pw-b", get("b").getEncodedPassword());
    }

    @Test
    void updateProfileImageUrl_setsValueAndNull() {
        StepVerifier.create(repo.updateProfileImageUrl("a", "https://cdn/x.png")).expectNext(1).verifyComplete();
        assertEquals("https://cdn/x.png", get("a").getProfileImageUrl());

        StepVerifier.create(repo.updateProfileImageUrl("a", null)).expectNext(1).verifyComplete();
        assertNull(get("a").getProfileImageUrl());
        assertEquals("pw-a", get("a").getEncodedPassword());
        assertEquals("img-b", get("b").getProfileImageUrl());
    }

    @Test
    void updateProfile_partialUpdate_leavesOtherColumnsAndRows() {
        StepVerifier.create(repo.updateProfile("a", false, null, true, "new status", false, null))
                .expectNext(1).verifyComplete();

        UserEntity a = get("a");
        assertEquals("new status", a.getStatusMessage());
        assertEquals("img-a", a.getProfileImageUrl());
        assertEquals("bio-a", a.getBio());
        assertEquals("st-b", get("b").getStatusMessage());
    }

    @Test
    void updateProfile_setToNull_clearsOnlyThatColumn() {
        StepVerifier.create(repo.updateProfile("a", true, null, false, null, true, "new bio"))
                .expectNext(1).verifyComplete();

        UserEntity a = get("a");
        assertNull(a.getProfileImageUrl());
        assertEquals("st-a", a.getStatusMessage());
        assertEquals("new bio", a.getBio());
        assertEquals("bio-b", get("b").getBio());
    }

    @Test
    void updateProfile_unknownUser_updatesNothing() {
        StepVerifier.create(repo.updateProfile("zzz", true, "x", true, "y", true, "z"))
                .expectNext(0).verifyComplete();
    }
}
