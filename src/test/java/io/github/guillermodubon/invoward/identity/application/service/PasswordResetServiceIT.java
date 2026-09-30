package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.InvoWardApplication;
import io.github.guillermodubon.invoward.identity.application.exception.InvalidPasswordResetTokenException;
import io.github.guillermodubon.invoward.identity.application.model.GeneratedVerificationToken;
import io.github.guillermodubon.invoward.identity.application.model.NewUserAccount;
import io.github.guillermodubon.invoward.identity.application.model.PasswordResetToken;
import io.github.guillermodubon.invoward.identity.application.port.PasswordHasher;
import io.github.guillermodubon.invoward.identity.application.port.PasswordResetTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.application.port.VerificationTokenGenerator;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.identity.infrastructure.security.AuthenticatedUserPrincipal;
import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryFailure;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import io.github.guillermodubon.invoward.support.email.FakeEmailSender;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = InvoWardApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PasswordResetServiceIT.TestEmailConfiguration.class)
class PasswordResetServiceIT {

    private static final String OLD_PASSWORD = "the original account passphrase";
    private static final String NEW_PASSWORD = "the replacement account passphrase";

    @Autowired
    private ResetPasswordService resetPasswordService;
    @Autowired
    private ResetPasswordTransaction resetPasswordTransaction;
    @Autowired
    private PasswordResetTokenRepository tokenRepository;
    @Autowired
    private UserAccountRepository userRepository;
    @Autowired
    private VerificationTokenGenerator tokenGenerator;
    @Autowired
    private PasswordHasher passwordHasher;
    @Autowired
    private FakeEmailSender fakeEmailSender;
    @Autowired
    private SessionRegistry sessionRegistry;
    @Autowired
    private Clock clock;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @BeforeEach
    void clearEmailCapture() {
        fakeEmailSender.clear();
    }

    @Test
    void successfulResetReplacesArgon2PasswordConsumesTokenInvalidatesSiblingsAndNotifies() {
        UserAccount user = activeAccount();
        TokenFixture presented = createResetToken(user);
        TokenFixture sibling = createResetToken(user);

        resetPasswordService.reset(presented.rawToken(), NEW_PASSWORD);

        UserAccount persisted = userRepository.findById(user.id()).orElseThrow();
        assertTrue(passwordHasher.matches(NEW_PASSWORD, persisted.passwordHash()));
        assertFalse(passwordHasher.matches(OLD_PASSWORD, persisted.passwordHash()));
        assertNotNull(tokenRepository.findByTokenHashForUpdate(presented.tokenHash()).orElseThrow().usedAt());
        assertNotNull(tokenRepository.findByTokenHashForUpdate(sibling.tokenHash()).orElseThrow().usedAt());

        List<TransactionalEmail> emails = fakeEmailSender.sentEmails();
        assertEquals(1, emails.size());
        TransactionalEmail email = emails.getFirst();
        assertEquals(user.email(), email.recipient());
        assertEquals("Your InvoWard password was changed", email.subject());
        assertTrue(email.textBody().contains("If you did not make this change"));
        for (String sensitiveValue : List.of(
                OLD_PASSWORD, NEW_PASSWORD, presented.rawToken(), presented.tokenHash())) {
            assertFalse(email.textBody().contains(sensitiveValue));
            assertFalse(email.htmlBody().contains(sensitiveValue));
        }
    }

    @Test
    void providerFailureDoesNotRollbackPasswordOrTokenConsumption() {
        UserAccount user = activeAccount();
        TokenFixture token = createResetToken(user);
        fakeEmailSender.configureFailure(EmailDeliveryFailure.PROVIDER_UNAVAILABLE);

        resetPasswordService.reset(token.rawToken(), NEW_PASSWORD);

        UserAccount persisted = userRepository.findById(user.id()).orElseThrow();
        assertTrue(passwordHasher.matches(NEW_PASSWORD, persisted.passwordHash()));
        assertNotNull(tokenRepository.findByTokenHashForUpdate(token.tokenHash()).orElseThrow().usedAt());
        assertTrue(fakeEmailSender.sentEmails().isEmpty());
    }

    @Test
    void concurrentReuseOfTheSameTokenHasAtMostOneSuccessfulReset() throws Exception {
        UserAccount user = activeAccount();
        TokenFixture token = createResetToken(user);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> attemptReset(start, token.rawToken()));
            Future<Boolean> second = executor.submit(() -> attemptReset(start, token.rawToken()));
            start.countDown();

            boolean firstSucceeded = first.get();
            boolean secondSucceeded = second.get();
            assertNotEquals(firstSucceeded, secondSucceeded);
            assertNotNull(tokenRepository.findByTokenHashForUpdate(token.tokenHash()).orElseThrow().usedAt());
            assertEquals(1, fakeEmailSender.sentEmails().size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sessionInvalidationAndEmailWaitUntilResetTransactionCommits() {
        UserAccount user = activeAccount();
        UserAccount anotherUser = activeAccount();
        TokenFixture token = createResetToken(user);
        String firstSession = "reset-session-" + UUID.randomUUID();
        String secondSession = "reset-session-" + UUID.randomUUID();
        String unrelatedSession = "other-session-" + UUID.randomUUID();
        sessionRegistry.registerNewSession(firstSession, new AuthenticatedUserPrincipal(user));
        sessionRegistry.registerNewSession(secondSession, new AuthenticatedUserPrincipal(user));
        sessionRegistry.registerNewSession(unrelatedSession, new AuthenticatedUserPrincipal(anotherUser));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        String replacementHash = passwordHasher.hash(NEW_PASSWORD);

        try {
            transaction.executeWithoutResult(status -> {
                resetPasswordTransaction.complete(token.tokenHash(), replacementHash);
                assertTrue(fakeEmailSender.sentEmails().isEmpty());
                assertFalse(sessionRegistry.getSessionInformation(firstSession).isExpired());
                assertFalse(sessionRegistry.getSessionInformation(secondSession).isExpired());
            });

            assertTrue(sessionRegistry.getSessionInformation(firstSession).isExpired());
            assertTrue(sessionRegistry.getSessionInformation(secondSession).isExpired());
            assertFalse(sessionRegistry.getSessionInformation(unrelatedSession).isExpired());
            assertEquals(1, fakeEmailSender.sentEmails().size());
        } finally {
            sessionRegistry.removeSessionInformation(firstSession);
            sessionRegistry.removeSessionInformation(secondSession);
            sessionRegistry.removeSessionInformation(unrelatedSession);
        }
    }

    @Test
    void usedExpiredUnknownAndMalformedTokensShareOneSafeFailure() {
        UserAccount user = activeAccount();
        TokenFixture used = createResetToken(user);
        resetPasswordService.reset(used.rawToken(), NEW_PASSWORD);

        InvalidPasswordResetTokenException usedFailure = assertThrows(
                InvalidPasswordResetTokenException.class,
                () -> resetPasswordService.reset(used.rawToken(), NEW_PASSWORD));
        InvalidPasswordResetTokenException unknownFailure = assertThrows(
                InvalidPasswordResetTokenException.class,
                () -> resetPasswordService.reset("C".repeat(43), NEW_PASSWORD));
        InvalidPasswordResetTokenException malformedFailure = assertThrows(
                InvalidPasswordResetTokenException.class,
                () -> resetPasswordService.reset("bad-token", NEW_PASSWORD));

        assertEquals("The password reset link is invalid or expired.", usedFailure.getMessage());
        assertEquals(usedFailure.getMessage(), unknownFailure.getMessage());
        assertEquals(usedFailure.getMessage(), malformedFailure.getMessage());
    }

    private boolean attemptReset(CountDownLatch start, String rawToken) throws Exception {
        start.await();
        try {
            resetPasswordService.reset(rawToken, NEW_PASSWORD);
            return true;
        } catch (InvalidPasswordResetTokenException exception) {
            return false;
        }
    }

    private UserAccount activeAccount() {
        Instant createdAt = clock.instant().minusSeconds(5);
        String email = "reset-" + UUID.randomUUID() + "@example.test";
        UserAccount pending = userRepository.create(new NewUserAccount(
                "Password Reset User", email, passwordHasher.hash(OLD_PASSWORD), createdAt));
        return userRepository.activateVerifiedRegistration(pending.activateVerifiedRegistration(clock.instant()));
    }

    private TokenFixture createResetToken(UserAccount user) {
        GeneratedVerificationToken generated = tokenGenerator.generate();
        Instant createdAt = clock.instant().minusSeconds(1);
        tokenRepository.save(new PasswordResetToken(
                user.id(), generated.tokenHash(), createdAt.plus(Duration.ofMinutes(30)), null, createdAt));
        return new TokenFixture(generated.rawToken(), generated.tokenHash());
    }

    private record TokenFixture(String rawToken, String tokenHash) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestEmailConfiguration {

        @Bean
        @Primary
        FakeEmailSender fakeEmailSender() {
            return new FakeEmailSender();
        }
    }
}
