package com.chatflow.chat.migration;

import com.chatflow.chat.entity.OutboxEvent;
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
 * Validates the full Flyway migration chain (V1..V15) against a real PostgreSQL 16 container.
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
                .as("All 15 versioned migrations (V1..V15) should have been executed")
                .isEqualTo(15);

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

    // ── Test 7: V15 — outbox status CHECK accepts every OutboxStatus value ─

    /**
     * The migrated schema must accept <strong>every</strong> value of
     * {@link OutboxEvent.OutboxStatus}. Driven off the enum itself, so adding a new
     * status in Java without a matching migration fails here instead of in prod.
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
            for (OutboxEvent.OutboxStatus status : OutboxEvent.OutboxStatus.values()) {
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
            stmt.executeUpdate(insertOutboxRowSql(OutboxEvent.OutboxStatus.PENDING));

            // This is the statement OutboxPoller.claimBatch() issues — it threw 23514 in prod.
            int claimed = stmt.executeUpdate(
                    "UPDATE outbox_events SET status = 'PROCESSING' WHERE status = 'PENDING'");

            assertThat(claimed)
                    .as("claim update (PENDING -> PROCESSING) should succeed after V15")
                    .isEqualTo(1);
        }
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
     * Status is interpolated from an enum constant, so there is no injection surface.
     */
    private static String insertOutboxRowSql(OutboxEvent.OutboxStatus status) {
        return "INSERT INTO outbox_events "
                + "(aggregate_type, aggregate_id, event_type, topic, partition_key, payload, status, created_at) "
                + "VALUES ('ChatMessage', 'room_test', 'MESSAGE_SENT', 'chat-messages', 'room_test', '{}', "
                + "'" + status.name() + "', now())";
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
