package io.github.guillermodubon.invoward.sharing.application.service;

import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.sharing.application.exception.DuplicateShareTokenHashException;
import io.github.guillermodubon.invoward.sharing.application.exception.ShareLinkCreationException;
import io.github.guillermodubon.invoward.sharing.application.model.CreatedShareLink;
import io.github.guillermodubon.invoward.sharing.application.model.ShareLinkExpiryChoice;
import io.github.guillermodubon.invoward.sharing.application.port.ShareTokenGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateShareLinkServiceTest {

    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final RegisteredUserOwner OWNER = new RegisteredUserOwner(UUID.randomUUID());
    private static final ShareLinkExpiryChoice EXPIRY = ShareLinkExpiryChoice.HOURS_24;

    private final CreateShareLinkTransaction transaction = mock(CreateShareLinkTransaction.class);
    private final CreateShareLinkService service = new CreateShareLinkService(transaction);

    @Test
    void retriesAUniqueHashCollisionInASeparateTransactionAndReturnsOnlySuccessfulResult() {
        CreatedShareLink expected = created();
        when(transaction.create(ANALYSIS_ID, OWNER, EXPIRY))
                .thenThrow(new DuplicateShareTokenHashException())
                .thenReturn(expected);

        assertEquals(expected, service.create(ANALYSIS_ID, OWNER, EXPIRY));

        verify(transaction, times(2)).create(ANALYSIS_ID, OWNER, EXPIRY);
    }

    @Test
    void stopsAfterTheApprovedBoundedNumberOfDatabaseCollisionAttempts() {
        when(transaction.create(ANALYSIS_ID, OWNER, EXPIRY))
                .thenThrow(new DuplicateShareTokenHashException());

        ShareLinkCreationException failure = assertThrows(
                ShareLinkCreationException.class, () -> service.create(ANALYSIS_ID, OWNER, EXPIRY));

        assertFalse(failure.getMessage().contains("token"));
        verify(transaction, times(ShareTokenGenerator.MAX_GENERATION_ATTEMPTS))
                .create(ANALYSIS_ID, OWNER, EXPIRY);
    }

    @Test
    void facadeIsNotTransactionalSoEachCollisionTransactionRollsBackBeforeRetry() throws NoSuchMethodException {
        assertFalse(CreateShareLinkService.class
                .getMethod("create", UUID.class, io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner.class,
                        ShareLinkExpiryChoice.class)
                .isAnnotationPresent(Transactional.class));
        assertEquals(3, ShareTokenGenerator.MAX_GENERATION_ATTEMPTS);
    }

    private static CreatedShareLink created() {
        Instant createdAt = Instant.parse("2026-10-25T12:00:00Z");
        return new CreatedShareLink(UUID.randomUUID(), "https://app.example.test/api/public/reports/token",
                EXPIRY, createdAt, createdAt.plusSeconds(86_400), null);
    }
}
