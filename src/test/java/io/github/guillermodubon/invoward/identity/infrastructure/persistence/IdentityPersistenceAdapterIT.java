package io.github.guillermodubon.invoward.identity.infrastructure.persistence;

import io.github.guillermodubon.invoward.InvoWardApplication;
import io.github.guillermodubon.invoward.identity.application.exception.DuplicateEmailException;
import io.github.guillermodubon.invoward.identity.application.model.NewUserAccount;
import io.github.guillermodubon.invoward.identity.application.port.EmailVerificationTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.domain.EmailVerificationPurpose;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityPersistenceAdapterIT {

    private static final PostgreSQLContainer POSTGRES = PostgresTestContainer.instance();
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static ConfigurableApplicationContext context;
    private static UserAccountRepository userAccounts;
    private static EmailVerificationTokenRepository verificationTokens;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startApplicationWithFreshSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS invoward CASCADE");
        }

        SpringApplication application = new SpringApplication(InvoWardApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        context = application.run(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.security.user.password=test-only");
        userAccounts = context.getBean(UserAccountRepository.class);
        verificationTokens = context.getBean(EmailVerificationTokenRepository.class);
        jdbcTemplate = context.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void closeApplication() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void createsPendingUserAndMapsPersistedState() {
        String email = uniqueEmail();
        UserAccount created = userAccounts.create(newUser(email));

        assertNotNull(created.id());
        assertEquals("Guillermo Hernández", created.displayName());
        assertEquals(email, created.email());
        assertEquals("$argon2id$integration-test-hash", created.passwordHash());
        assertFalse(created.emailVerified());
        assertEquals(UserStatus.PENDING_VERIFICATION, created.status());
        assertEquals(0, created.version());
        assertEquals(CREATED_AT, created.createdAt());
        assertEquals(CREATED_AT, created.updatedAt());
        assertEquals(created, userAccounts.findByNormalizedEmail(email).orElseThrow());
        assertTrue(userAccounts.existsByNormalizedEmail(email));
    }

    @Test
    void databaseEnforcesCaseInsensitiveEmailUniquenessAndAdapterTranslatesRace() {
        String email = uniqueEmail();
        UserAccount created = userAccounts.create(newUser(email));
        String upperCaseEmail = email.toUpperCase(Locale.ROOT);

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                INSERT INTO invoward.users (
                    id, display_name, email, password_hash, email_verified, status, version, created_at, updated_at
                ) VALUES (?, 'Database duplicate', ?, '$argon2id$duplicate-test', FALSE,
                    'PENDING_VERIFICATION', 0, ?, ?)
                """, UUID.randomUUID(), upperCaseEmail,
                java.sql.Timestamp.from(CREATED_AT), java.sql.Timestamp.from(CREATED_AT)));

        DuplicateEmailException failure = assertThrows(DuplicateEmailException.class,
                () -> userAccounts.create(newUser(email)));
        assertEquals("Account creation failed", failure.getMessage());
        assertFalse(failure.getMessage().contains(email));
        assertEquals(created.id(), userAccounts.findByNormalizedEmail(email).orElseThrow().id());
    }

    @Test
    void savesRegistrationTokenWithHashAndExpectedPurpose() {
        String email = uniqueEmail();
        UserAccount user = userAccounts.create(newUser(email));
        String tokenHash = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        Instant expiresAt = CREATED_AT.plusSeconds(86_400);

        verificationTokens.saveRegistrationToken(
                user.id(), email, tokenHash, CREATED_AT, expiresAt);

        PersistedToken persisted = jdbcTemplate.queryForObject("""
                SELECT user_id, token_hash, purpose, target_email, expires_at, used_at, created_at
                FROM invoward.email_verification_tokens
                WHERE token_hash = ?
                """, (result, rowNumber) -> new PersistedToken(
                result.getObject("user_id", UUID.class),
                result.getString("token_hash"),
                result.getString("purpose"),
                result.getString("target_email"),
                result.getTimestamp("expires_at").toInstant(),
                result.getTimestamp("used_at"),
                result.getTimestamp("created_at").toInstant()), tokenHash);
        assertNotNull(persisted);
        assertEquals(user.id(), persisted.userId());
        assertEquals(tokenHash, persisted.tokenHash());
        assertEquals(EmailVerificationPurpose.REGISTRATION.name(), persisted.purpose());
        assertEquals(email, persisted.targetEmail());
        assertEquals(expiresAt, persisted.expiresAt());
        assertNull(persisted.usedAt());
        assertEquals(CREATED_AT, persisted.createdAt());
    }

    private static NewUserAccount newUser(String email) {
        return new NewUserAccount(
                "Guillermo Hernández",
                email,
                "$argon2id$integration-test-hash",
                CREATED_AT);
    }

    private static String uniqueEmail() {
        return "identity-" + UUID.randomUUID() + "@example.com";
    }

    private record PersistedToken(
            UUID userId,
            String tokenHash,
            String purpose,
            String targetEmail,
            Instant expiresAt,
            java.sql.Timestamp usedAt,
            Instant createdAt) {
    }
}
