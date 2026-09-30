package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.InvoWardApplication;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = InvoWardApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(RequestEmailChangeServiceIT.TestEmailConfiguration.class)
class RequestEmailChangeServiceIT {

    private static final String PASSWORD = "the account's existing secure passphrase";

    @Autowired
    private RequestEmailChangeService service;
    @Autowired
    private UserAccountRepository userRepository;
    @Autowired
    private EmailVerificationTokenRepository tokenRepository;
    @Autowired
    private VerificationTokenGenerator tokenGenerator;
    @Autowired
    private PasswordHasher passwordHasher;
    @Autowired
    private FakeEmailSender fakeEmailSender;
    @Autowired
    private JdbcTemplate jdbcTemplate;
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
    void availableTargetPersistsOnlyHashRotatesEmailChangeTokensAndEmailsNewAddress() {
        UserAccount owner = activeAccount();
        GeneratedVerificationToken previousEmailChange = persistToken(
                owner, EmailVerificationPurpose.EMAIL_CHANGE, uniqueEmail("previous-target"));
        GeneratedVerificationToken registration = persistToken(
                owner, EmailVerificationPurpose.REGISTRATION, owner.email());
        String newEmail = uniqueEmail("new-target");

        service.request(owner.id(), PASSWORD, "  " + newEmail.toUpperCase() + "  ");

        assertEquals(owner.email(), userRepository.findById(owner.id()).orElseThrow().email());
        assertNotNull(tokenRepository.findByTokenHashForUpdate(previousEmailChange.tokenHash())
                .orElseThrow().usedAt());
        assertNull(tokenRepository.findByTokenHashForUpdate(registration.tokenHash()).orElseThrow().usedAt());

        List<TransactionalEmail> emails = fakeEmailSender.sentEmails();
        assertEquals(1, emails.size());
        TransactionalEmail email = emails.getFirst();
        assertEquals(newEmail, email.recipient());
        assertEquals("Confirm your new InvoWard email", email.subject());
        assertTrue(email.textBody().contains("expires at "));
        assertTrue(email.textBody().contains("you can ignore this message"));

        String rawToken = extractToken(email.textBody());
        String tokenHash = tokenGenerator.hash(rawToken);
        EmailVerificationToken persisted = tokenRepository.findByTokenHashForUpdate(tokenHash).orElseThrow();
        assertEquals(EmailVerificationPurpose.EMAIL_CHANGE, persisted.purpose());
        assertEquals(newEmail, persisted.targetEmail());
        assertNotEquals(rawToken, persisted.tokenHash());
        assertFalse(email.htmlBody().contains(owner.email()));
    }

    @Test
    void occupiedAndSameAddressRequestsRemainNoOps() {
        UserAccount owner = activeAccount();
        UserAccount other = activeAccount();

        service.request(owner.id(), PASSWORD, owner.email().toUpperCase());
        service.request(owner.id(), PASSWORD, other.email());

        assertEquals(owner.email(), userRepository.findById(owner.id()).orElseThrow().email());
        assertEquals(0, countEmailChangeTokens(owner.id()));
        assertTrue(fakeEmailSender.sentEmails().isEmpty());
    }

    @Test
    void cooldownSuppressesAnotherTokenAndEmail() {
        UserAccount owner = activeAccount();

        service.request(owner.id(), PASSWORD, uniqueEmail("first-target"));
        int tokenCountAfterFirstRequest = countEmailChangeTokens(owner.id());
        service.request(owner.id(), PASSWORD, uniqueEmail("second-target"));

        assertEquals(tokenCountAfterFirstRequest, countEmailChangeTokens(owner.id()));
        assertEquals(1, fakeEmailSender.sentEmails().size());
    }

    @Test
    void providerFailureDoesNotRollbackCommittedTokenIssuance() {
        UserAccount owner = activeAccount();
        String target = uniqueEmail("provider-failure-target");
        fakeEmailSender.configureFailure(EmailDeliveryFailure.PROVIDER_UNAVAILABLE);

        service.request(owner.id(), PASSWORD, target);

        assertEquals(owner.email(), userRepository.findById(owner.id()).orElseThrow().email());
        assertEquals(1, countEmailChangeTokens(owner.id()));
        assertTrue(fakeEmailSender.sentEmails().isEmpty());
        Map<String, Object> token = latestEmailChangeToken(owner.id());
        assertEquals(target, token.get("target_email"));
        assertNull(token.get("used_at"));
    }

    @Test
    void rollbackSuppressesEmailAndKeepsTokenStateUnchanged() {
        UserAccount owner = activeAccount();
        GeneratedVerificationToken prior = persistToken(
                owner, EmailVerificationPurpose.EMAIL_CHANGE, uniqueEmail("prior-target"));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            service.request(owner.id(), PASSWORD, uniqueEmail("rolled-back-target"));
            assertTrue(fakeEmailSender.sentEmails().isEmpty());
            status.setRollbackOnly();
        });

        assertNull(tokenRepository.findByTokenHashForUpdate(prior.tokenHash()).orElseThrow().usedAt());
        assertEquals(1, countEmailChangeTokens(owner.id()));
        assertTrue(fakeEmailSender.sentEmails().isEmpty());
    }

    private UserAccount activeAccount() {
        Instant createdAt = clock.instant().minusSeconds(5);
        UserAccount pending = userRepository.create(new NewUserAccount(
                "Email Change Request User", uniqueEmail("owner"), passwordHasher.hash(PASSWORD), createdAt));
        return userRepository.activateVerifiedRegistration(
                pending.activateVerifiedRegistration(clock.instant()));
    }

    private GeneratedVerificationToken persistToken(
            UserAccount user,
            EmailVerificationPurpose purpose,
            String targetEmail) {
        GeneratedVerificationToken token = tokenGenerator.generate();
        Instant createdAt = clock.instant().minus(Duration.ofSeconds(61));
        tokenRepository.save(new EmailVerificationToken(
                user.id(), token.tokenHash(), purpose, targetEmail,
                clock.instant().plus(Duration.ofHours(24)), null, createdAt));
        return token;
    }

    private int countEmailChangeTokens(UUID userId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from invoward.email_verification_tokens "
                        + "where user_id = ? and purpose = 'EMAIL_CHANGE'",
                Integer.class,
                userId);
        return count == null ? 0 : count;
    }

    private Map<String, Object> latestEmailChangeToken(UUID userId) {
        return jdbcTemplate.queryForMap(
                "select token_hash, target_email, used_at from invoward.email_verification_tokens "
                        + "where user_id = ? and purpose = 'EMAIL_CHANGE' order by created_at desc limit 1",
                userId);
    }

    private static String extractToken(String message) {
        String marker = "token=";
        int start = message.lastIndexOf(marker) + marker.length();
        int end = message.indexOf('\n', start);
        return end < 0 ? message.substring(start) : message.substring(start, end);
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.test";
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
