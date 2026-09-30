package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.exception.InvalidVerificationTokenException;
import io.github.guillermodubon.invoward.identity.application.model.EmailVerificationToken;
import io.github.guillermodubon.invoward.identity.application.port.EmailVerificationTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.application.port.VerificationTokenGenerator;
import io.github.guillermodubon.invoward.identity.domain.EmailVerificationPurpose;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Consumes a registration token and activates its account atomically. */
@Service
public class VerifyRegistrationEmailService {

    private static final Pattern RAW_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final EmailVerificationTokenRepository tokenRepository;
    private final UserAccountRepository userRepository;
    private final VerificationTokenGenerator tokenGenerator;
    private final Clock clock;

    public VerifyRegistrationEmailService(
            EmailVerificationTokenRepository tokenRepository,
            UserAccountRepository userRepository,
            VerificationTokenGenerator tokenGenerator,
            Clock clock) {
        this.tokenRepository = Objects.requireNonNull(tokenRepository);
        this.userRepository = Objects.requireNonNull(userRepository);
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public void verify(String rawToken) {
        if (!isWellFormed(rawToken)) {
            throw new InvalidVerificationTokenException();
        }

        Instant now = clock.instant();
        String tokenHash = tokenGenerator.hash(rawToken);
        EmailVerificationToken token = tokenRepository.findByTokenHashForUpdate(tokenHash)
                .filter(candidate -> isUsableRegistrationToken(candidate, now))
                .orElseThrow(InvalidVerificationTokenException::new);

        UserAccount user = userRepository.findById(token.userId())
                .orElseThrow(InvalidVerificationTokenException::new);
        if (!token.targetEmail().equals(user.email())) {
            throw new InvalidVerificationTokenException();
        }

        if (user.status() == UserStatus.ACTIVE && user.emailVerified()) {
            consume(tokenHash, user.id(), now);
            return;
        }
        if (user.status() != UserStatus.PENDING_VERIFICATION || user.emailVerified()) {
            throw new InvalidVerificationTokenException();
        }

        UserAccount activated = user.activateVerifiedRegistration(now);
        userRepository.activateVerifiedRegistration(activated);
        consume(tokenHash, user.id(), now);
    }

    private void consume(String tokenHash, UUID userId, Instant now) {
        if (!tokenRepository.markUsed(tokenHash, now)) {
            throw new InvalidVerificationTokenException();
        }
        tokenRepository.invalidateUnusedByUserAndPurpose(
                userId, EmailVerificationPurpose.REGISTRATION, now);
    }

    private static boolean isUsableRegistrationToken(EmailVerificationToken token, Instant now) {
        return token.purpose() == EmailVerificationPurpose.REGISTRATION
                && token.usedAt() == null
                && token.expiresAt().isAfter(now);
    }

    private static boolean isWellFormed(String rawToken) {
        return rawToken != null && RAW_TOKEN_PATTERN.matcher(rawToken).matches();
    }
}
