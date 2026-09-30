package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.EmailChanged;
import io.github.guillermodubon.invoward.identity.application.exception.EmailAddressConflictException;
import io.github.guillermodubon.invoward.identity.application.exception.InvalidEmailChangeTokenException;
import io.github.guillermodubon.invoward.identity.application.model.EmailVerificationToken;
import io.github.guillermodubon.invoward.identity.application.port.EmailVerificationTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.domain.EmailVerificationPurpose;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfirmEmailChangeTransactionTest {

    private static final Instant NOW = Instant.parse("2026-02-03T04:05:06Z");
    private static final UUID USER_ID = UUID.fromString("7429a1a0-27df-4a61-b160-289ce5a92ad8");
    private static final UUID OTHER_USER_ID = UUID.fromString("b54bd8c2-2f5f-441e-9159-f5903bff1206");
    private static final String TOKEN_HASH = "a".repeat(64);
    private static final String OLD_EMAIL = "old@example.test";
    private static final String NEW_EMAIL = "new@example.test";

    @Mock
    private EmailVerificationTokenRepository tokenRepository;
    @Mock
    private UserAccountRepository userRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ConfirmEmailChangeTransaction transaction;

    @BeforeEach
    void setUp() {
        transaction = new ConfirmEmailChangeTransaction(
                tokenRepository, userRepository, eventPublisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void changesEmailConsumesOwnerTokensAndPublishesOnlyThePreviousAddressAfterTransactionWork() {
        UserAccount account = account(USER_ID, OLD_EMAIL, UserStatus.ACTIVE, true);
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH))
                .thenReturn(Optional.of(token(USER_ID, EmailVerificationPurpose.EMAIL_CHANGE, NEW_EMAIL, null, NOW.plusSeconds(60))));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account));
        when(userRepository.existsByNormalizedEmail(NEW_EMAIL)).thenReturn(false);
        when(tokenRepository.markUsed(TOKEN_HASH, NOW)).thenReturn(true);

        transaction.confirm(USER_ID, TOKEN_HASH);

        ArgumentCaptor<UserAccount> changedAccount = ArgumentCaptor.forClass(UserAccount.class);
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        InOrder order = inOrder(tokenRepository, userRepository, eventPublisher);
        order.verify(tokenRepository).findByTokenHashForUpdate(TOKEN_HASH);
        order.verify(userRepository).findById(USER_ID);
        order.verify(userRepository).existsByNormalizedEmail(NEW_EMAIL);
        order.verify(userRepository).updateEmail(changedAccount.capture());
        order.verify(tokenRepository).markUsed(TOKEN_HASH, NOW);
        order.verify(tokenRepository).invalidateUnusedByUserAndPurpose(USER_ID, EmailVerificationPurpose.EMAIL_CHANGE, NOW);
        order.verify(eventPublisher).publishEvent(event.capture());

        assertEquals(NEW_EMAIL, changedAccount.getValue().email());
        assertEquals(USER_ID, changedAccount.getValue().id());
        assertEquals(NOW, changedAccount.getValue().updatedAt());
        assertEquals(new EmailChanged(USER_ID, OLD_EMAIL), event.getValue());
    }

    @Test
    void rejectsWrongOwnerWithoutLoadingOrChangingAccount() {
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH))
                .thenReturn(Optional.of(token(OTHER_USER_ID, EmailVerificationPurpose.EMAIL_CHANGE,
                        NEW_EMAIL, null, NOW.plusSeconds(60))));

        assertThrows(InvalidEmailChangeTokenException.class, () -> transaction.confirm(USER_ID, TOKEN_HASH));

        verify(userRepository, never()).findById(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void rejectsWrongPurposeExpiredAndAlreadyUsedTokens() {
        assertInvalidToken(token(USER_ID, EmailVerificationPurpose.REGISTRATION,
                NEW_EMAIL, null, NOW.plusSeconds(60)));
        assertInvalidToken(token(USER_ID, EmailVerificationPurpose.EMAIL_CHANGE,
                NEW_EMAIL, null, NOW));
        assertInvalidToken(token(USER_ID, EmailVerificationPurpose.EMAIL_CHANGE,
                NEW_EMAIL, NOW.minusSeconds(1), NOW.plusSeconds(60)));
    }

    @Test
    void rejectsMissingTokenAndIneligibleAccount() {
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.empty());
        assertThrows(InvalidEmailChangeTokenException.class, () -> transaction.confirm(USER_ID, TOKEN_HASH));

        resetMocks();
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH))
                .thenReturn(Optional.of(token(USER_ID, EmailVerificationPurpose.EMAIL_CHANGE,
                        NEW_EMAIL, null, NOW.plusSeconds(60))));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account(
                USER_ID, OLD_EMAIL, UserStatus.PENDING_VERIFICATION, false)));

        assertThrows(InvalidEmailChangeTokenException.class, () -> transaction.confirm(USER_ID, TOKEN_HASH));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void rejectsAlreadyOwnedOrUnchangedTargetWithoutMutatingAccount() {
        UserAccount account = account(USER_ID, OLD_EMAIL, UserStatus.ACTIVE, true);
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH))
                .thenReturn(Optional.of(token(USER_ID, EmailVerificationPurpose.EMAIL_CHANGE,
                        NEW_EMAIL, null, NOW.plusSeconds(60))));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account));
        when(userRepository.existsByNormalizedEmail(NEW_EMAIL)).thenReturn(true);

        assertThrows(InvalidEmailChangeTokenException.class, () -> transaction.confirm(USER_ID, TOKEN_HASH));
        verify(userRepository, never()).updateEmail(any());
        verify(eventPublisher, never()).publishEvent(any());

        resetMocks();
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH))
                .thenReturn(Optional.of(token(USER_ID, EmailVerificationPurpose.EMAIL_CHANGE,
                        OLD_EMAIL, null, NOW.plusSeconds(60))));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account));

        assertThrows(InvalidEmailChangeTokenException.class, () -> transaction.confirm(USER_ID, TOKEN_HASH));
        verify(userRepository, never()).existsByNormalizedEmail(any());
        verify(userRepository, never()).updateEmail(any());
    }

    @Test
    void mapsEmailUniquenessRaceToGenericInvalidToken() {
        UserAccount account = account(USER_ID, OLD_EMAIL, UserStatus.ACTIVE, true);
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH))
                .thenReturn(Optional.of(token(USER_ID, EmailVerificationPurpose.EMAIL_CHANGE,
                        NEW_EMAIL, null, NOW.plusSeconds(60))));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account));
        when(userRepository.existsByNormalizedEmail(NEW_EMAIL)).thenReturn(false);
        when(userRepository.updateEmail(any())).thenThrow(new EmailAddressConflictException());

        assertThrows(InvalidEmailChangeTokenException.class, () -> transaction.confirm(USER_ID, TOKEN_HASH));
        verify(tokenRepository, never()).markUsed(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void failedAtomicTokenConsumptionDoesNotPublishPostCommitEvent() {
        UserAccount account = account(USER_ID, OLD_EMAIL, UserStatus.ACTIVE, true);
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH))
                .thenReturn(Optional.of(token(USER_ID, EmailVerificationPurpose.EMAIL_CHANGE,
                        NEW_EMAIL, null, NOW.plusSeconds(60))));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account));
        when(userRepository.existsByNormalizedEmail(NEW_EMAIL)).thenReturn(false);
        when(tokenRepository.markUsed(TOKEN_HASH, NOW)).thenReturn(false);

        assertThrows(InvalidEmailChangeTokenException.class, () -> transaction.confirm(USER_ID, TOKEN_HASH));
        verify(eventPublisher, never()).publishEvent(any());
    }

    private void assertInvalidToken(EmailVerificationToken token) {
        resetMocks();
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(token));

        assertThrows(InvalidEmailChangeTokenException.class, () -> transaction.confirm(USER_ID, TOKEN_HASH));
        verify(userRepository, never()).findById(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    private void resetMocks() {
        org.mockito.Mockito.reset(tokenRepository, userRepository, eventPublisher);
    }

    private static EmailVerificationToken token(
            UUID userId,
            EmailVerificationPurpose purpose,
            String target,
            Instant usedAt,
            Instant expiresAt) {
        return new EmailVerificationToken(userId, TOKEN_HASH, purpose, target,
                expiresAt, usedAt, NOW.minusSeconds(10));
    }

    private static UserAccount account(UUID id, String email, UserStatus status, boolean verified) {
        Instant createdAt = NOW.minusSeconds(100);
        return new UserAccount(id, "Account Owner", email, "$argon2id$integration-test-hash",
                verified, status, 4, createdAt, createdAt);
    }
}
