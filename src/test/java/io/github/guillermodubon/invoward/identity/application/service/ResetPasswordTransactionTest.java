package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.PasswordChanged;
import io.github.guillermodubon.invoward.identity.application.exception.InvalidPasswordResetTokenException;
import io.github.guillermodubon.invoward.identity.application.model.PasswordResetToken;
import io.github.guillermodubon.invoward.identity.application.port.PasswordResetTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
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
class ResetPasswordTransactionTest {

    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");
    private static final UUID USER_ID = UUID.fromString("7429a1a0-27df-4a61-b160-289ce5a92ad8");
    private static final String TOKEN_HASH = "b".repeat(64);
    private static final String PASSWORD_HASH = "$argon2id$replacement-hash";

    @Mock
    private PasswordResetTokenRepository tokenRepository;
    @Mock
    private UserAccountRepository userRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ResetPasswordTransaction transaction;

    @BeforeEach
    void setUp() {
        transaction = new ResetPasswordTransaction(
                tokenRepository, userRepository, eventPublisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void locksTokenAndAtomicallyUpdatesPasswordConsumesTokenInvalidatesSiblingsAndPublishesEvent() {
        UserAccount account = account();
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH))
                .thenReturn(Optional.of(token(null, NOW.plusSeconds(60))));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account));
        when(tokenRepository.markUsed(TOKEN_HASH, NOW)).thenReturn(true);

        transaction.complete(TOKEN_HASH, PASSWORD_HASH);

        ArgumentCaptor<UserAccount> updatedAccount = ArgumentCaptor.forClass(UserAccount.class);
        InOrder order = inOrder(tokenRepository, userRepository, eventPublisher);
        order.verify(tokenRepository).findByTokenHashForUpdate(TOKEN_HASH);
        order.verify(userRepository).findById(USER_ID);
        order.verify(userRepository).updatePasswordHash(updatedAccount.capture());
        order.verify(tokenRepository).markUsed(TOKEN_HASH, NOW);
        order.verify(tokenRepository).invalidateUnusedByUser(USER_ID, NOW);
        order.verify(eventPublisher).publishEvent(any(PasswordChanged.class));
        assertEquals(PASSWORD_HASH, updatedAccount.getValue().passwordHash());
        assertEquals(0, updatedAccount.getValue().version());

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals(new PasswordChanged(USER_ID, account.email()), event.getValue());
    }

    @Test
    void missingOrInvalidTokenDoesNotMutateAccountOrPublishEvent() {
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.empty());

        assertThrows(InvalidPasswordResetTokenException.class,
                () -> transaction.complete(TOKEN_HASH, PASSWORD_HASH));

        verify(userRepository, never()).updatePasswordHash(any());
        verify(tokenRepository, never()).markUsed(any(), any());
        verify(tokenRepository, never()).invalidateUnusedByUser(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void tokenConsumptionFailurePreventsSiblingInvalidationAndEvent() {
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH))
                .thenReturn(Optional.of(token(null, NOW.plusSeconds(60))));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account()));
        when(tokenRepository.markUsed(TOKEN_HASH, NOW)).thenReturn(false);

        assertThrows(InvalidPasswordResetTokenException.class,
                () -> transaction.complete(TOKEN_HASH, PASSWORD_HASH));

        verify(tokenRepository, never()).invalidateUnusedByUser(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    private static PasswordResetToken token(Instant usedAt, Instant expiresAt) {
        return new PasswordResetToken(USER_ID, TOKEN_HASH, expiresAt, usedAt, NOW.minusSeconds(10));
    }

    private static UserAccount account() {
        return new UserAccount(USER_ID, "Reset User", "reset@example.com", "$argon2id$stored-hash",
                true, UserStatus.ACTIVE, 0, NOW.minusSeconds(100), NOW.minusSeconds(100));
    }
}
