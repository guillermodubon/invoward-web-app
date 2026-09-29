package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.exception.DuplicateEmailException;
import io.github.guillermodubon.invoward.identity.application.model.RegistrationOutcome;
import io.github.guillermodubon.invoward.identity.application.port.PasswordHasher;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.domain.PasswordPolicy;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class RegisterUserService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordHasher passwordHasher;
    private final RegisterUserTransaction registrationTransaction;

    public RegisterUserService(
            UserAccountRepository userAccountRepository,
            PasswordHasher passwordHasher,
            RegisterUserTransaction registrationTransaction) {
        this.userAccountRepository = Objects.requireNonNull(userAccountRepository);
        this.passwordHasher = Objects.requireNonNull(passwordHasher);
        this.registrationTransaction = Objects.requireNonNull(registrationTransaction);
    }

    public RegistrationOutcome register(String displayName, String email, String rawPassword) {
        String normalizedDisplayName = UserAccount.normalizeDisplayName(displayName);
        String normalizedEmail = UserAccount.normalizeEmail(email);
        PasswordPolicy.validate(rawPassword);

        // Hash before the membership check so existing accounts follow the same expensive password path.
        String passwordHash = passwordHasher.hash(rawPassword);
        if (userAccountRepository.existsByNormalizedEmail(normalizedEmail)) {
            return RegistrationOutcome.ACCEPTED;
        }

        try {
            registrationTransaction.createPendingRegistration(
                    normalizedDisplayName, normalizedEmail, passwordHash);
        } catch (DuplicateEmailException ignored) {
            // A database uniqueness race has the same generic public outcome as an existing email.
        }
        return RegistrationOutcome.ACCEPTED;
    }
}
