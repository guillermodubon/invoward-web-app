package io.github.guillermodubon.invoward.persistence;

import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Savepoint;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class PostgresIntegrityTestSupport {

    private static final PostgreSQLContainer POSTGRES = PostgresTestContainer.instance();

    @BeforeAll
    static void resetSchemaAndApplyMigrations() throws SQLException {
        try (Connection connection = openConnection();
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS invoward CASCADE");
        }

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("invoward")
                .defaultSchema("invoward")
                .locations("classpath:db/migration")
                .createSchemas(true)
                .load()
                .migrate();
    }

    protected static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    protected static void inTransaction(DatabaseWork work) throws Exception {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                work.execute(connection);
            } finally {
                connection.rollback();
            }
        }
    }

    protected static void assertRejected(Connection connection, DatabaseWork operation)
            throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try {
            SQLException failure = assertThrows(SQLException.class,
                    () -> operation.execute(connection));
            assertTrue(failure.getSQLState() != null && failure.getSQLState().startsWith("23"),
                    "Expected a PostgreSQL integrity-constraint violation");
        } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
    }

    @FunctionalInterface
    protected interface DatabaseWork {
        void execute(Connection connection) throws Exception;
    }
}
