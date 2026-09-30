package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.exception.AccountConflictException;
import io.github.guillermodubon.invoward.identity.application.model.CurrentAccount;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpdateProfileServiceTest {

    private static final UUID USER_ID = UUID.fromString("a3d9151b-50d3-42c4-9c08-9637795af0c1");
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");

    private final UserAccountRepository repository = mock(UserAccountRepository.class);
    private final UpdateProfileService service = new UpdateProfileService(
            repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void updatesOnlyDisplayNameUsingDomainNormalizationAndReturnsPersistedSnapshot() {
        UserAccount account = account("Before", 4);
        when(repository.findById(USER_ID)).thenReturn(Optional.of(account));
        when(repository.updateDisplayName(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CurrentAccount result = service.updateDisplayName(USER_ID, "  After  ");

        assertEquals("After", result.displayName());
        assertEquals("owner@example.com", result.email());
        assertEquals(NOW, updatedAccount().updatedAt());
        verify(repository).updateDisplayName(updatedAccount());
    }

    @Test
    void missingAccountDoesNotAttemptProfileMutation() {
        when(repository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThrows(AccountConflictException.class, () -> service.updateDisplayName(USER_ID, "Name"));
    }

    private static UserAccount updatedAccount() {
        return account("After", 4).updateDisplayName("After", NOW);
    }

    private static UserAccount account(String displayName, long version) {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        return new UserAccount(USER_ID, displayName, "owner@example.com", "[REDACTED]",
                true, UserStatus.ACTIVE, version, createdAt, createdAt);
    }
}
