package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.InvoWardApplication;
import io.github.guillermodubon.invoward.identity.application.exception.InvalidVerificationTokenException;
import io.github.guillermodubon.invoward.identity.application.model.EmailVerificationToken;
import io.github.guillermodubon.invoward.identity.application.model.GeneratedVerificationToken;
import io.github.guillermodubon.invoward.identity.application.model.NewUserAccount;
import io.github.guillermodubon.invoward.identity.application.model.PasswordResetToken;
import io.github.guillermodubon.invoward.identity.application.model.RegistrationOutcome;
import io.github.guillermodubon.invoward.identity.application.port.EmailVerificationTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.PasswordResetTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.application.port.VerificationTokenGenerator;
import io.github.guillermodubon.invoward.identity.domain.EmailVerificationPurpose;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.identity.infrastructure.crypto.SecureVerificationTokenGenerator;
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
import org.springframework.security.core.context.SecurityContextHolder;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationTransactionIT {

    private static final PostgreSQLContainer POSTGRES = PostgresTestContainer.instance();
    private static final String PASSWORD = "correct horse battery staple";

    private static ConfigurableApplicationContext context;
    private static RegisterUserService registerUserService;
    private static RegisterUserTransaction registrationTransaction;
    private static VerifyRegistrationEmailService verifyRegistrationEmailService;
    private static ResendRegistrationVerificationService resendRegistrationVerificationService;
    private static ForgotPasswordService forgotPasswordService;
    private static JdbcTemplate jdbcTemplate;
    private static ControlledVerificationTokenGenerator tokenGenerator;
    private static UserAccountRepository userAccountRepository;
    private static EmailVerificationTokenRepository verificationTokenRepository;
    private static PasswordResetTokenRepository passwordResetTokenRepository;
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
        context = application.run(PostgresTestContainer.applicationArguments(
                "--spring.security.user.password=test-only"));

        registerUserService = context.getBean(RegisterUserService.class);
        registrationTransaction = context.getBean(RegisterUserTransaction.class);
        verifyRegistrationEmailService = context.getBean(VerifyRegistrationEmailService.class);
        resendRegistrationVerificationService = context.getBean(ResendRegistrationVerificationService.class);
        forgotPasswordService = context.getBean(ForgotPasswordService.class);
        jdbcTemplate = context.getBean(JdbcTemplate.class);
        tokenGenerator = context.getBean(ControlledVerificationTokenGenerator.class);
        userAccountRepository = context.getBean(UserAccountRepository.class);
        verificationTokenRepository = context.getBean(EmailVerificationTokenRepository.class);
        passwordResetTokenRepository = context.getBean(PasswordResetTokenRepository.class);
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

    @Test
    void verificationActivatesAccountConsumesTokenAndInvalidatesSiblingTokensWithoutLoggingIn() {
        UserAccount pending = createPendingAccount(uniqueEmail());
        String rawToken = "F".repeat(43);
        String siblingRawToken = "G".repeat(43);
        String tokenHash = saveRegistrationToken(pending, rawToken);
        String siblingHash = saveRegistrationToken(pending, siblingRawToken);
        SecurityContextHolder.clearContext();

        verifyRegistrationEmailService.verify(rawToken);

        UserAccount activated = userAccountRepository.findById(pending.id()).orElseThrow();
        assertEquals(UserStatus.ACTIVE, activated.status());
        assertTrue(activated.emailVerified());
        assertEquals(1, activated.version());
        assertNotNull(verificationTokenRepository.findByTokenHashForUpdate(tokenHash).orElseThrow().usedAt());
        assertNotNull(verificationTokenRepository.findByTokenHashForUpdate(siblingHash).orElseThrow().usedAt());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void resendDuringCooldownCreatesNoTokenAndSendsNoEmail() {
        UserAccount pending = createPendingAccount(uniqueEmail());
        saveRegistrationToken(pending, "I".repeat(43), context.getBean(Clock.class).instant().minusSeconds(30));

        resendRegistrationVerificationService.resend(pending.email());

        assertEquals(1, countTokens(pending.email()));
        assertEquals(0, tokenGenerator.generationCount());
        assertTrue(fakeEmailSender.sentEmails().isEmpty());
    }

    @Test
    void resendAfterCooldownRotatesTokenAndSendsOnlyAfterCommit() {
        String email = uniqueEmail();
        UserAccount pending = createPendingAccount(email);
        String oldHash = saveRegistrationToken(
                pending, "J".repeat(43), context.getBean(Clock.class).instant().minusSeconds(61));
        Instant oldCreatedAt = verificationTokenRepository.findByTokenHashForUpdate(oldHash)
                .orElseThrow().createdAt();
        String rawToken = "K".repeat(43);
        String tokenHash = "6".repeat(64);
        tokenGenerator.enqueue(new GeneratedVerificationToken(rawToken, tokenHash));

        transactionTemplate.execute(status -> {
            resendRegistrationVerificationService.resend(" " + email.toUpperCase() + " ");
            assertTrue(fakeEmailSender.sentEmails().isEmpty());
            assertEquals(2, countTokens(email));
            return null;
        });

        assertNotNull(verificationTokenRepository.findByTokenHashForUpdate(oldHash).orElseThrow().usedAt());
        EmailVerificationToken savedToken = verificationTokenRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow();
        assertEquals(email, savedToken.targetEmail());
        assertEquals(EmailVerificationPurpose.REGISTRATION, savedToken.purpose());
        assertNull(savedToken.usedAt());
        assertTrue(savedToken.createdAt().isAfter(oldCreatedAt));
        assertEquals(Duration.ofHours(24), Duration.between(savedToken.createdAt(), savedToken.expiresAt()));
        assertEquals(1, tokenGenerator.generationCount());
        assertEquals(1, fakeEmailSender.sentEmails().size());
        assertEquals(email, fakeEmailSender.sentEmails().getFirst().recipient());
        assertTrue(fakeEmailSender.sentEmails().getFirst().textBody().contains("token=" + rawToken));
    }

    @Test
    void resendRollbackPreservesOldTokenAndSuppressesNewEmail() {
        String email = uniqueEmail();
        UserAccount pending = createPendingAccount(email);
        String oldHash = saveRegistrationToken(
                pending, "N".repeat(43), context.getBean(Clock.class).instant().minusSeconds(61));
        String newHash = "8".repeat(64);
        tokenGenerator.enqueue(new GeneratedVerificationToken("O".repeat(43), newHash));

        transactionTemplate.execute(status -> {
            resendRegistrationVerificationService.resend(email);
            assertEquals(2, countTokens(email));
            assertTrue(fakeEmailSender.sentEmails().isEmpty());
            status.setRollbackOnly();
            return null;
        });

        assertEquals(1, countTokens(email));
        assertNull(verificationTokenRepository.findByTokenHashForUpdate(oldHash).orElseThrow().usedAt());
        assertTrue(verificationTokenRepository.findByTokenHashForUpdate(newHash).isEmpty());
        assertEquals(1, tokenGenerator.generationCount());
        assertTrue(fakeEmailSender.sentEmails().isEmpty());
    }

    @Test
    void resendEmailFailureDoesNotRollbackTokenRotation() {
        String email = uniqueEmail();
        UserAccount pending = createPendingAccount(email);
        String oldHash = saveRegistrationToken(
                pending, "L".repeat(43), context.getBean(Clock.class).instant().minusSeconds(61));
        tokenGenerator.enqueue(new GeneratedVerificationToken("M".repeat(43), "7".repeat(64)));
        fakeEmailSender.configureFailure(EmailDeliveryFailure.PROVIDER_UNAVAILABLE);

        resendRegistrationVerificationService.resend(email);

        assertEquals(2, countTokens(email));
        assertEquals(1, tokenGenerator.generationCount());
        assertNotNull(verificationTokenRepository.findByTokenHashForUpdate(oldHash).orElseThrow().usedAt());
        assertTrue(fakeEmailSender.sentEmails().isEmpty());
    }

    @Test
    void forgotPasswordRotatesHashOnlyTokenAndSendsEmailOnlyAfterCommit() {
        String email = uniqueEmail();
        UserAccount active = createActiveVerifiedAccount(email);
        String oldHash = savePasswordResetToken(
                active, "P".repeat(43), context.getBean(Clock.class).instant().minusSeconds(61));
        String rawToken = "Q".repeat(43);
        String tokenHash = "9".repeat(64);
        tokenGenerator.enqueue(new GeneratedVerificationToken(rawToken, tokenHash));

        transactionTemplate.execute(status -> {
            forgotPasswordService.request(" " + email.toUpperCase() + " ");
            assertTrue(fakeEmailSender.sentEmails().isEmpty());
            assertEquals(2, countPasswordResetTokens(active.id()));
            return null;
        });

        assertNotNull(passwordResetTokenRepository.findByTokenHashForUpdate(oldHash).orElseThrow().usedAt());
        PasswordResetToken savedToken = passwordResetTokenRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow();
        assertEquals(active.id(), savedToken.userId());
        assertNull(savedToken.usedAt());
        assertTrue(savedToken.createdAt().isAfter(
                passwordResetTokenRepository.findByTokenHashForUpdate(oldHash).orElseThrow().createdAt()));
        assertEquals(Duration.ofMinutes(30), Duration.between(savedToken.createdAt(), savedToken.expiresAt()));
        assertTrue(passwordResetTokenRepository.findByTokenHashForUpdate(rawToken).isEmpty());
        assertEquals(1, tokenGenerator.generationCount());
        assertEquals(1, fakeEmailSender.sentEmails().size());
        assertEquals(email, fakeEmailSender.sentEmails().getFirst().recipient());
        assertEquals("Reset your InvoWard password", fakeEmailSender.sentEmails().getFirst().subject());
        assertTrue(fakeEmailSender.sentEmails().getFirst().textBody().contains("token=" + rawToken));
        assertTrue(fakeEmailSender.sentEmails().getFirst().textBody().contains("expires at"));
    }

    @Test
    void forgotPasswordDuringCooldownCreatesNoTokenAndSendsNoEmail() {
        UserAccount active = createActiveVerifiedAccount(uniqueEmail());
        savePasswordResetToken(
                active, "R".repeat(43), context.getBean(Clock.class).instant().minusSeconds(30));

        forgotPasswordService.request(active.email());

        assertEquals(1, countPasswordResetTokens(active.id()));
        assertEquals(0, tokenGenerator.generationCount());
        assertTrue(fakeEmailSender.sentEmails().isEmpty());
    }

    @Test
    void forgotPasswordRollbackPreservesOldTokenAndSuppressesEmail() {
        UserAccount active = createActiveVerifiedAccount(uniqueEmail());
        String oldHash = savePasswordResetToken(
                active, "S".repeat(43), context.getBean(Clock.class).instant().minusSeconds(61));
        String newHash = "a".repeat(64);
        tokenGenerator.enqueue(new GeneratedVerificationToken("T".repeat(43), newHash));

        transactionTemplate.execute(status -> {
            forgotPasswordService.request(active.email());
            assertEquals(2, countPasswordResetTokens(active.id()));
            assertTrue(fakeEmailSender.sentEmails().isEmpty());
            status.setRollbackOnly();
            return null;
        });

        assertEquals(1, countPasswordResetTokens(active.id()));
        assertNull(passwordResetTokenRepository.findByTokenHashForUpdate(oldHash).orElseThrow().usedAt());
        assertTrue(passwordResetTokenRepository.findByTokenHashForUpdate(newHash).isEmpty());
        assertEquals(1, tokenGenerator.generationCount());
        assertTrue(fakeEmailSender.sentEmails().isEmpty());
    }

    @Test
    void forgotPasswordEmailFailureDoesNotRollbackCommittedTokens() {
        UserAccount active = createActiveVerifiedAccount(uniqueEmail());
        String oldHash = savePasswordResetToken(
                active, "U".repeat(43), context.getBean(Clock.class).instant().minusSeconds(61));
        tokenGenerator.enqueue(new GeneratedVerificationToken("V".repeat(43), "c".repeat(64)));
        fakeEmailSender.configureFailure(EmailDeliveryFailure.PROVIDER_UNAVAILABLE);

        forgotPasswordService.request(active.email());

        assertEquals(2, countPasswordResetTokens(active.id()));
        assertNotNull(passwordResetTokenRepository.findByTokenHashForUpdate(oldHash).orElseThrow().usedAt());
        assertEquals(1, tokenGenerator.generationCount());
        assertTrue(fakeEmailSender.sentEmails().isEmpty());
    }

    @Test
    void concurrentConsumptionOfSameRegistrationTokenHasAtMostOneSuccess() throws Exception {
        UserAccount pending = createPendingAccount(uniqueEmail());
        String rawToken = "H".repeat(43);
        saveRegistrationToken(pending, rawToken);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> first = executor.submit(() -> attemptVerification(rawToken, ready, start));
            Future<Boolean> second = executor.submit(() -> attemptVerification(rawToken, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            boolean firstSucceeded = first.get(20, TimeUnit.SECONDS);
            boolean secondSucceeded = second.get(20, TimeUnit.SECONDS);
            assertNotEquals(firstSucceeded, secondSucceeded);
            UserAccount activated = userAccountRepository.findById(pending.id()).orElseThrow();
            assertEquals(UserStatus.ACTIVE, activated.status());
            assertTrue(activated.emailVerified());
            assertEquals(1, activated.version());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static boolean attemptVerification(
            String rawToken,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Verification test start signal was not received");
        }
        try {
            verifyRegistrationEmailService.verify(rawToken);
            return true;
        } catch (InvalidVerificationTokenException exception) {
            return false;
        }
    }

    private UserAccount createPendingAccount(String email) {
        Instant createdAt = context.getBean(Clock.class).instant();
        return userAccountRepository.create(new NewUserAccount(
                "Verification User", email, "$argon2id$test-hash", createdAt));
    }

    private UserAccount createActiveVerifiedAccount(String email) {
        UserAccount pending = createPendingAccount(email);
        return userAccountRepository.activateVerifiedRegistration(
                pending.activateVerifiedRegistration(context.getBean(Clock.class).instant()));
    }

    private String saveRegistrationToken(UserAccount user, String rawToken) {
        return saveRegistrationToken(user, rawToken, context.getBean(Clock.class).instant());
    }

    private String saveRegistrationToken(UserAccount user, String rawToken, Instant createdAt) {
        String tokenHash = tokenGenerator.hash(rawToken);
        verificationTokenRepository.save(new EmailVerificationToken(
                user.id(), tokenHash, EmailVerificationPurpose.REGISTRATION, user.email(),
                createdAt.plus(Duration.ofHours(24)), null, createdAt));
        return tokenHash;
    }

    private String savePasswordResetToken(UserAccount user, String rawToken, Instant createdAt) {
        String tokenHash = tokenGenerator.hash(rawToken);
        passwordResetTokenRepository.save(new PasswordResetToken(
                user.id(), tokenHash, createdAt.plus(Duration.ofMinutes(30)), null, createdAt));
        return tokenHash;
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

    private int countPasswordResetTokens(java.util.UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.password_reset_tokens WHERE user_id = ?",
                Integer.class,
                userId);
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

        @Override
        public String hash(String rawToken) {
            return new SecureVerificationTokenGenerator().hash(rawToken);
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
