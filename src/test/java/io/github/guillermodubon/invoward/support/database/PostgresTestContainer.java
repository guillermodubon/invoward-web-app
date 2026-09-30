package io.github.guillermodubon.invoward.support.database;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PostgresTestContainer {

    private static final String DATASOURCE_URL = "spring.datasource.url";
    private static final String DATASOURCE_USERNAME = "spring.datasource.username";
    private static final String DATASOURCE_PASSWORD = "spring.datasource.password";
    private static final String HIKARI_MAXIMUM_POOL_SIZE = "spring.datasource.hikari.maximum-pool-size";
    private static final String HIKARI_MINIMUM_IDLE = "spring.datasource.hikari.minimum-idle";
    private static final int TEST_MAXIMUM_POOL_SIZE = 2;
    private static final int TEST_MINIMUM_IDLE = 0;
    private static final PostgreSQLContainer INSTANCE = createAndStart();

    private PostgresTestContainer() {
    }

    public static PostgreSQLContainer instance() {
        return INSTANCE;
    }

    public static void configure(DynamicPropertyRegistry registry) {
        Objects.requireNonNull(registry);
        registry.add(DATASOURCE_URL, INSTANCE::getJdbcUrl);
        registry.add(DATASOURCE_USERNAME, INSTANCE::getUsername);
        registry.add(DATASOURCE_PASSWORD, INSTANCE::getPassword);
        registry.add(HIKARI_MAXIMUM_POOL_SIZE, () -> TEST_MAXIMUM_POOL_SIZE);
        registry.add(HIKARI_MINIMUM_IDLE, () -> TEST_MINIMUM_IDLE);
    }

    public static String[] applicationArguments(String... additionalArguments) {
        Objects.requireNonNull(additionalArguments);
        List<String> arguments = new ArrayList<>(List.of(
                "--" + DATASOURCE_URL + "=" + INSTANCE.getJdbcUrl(),
                "--" + DATASOURCE_USERNAME + "=" + INSTANCE.getUsername(),
                "--" + DATASOURCE_PASSWORD + "=" + INSTANCE.getPassword(),
                "--" + HIKARI_MAXIMUM_POOL_SIZE + "=" + TEST_MAXIMUM_POOL_SIZE,
                "--" + HIKARI_MINIMUM_IDLE + "=" + TEST_MINIMUM_IDLE));
        arguments.addAll(List.of(additionalArguments));
        return arguments.toArray(String[]::new);
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
