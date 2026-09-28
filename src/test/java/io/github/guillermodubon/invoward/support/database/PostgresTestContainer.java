package io.github.guillermodubon.invoward.support.database;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public final class PostgresTestContainer {

    private static final PostgreSQLContainer INSTANCE = createAndStart();

    private PostgresTestContainer() {
    }

    public static PostgreSQLContainer instance() {
        return INSTANCE;
    }

    private static PostgreSQLContainer createAndStart() {
        PostgreSQLContainer container = new PostgreSQLContainer(
                DockerImageName.parse("postgres:17-alpine"))
                .withDatabaseName("invoward")
                .withUsername("invoward_test")
                .withPassword("invoward_test");
        container.start();
        return container;
    }
}
