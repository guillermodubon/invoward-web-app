package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.RegistrationVerificationRequested;
import io.github.guillermodubon.invoward.identity.application.model.EmailVerificationToken;
import io.github.guillermodubon.invoward.identity.application.model.GeneratedVerificationToken;
import io.github.guillermodubon.invoward.identity.application.port.EmailVerificationTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.application.port.VerificationTokenGenerator;
import io.github.guillermodubon.invoward.identity.domain.EmailVerificationPurpose;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResendRegistrationVerificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");
    private static final UUID USER_ID = UUID.fromString("7429a1a0-27df-4a61-b160-289ce5a92ad8");
    private static final String EMAIL = "person@example.com";
    private static final String RAW_TOKEN = "A".repeat(43);
    private static final String TOKEN_HASH = "b".repeat(64);
    private static final Duration TOKEN_TTL = Duration.ofHours(24);
    private static final Duration COOLDOWN = Duration.ofSeconds(60);

    @Mock
    private UserAccountRepository userRepository;
    @Mock
    private EmailVerificationTokenRepository tokenRepository;
    @Mock
    private VerificationTokenGenerator tokenGenerator;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ResendRegistrationVerificationService service;

    @BeforeEach
    void setUp() {
        service = new ResendRegistrationVerificationService(
                userRepository, tokenRepository, tokenGenerator, eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC), TOKEN_TTL, COOLDOWN);
    }

    @Test
    void eligiblePendingAccountRotatesTokenAndPublishesEmailRequest() {
        UserAccount pending = account(UserStatus.PENDING_VERIFICATION, false);
        when(userRepository.findByNormalizedEmail(EMAIL)).thenReturn(Optional.of(pending));
        when(tokenRepository.findLatestCreatedAtByUserAndPurpose(USER_ID, EmailVerificationPurpose.REGISTRATION))
                .thenReturn(Optional.of(NOW.minusSeconds(61)));
        when(tokenGenerator.generate()).thenReturn(new GeneratedVerificationToken(RAW_TOKEN, TOKEN_HASH));

        service.resend(" PERSON@Example.COM ");

        ArgumentCaptor<EmailVerificationToken> savedToken = ArgumentCaptor.forClass(EmailVerificationToken.class);
        InOrder order = inOrder(tokenRepository, tokenGenerator, eventPublisher);
        order.verify(tokenRepository).invalidateUnusedByUserAndPurpose(
                USER_ID, EmailVerificationPurpose.REGISTRATION, NOW);
        order.verify(tokenGenerator).generate();
        order.verify(tokenRepository).save(savedToken.capture());
        order.verify(eventPublisher).publishEvent(Mockito.any(RegistrationVerificationRequested.class));
        assertEquals(new EmailVerificationToken(
                USER_ID, TOKEN_HASH, EmailVerificationPurpose.REGISTRATION, EMAIL,
                NOW.plus(TOKEN_TTL), null, NOW), savedToken.getValue());

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        RegistrationVerificationRequested request = (RegistrationVerificationRequested) event.getValue();
        assertEquals(EMAIL, request.recipient());
        assertEquals(RAW_TOKEN, request.rawVerificationToken());
        assertEquals(NOW.plus(TOKEN_TTL), request.expiresAt());
    }

    @Test
    void unknownAccountDoesNothing() {
        when(userRepository.findByNormalizedEmail(EMAIL)).thenReturn(Optional.empty());

        service.resend(EMAIL);

        verify(tokenRepository, never()).findLatestCreatedAtByUserAndPurpose(
                Mockito.any(), Mockito.any());
        verify(tokenRepository, never()).save(Mockito.any());
        verify(eventPublisher, never()).publishEvent(Mockito.any());
        verify(tokenGenerator, never()).generate();
    }

    @Test
    void activeDisabledAndVerifiedPendingAccountsDoNothing() {
        assertIneligible(UserStatus.ACTIVE, true);
        assertIneligible(UserStatus.DISABLED, false);
        assertIneligible(UserStatus.PENDING_VERIFICATION, true);
    }

    @Test
    void cooldownSuppressesTokenRotationAndEmailRequest() {
        when(userRepository.findByNormalizedEmail(EMAIL)).thenReturn(
                Optional.of(account(UserStatus.PENDING_VERIFICATION, false)));
        when(tokenRepository.findLatestCreatedAtByUserAndPurpose(USER_ID, EmailVerificationPurpose.REGISTRATION))
                .thenReturn(Optional.of(NOW.minusSeconds(59)));

        service.resend(EMAIL);

        verify(tokenRepository, never()).invalidateUnusedByUserAndPurpose(
                Mockito.any(), Mockito.any(), Mockito.any());
        verify(tokenRepository, never()).save(Mockito.any());
        verify(eventPublisher, never()).publishEvent(Mockito.any());
        verify(tokenGenerator, never()).generate();
    }

    @Test
    void requestAtExactCooldownBoundaryIsAllowed() {
        when(userRepository.findByNormalizedEmail(EMAIL)).thenReturn(
                Optional.of(account(UserStatus.PENDING_VERIFICATION, false)));
        when(tokenRepository.findLatestCreatedAtByUserAndPurpose(USER_ID, EmailVerificationPurpose.REGISTRATION))
                .thenReturn(Optional.of(NOW.minus(COOLDOWN)));
        when(tokenGenerator.generate()).thenReturn(new GeneratedVerificationToken(RAW_TOKEN, TOKEN_HASH));

        service.resend(EMAIL);

        verify(tokenRepository).invalidateUnusedByUserAndPurpose(
                USER_ID, EmailVerificationPurpose.REGISTRATION, NOW);
        verify(tokenRepository).save(Mockito.any(EmailVerificationToken.class));
        verify(eventPublisher).publishEvent(Mockito.any(RegistrationVerificationRequested.class));
    }

    private void assertIneligible(UserStatus status, boolean verified) {
        Mockito.reset(userRepository, tokenRepository, tokenGenerator, eventPublisher);
        when(userRepository.findByNormalizedEmail(EMAIL)).thenReturn(Optional.of(account(status, verified)));

        service.resend(EMAIL);

        verify(tokenRepository, never()).findLatestCreatedAtByUserAndPurpose(
                Mockito.any(), Mockito.any());
        verify(tokenRepository, never()).save(Mockito.any());
        verify(eventPublisher, never()).publishEvent(Mockito.any());
        verify(tokenGenerator, never()).generate();
    }

    private static UserAccount account(UserStatus status, boolean emailVerified) {
        return new UserAccount(USER_ID, "Pending User", EMAIL, "$argon2id$test-hash", emailVerified,
                status, 0, NOW.minusSeconds(100), NOW.minusSeconds(100));
    }
}
