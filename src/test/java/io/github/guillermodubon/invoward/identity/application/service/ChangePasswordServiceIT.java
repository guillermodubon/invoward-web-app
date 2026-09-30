package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.InvoWardApplication;
import io.github.guillermodubon.invoward.identity.application.exception.ReauthenticationFailedException;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = InvoWardApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(ChangePasswordServiceIT.TestEmailConfiguration.class)
class ChangePasswordServiceIT {

    private static final String OLD_PASSWORD = "the original account passphrase";
    private static final String NEW_PASSWORD = "the replacement account passphrase";

    @Autowired
    private ChangePasswordService changePasswordService;
    @Autowired
    private ChangePasswordTransaction changePasswordTransaction;
    @Autowired
    private UserAccountRepository userRepository;
    @Autowired
    private PasswordResetTokenRepository resetTokenRepository;
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
    void successfulChangeUsesArgon2InvalidatesOwnerTokensAndSessionsAndSendsSafeNotification() {
        UserAccount user = activeAccount();
        UserAccount anotherUser = activeAccount();
        TokenFixture first = createResetToken(user);
        TokenFixture second = createResetToken(user);
        TokenFixture unrelated = createResetToken(anotherUser);
        String firstSession = registerSession(user);
        String secondSession = registerSession(user);
        String unrelatedSession = registerSession(anotherUser);

        try {
            changePasswordService.change(user.id(), OLD_PASSWORD, NEW_PASSWORD);

            UserAccount changed = userRepository.findById(user.id()).orElseThrow();
            assertTrue(passwordHasher.matches(NEW_PASSWORD, changed.passwordHash()));
            assertFalse(passwordHasher.matches(OLD_PASSWORD, changed.passwordHash()));
            assertNotEquals(user.passwordHash(), changed.passwordHash());
            assertNotNull(resetTokenRepository.findByTokenHashForUpdate(first.tokenHash()).orElseThrow().usedAt());
            assertNotNull(resetTokenRepository.findByTokenHashForUpdate(second.tokenHash()).orElseThrow().usedAt());
            assertNull(resetTokenRepository.findByTokenHashForUpdate(unrelated.tokenHash()).orElseThrow().usedAt());
            assertTrue(sessionRegistry.getSessionInformation(firstSession).isExpired());
            assertTrue(sessionRegistry.getSessionInformation(secondSession).isExpired());
            assertFalse(sessionRegistry.getSessionInformation(unrelatedSession).isExpired());

            List<TransactionalEmail> emails = fakeEmailSender.sentEmails();
            assertEquals(1, emails.size());
            TransactionalEmail email = emails.getFirst();
            assertEquals(user.email(), email.recipient());
            assertEquals("Your InvoWard password was changed", email.subject());
            assertContainsNoCredentials(email, OLD_PASSWORD, NEW_PASSWORD);
        } finally {
            removeSessions(firstSession, secondSession, unrelatedSession);
        }
    }

    @Test
    void wrongCurrentPasswordDoesNotMutateTokensSessionsOrSendEmail() {
        UserAccount user = activeAccount();
        TokenFixture token = createResetToken(user);
        String session = registerSession(user);

        try {
            assertThrows(ReauthenticationFailedException.class,
                    () -> changePasswordService.change(user.id(), "incorrect account passphrase", NEW_PASSWORD));

            UserAccount unchanged = userRepository.findById(user.id()).orElseThrow();
            assertEquals(user.passwordHash(), unchanged.passwordHash());
            assertNull(resetTokenRepository.findByTokenHashForUpdate(token.tokenHash()).orElseThrow().usedAt());
            assertFalse(sessionRegistry.getSessionInformation(session).isExpired());
            assertTrue(fakeEmailSender.sentEmails().isEmpty());
        } finally {
            removeSessions(session);
        }
    }

    @Test
    void reauthenticationUsesTheLatestPersistedPasswordHashRatherThanAnEarlierSnapshot() {
        UserAccount user = activeAccount();
        String replacementBeforeRequest = "the password changed before this request";
        UserAccount latest = userRepository.updatePasswordHash(user.updatePasswordHash(
                passwordHasher.hash(replacementBeforeRequest), clock.instant()));

        assertThrows(ReauthenticationFailedException.class,
                () -> changePasswordService.change(user.id(), OLD_PASSWORD, NEW_PASSWORD));

        UserAccount persisted = userRepository.findById(user.id()).orElseThrow();
        assertEquals(latest.passwordHash(), persisted.passwordHash());
        assertTrue(passwordHasher.matches(replacementBeforeRequest, persisted.passwordHash()));
        assertTrue(fakeEmailSender.sentEmails().isEmpty());
    }

    @Test
    void providerFailureAfterCommitDoesNotUndoPasswordTokenOrSessionChanges() {
        UserAccount user = activeAccount();
        TokenFixture token = createResetToken(user);
        String session = registerSession(user);
        fakeEmailSender.configureFailure(EmailDeliveryFailure.PROVIDER_UNAVAILABLE);

        try {
            changePasswordService.change(user.id(), OLD_PASSWORD, NEW_PASSWORD);

            UserAccount changed = userRepository.findById(user.id()).orElseThrow();
            assertTrue(passwordHasher.matches(NEW_PASSWORD, changed.passwordHash()));
            assertNotNull(resetTokenRepository.findByTokenHashForUpdate(token.tokenHash()).orElseThrow().usedAt());
            assertTrue(sessionRegistry.getSessionInformation(session).isExpired());
            assertTrue(fakeEmailSender.sentEmails().isEmpty());
        } finally {
            removeSessions(session);
        }
    }

    @Test
    void sessionInvalidationAndNotificationWaitUntilPasswordTransactionCommits() {
        UserAccount user = activeAccount();
        UserAccount anotherUser = activeAccount();
        String userSession = registerSession(user);
        String unrelatedSession = registerSession(anotherUser);
        String encodedPassword = passwordHasher.hash(NEW_PASSWORD);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        try {
            transaction.executeWithoutResult(status -> {
                changePasswordTransaction.complete(user.id(), user.version(), encodedPassword);
                assertFalse(sessionRegistry.getSessionInformation(userSession).isExpired());
                assertTrue(fakeEmailSender.sentEmails().isEmpty());
            });

            assertTrue(sessionRegistry.getSessionInformation(userSession).isExpired());
            assertFalse(sessionRegistry.getSessionInformation(unrelatedSession).isExpired());
            assertEquals(1, fakeEmailSender.sentEmails().size());
        } finally {
            removeSessions(userSession, unrelatedSession);
        }
    }

    private UserAccount activeAccount() {
        Instant createdAt = clock.instant().minusSeconds(5);
        String email = "change-password-" + UUID.randomUUID() + "@example.test";
        UserAccount pending = userRepository.create(new NewUserAccount(
                "Password Change User", email, passwordHasher.hash(OLD_PASSWORD), createdAt));
        return userRepository.activateVerifiedRegistration(pending.activateVerifiedRegistration(clock.instant()));
    }

    private TokenFixture createResetToken(UserAccount user) {
        GeneratedVerificationToken generated = tokenGenerator.generate();
        Instant createdAt = clock.instant().minusSeconds(1);
        resetTokenRepository.save(new PasswordResetToken(
                user.id(), generated.tokenHash(), createdAt.plus(Duration.ofMinutes(30)), null, createdAt));
        return new TokenFixture(generated.tokenHash());
    }

    private String registerSession(UserAccount user) {
        String session = "change-password-session-" + UUID.randomUUID();
        sessionRegistry.registerNewSession(session, new AuthenticatedUserPrincipal(user));
        return session;
    }

    private void removeSessions(String... sessionIds) {
        for (String sessionId : sessionIds) {
            sessionRegistry.removeSessionInformation(sessionId);
        }
    }

    private static void assertContainsNoCredentials(TransactionalEmail email, String... credentials) {
        for (String credential : credentials) {
            assertFalse(email.textBody().contains(credential));
            assertFalse(email.htmlBody().contains(credential));
        }
    }

    private record TokenFixture(String tokenHash) {
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
