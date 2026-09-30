package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.exception.InvalidPasswordResetTokenException;
import io.github.guillermodubon.invoward.identity.application.model.PasswordResetToken;
import io.github.guillermodubon.invoward.identity.application.port.PasswordHasher;
import io.github.guillermodubon.invoward.identity.application.port.PasswordResetTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.application.port.VerificationTokenGenerator;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
class ResetPasswordServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");
    private static final UUID USER_ID = UUID.fromString("7429a1a0-27df-4a61-b160-289ce5a92ad8");
    private static final String RAW_TOKEN = "A".repeat(43);
    private static final String TOKEN_HASH = "b".repeat(64);
    private static final String NEW_PASSWORD = "  deliberately spaced passphrase  ";
    private static final String NEW_PASSWORD_HASH = "$argon2id$encoded-new-password";

    @Mock
    private PasswordResetTokenRepository tokenRepository;
    @Mock
    private UserAccountRepository userRepository;
    @Mock
    private VerificationTokenGenerator tokenGenerator;
    @Mock
    private PasswordHasher passwordHasher;
    @Mock
    private ResetPasswordTransaction resetTransaction;

    private ResetPasswordService service;

    @BeforeEach
    void setUp() {
        service = new ResetPasswordService(
                tokenRepository, userRepository, tokenGenerator, passwordHasher, resetTransaction,
                Clock.fixed(NOW, ZoneOffset.UTC));
        org.mockito.Mockito.lenient().when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
    }

    @Test
    void validatesThenHashesOutsidePersistenceLookupAndDelegatesAtomicMutation() {
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH))
                .thenReturn(Optional.of(token(null, NOW.plusSeconds(60))));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account(UserStatus.ACTIVE, true)));
        when(passwordHasher.hash(NEW_PASSWORD)).thenReturn(NEW_PASSWORD_HASH);

        service.reset(RAW_TOKEN, NEW_PASSWORD);

        InOrder order = inOrder(tokenRepository, userRepository, passwordHasher, resetTransaction);
        order.verify(tokenRepository).findByTokenHashForUpdate(TOKEN_HASH);
        order.verify(userRepository).findById(USER_ID);
        order.verify(passwordHasher).hash(NEW_PASSWORD);
        order.verify(resetTransaction).complete(TOKEN_HASH, NEW_PASSWORD_HASH);
    }

    @Test
    void invalidPasswordFailsBeforeTokenLookupOrHashing() {
        assertThrows(IllegalArgumentException.class, () -> service.reset(RAW_TOKEN, "too short"));

        verify(tokenGenerator, never()).hash(any());
        verify(tokenRepository, never()).findByTokenHashForUpdate(any());
        verify(passwordHasher, never()).hash(any());
        verify(resetTransaction, never()).complete(any(), any());
    }

    @Test
    void malformedTokenUsesGenericFailureBeforeDatabaseWork() {
        InvalidPasswordResetTokenException exception = assertThrows(
                InvalidPasswordResetTokenException.class, () -> service.reset("not-a-token", NEW_PASSWORD));

        assertEquals("The password reset link is invalid or expired.", exception.getMessage());
        verify(tokenGenerator, never()).hash(any());
        verify(tokenRepository, never()).findByTokenHashForUpdate(any());
        verify(passwordHasher, never()).hash(any());
    }

    @Test
    void unknownTokenUsesGenericFailureWithoutPasswordHashing() {
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.empty());

        assertInvalidToken();

        verify(userRepository, never()).findById(any());
        verify(passwordHasher, never()).hash(any());
        verify(resetTransaction, never()).complete(any(), any());
    }

    @Test
    void expiredAndUsedTokensUseTheSameGenericFailure() {
        assertInvalidCandidate(token(null, NOW));
        assertInvalidCandidate(token(NOW.minusSeconds(1), NOW.plusSeconds(60)));
    }

    @Test
    void missingOrIneligibleAccountUsesGenericFailureWithoutHashingPassword() {
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH))
                .thenReturn(Optional.of(token(null, NOW.plusSeconds(60))));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
        assertInvalidToken();
        verify(passwordHasher, never()).hash(any());

        org.mockito.Mockito.reset(tokenRepository, userRepository, tokenGenerator, passwordHasher, resetTransaction);
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH))
                .thenReturn(Optional.of(token(null, NOW.plusSeconds(60))));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account(UserStatus.DISABLED, true)));

        assertInvalidToken();
        verify(passwordHasher, never()).hash(any());
        verify(resetTransaction, never()).complete(any(), any());
    }

    @Test
    void pendingAndUnverifiedAccountsCannotResetPassword() {
        assertInvalidForAccount(account(UserStatus.PENDING_VERIFICATION, false));
        assertInvalidForAccount(account(UserStatus.ACTIVE, false));
    }

    private void assertInvalidCandidate(PasswordResetToken candidate) {
        org.mockito.Mockito.reset(tokenRepository, userRepository, tokenGenerator, passwordHasher, resetTransaction);
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(candidate));

        assertInvalidToken();

        verify(userRepository, never()).findById(any());
        verify(passwordHasher, never()).hash(any());
    }

    private void assertInvalidForAccount(UserAccount account) {
        org.mockito.Mockito.reset(tokenRepository, userRepository, tokenGenerator, passwordHasher, resetTransaction);
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH))
                .thenReturn(Optional.of(token(null, NOW.plusSeconds(60))));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account));

        assertInvalidToken();

        verify(passwordHasher, never()).hash(any());
        verify(resetTransaction, never()).complete(any(), any());
    }

    private void assertInvalidToken() {
        InvalidPasswordResetTokenException exception = assertThrows(
                InvalidPasswordResetTokenException.class, () -> service.reset(RAW_TOKEN, NEW_PASSWORD));
        assertEquals("The password reset link is invalid or expired.", exception.getMessage());
    }

    private static PasswordResetToken token(Instant usedAt, Instant expiresAt) {
        return new PasswordResetToken(USER_ID, TOKEN_HASH, expiresAt, usedAt, NOW.minusSeconds(10));
    }

    private static UserAccount account(UserStatus status, boolean verified) {
        return new UserAccount(USER_ID, "Reset User", "reset@example.com", "$argon2id$stored-hash",
                verified, status, 0, NOW.minusSeconds(100), NOW.minusSeconds(100));
    }
}
