package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.exception.InvalidPasswordResetTokenException;
import io.github.guillermodubon.invoward.identity.application.model.PasswordResetToken;
import io.github.guillermodubon.invoward.identity.application.port.PasswordHasher;
import io.github.guillermodubon.invoward.identity.application.port.PasswordResetTokenRepository;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.application.port.VerificationTokenGenerator;
import io.github.guillermodubon.invoward.identity.domain.PasswordPolicy;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Validates and hashes reset input outside the short token-consumption transaction. */
public class ResetPasswordService {

    private static final Pattern RAW_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final PasswordResetTokenRepository tokenRepository;
    private final UserAccountRepository userRepository;
    private final VerificationTokenGenerator tokenGenerator;
    private final PasswordHasher passwordHasher;
    private final ResetPasswordTransaction resetTransaction;
    private final Clock clock;

    public ResetPasswordService(
            PasswordResetTokenRepository tokenRepository,
            UserAccountRepository userRepository,
            VerificationTokenGenerator tokenGenerator,
            PasswordHasher passwordHasher,
            ResetPasswordTransaction resetTransaction,
            Clock clock) {
        this.tokenRepository = Objects.requireNonNull(tokenRepository);
        this.userRepository = Objects.requireNonNull(userRepository);
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator);
        this.passwordHasher = Objects.requireNonNull(passwordHasher);
        this.resetTransaction = Objects.requireNonNull(resetTransaction);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void reset(String rawToken, String newPassword) {
        PasswordPolicy.validate(newPassword);
        if (!isWellFormed(rawToken)) {
            throw new InvalidPasswordResetTokenException();
        }

        String tokenHash = tokenGenerator.hash(rawToken);
        PasswordResetToken token = tokenRepository.findByTokenHashForUpdate(tokenHash)
                .filter(candidate -> isUsable(candidate, clock.instant()))
                .orElseThrow(InvalidPasswordResetTokenException::new);
        userRepository.findById(token.userId())
                .filter(ResetPasswordService::isEligible)
                .orElseThrow(InvalidPasswordResetTokenException::new);

        // The repository lookup transaction has ended before the deliberately expensive Argon2 work.
        String newPasswordHash = passwordHasher.hash(newPassword);
        resetTransaction.complete(tokenHash, newPasswordHash);
    }

    private static boolean isUsable(PasswordResetToken token, Instant now) {
        return token.usedAt() == null && token.expiresAt().isAfter(now);
    }

    private static boolean isEligible(UserAccount user) {
        return user.status() == UserStatus.ACTIVE && user.emailVerified();
    }

    private static boolean isWellFormed(String rawToken) {
        return rawToken != null && RAW_TOKEN_PATTERN.matcher(rawToken).matches();
    }
}
