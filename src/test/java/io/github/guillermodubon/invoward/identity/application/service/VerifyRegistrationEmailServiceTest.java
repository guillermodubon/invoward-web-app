package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.exception.InvalidVerificationTokenException;
import io.github.guillermodubon.invoward.identity.application.model.EmailVerificationToken;
import io.github.guillermodubon.invoward.identity.application.port.EmailVerificationTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.application.port.VerificationTokenGenerator;
import io.github.guillermodubon.invoward.identity.domain.EmailVerificationPurpose;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerifyRegistrationEmailServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");
    private static final UUID USER_ID = UUID.fromString("7429a1a0-27df-4a61-b160-289ce5a92ad8");
    private static final String RAW_TOKEN = "A".repeat(43);
    private static final String TOKEN_HASH = "b".repeat(64);
    private static final String EMAIL = "person@example.com";

    @Mock
    private EmailVerificationTokenRepository tokenRepository;
    @Mock
    private UserAccountRepository userRepository;
    @Mock
    private VerificationTokenGenerator tokenGenerator;

    private VerifyRegistrationEmailService service;

    @BeforeEach
    void setUp() {
        service = new VerifyRegistrationEmailService(
                tokenRepository,
                userRepository,
                tokenGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC));
        Mockito.lenient().when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
    }

    @Test
    void activatesAccountConsumesTokenAndInvalidatesRegistrationSiblings() {
        EmailVerificationToken token = token(EmailVerificationPurpose.REGISTRATION, EMAIL, null,
                NOW.plusSeconds(60));
        UserAccount pending = account(UserStatus.PENDING_VERIFICATION, false, EMAIL);
        UserAccount activated = pending.activateVerifiedRegistration(NOW);
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(token));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(pending));
        when(userRepository.activateVerifiedRegistration(activated)).thenReturn(activated);
        when(tokenRepository.markUsed(TOKEN_HASH, NOW)).thenReturn(true);

        service.verify(RAW_TOKEN);

        InOrder order = inOrder(tokenRepository, userRepository);
        order.verify(tokenRepository).findByTokenHashForUpdate(TOKEN_HASH);
        order.verify(userRepository).findById(USER_ID);
        order.verify(userRepository).activateVerifiedRegistration(activated);
        order.verify(tokenRepository).markUsed(TOKEN_HASH, NOW);
        order.verify(tokenRepository).invalidateUnusedByUserAndPurpose(
                USER_ID, EmailVerificationPurpose.REGISTRATION, NOW);
        assertEquals(UserStatus.ACTIVE, activated.status());
        assertTrue(activated.emailVerified());
    }

    @Test
    void alreadyVerifiedAccountConsumesPresentedRegistrationTokenIdempotently() {
        EmailVerificationToken token = token(EmailVerificationPurpose.REGISTRATION, EMAIL, null,
                NOW.plusSeconds(60));
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(token));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account(UserStatus.ACTIVE, true, EMAIL)));
        when(tokenRepository.markUsed(TOKEN_HASH, NOW)).thenReturn(true);

        service.verify(RAW_TOKEN);

        verify(userRepository, never()).activateVerifiedRegistration(org.mockito.ArgumentMatchers.any());
        verify(tokenRepository).markUsed(TOKEN_HASH, NOW);
        verify(tokenRepository).invalidateUnusedByUserAndPurpose(
                USER_ID, EmailVerificationPurpose.REGISTRATION, NOW);
    }

    @Test
    void malformedTokenUsesSameSafeFailureWithoutHashLookup() {
        InvalidVerificationTokenException nullToken = assertThrows(
                InvalidVerificationTokenException.class, () -> service.verify(null));
        InvalidVerificationTokenException malformedToken = assertThrows(
                InvalidVerificationTokenException.class, () -> service.verify("not-a-token"));

        assertEquals("The verification link is invalid or expired.", nullToken.getMessage());
        assertEquals(nullToken.getMessage(), malformedToken.getMessage());
        verify(tokenGenerator, never()).hash(org.mockito.ArgumentMatchers.any());
        verify(tokenRepository, never()).findByTokenHashForUpdate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unknownTokenUsesGenericFailure() {
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.empty());

        assertInvalidToken();
        verify(userRepository, never()).findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void expiredUsedAndWrongPurposeTokensUseGenericFailure() {
        assertInvalidTokenFor(token(EmailVerificationPurpose.REGISTRATION, EMAIL, null, NOW));
        assertInvalidTokenFor(token(EmailVerificationPurpose.REGISTRATION, EMAIL, NOW.minusSeconds(1),
                NOW.plusSeconds(60)));
        assertInvalidTokenFor(token(EmailVerificationPurpose.EMAIL_CHANGE, EMAIL, null, NOW.plusSeconds(60)));
    }

    @Test
    void missingAccountUsesGenericFailure() {
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(
                token(EmailVerificationPurpose.REGISTRATION, EMAIL, null, NOW.plusSeconds(60))));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertInvalidToken();
    }

    @Test
    void targetEmailMismatchUsesGenericFailure() {
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(
                token(EmailVerificationPurpose.REGISTRATION, "other@example.com", null, NOW.plusSeconds(60))));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                account(UserStatus.PENDING_VERIFICATION, false, EMAIL)));

        assertInvalidToken();
        verify(userRepository, never()).activateVerifiedRegistration(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void disabledAccountIsNeverReactivated() {
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(
                token(EmailVerificationPurpose.REGISTRATION, EMAIL, null, NOW.plusSeconds(60))));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account(UserStatus.DISABLED, false, EMAIL)));

        assertInvalidToken();
        verify(userRepository, never()).activateVerifiedRegistration(org.mockito.ArgumentMatchers.any());
        verify(tokenRepository, never()).markUsed(TOKEN_HASH, NOW);
    }

    @Test
    void tokenThatCannotBeMarkedUsedDoesNotInvalidateSiblings() {
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(
                token(EmailVerificationPurpose.REGISTRATION, EMAIL, null, NOW.plusSeconds(60))));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account(UserStatus.PENDING_VERIFICATION, false, EMAIL)));
        when(tokenRepository.markUsed(TOKEN_HASH, NOW)).thenReturn(false);

        assertInvalidToken();
        verify(tokenRepository, never()).invalidateUnusedByUserAndPurpose(
                USER_ID, EmailVerificationPurpose.REGISTRATION, NOW);
    }

    private void assertInvalidTokenFor(EmailVerificationToken token) {
        when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(token));
        assertInvalidToken();
    }

    private void assertInvalidToken() {
        InvalidVerificationTokenException exception = assertThrows(
                InvalidVerificationTokenException.class, () -> service.verify(RAW_TOKEN));
        assertEquals("The verification link is invalid or expired.", exception.getMessage());
    }

    private static EmailVerificationToken token(
            EmailVerificationPurpose purpose,
            String targetEmail,
            Instant usedAt,
            Instant expiresAt) {
        return new EmailVerificationToken(
                USER_ID, TOKEN_HASH, purpose, targetEmail, expiresAt, usedAt, NOW.minusSeconds(10));
    }

    private static UserAccount account(UserStatus status, boolean verified, String email) {
        return new UserAccount(USER_ID, "Verification User", email, "$argon2id$test-hash", verified,
                status, 0, NOW.minusSeconds(100), NOW.minusSeconds(100));
    }
}
