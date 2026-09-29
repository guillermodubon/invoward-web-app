package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.InvoWardApplication;
import io.github.guillermodubon.invoward.identity.application.model.GeneratedVerificationToken;
import io.github.guillermodubon.invoward.identity.application.model.RegistrationOutcome;
import io.github.guillermodubon.invoward.identity.application.port.VerificationTokenGenerator;
import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryFailure;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import io.github.guillermodubon.invoward.support.email.FakeEmailSender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationTransactionIT {

    private static final PostgreSQLContainer POSTGRES = PostgresTestContainer.instance();
    private static final String PASSWORD = "correct horse battery staple";

    private static ConfigurableApplicationContext context;
    private static RegisterUserService registerUserService;
    private static RegisterUserTransaction registrationTransaction;
    private static JdbcTemplate jdbcTemplate;
    private static ControlledVerificationTokenGenerator tokenGenerator;
    private static FakeEmailSender fakeEmailSender;
    private static TransactionTemplate transactionTemplate;

    @BeforeAll
    static void startApplicationWithFreshSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS invoward CASCADE");
        }

        SpringApplication application = new SpringApplication(InvoWardApplication.class, TestBeans.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        context = application.run(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.security.user.password=test-only");

        registerUserService = context.getBean(RegisterUserService.class);
        registrationTransaction = context.getBean(RegisterUserTransaction.class);
        jdbcTemplate = context.getBean(JdbcTemplate.class);
        tokenGenerator = context.getBean(ControlledVerificationTokenGenerator.class);
        fakeEmailSender = context.getBean(FakeEmailSender.class);
        transactionTemplate = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    @AfterAll
    static void closeApplication() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void resetTokenGenerator() {
        tokenGenerator.clear();
        fakeEmailSender.clear();
    }

    @Test
    void persistsNormalizedPendingAccountAndHashOnlyRegistrationToken() {
        String rawToken = "A".repeat(43);
        String tokenHash = "1".repeat(64);
        String email = uniqueEmail();
        tokenGenerator.enqueue(new GeneratedVerificationToken(rawToken, tokenHash));
        Instant beforeRegistration = context.getBean(Clock.class).instant();

        RegistrationOutcome outcome = registerUserService.register(
                " Guillermo Hernández ", " " + email.toUpperCase() + " ", PASSWORD);

        Instant afterRegistration = context.getBean(Clock.class).instant();
        assertEquals(RegistrationOutcome.ACCEPTED, outcome);

        UserRow user = jdbcTemplate.queryForObject("""
                SELECT display_name, email, password_hash, email_verified, status, created_at, updated_at
                FROM invoward.users WHERE email = ?
                """, (result, rowNumber) -> new UserRow(
                result.getString("display_name"),
                result.getString("email"),
                result.getString("password_hash"),
                result.getBoolean("email_verified"),
                result.getString("status"),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant()), email);
        assertNotNull(user);
        assertEquals("Guillermo Hernández", user.displayName());
        assertEquals(email, user.email());
        assertTrue(user.passwordHash().startsWith("$argon2id$"));
        assertFalse(user.passwordHash().contains(PASSWORD));
        assertFalse(user.emailVerified());
        assertEquals("PENDING_VERIFICATION", user.status());
        assertTrue(!user.createdAt().isBefore(beforeRegistration));
        assertTrue(!user.createdAt().isAfter(afterRegistration));
        assertEquals(user.createdAt(), user.updatedAt());

        TokenRow token = jdbcTemplate.queryForObject("""
                SELECT token_hash, purpose, target_email, created_at, expires_at, used_at
                FROM invoward.email_verification_tokens WHERE target_email = ?
                """, (result, rowNumber) -> new TokenRow(
                result.getString("token_hash"),
                result.getString("purpose"),
                result.getString("target_email"),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("expires_at").toInstant(),
                result.getTimestamp("used_at")), email);
        assertNotNull(token);
        assertEquals(tokenHash, token.tokenHash());
        assertFalse(token.tokenHash().equals(rawToken));
        assertEquals("REGISTRATION", token.purpose());
        assertEquals(email, token.targetEmail());
        assertEquals(user.createdAt(), token.createdAt());
        assertEquals(Duration.ofHours(24), Duration.between(token.createdAt(), token.expiresAt()));
        assertNull(token.usedAt());
        assertEquals(1, tokenGenerator.generationCount());

        List<TransactionalEmail> sentEmails = fakeEmailSender.sentEmails();
        assertEquals(1, sentEmails.size());
        assertEquals(email, sentEmails.getFirst().recipient());
        assertEquals("Verify your InvoWard email", sentEmails.getFirst().subject());
        assertTrue(sentEmails.getFirst().textBody().contains("token=" + rawToken));
        assertTrue(sentEmails.getFirst().textBody().contains("expires at"));
    }

    @Test
    void existingEmailHasSameAcceptedOutcomeAndCreatesNoSecondToken() {
        String email = uniqueEmail();
        tokenGenerator.enqueue(new GeneratedVerificationToken("B".repeat(43), "2".repeat(64)));
        assertEquals(RegistrationOutcome.ACCEPTED,
                registerUserService.register("First", email, PASSWORD));

        assertEquals(RegistrationOutcome.ACCEPTED,
                registerUserService.register("Second", " " + email.toUpperCase() + " ", PASSWORD));

        assertEquals(1, countUsers(email));
        assertEquals(1, countTokens(email));
        assertEquals(1, tokenGenerator.generationCount());
        assertEquals(1, fakeEmailSender.sentEmails().size());
    }

    @Test
    void userAndTokenRowsAreBothRolledBackBeforeCommit() {
        String email = uniqueEmail();
        tokenGenerator.enqueue(new GeneratedVerificationToken("C".repeat(43), "3".repeat(64)));

        transactionTemplate.execute(status -> {
            registrationTransaction.createPendingRegistration("Rollback User", email, "$argon2id$test-hash");
            assertEquals(1, countUsers(email));
            assertEquals(1, countTokens(email));
            status.setRollbackOnly();
            return null;
        });

        assertEquals(0, countUsers(email));
        assertEquals(0, countTokens(email));
        assertTrue(fakeEmailSender.sentEmails().isEmpty());
    }

    @Test
    void sendsVerificationEmailOnlyAfterOuterTransactionCommits() {
        String email = uniqueEmail();
        tokenGenerator.enqueue(new GeneratedVerificationToken("D".repeat(43), "4".repeat(64)));

        transactionTemplate.execute(status -> {
            registrationTransaction.createPendingRegistration("Committed User", email, "$argon2id$test-hash");
            assertTrue(fakeEmailSender.sentEmails().isEmpty());
            return null;
        });

        assertEquals(1, fakeEmailSender.sentEmails().size());
        assertEquals(email, fakeEmailSender.sentEmails().getFirst().recipient());
    }

    @Test
    void emailProviderFailureDoesNotUndoCommittedRegistrationOrChangeAcceptedOutcome() {
        String email = uniqueEmail();
        tokenGenerator.enqueue(new GeneratedVerificationToken("E".repeat(43), "5".repeat(64)));
        fakeEmailSender.configureFailure(EmailDeliveryFailure.PROVIDER_UNAVAILABLE);

        assertEquals(RegistrationOutcome.ACCEPTED,
                registerUserService.register("Provider Failure User", email, PASSWORD));

        assertEquals(1, countUsers(email));
        assertEquals(1, countTokens(email));
        assertTrue(fakeEmailSender.sentEmails().isEmpty());
    }

    private int countUsers(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.users WHERE email = ?", Integer.class, email);
    }

    private int countTokens(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.email_verification_tokens WHERE target_email = ?",
                Integer.class,
                email);
    }

    private static String uniqueEmail() {
        return "registration-" + java.util.UUID.randomUUID() + "@example.com";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        @Primary
        ControlledVerificationTokenGenerator controlledVerificationTokenGenerator() {
            return new ControlledVerificationTokenGenerator();
        }

        @Bean
        @Primary
        FakeEmailSender fakeEmailSender() {
            return new FakeEmailSender();
        }
    }

    static final class ControlledVerificationTokenGenerator implements VerificationTokenGenerator {

        private final Queue<GeneratedVerificationToken> queuedTokens = new ConcurrentLinkedQueue<>();
        private final AtomicInteger generatedCount = new AtomicInteger();

        void enqueue(GeneratedVerificationToken token) {
            queuedTokens.add(token);
        }

        void clear() {
            queuedTokens.clear();
            generatedCount.set(0);
        }

        int generationCount() {
            return generatedCount.get();
        }

        @Override
        public GeneratedVerificationToken generate() {
            generatedCount.incrementAndGet();
            GeneratedVerificationToken token = queuedTokens.poll();
            if (token == null) {
                throw new IllegalStateException("Test verification token was not queued");
            }
            return token;
        }
    }

    private record UserRow(
            String displayName,
            String email,
            String passwordHash,
            boolean emailVerified,
            String status,
            Instant createdAt,
            Instant updatedAt) {
    }

    private record TokenRow(
            String tokenHash,
            String purpose,
            String targetEmail,
            Instant createdAt,
            Instant expiresAt,
            java.sql.Timestamp usedAt) {
    }
}
