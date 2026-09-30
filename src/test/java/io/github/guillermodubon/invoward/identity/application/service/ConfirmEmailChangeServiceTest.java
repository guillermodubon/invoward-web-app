package io.github.guillermodubon.invoward.identity.application.service;

import io.github.guillermodubon.invoward.identity.application.exception.InvalidEmailChangeTokenException;
import io.github.guillermodubon.invoward.identity.application.port.VerificationTokenGenerator;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfirmEmailChangeServiceTest {

    private static final UUID USER_ID = UUID.fromString("7429a1a0-27df-4a61-b160-289ce5a92ad8");
    private static final String RAW_TOKEN = "A".repeat(43);
    private static final String TOKEN_HASH = "a".repeat(64);

    private final VerificationTokenGenerator tokenGenerator = mock(VerificationTokenGenerator.class);
    private final ConfirmEmailChangeTransaction transaction = mock(ConfirmEmailChangeTransaction.class);
    private final ConfirmEmailChangeService service = new ConfirmEmailChangeService(tokenGenerator, transaction);

    @Test
    void hashesWellFormedRawTokenBeforeDelegatingWithAuthenticatedOwner() {
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);

        service.confirm(USER_ID, RAW_TOKEN);

        verify(tokenGenerator).hash(RAW_TOKEN);
        verify(transaction).confirm(USER_ID, TOKEN_HASH);
    }

    @Test
    void rejectsMalformedTokenWithoutHashingOrDelegating() {
        assertThrows(InvalidEmailChangeTokenException.class, () -> service.confirm(USER_ID, "malformed"));

        verify(tokenGenerator, never()).hash("malformed");
        verify(transaction, never()).confirm(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsMissingTokenOrAuthenticatedOwnerGenerically() {
        assertThrows(InvalidEmailChangeTokenException.class, () -> service.confirm(USER_ID, null));
        assertThrows(InvalidEmailChangeTokenException.class, () -> service.confirm(null, RAW_TOKEN));

        verify(tokenGenerator, never()).hash(org.mockito.ArgumentMatchers.any());
        verify(transaction, never()).confirm(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
