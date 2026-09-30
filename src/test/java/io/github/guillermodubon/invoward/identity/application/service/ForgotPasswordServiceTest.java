package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.PasswordResetRequested;
import io.github.guillermodubon.invoward.identity.application.model.GeneratedVerificationToken;
import io.github.guillermodubon.invoward.identity.application.model.PasswordResetToken;
import io.github.guillermodubon.invoward.identity.application.port.PasswordResetTokenRepository;
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
class ForgotPasswordServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");
    private static final UUID USER_ID = UUID.fromString("7429a1a0-27df-4a61-b160-289ce5a92ad8");
    private static final String EMAIL = "person@example.com";
    private static final String RAW_TOKEN = "A".repeat(43);
    private static final String TOKEN_HASH = "b".repeat(64);
    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);
    private static final Duration COOLDOWN = Duration.ofSeconds(60);

    @Mock
    private UserAccountRepository userRepository;
    @Mock
    private PasswordResetTokenRepository tokenRepository;
    @Mock
    private VerificationTokenGenerator tokenGenerator;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ForgotPasswordService service;

    @BeforeEach
    void setUp() {
        service = new ForgotPasswordService(
                userRepository, tokenRepository, tokenGenerator, eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC), TOKEN_TTL, COOLDOWN);
    }

    @Test
    void eligibleAccountRotatesPreviousTokensAndPublishesResetEmailRequest() {
        when(userRepository.findByNormalizedEmail(EMAIL))
                .thenReturn(Optional.of(account(UserStatus.ACTIVE, true)));
        when(tokenRepository.findLatestCreatedAtByUser(USER_ID))
                .thenReturn(Optional.of(NOW.minusSeconds(61)));
        when(tokenGenerator.generate()).thenReturn(new GeneratedVerificationToken(RAW_TOKEN, TOKEN_HASH));

        service.request(" PERSON@Example.COM ");

        ArgumentCaptor<PasswordResetToken> savedToken = ArgumentCaptor.forClass(PasswordResetToken.class);
        InOrder order = inOrder(tokenRepository, tokenGenerator, eventPublisher);
        order.verify(tokenRepository).invalidateUnusedByUser(USER_ID, NOW);
        order.verify(tokenGenerator).generate();
        order.verify(tokenRepository).save(savedToken.capture());
        order.verify(eventPublisher).publishEvent(Mockito.any(PasswordResetRequested.class));
        assertEquals(new PasswordResetToken(USER_ID, TOKEN_HASH, NOW.plus(TOKEN_TTL), null, NOW),
                savedToken.getValue());

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        PasswordResetRequested request = (PasswordResetRequested) event.getValue();
        assertEquals(EMAIL, request.recipient());
        assertEquals(RAW_TOKEN, request.rawToken());
        assertEquals(NOW.plus(TOKEN_TTL), request.expiresAt());
    }

    @Test
    void unknownAccountDoesNothing() {
        when(userRepository.findByNormalizedEmail(EMAIL)).thenReturn(Optional.empty());

        service.request(EMAIL);

        verify(tokenRepository, never()).findLatestCreatedAtByUser(Mockito.any());
        verify(tokenRepository, never()).invalidateUnusedByUser(Mockito.any(), Mockito.any());
        verify(tokenRepository, never()).save(Mockito.any());
        verify(tokenGenerator, never()).generate();
        verify(eventPublisher, never()).publishEvent(Mockito.any());
    }

    @Test
    void pendingDisabledAndUnverifiedAccountsDoNothing() {
        assertIneligible(UserStatus.PENDING_VERIFICATION, false);
        assertIneligible(UserStatus.DISABLED, false);
        assertIneligible(UserStatus.ACTIVE, false);
    }

    @Test
    void cooldownSuppressesTokenRotationAndEmailRequest() {
        when(userRepository.findByNormalizedEmail(EMAIL))
                .thenReturn(Optional.of(account(UserStatus.ACTIVE, true)));
        when(tokenRepository.findLatestCreatedAtByUser(USER_ID))
                .thenReturn(Optional.of(NOW.minusSeconds(59)));

        service.request(EMAIL);

        verify(tokenRepository, never()).invalidateUnusedByUser(Mockito.any(), Mockito.any());
        verify(tokenRepository, never()).save(Mockito.any());
        verify(tokenGenerator, never()).generate();
        verify(eventPublisher, never()).publishEvent(Mockito.any());
    }

    @Test
    void requestAtExactCooldownBoundaryIsAllowed() {
        when(userRepository.findByNormalizedEmail(EMAIL))
                .thenReturn(Optional.of(account(UserStatus.ACTIVE, true)));
        when(tokenRepository.findLatestCreatedAtByUser(USER_ID))
                .thenReturn(Optional.of(NOW.minus(COOLDOWN)));
        when(tokenGenerator.generate()).thenReturn(new GeneratedVerificationToken(RAW_TOKEN, TOKEN_HASH));

        service.request(EMAIL);

        verify(tokenRepository).invalidateUnusedByUser(USER_ID, NOW);
        verify(tokenRepository).save(Mockito.any(PasswordResetToken.class));
        verify(eventPublisher).publishEvent(Mockito.any(PasswordResetRequested.class));
    }

    private void assertIneligible(UserStatus status, boolean emailVerified) {
        Mockito.reset(userRepository, tokenRepository, tokenGenerator, eventPublisher);
        when(userRepository.findByNormalizedEmail(EMAIL))
                .thenReturn(Optional.of(account(status, emailVerified)));

        service.request(EMAIL);

        verify(tokenRepository, never()).findLatestCreatedAtByUser(Mockito.any());
        verify(tokenRepository, never()).invalidateUnusedByUser(Mockito.any(), Mockito.any());
        verify(tokenRepository, never()).save(Mockito.any());
        verify(tokenGenerator, never()).generate();
        verify(eventPublisher, never()).publishEvent(Mockito.any());
    }

    private static UserAccount account(UserStatus status, boolean emailVerified) {
        return new UserAccount(USER_ID, "Account User", EMAIL, "$argon2id$test-hash", emailVerified,
                status, 0, NOW.minusSeconds(100), NOW.minusSeconds(100));
    }
}
