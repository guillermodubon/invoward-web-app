package io.github.guillermodubon.invoward.persistence;

import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresContainerConnectivityIT {

    private static final PostgreSQLContainer POSTGRES = PostgresTestContainer.instance();

    @Test
    void connectsToPostgreSql17Container() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT version(), current_database()")) {

            assertTrue(connection.isValid(5));
            assertTrue(result.next());
            assertTrue(result.getString("version").startsWith("PostgreSQL 17."));
            assertEquals("invoward", result.getString("current_database"));
        }
    }
}
