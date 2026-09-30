package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.exception.ReauthenticationFailedException;
import io.github.guillermodubon.invoward.identity.application.port.PasswordHasher;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.domain.PasswordPolicy;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** Reauthenticates against the current persisted hash before preparing a credential change. */
public class ChangePasswordService {

    private final UserAccountRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final ChangePasswordTransaction changePasswordTransaction;

    public ChangePasswordService(
            UserAccountRepository userRepository,
            PasswordHasher passwordHasher,
            ChangePasswordTransaction changePasswordTransaction) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.passwordHasher = Objects.requireNonNull(passwordHasher);
        this.changePasswordTransaction = Objects.requireNonNull(changePasswordTransaction);
    }

    /** Performs Argon2 work outside the short transaction that updates the account and reset tokens. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void change(UUID userId, String currentPassword, String newPassword) {
        Objects.requireNonNull(userId, "userId must not be null");

        UserAccount account = userRepository.findById(userId)
                .filter(ChangePasswordService::isEligible)
                .orElseThrow(ReauthenticationFailedException::new);
        if (!passwordHasher.matches(currentPassword, account.passwordHash())) {
            throw new ReauthenticationFailedException();
        }

        PasswordPolicy.validate(newPassword);
        String encodedPassword = passwordHasher.hash(newPassword);
        changePasswordTransaction.complete(account.id(), account.version(), encodedPassword);
    }

    private static boolean isEligible(UserAccount account) {
        return account.status() == UserStatus.ACTIVE && account.emailVerified();
    }
}
