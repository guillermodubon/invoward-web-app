package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.InvoWardApplication;
import io.github.guillermodubon.invoward.identity.application.exception.InvalidEmailChangeTokenException;
import io.github.guillermodubon.invoward.identity.application.model.EmailVerificationToken;
import io.github.guillermodubon.invoward.identity.application.model.GeneratedVerificationToken;
import io.github.guillermodubon.invoward.identity.application.model.NewUserAccount;
import io.github.guillermodubon.invoward.identity.application.port.EmailVerificationTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.PasswordHasher;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.application.port.VerificationTokenGenerator;
import io.github.guillermodubon.invoward.identity.domain.EmailVerificationPurpose;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.identity.infrastructure.security.AuthenticatedUserPrincipal;
import io.github.guillermodubon.invoward.notification.application.exception.EmailDeliveryFailure;
import io.github.guillermodubon.invoward.notification.application.model.TransactionalEmail;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import io.github.guillermodubon.invoward.support.email.FakeEmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.AuthenticationException;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = InvoWardApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(ConfirmEmailChangeServiceIT.TestEmailConfiguration.class)
class ConfirmEmailChangeServiceIT {

    private static final String PASSWORD = "the account's existing secure passphrase";

    @Autowired
    private ConfirmEmailChangeService service;
    @Autowired
    private ConfirmEmailChangeTransaction transaction;
    @Autowired
    private UserAccountRepository userRepository;
    @Autowired
    private EmailVerificationTokenRepository tokenRepository;
    @Autowired
    private VerificationTokenGenerator tokenGenerator;
    @Autowired
    private PasswordHasher passwordHasher;
    @Autowired
    private AuthenticationConfiguration authenticationConfiguration;
    @Autowired
    private JdbcTemplate jdbcTemplate;
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
    void confirmationUpdatesOwnerConsumesSiblingTokensExpiresSessionsAndNotifiesOldAddress() throws Exception {
        UserAccount owner = activeAccount();
        UserAccount other = activeAccount();
        String targetEmail = uniqueEmail("new-target");
        TokenFixture confirming = emailChangeToken(owner, targetEmail, null, null);
        TokenFixture sibling = emailChangeToken(owner, uniqueEmail("sibling-target"), null, null);
        TokenFixture registration = token(owner, EmailVerificationPurpose.REGISTRATION,
                owner.email(), null, null);
        TokenFixture otherUserToken = emailChangeToken(other, uniqueEmail("other-target"), null, null);
        String ownerSessionOne = registerSession(owner);
        String ownerSessionTwo = registerSession(owner);
        String otherSession = registerSession(other);

        try {
            service.confirm(owner.id(), confirming.rawToken());

            UserAccount changed = userRepository.findById(owner.id()).orElseThrow();
            assertEquals(targetEmail, changed.email());
            assertTrue(changed.emailVerified());
            assertEquals(UserStatus.ACTIVE, changed.status());
            assertTrue(changed.updatedAt().compareTo(owner.updatedAt()) >= 0);
            assertTrue(tokenRepository.findByTokenHashForUpdate(confirming.tokenHash()).orElseThrow().usedAt() != null);
            assertTrue(tokenRepository.findByTokenHashForUpdate(sibling.tokenHash()).orElseThrow().usedAt() != null);
            assertNull(tokenRepository.findByTokenHashForUpdate(registration.tokenHash()).orElseThrow().usedAt());
            assertNull(tokenRepository.findByTokenHashForUpdate(otherUserToken.tokenHash()).orElseThrow().usedAt());
            assertTrue(sessionRegistry.getSessionInformation(ownerSessionOne).isExpired());
            assertTrue(sessionRegistry.getSessionInformation(ownerSessionTwo).isExpired());
            assertFalse(sessionRegistry.getSessionInformation(otherSession).isExpired());

            List<TransactionalEmail> sent = fakeEmailSender.sentEmails();
            assertEquals(1, sent.size());
            assertEquals(owner.email(), sent.getFirst().recipient());
            assertEquals("Your InvoWard email address was changed", sent.getFirst().subject());
            assertFalse(sent.getFirst().textBody().contains(targetEmail));
            assertFalse(sent.getFirst().htmlBody().contains(targetEmail));
            assertFalse(sent.getFirst().textBody().contains(confirming.rawToken()));
            assertFalse(sent.getFirst().htmlBody().contains(confirming.rawToken()));

            assertEquals(targetEmail, authenticationConfiguration.getAuthenticationManager().authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(targetEmail, PASSWORD)).getName());
            assertThrows(AuthenticationException.class, () -> authenticationConfiguration.getAuthenticationManager().authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(owner.email(), PASSWORD)));
        } finally {
            removeSessions(ownerSessionOne, ownerSessionTwo, otherSession);
        }
    }

    @Test
    void rejectsUnknownMalformedWrongOwnerExpiredUsedWrongPurposeAndDisabledTokensGenerically() {
        UserAccount owner = activeAccount();
        UserAccount other = activeAccount();
        TokenFixture wrongOwner = emailChangeToken(owner, uniqueEmail("wrong-owner"), null, null);
        TokenFixture expired = emailChangeToken(owner, uniqueEmail("expired"), null, clock.instant().minusSeconds(1));
        TokenFixture used = emailChangeToken(owner, uniqueEmail("used"), clock.instant().minusSeconds(1), null);
        TokenFixture wrongPurpose = token(owner, EmailVerificationPurpose.REGISTRATION, owner.email(), null, null);
        UserAccount disabledAccount = activeAccount();
        jdbcTemplate.update("UPDATE invoward.users SET status = 'DISABLED' WHERE id = ?", disabledAccount.id());
        TokenFixture disabled = emailChangeToken(disabledAccount, uniqueEmail("disabled"), null, null);
        GeneratedVerificationToken unknown = tokenGenerator.generate();

        assertThrows(InvalidEmailChangeTokenException.class, () -> service.confirm(owner.id(), "bad"));
        assertThrows(InvalidEmailChangeTokenException.class,
                () -> service.confirm(owner.id(), unknown.rawToken()));
        assertThrows(InvalidEmailChangeTokenException.class,
                () -> service.confirm(other.id(), wrongOwner.rawToken()));
        assertThrows(InvalidEmailChangeTokenException.class,
                () -> service.confirm(owner.id(), expired.rawToken()));
        assertThrows(InvalidEmailChangeTokenException.class,
                () -> service.confirm(owner.id(), used.rawToken()));
        assertThrows(InvalidEmailChangeTokenException.class,
                () -> service.confirm(owner.id(), wrongPurpose.rawToken()));
        assertThrows(InvalidEmailChangeTokenException.class,
                () -> service.confirm(disabledAccount.id(), disabled.rawToken()));

        assertEquals(owner.email(), userRepository.findById(owner.id()).orElseThrow().email());
        assertEquals(other.email(), userRepository.findById(other.id()).orElseThrow().email());
        assertEquals(UserStatus.DISABLED, userRepository.findById(disabledAccount.id()).orElseThrow().status());
        assertTrue(fakeEmailSender.sentEmails().isEmpty());
    }

    @Test
    void rejectsTargetAlreadyOwnedWithoutChangingTheAccount() {
        UserAccount owner = activeAccount();
        UserAccount targetOwner = activeAccount();
        TokenFixture token = emailChangeToken(owner, targetOwner.email(), null, null);

        assertThrows(InvalidEmailChangeTokenException.class,
                () -> service.confirm(owner.id(), token.rawToken()));

        assertEquals(owner.email(), userRepository.findById(owner.id()).orElseThrow().email());
        assertNull(tokenRepository.findByTokenHashForUpdate(token.tokenHash()).orElseThrow().usedAt());
        assertTrue(fakeEmailSender.sentEmails().isEmpty());
    }

    @Test
    void concurrentConfirmationOfSameTokenHasAtMostOneSuccess() throws Exception {
        UserAccount owner = activeAccount();
        String targetEmail = uniqueEmail("concurrent-target");
        TokenFixture token = emailChangeToken(owner, targetEmail, null, null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> first = executor.submit(() -> confirmAfterBarrier(owner.id(), token.rawToken(), ready, start));
            Future<Boolean> second = executor.submit(() -> confirmAfterBarrier(owner.id(), token.rawToken(), ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Both confirmation attempts should reach the start gate.");
            start.countDown();

            boolean firstSucceeded = first.get(20, TimeUnit.SECONDS);
            boolean secondSucceeded = second.get(20, TimeUnit.SECONDS);
            assertNotEquals(firstSucceeded, secondSucceeded);
            assertEquals(targetEmail, userRepository.findById(owner.id()).orElseThrow().email());
            assertTrue(tokenRepository.findByTokenHashForUpdate(token.tokenHash()).orElseThrow().usedAt() != null);
            assertEquals(1, fakeEmailSender.sentEmails().size());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void sessionInvalidationAndNotificationWaitForCommitAndRollbackKeepsAllState() {
        UserAccount owner = activeAccount();
        TokenFixture confirming = emailChangeToken(owner, uniqueEmail("rollback-target"), null, null);
        TokenFixture sibling = emailChangeToken(owner, uniqueEmail("rollback-sibling"), null, null);
        String session = registerSession(owner);
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);

        try {
            outerTransaction.executeWithoutResult(status -> {
                transaction.confirm(owner.id(), confirming.tokenHash());
                assertFalse(sessionRegistry.getSessionInformation(session).isExpired());
                assertTrue(fakeEmailSender.sentEmails().isEmpty());
                status.setRollbackOnly();
            });

            assertEquals(owner.email(), userRepository.findById(owner.id()).orElseThrow().email());
            assertNull(tokenRepository.findByTokenHashForUpdate(confirming.tokenHash()).orElseThrow().usedAt());
            assertNull(tokenRepository.findByTokenHashForUpdate(sibling.tokenHash()).orElseThrow().usedAt());
            assertFalse(sessionRegistry.getSessionInformation(session).isExpired());
            assertTrue(fakeEmailSender.sentEmails().isEmpty());
        } finally {
            removeSessions(session);
        }
    }

    @Test
    void emailProviderFailureAfterCommitDoesNotUndoEmailChangeOrSessionInvalidation() {
        UserAccount owner = activeAccount();
        String targetEmail = uniqueEmail("provider-failure");
        TokenFixture token = emailChangeToken(owner, targetEmail, null, null);
        String session = registerSession(owner);
        fakeEmailSender.configureFailure(EmailDeliveryFailure.PROVIDER_UNAVAILABLE);

        try {
            service.confirm(owner.id(), token.rawToken());

            assertEquals(targetEmail, userRepository.findById(owner.id()).orElseThrow().email());
            assertTrue(tokenRepository.findByTokenHashForUpdate(token.tokenHash()).orElseThrow().usedAt() != null);
            assertTrue(sessionRegistry.getSessionInformation(session).isExpired());
            assertTrue(fakeEmailSender.sentEmails().isEmpty());
        } finally {
            removeSessions(session);
        }
    }

    private UserAccount activeAccount() {
        Instant createdAt = clock.instant().minusSeconds(5);
        UserAccount pending = userRepository.create(new NewUserAccount(
                "Email Change User", uniqueEmail("owner"), passwordHasher.hash(PASSWORD), createdAt));
        return userRepository.activateVerifiedRegistration(pending.activateVerifiedRegistration(clock.instant()));
    }

    private boolean confirmAfterBarrier(
            UUID userId,
            String rawToken,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Confirmation start gate was not released.");
        }
        try {
            service.confirm(userId, rawToken);
            return true;
        } catch (InvalidEmailChangeTokenException exception) {
            return false;
        }
    }

    private TokenFixture emailChangeToken(
            UserAccount user,
            String targetEmail,
            Instant usedAt,
            Instant expiresAtOverride) {
        return token(user, EmailVerificationPurpose.EMAIL_CHANGE, targetEmail, usedAt, expiresAtOverride);
    }

    private TokenFixture token(
            UserAccount user,
            EmailVerificationPurpose purpose,
            String targetEmail,
            Instant usedAt,
            Instant expiresAtOverride) {
        GeneratedVerificationToken generated = tokenGenerator.generate();
        Instant createdAt = clock.instant().minusSeconds(2);
        Instant expiresAt = expiresAtOverride == null
                ? clock.instant().plus(Duration.ofHours(24)) : expiresAtOverride;
        tokenRepository.save(new EmailVerificationToken(
                user.id(), generated.tokenHash(), purpose, targetEmail, expiresAt, usedAt, createdAt));
        return new TokenFixture(generated.rawToken(), generated.tokenHash());
    }

    private String registerSession(UserAccount user) {
        String sessionId = "email-change-session-" + UUID.randomUUID();
        sessionRegistry.registerNewSession(sessionId, new AuthenticatedUserPrincipal(user));
        return sessionId;
    }

    private void removeSessions(String... sessionIds) {
        for (String sessionId : sessionIds) {
            sessionRegistry.removeSessionInformation(sessionId);
        }
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.test";
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
