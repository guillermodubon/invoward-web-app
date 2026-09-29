package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.RegistrationVerificationRequested;
import io.github.guillermodubon.invoward.identity.application.model.GeneratedVerificationToken;
import io.github.guillermodubon.invoward.identity.application.model.NewUserAccount;
import io.github.guillermodubon.invoward.identity.application.port.EmailVerificationTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.application.port.VerificationTokenGenerator;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegisterUserTransactionTest {

    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");
    private static final String EMAIL = "user@example.com";
    private static final String RAW_TOKEN = "A".repeat(43);
    private static final String TOKEN_HASH = "b".repeat(64);

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private EmailVerificationTokenRepository verificationTokenRepository;

    @Mock
    private VerificationTokenGenerator verificationTokenGenerator;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private RegisterUserTransaction transaction;

    @BeforeEach
    void setUp() {
        transaction = new RegisterUserTransaction(
                userAccountRepository,
                verificationTokenRepository,
                verificationTokenGenerator,
                eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofHours(24));
    }

    @Test
    void atomicallyCreatesPendingUserPersistsOnlyHashAndPublishesVerificationEvent() {
        UUID userId = UUID.randomUUID();
        GeneratedVerificationToken generatedToken = new GeneratedVerificationToken(RAW_TOKEN, TOKEN_HASH);
        when(userAccountRepository.create(any(NewUserAccount.class))).thenReturn(new UserAccount(
                userId,
                "New User",
                EMAIL,
                "$argon2id$test-hash",
                false,
                UserStatus.PENDING_VERIFICATION,
                0,
                NOW,
                NOW));
        when(verificationTokenGenerator.generate()).thenReturn(generatedToken);

        transaction.createPendingRegistration("New User", EMAIL, "$argon2id$test-hash");

        ArgumentCaptor<NewUserAccount> accountCaptor = ArgumentCaptor.forClass(NewUserAccount.class);
        ArgumentCaptor<RegistrationVerificationRequested> eventCaptor =
                ArgumentCaptor.forClass(RegistrationVerificationRequested.class);
        InOrder order = inOrder(userAccountRepository, verificationTokenGenerator,
                verificationTokenRepository, eventPublisher);
        order.verify(userAccountRepository).create(accountCaptor.capture());
        order.verify(verificationTokenGenerator).generate();
        order.verify(verificationTokenRepository).saveRegistrationToken(
                userId, EMAIL, TOKEN_HASH, NOW, NOW.plus(Duration.ofHours(24)));
        order.verify(eventPublisher).publishEvent(eventCaptor.capture());

        NewUserAccount createdAccount = accountCaptor.getValue();
        assertEquals("New User", createdAccount.displayName());
        assertEquals(EMAIL, createdAccount.email());
        assertEquals("$argon2id$test-hash", createdAccount.passwordHash());
        assertEquals(NOW, createdAccount.createdAt());

        RegistrationVerificationRequested event = eventCaptor.getValue();
        assertEquals(EMAIL, event.recipient());
        assertEquals(RAW_TOKEN, event.rawVerificationToken());
        assertEquals(NOW.plus(Duration.ofHours(24)), event.expiresAt());
        assertFalse(event.toString().contains(EMAIL));
        assertFalse(event.toString().contains(RAW_TOKEN));
        assertFalse(event.toString().contains(TOKEN_HASH));
    }

    @Test
    void doesNotPublishEventWhenTokenPersistenceFails() {
        UUID userId = UUID.randomUUID();
        when(userAccountRepository.create(any(NewUserAccount.class))).thenReturn(new UserAccount(
                userId,
                "New User",
                EMAIL,
                "$argon2id$test-hash",
                false,
                UserStatus.PENDING_VERIFICATION,
                0,
                NOW,
                NOW));
        when(verificationTokenGenerator.generate())
                .thenReturn(new GeneratedVerificationToken(RAW_TOKEN, TOKEN_HASH));
        doThrow(new DataIntegrityViolationException("token persistence failure"))
                .when(verificationTokenRepository)
                .saveRegistrationToken(userId, EMAIL, TOKEN_HASH, NOW, NOW.plus(Duration.ofHours(24)));

        assertThrows(DataIntegrityViolationException.class,
                () -> transaction.createPendingRegistration("New User", EMAIL, "$argon2id$test-hash"));

        verify(eventPublisher, never()).publishEvent(any());
    }
}
