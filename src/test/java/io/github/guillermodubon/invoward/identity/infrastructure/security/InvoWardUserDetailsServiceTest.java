package io.github.guillermodubon.invoward.identity.infrastructure.security;

import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoWardUserDetailsServiceTest {

    @Mock
    private UserAccountRepository userAccountRepository;

    @Test
    void normalizesUsernameBeforeLookingUpAccount() {
        UserAccount account = account("user@example.com", UserStatus.PENDING_VERIFICATION, false);
        when(userAccountRepository.findByNormalizedEmail("user@example.com")).thenReturn(Optional.of(account));
        InvoWardUserDetailsService service = new InvoWardUserDetailsService(userAccountRepository);

        AuthenticatedUserPrincipal principal = assertInstanceOf(
                AuthenticatedUserPrincipal.class, service.loadUserByUsername(" User@Example.com "));

        assertEquals("user@example.com", principal.getUsername());
        assertFalse(principal.isEnabled());
        verify(userAccountRepository).findByNormalizedEmail("user@example.com");
    }

    @Test
    void unknownUsernameThrowsGenericUsernameNotFoundException() {
        when(userAccountRepository.findByNormalizedEmail("unknown@example.com")).thenReturn(Optional.empty());
        InvoWardUserDetailsService service = new InvoWardUserDetailsService(userAccountRepository);

        UsernameNotFoundException exception = assertThrows(
                UsernameNotFoundException.class,
                () -> service.loadUserByUsername("unknown@example.com"));

        assertEquals("User not found", exception.getMessage());
        assertFalse(exception.getMessage().contains("unknown@example.com"));
    }

    @Test
    void blankUsernameFailsWithoutQueryingRepository() {
        InvoWardUserDetailsService service = new InvoWardUserDetailsService(userAccountRepository);

        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername(" \t "));
        verifyNoInteractions(userAccountRepository);
    }

    private static UserAccount account(String email, UserStatus status, boolean verified) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new UserAccount(
                UUID.fromString("d0f8080a-56f0-4f1f-9d84-9b8f4837e4d5"),
                "Person",
                email,
                "$argon2id$test-hash",
                verified,
                status,
                0,
                now,
                now);
    }
}
