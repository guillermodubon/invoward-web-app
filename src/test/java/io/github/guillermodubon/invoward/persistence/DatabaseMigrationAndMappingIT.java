package io.github.guillermodubon.invoward.persistence;

import io.github.guillermodubon.invoward.InvoWardApplication;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseMigrationAndMappingIT {

    private static final PostgreSQLContainer POSTGRES = PostgresTestContainer.instance();
    private static final List<String> EXPECTED_MIGRATIONS = List.of(
            "001", "002", "003", "004", "005", "006", "007", "008");
    private static final Set<String> EXPECTED_APPLICATION_TABLES = Set.of(
            "users",
            "email_verification_tokens",
            "password_reset_tokens",
            "guest_sessions",
            "analyses",
            "analysis_jobs",
            "documents",
            "extracted_documents",
            "extracted_line_items",
            "extraction_cache_entries",
            "line_item_matches",
            "reconciliation_lines",
            "discrepancies",
            "discrepancy_evidence",
            "generated_reports",
            "share_links",
            "analysis_notes");

    @Test
    void emptyDatabaseMigratesOnceAndAllJpaMappingsValidateOnEveryBoot() throws Exception {
        resetApplicationSchema();
        try (Connection connection = openConnection()) {
            assertFalse(applicationSchemaExists(connection));
        }

        List<MigrationRecord> firstBootHistory;
        try (ConfigurableApplicationContext context = startApplication()) {
            assertApplicationPersistenceContext(context);
            context.getBean(Flyway.class).validate();

            try (Connection connection = openConnection()) {
                assertApplicationSchemaAndTables(connection);
                assertReviewedAtIsNullableWithoutDefault(connection);
                firstBootHistory = migrationHistory(connection);
                assertEquals(EXPECTED_MIGRATIONS,
                        firstBootHistory.stream().map(MigrationRecord::version).toList());
            }
        }

        try (ConfigurableApplicationContext context = startApplication()) {
            assertApplicationPersistenceContext(context);
            context.getBean(Flyway.class).validate();
        }

        try (Connection connection = openConnection()) {
            assertApplicationSchemaAndTables(connection);
            assertEquals(firstBootHistory, migrationHistory(connection));
        }
    }

    @Test
    void reviewedAtMigrationPreservesLegacyPendingAndReviewedRowsAsNull() throws Exception {
        resetApplicationSchema();

        try (ConfigurableApplicationContext context = startApplication(
                "--spring.flyway.target=7", "--spring.jpa.hibernate.ddl-auto=none")) {
            assertApplicationPersistenceContext(context, "none");
            try (Connection connection = openConnection()) {
                assertApplicationSchemaAndTables(connection);
                assertReviewedAtDoesNotExist(connection);
                assertEquals(List.of("001", "002", "003", "004", "005", "006", "007"),
                        migrationHistory(connection).stream().map(MigrationRecord::version).toList());
                insertLegacyReviewRows(connection);
            }
        }

        try (ConfigurableApplicationContext context = startApplication()) {
            assertApplicationPersistenceContext(context);
            context.getBean(Flyway.class).validate();
            try (Connection connection = openConnection()) {
                assertReviewedAtIsNullableWithoutDefault(connection);
                assertLegacyReviewRowsRemainUnmodified(connection);
                assertEquals(EXPECTED_MIGRATIONS,
                        migrationHistory(connection).stream().map(MigrationRecord::version).toList());
            }
        }
    }

    private static ConfigurableApplicationContext startApplication(String... additionalArguments) {
        SpringApplication application = new SpringApplication(InvoWardApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        String[] arguments = new String[additionalArguments.length + 1];
        arguments[0] = "--spring.security.user.password=test-only";
        System.arraycopy(additionalArguments, 0, arguments, 1, additionalArguments.length);
        return application.run(PostgresTestContainer.applicationArguments(arguments));
    }

    private static void assertApplicationPersistenceContext(
            ConfigurableApplicationContext context) {
        assertApplicationPersistenceContext(context, "validate");
    }

    private static void assertApplicationPersistenceContext(
            ConfigurableApplicationContext context, String expectedDdlAuto) {
        assertNotNull(context);
        assertEquals(expectedDdlAuto,
                context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"));
        assertTrue(context.getBean(EntityManagerFactory.class).isOpen());
        assertEquals(17,
                context.getBean(EntityManagerFactory.class).getMetamodel().getEntities().size());
    }

    private static void assertApplicationSchemaAndTables(Connection connection) throws SQLException {
        assertTrue(applicationSchemaExists(connection));

        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT to_regclass('invoward.flyway_schema_history') IS NOT NULL")) {
            assertTrue(result.next());
            assertTrue(result.getBoolean(1));
        }

        assertEquals(EXPECTED_APPLICATION_TABLES, applicationTables(connection));
    }

    private static void assertReviewedAtIsNullableWithoutDefault(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'invoward'
                  AND table_name = 'analyses'
                  AND column_name = 'reviewed_at'
                """);
             ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            assertEquals("YES", result.getString("is_nullable"));
            assertNull(result.getString("column_default"));
            assertFalse(result.next());
        }
    }

    private static void assertReviewedAtDoesNotExist(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM information_schema.columns
                WHERE table_schema = 'invoward'
                  AND table_name = 'analyses'
                  AND column_name = 'reviewed_at'
                """);
             ResultSet result = statement.executeQuery()) {
            assertFalse(result.next());
        }
    }

    private static void insertLegacyReviewRows(Connection connection) throws SQLException {
        UUID guestSessionId = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO invoward.guest_sessions (id, expires_at, last_seen_at, created_at)
                VALUES (?, CURRENT_TIMESTAMP + INTERVAL '1 day', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """)) {
            statement.setObject(1, guestSessionId);
            assertEquals(1, statement.executeUpdate());
        }

        insertLegacyReviewRow(connection, guestSessionId, UUID.randomUUID(), "PENDING");
        insertLegacyReviewRow(connection, guestSessionId, UUID.randomUUID(), "REVIEWED");
    }

    private static void insertLegacyReviewRow(
            Connection connection, UUID guestSessionId, UUID analysisId, String reviewStatus)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO invoward.analyses (
                    id, guest_session_id, status, review_status, expires_at, created_at, updated_at)
                VALUES (?, ?, 'COMPLETED', ?, CURRENT_TIMESTAMP + INTERVAL '1 day',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """)) {
            statement.setObject(1, analysisId);
            statement.setObject(2, guestSessionId);
            statement.setString(3, reviewStatus);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void assertLegacyReviewRowsRemainUnmodified(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT review_status, reviewed_at
                FROM invoward.analyses
                ORDER BY review_status
                """);
             ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            assertEquals("PENDING", result.getString("review_status"));
            assertNull(result.getObject("reviewed_at"));
            assertTrue(result.next());
            assertEquals("REVIEWED", result.getString("review_status"));
            assertNull(result.getObject("reviewed_at"));
            assertFalse(result.next());
        }
    }

    private static boolean applicationSchemaExists(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'invoward')")) {
            assertTrue(result.next());
            return result.getBoolean(1);
        }
    }

    private static Set<String> applicationTables(Connection connection) throws SQLException {
        Set<String> tables = new java.util.HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT table_name
                     FROM information_schema.tables
                     WHERE table_schema = 'invoward'
                       AND table_type = 'BASE TABLE'
                       AND table_name <> 'flyway_schema_history'
                     """)) {
            while (result.next()) {
                tables.add(result.getString("table_name"));
            }
        }
        return Set.copyOf(tables);
    }

    private static List<MigrationRecord> migrationHistory(Connection connection) throws SQLException {
        List<MigrationRecord> history = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT installed_rank, version, checksum
                     FROM invoward.flyway_schema_history
                     WHERE success = TRUE
                       AND version IS NOT NULL
                     ORDER BY installed_rank
                     """)) {
            while (result.next()) {
                history.add(new MigrationRecord(
                        result.getInt("installed_rank"),
                        result.getString("version"),
                        result.getInt("checksum")));
            }
        }
        return List.copyOf(history);
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void resetApplicationSchema() throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS invoward CASCADE");
        }
    }

    private record MigrationRecord(int installedRank, String version, int checksum) {
    }
}
