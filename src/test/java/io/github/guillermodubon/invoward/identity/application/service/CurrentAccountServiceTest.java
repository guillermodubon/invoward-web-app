package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.exception.AccountConflictException;
import io.github.guillermodubon.invoward.identity.application.model.CurrentAccount;
import io.github.guillermodubon.invoward.identity.application.port.UserAccountRepository;
import io.github.guillermodubon.invoward.identity.domain.UserAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CurrentAccountServiceTest {

    private static final UUID USER_ID = UUID.fromString("a3d9151b-50d3-42c4-9c08-9637795af0c1");

    private final UserAccountRepository repository = mock(UserAccountRepository.class);
    private final CurrentAccountService service = new CurrentAccountService(repository);

    @Test
    void mapsLatestPersistedAccountToTheSafeAuthenticatedProjection() {
        Instant timestamp = Instant.parse("2026-09-01T12:00:00Z");
        UserAccount latest = new UserAccount(USER_ID, "Updated Name", "updated@example.com", "[REDACTED]",
                true, UserStatus.ACTIVE, 3, timestamp, timestamp);
        when(repository.findById(USER_ID)).thenReturn(Optional.of(latest));

        CurrentAccount result = service.get(USER_ID);

        assertEquals(new CurrentAccount(USER_ID, "Updated Name", "updated@example.com", true, UserStatus.ACTIVE),
                result);
        verify(repository).findById(USER_ID);
    }

    @Test
    void missingAccountFailsWithTheExistingSafeAccountConflict() {
        when(repository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThrows(AccountConflictException.class, () -> service.get(USER_ID));
    }
}
