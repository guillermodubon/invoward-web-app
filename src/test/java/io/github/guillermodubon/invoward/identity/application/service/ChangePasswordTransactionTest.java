package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.PasswordChanged;
import io.github.guillermodubon.invoward.identity.application.exception.AccountConflictException;
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
class ChangePasswordTransactionTest {

    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");
    private static final UUID USER_ID = UUID.fromString("7429a1a0-27df-4a61-b160-289ce5a92ad8");
    private static final String NEW_PASSWORD_HASH = "$argon2id$replacement-hash";

    @Mock
    private UserAccountRepository userRepository;
    @Mock
    private PasswordResetTokenRepository resetTokenRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ChangePasswordTransaction transaction;

    @BeforeEach
    void setUp() {
        transaction = new ChangePasswordTransaction(
                userRepository,
                resetTokenRepository,
                eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void atomicallyUpdatesPasswordInvalidatesResetTokensAndPublishesPostCommitSignal() {
        UserAccount current = account(4, UserStatus.ACTIVE, true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(current));

        transaction.complete(USER_ID, current.version(), NEW_PASSWORD_HASH);

        ArgumentCaptor<UserAccount> changedAccount = ArgumentCaptor.forClass(UserAccount.class);
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        InOrder order = inOrder(userRepository, resetTokenRepository, eventPublisher);
        order.verify(userRepository).findById(USER_ID);
        order.verify(userRepository).updatePasswordHash(changedAccount.capture());
        order.verify(resetTokenRepository).invalidateUnusedByUser(USER_ID, NOW);
        order.verify(eventPublisher).publishEvent(event.capture());

        assertEquals(NEW_PASSWORD_HASH, changedAccount.getValue().passwordHash());
        assertEquals(current.version(), changedAccount.getValue().version());
        assertEquals(NOW, changedAccount.getValue().updatedAt());
        assertEquals(new PasswordChanged(USER_ID, current.email()), event.getValue());
    }

    @Test
    void staleOrIneligibleAccountDoesNotMutateOrPublish() {
        UserAccount changedSinceReauthentication = account(9, UserStatus.ACTIVE, true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(changedSinceReauthentication));

        assertThrows(AccountConflictException.class, () -> transaction.complete(USER_ID, 8, NEW_PASSWORD_HASH));

        verify(userRepository, never()).updatePasswordHash(any());
        verify(resetTokenRepository, never()).invalidateUnusedByUser(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    private static UserAccount account(long version, UserStatus status, boolean emailVerified) {
        Instant createdAt = NOW.minusSeconds(100);
        return new UserAccount(USER_ID, "Account Owner", "owner@example.test", "$argon2id$current-hash",
                emailVerified, status, version, createdAt, createdAt);
    }
}
