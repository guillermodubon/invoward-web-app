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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseMigrationAndMappingIT {

    private static final PostgreSQLContainer POSTGRES = PostgresTestContainer.instance();
    private static final List<String> EXPECTED_MIGRATIONS = List.of(
            "001", "002", "003", "004", "005", "006", "007");
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

    private static ConfigurableApplicationContext startApplication() {
        SpringApplication application = new SpringApplication(InvoWardApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        return application.run(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.security.user.password=test-only");
    }

    private static void assertApplicationPersistenceContext(
            ConfigurableApplicationContext context) {
        assertNotNull(context);
        assertEquals("validate",
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
