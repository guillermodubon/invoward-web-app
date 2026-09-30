package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.exception.ReauthenticationFailedException;
import io.github.guillermodubon.invoward.identity.application.port.PasswordHasher;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChangePasswordServiceTest {

    private static final UUID USER_ID = UUID.fromString("7429a1a0-27df-4a61-b160-289ce5a92ad8");
    private static final String CURRENT_PASSWORD = "  the current passphrase  ";
    private static final String LATEST_PASSWORD_HASH = "$argon2id$latest-database-hash";
    private static final String NEW_PASSWORD = "  a deliberately long replacement passphrase  ";
    private static final String NEW_PASSWORD_HASH = "$argon2id$replacement-hash";

    @Mock
    private UserAccountRepository userRepository;
    @Mock
    private PasswordHasher passwordHasher;
    @Mock
    private ChangePasswordTransaction transaction;

    private ChangePasswordService service;

    @BeforeEach
    void setUp() {
        service = new ChangePasswordService(userRepository, passwordHasher, transaction);
    }

    @Test
    void reauthenticatesAgainstLatestDatabaseHashAndPassesUnmodifiedPasswordsForHashing() {
        UserAccount account = account(LATEST_PASSWORD_HASH, UserStatus.ACTIVE, true, 7);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account));
        when(passwordHasher.matches(CURRENT_PASSWORD, LATEST_PASSWORD_HASH)).thenReturn(true);
        when(passwordHasher.hash(NEW_PASSWORD)).thenReturn(NEW_PASSWORD_HASH);

        service.change(USER_ID, CURRENT_PASSWORD, NEW_PASSWORD);

        InOrder order = inOrder(userRepository, passwordHasher, transaction);
        order.verify(userRepository).findById(USER_ID);
        order.verify(passwordHasher).matches(CURRENT_PASSWORD, LATEST_PASSWORD_HASH);
        order.verify(passwordHasher).hash(NEW_PASSWORD);
        order.verify(transaction).complete(USER_ID, 7, NEW_PASSWORD_HASH);
    }

    @Test
    void wrongCurrentPasswordFailsGenericallyWithoutMutationOrSideEffects() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                account(LATEST_PASSWORD_HASH, UserStatus.ACTIVE, true, 0)));
        when(passwordHasher.matches(CURRENT_PASSWORD, LATEST_PASSWORD_HASH)).thenReturn(false);

        ReauthenticationFailedException exception = assertThrows(
                ReauthenticationFailedException.class,
                () -> service.change(USER_ID, CURRENT_PASSWORD, NEW_PASSWORD));

        assertEquals("The current password could not be verified.", exception.getMessage());
        verify(passwordHasher, never()).hash(NEW_PASSWORD);
        verify(transaction, never()).complete(USER_ID, 0, NEW_PASSWORD_HASH);
    }

    @Test
    void invalidNewPasswordDoesNotHashOrStartTheMutationTransaction() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                account(LATEST_PASSWORD_HASH, UserStatus.ACTIVE, true, 0)));
        when(passwordHasher.matches(CURRENT_PASSWORD, LATEST_PASSWORD_HASH)).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.change(USER_ID, CURRENT_PASSWORD, "too short"));

        verify(passwordHasher, never()).hash("too short");
        verify(transaction, never()).complete(USER_ID, 0, NEW_PASSWORD_HASH);
    }

    @Test
    void missingOrIneligibleAccountFailsWithoutCheckingOrChangingPasswords() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThrows(ReauthenticationFailedException.class,
                () -> service.change(USER_ID, CURRENT_PASSWORD, NEW_PASSWORD));

        verify(passwordHasher, never()).matches(CURRENT_PASSWORD, LATEST_PASSWORD_HASH);
        verify(passwordHasher, never()).hash(NEW_PASSWORD);
        verify(transaction, never()).complete(USER_ID, 0, NEW_PASSWORD_HASH);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                account(LATEST_PASSWORD_HASH, UserStatus.DISABLED, true, 0)));
        assertThrows(ReauthenticationFailedException.class,
                () -> service.change(USER_ID, CURRENT_PASSWORD, NEW_PASSWORD));
        verify(passwordHasher, never()).matches(CURRENT_PASSWORD, LATEST_PASSWORD_HASH);
    }

    private static UserAccount account(String passwordHash, UserStatus status, boolean emailVerified, long version) {
        Instant timestamp = Instant.parse("2026-01-02T03:04:05Z");
        return new UserAccount(USER_ID, "Account Owner", "owner@example.test", passwordHash,
                emailVerified, status, version, timestamp, timestamp);
    }
}
