package com.chatflow.chat.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the full Flyway migration chain (V1..V19) against a real PostgreSQL 16 container.
 *
 * <h3>Why this test exists</h3>
 * The regular test profile runs on H2 with {@code spring.flyway.enabled=false} and
 * {@code ddl-auto=create-drop}, so these migrations are <strong>never executed</strong>
 * before prod. Several migrations use Postgres-only SQL that H2 cannot even parse:
 * <ul>
 *   <li>V5 uses {@code CROSS JOIN LATERAL unnest(string_to_array(...))}</li>
 *   <li>V10 uses {@code LEFT(...)}, {@code char_length(...)} guards</li>
 * </ul>
 * In prod, a single bad statement aborts Flyway and <strong>blocks the deploy</strong>.
 * This test is the safety net that catches such errors before they reach production.
 *
 * <h3>{@code users} table precondition</h3>
 * V5 and V10 join the {@code users} table, which is owned by <strong>gateway-service</strong>
 * (created via {@code spring.sql.init} schema.sql), not by any chat-service migration.
 * In prod, both services share the same {@code chatflow} database and gateway initialises
 * {@code users} first. For this standalone test we create a minimal {@code users} table
 * (just {@code user_id VARCHAR(50) PRIMARY KEY, username VARCHAR(100) NOT NULL}) before
 * running Flyway. This is a test-infrastructure concern, <strong>not a production bug</strong>.
 *
 * <p>The test is annotated with {@code disabledWithoutDocker = true} so it skips cleanly
 * on machines or CI environments without Docker — it never breaks the green gate.
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("chatflow")
                    .withUsername("chatflow")
                    .withPassword("chatflow");

    private static Flyway flyway;
    private static MigrateResult result;

    /**
     * Seeds the gateway-owned {@code users} table (precondition for V5/V10),
     * then runs the full Flyway migration chain programmatically.
     */
    @BeforeAll
    static void runMigrations() throws SQLException {
        // Create the minimal `users` table that V5 and V10 depend on.
        // In prod this table is owned by gateway-service (spring.sql.init schema.sql).
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        user_id  VARCHAR(50)  PRIMARY KEY,
                        username VARCHAR(100) NOT NULL
                    )
                    """);
        }

        flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                // The `users` table we just created makes the schema non-empty before
                // Flyway has a schema history table. baselineOnMigrate allows Flyway to
                // initialise cleanly; baselineVersion "0" ensures all V1+ migrations run.
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();

        result = flyway.migrate();
    }

    // ── Test 1: full chain applies without error ──────────────────────────

    /**
     * Every migration V1..V14 must apply cleanly. If any migration contains
     * invalid Postgres SQL, Flyway will fail and this test catches it.
     */
    @Test
    void allMigrationsApplyCleanly() {
        assertThat(result.success)
                .as("Flyway migrate() should succeed")
                .isTrue();

        assertThat(result.migrationsExecuted)
                .as("All 19 versioned migrations (V1..V19) should have been executed")
                .isEqualTo(19);

        // Double-check: every entry in the schema history should be SUCCESS or BASELINE.
        // The baseline entry (version 0, state BASELINE) is created by baselineOnMigrate;
        // all V1..V13 entries should be SUCCESS.
        for (MigrationInfo info : flyway.info().all()) {
            assertThat(info.getState())
                    .as("Migration %s should be in SUCCESS or BASELINE state", info.getVersion())
                    .isIn(MigrationState.SUCCESS, MigrationState.BASELINE);
        }
    }

    // ── Test 2: chain is idempotent / internally consistent ───────────────

    /**
     * A second {@code migrate()} call must be a no-op — zero pending migrations.
     * Proves the chain is internally consistent and re-runnable.
     */
    @Test
    void migrationChainIsComplete_secondMigrateIsNoOp() {
        MigrateResult secondRun = flyway.migrate();

        assertThat(secondRun.migrationsExecuted)
                .as("Second migrate() should execute zero migrations (none pending)")
                .isZero();
    }

    // ── Test 3: V11 — message_mentions table + unique constraint ──────────

    /**
     * V11 must create the {@code message_mentions} table with all 8 expected
     * columns and a UNIQUE constraint on {@code (message_id, mentioned_user_id)}.
     */
    @Test
    void v11_messageMentionsTableAndUniqueConstraintExist() throws SQLException {
        Set<String> expectedColumns = Set.of(
                "id", "message_id", "room_id",
                "mentioned_user_id", "mentioned_username",
                "from_username", "created_at", "read"
        );

        Set<String> actualColumns = columnsOf("message_mentions");
        assertThat(actualColumns)
                .as("message_mentions should contain all 8 expected columns")
                .containsAll(expectedColumns);

        // Assert UNIQUE constraint on (message_id, mentioned_user_id)
        assertThat(uniqueConstraintExists("message_mentions", "message_id", "mentioned_user_id"))
                .as("UNIQUE constraint uq_message_mentions(message_id, mentioned_user_id) should exist")
                .isTrue();
    }

    // ── Test 4: V12 — room_members.last_read_at column ────────────────────

    /**
     * V12 must add a {@code last_read_at} TIMESTAMP column to {@code room_members}.
     */
    @Test
    void v12_roomMembersHasLastReadAt() throws SQLException {
        assertThat(columnExists("room_members", "last_read_at"))
                .as("room_members should have a last_read_at column (added by V12)")
                .isTrue();

        // Verify it is a timestamp type
        String dataType = columnDataType("room_members", "last_read_at");
        assertThat(dataType)
                .as("last_read_at should be a timestamp type")
                .containsIgnoringCase("timestamp");
    }

    // ── Test 5: V13 — outbox_events claim columns ─────────────────────────

    /**
     * V13 must add {@code claim_token} (VARCHAR) and {@code claimed_at} (TIMESTAMP)
     * columns to {@code outbox_events}.
     */
    @Test
    void v13_outboxEventsHasClaimColumns() throws SQLException {
        assertThat(columnExists("outbox_events", "claim_token"))
                .as("outbox_events should have a claim_token column (added by V13)")
                .isTrue();
        assertThat(columnExists("outbox_events", "claimed_at"))
                .as("outbox_events should have a claimed_at column (added by V13)")
                .isTrue();
    }

    // ── Test 6: V14 — message_reports rate-limit index ──────────────────

    /**
     * V14 must create a composite index {@code idx_message_reports_reporter_created}
     * on {@code message_reports (reported_by, created_at)} to support the per-user
     * report rate-limit query.
     */
    @Test
    void v14_messageReportsRateLimitIndexExists() throws SQLException {
        assertThat(indexExists("message_reports", "idx_message_reports_reporter_created"))
                .as("Index idx_message_reports_reporter_created should exist on message_reports (added by V14)")
                .isTrue();
    }

    // ── Test 7: V15 — outbox status CHECK accepts every outbox status value ─

    /**
     * The table stays after the outbox writer was removed, so these are the four
     * statuses V15 must accept (the former entity enum).
     */
    private static final String[] OUTBOX_STATUSES = {"PENDING", "PROCESSING", "PROCESSED", "FAILED"};

    /**
     * The migrated schema must accept <strong>every</strong> outbox status value.
     *
     * <p>History: V13 introduced the PENDING → PROCESSING claim step, but prod carried a
     * pre-Flyway Hibernate-generated CHECK constraint listing only the three older values.
     * Every poll failed with SQLState 23514 and the outbox stalled for a week. V15 rebuilds
     * the constraint with all four values.
     */
    @Test
    void v15_outboxStatusCheckAcceptsEveryEnumValue() throws SQLException {
        try (Connection conn = connect(POSTGRES.getDatabaseName());
             Statement stmt = conn.createStatement()) {
            for (String status : OUTBOX_STATUSES) {
                stmt.executeUpdate(insertOutboxRowSql(status));
            }
        }
    }

    // ── Test 8: V15 — heals a drifted (legacy 3-value constraint) database ─

    /**
     * Reproduces the exact production drift: {@code outbox_events} pre-exists with the
     * legacy three-value CHECK constraint (Hibernate ddl-auto, pre-Flyway), so V1's
     * {@code CREATE TABLE IF NOT EXISTS} is a no-op and the stale constraint survives the
     * whole chain. After V15 the claim update (PENDING → PROCESSING) must succeed.
     *
     * <p>Runs against a separate database inside the same container — no second container.
     */
    @Test
    void v15_healsLegacyThreeValueConstraintOnDriftedDatabase() throws SQLException {
        final String driftedDb = "chatflow_drifted";

        try (Connection conn = connect(POSTGRES.getDatabaseName());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE " + driftedDb);
        }

        try (Connection conn = connect(driftedDb);
             Statement stmt = conn.createStatement()) {
            // gateway-owned table that V5/V10 join against
            stmt.execute("""
                    CREATE TABLE users (
                        user_id  VARCHAR(50)  PRIMARY KEY,
                        username VARCHAR(100) NOT NULL
                    )
                    """);
            // The pre-Flyway shape: V1's columns plus the stale 3-value constraint.
            stmt.execute("""
                    CREATE TABLE outbox_events (
                        id              BIGSERIAL PRIMARY KEY,
                        aggregate_type  VARCHAR(50)  NOT NULL,
                        aggregate_id    VARCHAR(100) NOT NULL,
                        event_type      VARCHAR(50)  NOT NULL,
                        topic           VARCHAR(100) NOT NULL,
                        partition_key   VARCHAR(100) NOT NULL,
                        payload         TEXT         NOT NULL,
                        status          VARCHAR(20)  NOT NULL,
                        created_at      TIMESTAMP    NOT NULL,
                        processed_at    TIMESTAMP,
                        version         BIGINT,
                        CONSTRAINT outbox_events_status_check
                            CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED'))
                    )
                    """);
        }

        MigrateResult driftedResult = Flyway.configure()
                .dataSource(jdbcUrl(driftedDb), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();

        assertThat(driftedResult.success)
                .as("Flyway should migrate a drifted (pre-Flyway schema) database cleanly")
                .isTrue();

        try (Connection conn = connect(driftedDb);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(insertOutboxRowSql("PENDING"));

            // This is the claim statement the former outbox poller issued — it threw 23514 in prod.
            int claimed = stmt.executeUpdate(
                    "UPDATE outbox_events SET status = 'PROCESSING' WHERE status = 'PENDING'");

            assertThat(claimed)
                    .as("claim update (PENDING -> PROCESSING) should succeed after V15")
                    .isEqualTo(1);
        }
    }

    // ── Test 9: V17 — outbox claim_token index ───────────────────────────

    @Test
    void v17_outboxClaimTokenIndexExists() throws SQLException {
        assertThat(indexExists("outbox_events", "idx_outbox_claim_token"))
                .as("Index idx_outbox_claim_token should exist on outbox_events (added by V17)")
                .isTrue();
    }

    // ── Test 10: V18 — scheduled_messages status CHECK allows PROCESSING ──

    @Test
    void v18_scheduledMessagesStatusCheckAllowsEveryEnumValue() throws SQLException {
        try (Connection conn = connect(POSTGRES.getDatabaseName());
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                             + "WHERE conname = 'scheduled_messages_status_check'");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).as("scheduled_messages_status_check should exist").isTrue();
            String definition = rs.getString(1);
            for (com.chatflow.chat.entity.ScheduledMessageEntity.ScheduledMessageStatus status
                    : com.chatflow.chat.entity.ScheduledMessageEntity.ScheduledMessageStatus.values()) {
                assertThat(definition).contains("'" + status.name() + "'");
            }
        }
    }

    // ── Test 11: V19 — game tables, constraints and seed ─────────────────

    @Test
    void v19_gameTablesExistWithExpectedColumns() throws SQLException {
        assertThat(columnsOf("game_dungeon")).containsAll(Set.of("id", "name", "sort_order", "building_style"));
        assertThat(columnsOf("game_topic")).containsAll(Set.of("id", "dungeon_id", "title", "summary", "source_ref"));
        assertThat(columnsOf("game_monster"))
                .containsAll(Set.of("id", "topic_id", "dungeon_id", "name", "sprite_key", "is_boss"));
        assertThat(columnsOf("game_question")).containsAll(Set.of("id", "topic_id", "stem", "choices",
                "answer_index", "explanation", "status", "content_hash", "created_at", "reviewed_at"));
        assertThat(columnsOf("game_progress"))
                .containsAll(Set.of("user_id", "dungeon_id", "username", "cleared_at", "best_streak"));
        assertThat(columnsOf("game_answer"))
                .containsAll(Set.of("id", "user_id", "question_id", "correct", "answered_at", "next_due_at"));

        assertThat(columnDataType("game_question", "choices")).isEqualTo("text");
        assertThat(uniqueConstraintExists("game_topic", "dungeon_id", "title")).isTrue();
        assertThat(uniqueConstraintExists("game_question", "content_hash")).isTrue();
        assertThat(indexExists("game_answer", "idx_game_answer_user_due")).isTrue();
        assertThat(indexExists("game_monster", "uq_game_monster_boss")).isTrue();
        assertThat(indexExists("game_monster", "uq_game_monster_topic")).isTrue();
    }

    @Test
    void v19_seedsEightDungeonsEachWithOneBoss() throws SQLException {
        try (Connection conn = connect(POSTGRES.getDatabaseName());
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM game_dungeon WHERE building_style = id")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(8);
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM game_monster m JOIN game_dungeon d ON d.id = m.dungeon_id "
                            + "WHERE m.is_boss AND m.topic_id IS NULL AND m.sprite_key = 'boss_' || d.id")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(8);
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT name FROM game_monster WHERE dungeon_id = 'k8s' AND is_boss")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("크래시루프 드래곤");
            }
        }
    }

    @Test
    void v19_rejectsSecondBossAndOutOfRangeAnswerIndex() throws SQLException {
        try (Connection conn = connect(POSTGRES.getDatabaseName());
             Statement stmt = conn.createStatement()) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> stmt.executeUpdate(
                    "INSERT INTO game_monster (dungeon_id, name, sprite_key, is_boss) "
                            + "VALUES ('k8s', 'second boss', 'boss_k8s', TRUE)"))
                    .isInstanceOf(SQLException.class);

            stmt.executeUpdate("INSERT INTO game_topic (dungeon_id, title) VALUES ('jvm', 'v19 check topic')");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> stmt.executeUpdate(
                    "INSERT INTO game_question (topic_id, stem, choices, answer_index, explanation, content_hash) "
                            + "SELECT id, 's', '[]', 4, 'e', repeat('a', 64) FROM game_topic "
                            + "WHERE title = 'v19 check topic'"))
                    .isInstanceOf(SQLException.class);
            stmt.executeUpdate("DELETE FROM game_topic WHERE title = 'v19 check topic'");
        }
    }

    // ── Test 14: V19 — game entities pass Hibernate ddl-auto=validate ──────

    /**
     * Prod runs {@code ddl-auto: validate}, which no other test exercises. Boots a JPA
     * EntityManagerFactory over the game entities against the migrated schema, using the
     * same naming strategies Spring Boot applies.
     */
    @Test
    void v19_gameEntitiesPassHibernateSchemaValidation() throws SQLException {
        assertThat(columnDataType("game_question", "content_hash")).isEqualTo("character");

        org.springframework.jdbc.datasource.DriverManagerDataSource dataSource =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean factory =
                new org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.chatflow.chat.game.entity");
        factory.setJpaVendorAdapter(new org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(java.util.Map.of(
                "hibernate.hbm2ddl.auto", "validate",
                "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                "hibernate.implicit_naming_strategy",
                "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy"));

        org.assertj.core.api.Assertions.assertThatCode(factory::afterPropertiesSet)
                .as("game entities should validate against the V19 schema")
                .doesNotThrowAnyException();
        factory.destroy();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Builds a JDBC URL for an arbitrary database inside the test container.
     * {@link PostgreSQLContainer#getJdbcUrl()} is hardcoded to the container's default
     * database, so tests needing a second database construct their own URL.
     */
    private static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + POSTGRES.getHost()
                + ":" + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                + "/" + database;
    }

    private static Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(
                jdbcUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /**
     * A minimal valid {@code outbox_events} INSERT for the given status.
     * Status is interpolated from a test constant, so there is no injection surface.
     */
    private static String insertOutboxRowSql(String status) {
        return "INSERT INTO outbox_events "
                + "(aggregate_type, aggregate_id, event_type, topic, partition_key, payload, status, created_at) "
                + "VALUES ('ChatMessage', 'room_test', 'MESSAGE_SENT', 'chat-messages', 'room_test', '{}', "
                + "'" + status + "', now())";
    }


    /**
     * Returns all column names for the given table via {@code information_schema.columns}.
     */
    private static Set<String> columnsOf(String table) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT column_name FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString("column_name"));
                }
            }
        }
        return columns;
    }

    /**
     * Checks whether a specific column exists in the given table.
     */
    private static boolean columnExists(String table, String column) throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Returns the {@code data_type} of a column from {@code information_schema.columns}.
     */
    private static String columnDataType(String table, String column) throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT data_type FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("data_type");
                }
                return "";
            }
        }
    }

    /**
     * Checks whether an index with the given name exists on the specified table.
     * Uses {@code pg_indexes} for Postgres introspection.
     */
    private static boolean indexExists(String table, String indexName) throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM pg_indexes "
                             + "WHERE schemaname = 'public' AND tablename = ? AND indexname = ?")) {
            ps.setString(1, table);
            ps.setString(2, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Checks whether a UNIQUE constraint covering the given columns exists on the table.
     * Uses {@code pg_constraint} + {@code pg_attribute} for accurate Postgres introspection.
     */
    private static boolean uniqueConstraintExists(String table, String... columns) throws SQLException {
        // Query pg_constraint for UNIQUE constraints on the table, then check
        // that at least one constraint covers exactly the given column set.
        String sql = """
                SELECT con.conname, array_agg(att.attname ORDER BY att.attnum) AS cols
                FROM pg_constraint con
                JOIN pg_class rel ON rel.oid = con.conrelid
                JOIN pg_namespace ns ON ns.oid = rel.relnamespace
                JOIN pg_attribute att ON att.attrelid = con.conrelid
                    AND att.attnum = ANY(con.conkey)
                WHERE rel.relname = ?
                  AND ns.nspname = 'public'
                  AND con.contype = 'u'
                GROUP BY con.conname
                """;
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                Set<String> expected = Set.of(columns);
                while (rs.next()) {
                    java.sql.Array arr = rs.getArray("cols");
                    String[] constraintCols = (String[]) arr.getArray();
                    if (Set.of(constraintCols).equals(expected)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
