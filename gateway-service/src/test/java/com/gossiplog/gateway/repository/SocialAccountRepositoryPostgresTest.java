package com.gossiplog.gateway.repository;

import com.gossiplog.gateway.entity.SocialAccountEntity;
import com.gossiplog.gateway.entity.SocialProvider;
import com.gossiplog.gateway.entity.UserEntity;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** user_social_accounts의 제약(제공자 계정 1:1, 사용자당 제공자 1개, 사용자 삭제 시 함께 삭제)과 쿼리를 실제 PostgreSQL에서 검증한다. */
@Testcontainers(disabledWithoutDocker = true)
class SocialAccountRepositoryPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("gossiplog").withUsername("gossiplog").withPassword("gossiplog");

    private static DatabaseClient client;
    private static UserRepository users;
    private static SocialAccountRepository repo;

    @BeforeAll
    static void setUp() throws Exception {
        ConnectionFactory cf = ConnectionFactories.get(String.format(
                "r2dbc:postgresql://gossiplog:gossiplog@%s:%d/gossiplog",
                POSTGRES.getHost(), POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)));
        client = DatabaseClient.create(cf);
        String schema = new String(new ClassPathResource("schema.sql").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        // 운영처럼 매 기동마다 다시 돌므로 두 번 실행해도 실패하지 않아야 한다.
        for (int run = 0; run < 2; run++) {
            for (String stmt : schema.split(";")) {
                String sql = stmt.replaceAll("(?m)^--.*$", "").trim();
                if (!sql.isEmpty()) {
                    client.sql(sql).fetch().rowsUpdated().block();
                }
            }
        }
        R2dbcRepositoryFactory factory =
                new R2dbcRepositoryFactory(new R2dbcEntityTemplate(client, PostgresDialect.INSTANCE));
        users = factory.getRepository(UserRepository.class);
        repo = factory.getRepository(SocialAccountRepository.class);
    }

    @BeforeEach
    void seed() {
        client.sql("DELETE FROM user_social_accounts").fetch().rowsUpdated().block();
        client.sql("DELETE FROM users").fetch().rowsUpdated().block();
        for (String id : new String[] {"a", "b"}) {
            users.save(UserEntity.builder().userId(id).username("user-" + id).encodedPassword("pw-" + id)
                    .createdAt(LocalDateTime.now()).build()).block();
        }
    }

    private static SocialAccountEntity link(String userId, SocialProvider provider, String providerUserId) {
        return SocialAccountEntity.builder().userId(userId).provider(provider)
                .providerUserId(providerUserId).createdAt(LocalDateTime.now()).build();
    }

    @Test
    void findByProviderAndProviderUserId_returnsLinkedUser() {
        repo.save(link("a", SocialProvider.GOOGLE, "g-1")).block();

        StepVerifier.create(repo.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "g-1"))
                .assertNext(acc -> assertEquals("a", acc.getUserId()))
                .verifyComplete();
        StepVerifier.create(repo.findByProviderAndProviderUserId(SocialProvider.NAVER, "g-1"))
                .verifyComplete();
    }

    @Test
    void kakaoAccount_isStoredAndFoundByNumericId() {
        // 카카오 회원번호는 숫자(Long)로 오지만 provider_user_id는 문자열로 저장한다.
        repo.save(link("a", SocialProvider.KAKAO, "4012345678")).block();

        StepVerifier.create(repo.findByProviderAndProviderUserId(SocialProvider.KAKAO, "4012345678"))
                .assertNext(acc -> assertEquals("a", acc.getUserId()))
                .verifyComplete();
    }

    @Test
    void sameProviderAccount_cannotLinkToTwoUsers() {
        repo.save(link("a", SocialProvider.NAVER, "n-1")).block();

        StepVerifier.create(repo.save(link("b", SocialProvider.NAVER, "n-1")))
                .expectError(DataIntegrityViolationException.class)
                .verify();
    }

    @Test
    void userCannotLinkTwoAccountsOfSameProvider() {
        repo.save(link("a", SocialProvider.GOOGLE, "g-1")).block();

        StepVerifier.create(repo.save(link("a", SocialProvider.GOOGLE, "g-2")))
                .expectError(DataIntegrityViolationException.class)
                .verify();
    }

    @Test
    void findByUserId_listsEachProvider() {
        repo.save(link("a", SocialProvider.GOOGLE, "g-1")).block();
        repo.save(link("a", SocialProvider.NAVER, "n-1")).block();

        StepVerifier.create(repo.findByUserId("a").map(SocialAccountEntity::getProvider).collectList())
                .assertNext(list -> assertEquals(2, list.size()))
                .verifyComplete();
    }

    @Test
    void touchLastLogin_setsOnlyThatRow() {
        repo.save(link("a", SocialProvider.GOOGLE, "g-1")).block();
        repo.save(link("b", SocialProvider.GOOGLE, "g-2")).block();
        LocalDateTime at = LocalDateTime.of(2026, 11, 6, 12, 0);

        StepVerifier.create(repo.touchLastLogin(SocialProvider.GOOGLE, "g-1", at)).expectNext(1).verifyComplete();

        assertEquals(at, repo.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "g-1").block().getLastLoginAt());
        assertEquals(null, repo.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "g-2").block().getLastLoginAt());
    }

    @Test
    void deletingUser_removesLinkedAccounts() {
        SocialAccountEntity saved = repo.save(link("a", SocialProvider.GOOGLE, "g-1")).block();
        assertNotNull(saved.getId());

        client.sql("DELETE FROM users WHERE user_id = 'a'").fetch().rowsUpdated().block();

        StepVerifier.create(repo.findByUserId("a")).verifyComplete();
    }
}
