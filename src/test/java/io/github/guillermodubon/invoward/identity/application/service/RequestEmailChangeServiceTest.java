package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.event.EmailChangeVerificationRequested;
import io.github.guillermodubon.invoward.identity.application.exception.ReauthenticationFailedException;
import io.github.guillermodubon.invoward.identity.application.model.EmailVerificationToken;
import io.github.guillermodubon.invoward.identity.application.model.GeneratedVerificationToken;
import io.github.guillermodubon.invoward.identity.application.port.EmailVerificationTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.PasswordHasher;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequestEmailChangeServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");
    private static final UUID USER_ID = UUID.fromString("7429a1a0-27df-4a61-b160-289ce5a92ad8");
    private static final String EMAIL = "person@example.com";
    private static final String PASSWORD = "the existing secure passphrase";
    private static final String PASSWORD_HASH = "$argon2id$latest-account-hash";
    private static final String RAW_TOKEN = "A".repeat(43);
    private static final String TOKEN_HASH = "b".repeat(64);
    private static final Duration TOKEN_TTL = Duration.ofHours(24);
    private static final Duration COOLDOWN = Duration.ofSeconds(60);

    @Mock
    private UserAccountRepository userRepository;
    @Mock
    private EmailVerificationTokenRepository tokenRepository;
    @Mock
    private PasswordHasher passwordHasher;
    @Mock
    private VerificationTokenGenerator tokenGenerator;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private RequestEmailChangeService service;

    @BeforeEach
    void setUp() {
        service = new RequestEmailChangeService(
                userRepository, tokenRepository, passwordHasher, tokenGenerator, eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC), TOKEN_TTL, COOLDOWN);
    }

    @Test
    void availableTargetRotatesOnlyEmailChangeTokensAndPublishesNewAddressEmail() {
        UserAccount account = account(EMAIL, PASSWORD_HASH, UserStatus.ACTIVE, true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account));
        when(passwordHasher.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);
        when(userRepository.existsByNormalizedEmail("new.person@example.com")).thenReturn(false);
        when(tokenRepository.findLatestCreatedAtByUserAndPurpose(USER_ID, EmailVerificationPurpose.EMAIL_CHANGE))
                .thenReturn(Optional.of(NOW.minusSeconds(61)));
        when(tokenGenerator.generate()).thenReturn(new GeneratedVerificationToken(RAW_TOKEN, TOKEN_HASH));

        service.request(USER_ID, PASSWORD, "  NEW.Person@Example.COM  ");

        ArgumentCaptor<EmailVerificationToken> savedToken = ArgumentCaptor.forClass(EmailVerificationToken.class);
        InOrder order = inOrder(userRepository, passwordHasher, tokenRepository, tokenGenerator, eventPublisher);
        order.verify(userRepository).findById(USER_ID);
        order.verify(passwordHasher).matches(PASSWORD, PASSWORD_HASH);
        order.verify(userRepository).existsByNormalizedEmail("new.person@example.com");
        order.verify(tokenRepository).findLatestCreatedAtByUserAndPurpose(
                USER_ID, EmailVerificationPurpose.EMAIL_CHANGE);
        order.verify(tokenRepository).invalidateUnusedByUserAndPurpose(
                USER_ID, EmailVerificationPurpose.EMAIL_CHANGE, NOW);
        order.verify(tokenGenerator).generate();
        order.verify(tokenRepository).save(savedToken.capture());
        order.verify(eventPublisher).publishEvent(Mockito.any(EmailChangeVerificationRequested.class));

        assertEquals(new EmailVerificationToken(
                USER_ID, TOKEN_HASH, EmailVerificationPurpose.EMAIL_CHANGE,
                "new.person@example.com", NOW.plus(TOKEN_TTL), null, NOW), savedToken.getValue());
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        EmailChangeVerificationRequested event = (EmailChangeVerificationRequested) eventCaptor.getValue();
        assertEquals("new.person@example.com", event.recipient());
        assertEquals(RAW_TOKEN, event.rawToken());
        assertEquals(NOW.plus(TOKEN_TTL), event.expiresAt());
    }

    @Test
    void wrongCurrentPasswordFailsWithoutIssuingToken() {
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(account(EMAIL, PASSWORD_HASH, UserStatus.ACTIVE, true)));
        when(passwordHasher.matches("wrong password", PASSWORD_HASH)).thenReturn(false);

        assertThrows(ReauthenticationFailedException.class,
                () -> service.request(USER_ID, "wrong password", "new@example.com"));

        verify(userRepository, never()).existsByNormalizedEmail(Mockito.anyString());
        verify(tokenRepository, never()).findLatestCreatedAtByUserAndPurpose(Mockito.any(), Mockito.any());
        verify(tokenRepository, never()).invalidateUnusedByUserAndPurpose(Mockito.any(), Mockito.any(), Mockito.any());
        verify(tokenRepository, never()).save(Mockito.any());
        verify(tokenGenerator, never()).generate();
        verify(eventPublisher, never()).publishEvent(Mockito.any());
    }

    @Test
    void sameEmailOccupiedEmailAndCooldownHaveIndistinguishableNoOpOutcome() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                account(EMAIL, PASSWORD_HASH, UserStatus.ACTIVE, true)));
        when(passwordHasher.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);

        service.request(USER_ID, PASSWORD, " PERSON@EXAMPLE.COM ");
        when(userRepository.existsByNormalizedEmail("occupied@example.com")).thenReturn(true);
        service.request(USER_ID, PASSWORD, "occupied@example.com");
        when(userRepository.existsByNormalizedEmail("available@example.com")).thenReturn(false);
        when(tokenRepository.findLatestCreatedAtByUserAndPurpose(USER_ID, EmailVerificationPurpose.EMAIL_CHANGE))
                .thenReturn(Optional.of(NOW.minusSeconds(59)));
        service.request(USER_ID, PASSWORD, "available@example.com");

        verify(tokenRepository, never()).invalidateUnusedByUserAndPurpose(
                Mockito.any(), Mockito.any(), Mockito.any());
        verify(tokenRepository, never()).save(Mockito.any());
        verify(tokenGenerator, never()).generate();
        verify(eventPublisher, never()).publishEvent(Mockito.any());
    }

    @Test
    void exactCooldownBoundaryAllowsIssuance() {
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(account(EMAIL, PASSWORD_HASH, UserStatus.ACTIVE, true)));
        when(passwordHasher.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);
        when(userRepository.existsByNormalizedEmail("new@example.com")).thenReturn(false);
        when(tokenRepository.findLatestCreatedAtByUserAndPurpose(USER_ID, EmailVerificationPurpose.EMAIL_CHANGE))
                .thenReturn(Optional.of(NOW.minus(COOLDOWN)));
        when(tokenGenerator.generate()).thenReturn(new GeneratedVerificationToken(RAW_TOKEN, TOKEN_HASH));

        service.request(USER_ID, PASSWORD, "new@example.com");

        verify(tokenRepository).invalidateUnusedByUserAndPurpose(
                USER_ID, EmailVerificationPurpose.EMAIL_CHANGE, NOW);
        verify(tokenRepository).save(Mockito.any(EmailVerificationToken.class));
        verify(eventPublisher).publishEvent(Mockito.any(EmailChangeVerificationRequested.class));
    }

    @Test
    void missingOrIneligibleAccountFailsAsReauthenticationFailure() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThrows(ReauthenticationFailedException.class,
                () -> service.request(USER_ID, PASSWORD, "new@example.com"));

        verify(passwordHasher, never()).matches(Mockito.any(), Mockito.any());
        verify(tokenRepository, never()).save(Mockito.any());
    }

    private static UserAccount account(String email, String passwordHash, UserStatus status, boolean verified) {
        return new UserAccount(USER_ID, "Account User", email, passwordHash, verified,
                status, 2, NOW.minusSeconds(100), NOW.minusSeconds(100));
    }
}
