package io.github.guillermodubon.invoward.identity.infrastructure.persistence;

import io.github.guillermodubon.invoward.InvoWardApplication;
import io.github.guillermodubon.invoward.identity.application.exception.AccountConflictException;
import io.github.guillermodubon.invoward.identity.application.exception.DuplicateEmailException;
import io.github.guillermodubon.invoward.identity.application.exception.EmailAddressConflictException;
import io.github.guillermodubon.invoward.identity.application.model.EmailVerificationToken;
import io.github.guillermodubon.invoward.identity.application.model.NewUserAccount;
import io.github.guillermodubon.invoward.identity.application.model.PasswordResetToken;
import io.github.guillermodubon.invoward.identity.application.port.EmailVerificationTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.PasswordResetTokenRepository;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityPersistenceAdapterIT {

    private static final PostgreSQLContainer POSTGRES = PostgresTestContainer.instance();
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static ConfigurableApplicationContext context;
    private static UserAccountRepository userAccounts;
    private static EmailVerificationTokenRepository verificationTokens;
    private static PasswordResetTokenRepository passwordResetTokens;
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
        context = application.run(PostgresTestContainer.applicationArguments(
                "--spring.security.user.password=test-only"));
        userAccounts = context.getBean(UserAccountRepository.class);
        verificationTokens = context.getBean(EmailVerificationTokenRepository.class);
        passwordResetTokens = context.getBean(PasswordResetTokenRepository.class);
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
        assertEquals(created, userAccounts.findById(created.id()).orElseThrow());
        assertTrue(userAccounts.existsByNormalizedEmail(email));
    }

    @Test
    void appliesVerifiedActivationAndAccountMutationsWithVersionedPersistence() {
        UserAccount pending = userAccounts.create(newUser(uniqueEmail()));
        Instant activatedAt = CREATED_AT.plusSeconds(10);
        UserAccount active = userAccounts.activateVerifiedRegistration(
                pending.activateVerifiedRegistration(activatedAt));

        assertEquals(UserStatus.ACTIVE, active.status());
        assertTrue(active.emailVerified());
        assertEquals(1, active.version());
        assertEquals(activatedAt, active.updatedAt());

        Instant displayChangedAt = activatedAt.plusSeconds(10);
        UserAccount renamed = userAccounts.updateDisplayName(
                active.updateDisplayName("  New  Account Name  ", displayChangedAt));
        assertEquals("New  Account Name", renamed.displayName());
        assertEquals(2, renamed.version());
        assertEquals(displayChangedAt, renamed.updatedAt());

        Instant passwordChangedAt = displayChangedAt.plusSeconds(10);
        UserAccount passwordChanged = userAccounts.updatePasswordHash(
                renamed.updatePasswordHash("$argon2id$replacement-hash", passwordChangedAt));
        assertEquals("$argon2id$replacement-hash", passwordChanged.passwordHash());
        assertEquals(3, passwordChanged.version());
        assertEquals(passwordChangedAt, passwordChanged.updatedAt());

        Instant emailChangedAt = passwordChangedAt.plusSeconds(10);
        UserAccount emailChanged = userAccounts.updateEmail(
                passwordChanged.updateEmail(" New.Target@Example.com ", emailChangedAt));
        assertEquals("new.target@example.com", emailChanged.email());
        assertTrue(emailChanged.emailVerified());
        assertEquals(UserStatus.ACTIVE, emailChanged.status());
        assertEquals(4, emailChanged.version());
        assertEquals(emailChangedAt, emailChanged.updatedAt());
        assertEquals(emailChanged, userAccounts.findById(emailChanged.id()).orElseThrow());
    }

    @Test
    void rejectsStaleAccountMutationWithoutOverwritingNewerState() {
        UserAccount pending = userAccounts.create(newUser(uniqueEmail()));
        UserAccount active = userAccounts.activateVerifiedRegistration(
                pending.activateVerifiedRegistration(CREATED_AT.plusSeconds(10)));
        UserAccount staleSnapshot = active;
        Instant updateAt = CREATED_AT.plusSeconds(20);

        UserAccount newerState = userAccounts.updateDisplayName(
                active.updateDisplayName("Newer display name", updateAt));
        AccountConflictException conflict = assertThrows(AccountConflictException.class,
                () -> userAccounts.updatePasswordHash(
                        staleSnapshot.updatePasswordHash("$argon2id$stale-password", updateAt.plusSeconds(1))));

        assertEquals("The account changed while the operation was in progress", conflict.getMessage());
        UserAccount persisted = userAccounts.findById(active.id()).orElseThrow();
        assertEquals(newerState, persisted);
        assertEquals("Newer display name", persisted.displayName());
        assertEquals("$argon2id$integration-test-hash", persisted.passwordHash());
    }

    @Test
    void concurrentOptimisticAccountUpdatesAllowOnlyOneStaleSnapshotMutation() throws Exception {
        UserAccount original = createActiveUser();
        UserAccount displaySnapshot = userAccounts.findById(original.id()).orElseThrow();
        UserAccount passwordSnapshot = userAccounts.findById(original.id()).orElseThrow();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> displayUpdate = executor.submit(() -> attemptUpdate(
                    ready,
                    start,
                    () -> userAccounts.updateDisplayName(displaySnapshot.updateDisplayName(
                            "Concurrent display winner", CREATED_AT.plusSeconds(20)))));
            Future<Boolean> passwordUpdate = executor.submit(() -> attemptUpdate(
                    ready,
                    start,
                    () -> userAccounts.updatePasswordHash(passwordSnapshot.updatePasswordHash(
                            "$argon2id$concurrent-password", CREATED_AT.plusSeconds(20)))));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Both updates should use snapshots read before either write.");
            start.countDown();

            boolean displaySucceeded = displayUpdate.get(20, TimeUnit.SECONDS);
            boolean passwordSucceeded = passwordUpdate.get(20, TimeUnit.SECONDS);
            assertNotEquals(displaySucceeded, passwordSucceeded);

            UserAccount persisted = userAccounts.findById(original.id()).orElseThrow();
            assertEquals(original.version() + 1, persisted.version());
            if (displaySucceeded) {
                assertEquals("Concurrent display winner", persisted.displayName());
                assertEquals(original.passwordHash(), persisted.passwordHash());
            } else {
                assertEquals(original.displayName(), persisted.displayName());
                assertEquals("$argon2id$concurrent-password", persisted.passwordHash());
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentEmailUpdatesTranslateDatabaseUniquenessRace() throws Exception {
        UserAccount firstOwner = createActiveUser();
        UserAccount secondOwner = createActiveUser();
        String sharedTarget = uniqueEmail();
        CountDownLatch availableChecksComplete = new CountDownLatch(2);
        CountDownLatch updateStart = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> first = executor.submit(() -> updateEmailAfterAvailabilityCheck(
                    firstOwner, sharedTarget, availableChecksComplete, updateStart));
            Future<Boolean> second = executor.submit(() -> updateEmailAfterAvailabilityCheck(
                    secondOwner, sharedTarget, availableChecksComplete, updateStart));
            assertTrue(availableChecksComplete.await(10, TimeUnit.SECONDS),
                    "Both transactions should observe the target as available before either update.");
            updateStart.countDown();

            boolean firstSucceeded = first.get(20, TimeUnit.SECONDS);
            boolean secondSucceeded = second.get(20, TimeUnit.SECONDS);
            assertNotEquals(firstSucceeded, secondSucceeded);
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM invoward.users WHERE email = ?", Integer.class, sharedTarget));
            assertEquals(sharedTarget, userAccounts.findByNormalizedEmail(sharedTarget).orElseThrow().email());
        } finally {
            updateStart.countDown();
            executor.shutdownNow();
        }
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
    void translatesEmailUniquenessViolationDuringAccountEmailUpdate() {
        UserAccount owner = userAccounts.create(newUser(uniqueEmail()));
        UserAccount targetOwner = userAccounts.create(newUser(uniqueEmail()));
        UserAccount activeOwner = userAccounts.activateVerifiedRegistration(
                owner.activateVerifiedRegistration(CREATED_AT.plusSeconds(10)));

        EmailAddressConflictException failure = assertThrows(EmailAddressConflictException.class,
                () -> userAccounts.updateEmail(activeOwner.updateEmail(
                        targetOwner.email(), CREATED_AT.plusSeconds(20))));

        assertEquals("The email address is unavailable.", failure.getMessage());
        assertFalse(failure.getMessage().contains(targetOwner.email()));
        assertEquals(activeOwner.email(), userAccounts.findById(owner.id()).orElseThrow().email());
    }

    @Test
    void savesRegistrationTokenWithHashAndExpectedPurpose() {
        String email = uniqueEmail();
        UserAccount user = userAccounts.create(newUser(email));
        String tokenHash = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        Instant expiresAt = CREATED_AT.plusSeconds(86_400);

        EmailVerificationToken token = new EmailVerificationToken(
                user.id(), tokenHash, EmailVerificationPurpose.REGISTRATION, email, expiresAt, null, CREATED_AT);
        verificationTokens.save(token);

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
        assertEquals(token, verificationTokens.findByTokenHashForUpdate(tokenHash).orElseThrow());
        assertEquals(CREATED_AT, verificationTokens.findLatestCreatedAtByUserAndPurpose(
                user.id(), EmailVerificationPurpose.REGISTRATION).orElseThrow());
    }

    @Test
    void savesAndLocksEmailChangeTokensAndInvalidatesOnlyUnusedPurposeTokensForOwner() {
        UserAccount owner = userAccounts.create(newUser(uniqueEmail()));
        UserAccount otherUser = userAccounts.create(newUser(uniqueEmail()));
        Instant firstEmailChangeAt = CREATED_AT.plusSeconds(10);
        Instant secondEmailChangeAt = CREATED_AT.plusSeconds(20);
        String registrationHash = newTokenHash();
        String firstEmailChangeHash = newTokenHash();
        String secondEmailChangeHash = newTokenHash();
        String otherUserEmailChangeHash = newTokenHash();

        verificationTokens.save(new EmailVerificationToken(
                owner.id(), registrationHash, EmailVerificationPurpose.REGISTRATION,
                owner.email(), CREATED_AT.plusSeconds(86_400), null, CREATED_AT));
        verificationTokens.save(new EmailVerificationToken(
                owner.id(), firstEmailChangeHash, EmailVerificationPurpose.EMAIL_CHANGE,
                "first-target@example.com", firstEmailChangeAt.plusSeconds(86_400), null, firstEmailChangeAt));
        verificationTokens.save(new EmailVerificationToken(
                owner.id(), secondEmailChangeHash, EmailVerificationPurpose.EMAIL_CHANGE,
                "second-target@example.com", secondEmailChangeAt.plusSeconds(86_400), null, secondEmailChangeAt));
        verificationTokens.save(new EmailVerificationToken(
                otherUser.id(), otherUserEmailChangeHash, EmailVerificationPurpose.EMAIL_CHANGE,
                "other-target@example.com", secondEmailChangeAt.plusSeconds(86_400), null, secondEmailChangeAt));

        assertEquals(secondEmailChangeAt, verificationTokens.findLatestCreatedAtByUserAndPurpose(
                owner.id(), EmailVerificationPurpose.EMAIL_CHANGE).orElseThrow());
        assertEquals("second-target@example.com", verificationTokens.findByTokenHashForUpdate(
                secondEmailChangeHash).orElseThrow().targetEmail());

        Instant invalidatedAt = CREATED_AT.plusSeconds(30);
        assertEquals(2, verificationTokens.invalidateUnusedByUserAndPurpose(
                owner.id(), EmailVerificationPurpose.EMAIL_CHANGE, invalidatedAt));
        assertEquals(invalidatedAt, verificationTokens.findByTokenHashForUpdate(firstEmailChangeHash)
                .orElseThrow().usedAt());
        assertEquals(invalidatedAt, verificationTokens.findByTokenHashForUpdate(secondEmailChangeHash)
                .orElseThrow().usedAt());
        assertNull(verificationTokens.findByTokenHashForUpdate(registrationHash).orElseThrow().usedAt());
        assertNull(verificationTokens.findByTokenHashForUpdate(otherUserEmailChangeHash).orElseThrow().usedAt());

        Instant usedAt = CREATED_AT.plusSeconds(40);
        assertTrue(verificationTokens.markUsed(registrationHash, usedAt));
        assertFalse(verificationTokens.markUsed(registrationHash, usedAt.plusSeconds(1)));
        assertEquals(usedAt, verificationTokens.findByTokenHashForUpdate(registrationHash).orElseThrow().usedAt());
    }

    @Test
    void savesAndLocksPasswordResetTokensAndInvalidatesOnlyUnusedTokensForOwner() {
        UserAccount owner = userAccounts.create(newUser(uniqueEmail()));
        UserAccount otherUser = userAccounts.create(newUser(uniqueEmail()));
        Instant firstCreatedAt = CREATED_AT.plusSeconds(100);
        Instant latestCreatedAt = CREATED_AT.plusSeconds(200);
        String firstHash = newTokenHash();
        String latestHash = newTokenHash();
        String otherUserHash = newTokenHash();
        PasswordResetToken latestToken = new PasswordResetToken(
                owner.id(), latestHash, latestCreatedAt.plusSeconds(1_800), null, latestCreatedAt);

        passwordResetTokens.save(new PasswordResetToken(
                owner.id(), firstHash, firstCreatedAt.plusSeconds(1_800), null, firstCreatedAt));
        passwordResetTokens.save(latestToken);
        passwordResetTokens.save(new PasswordResetToken(
                otherUser.id(), otherUserHash, latestCreatedAt.plusSeconds(1_800), null, latestCreatedAt));

        assertEquals(latestToken, passwordResetTokens.findByTokenHashForUpdate(latestHash).orElseThrow());
        assertEquals(latestCreatedAt, passwordResetTokens.findLatestCreatedAtByUser(owner.id()).orElseThrow());

        Instant usedAt = latestCreatedAt.plusSeconds(5);
        assertTrue(passwordResetTokens.markUsed(latestHash, usedAt));
        assertFalse(passwordResetTokens.markUsed(latestHash, usedAt.plusSeconds(1)));
        assertEquals(1, passwordResetTokens.invalidateUnusedByUser(owner.id(), latestCreatedAt.plusSeconds(10)));
        assertNotNull(passwordResetTokens.findByTokenHashForUpdate(firstHash).orElseThrow().usedAt());
        assertNull(passwordResetTokens.findByTokenHashForUpdate(otherUserHash).orElseThrow().usedAt());
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

    private static UserAccount createActiveUser() {
        UserAccount pending = userAccounts.create(newUser(uniqueEmail()));
        return userAccounts.activateVerifiedRegistration(
                pending.activateVerifiedRegistration(CREATED_AT.plusSeconds(10)));
    }

    private static boolean attemptUpdate(
            CountDownLatch ready,
            CountDownLatch start,
            AccountUpdate update) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Account update start gate was not released.");
        }
        try {
            update.run();
            return true;
        } catch (AccountConflictException exception) {
            return false;
        }
    }

    private static boolean updateEmailAfterAvailabilityCheck(
            UserAccount owner,
            String target,
            CountDownLatch checksComplete,
            CountDownLatch start) throws InterruptedException {
        if (userAccounts.existsByNormalizedEmail(target)) {
            throw new AssertionError("The shared target should be available before either write.");
        }
        checksComplete.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Email update start gate was not released.");
        }
        try {
            userAccounts.updateEmail(owner.updateEmail(target, CREATED_AT.plusSeconds(20)));
            return true;
        } catch (EmailAddressConflictException exception) {
            return false;
        }
    }

    @FunctionalInterface
    private interface AccountUpdate {
        void run();
    }

    private static String newTokenHash() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
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
