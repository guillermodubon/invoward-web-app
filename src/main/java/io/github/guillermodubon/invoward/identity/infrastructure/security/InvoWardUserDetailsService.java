package io.github.guillermodubon.invoward.identity.infrastructure.security;

import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class InvoWardUserDetailsService implements UserDetailsService {

    private static final String USER_NOT_FOUND_MESSAGE = "User not found";

    private final UserAccountRepository userAccountRepository;

    public InvoWardUserDetailsService(UserAccountRepository userAccountRepository) {
        this.userAccountRepository = Objects.requireNonNull(userAccountRepository);
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String normalizedEmail = normalizeUsername(username);
        return userAccountRepository.findByNormalizedEmail(normalizedEmail)
                .map(AuthenticatedUserPrincipal::new)
                .orElseThrow(InvoWardUserDetailsService::userNotFound);
    }

    private static String normalizeUsername(String username) {
        if (username == null) {
            throw userNotFound();
        }
        try {
            return UserAccount.normalizeEmail(username);
        } catch (IllegalArgumentException exception) {
            throw userNotFound();
        }
    }

    private static UsernameNotFoundException userNotFound() {
        return new UsernameNotFoundException(USER_NOT_FOUND_MESSAGE);
    }
}
