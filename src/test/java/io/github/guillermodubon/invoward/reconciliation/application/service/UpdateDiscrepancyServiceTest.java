package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.reconciliation.application.model.DiscrepancyResolutionUpdate;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionReason;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpdateDiscrepancyServiceTest {

    @Test
    void delegatesResolutionUpdateAndReturnsThePersistedDiscrepancy() {
        UpdateDiscrepancyTransaction transaction = mock(UpdateDiscrepancyTransaction.class);
        UpdateDiscrepancyService service = new UpdateDiscrepancyService(transaction);
        UUID analysisId = UUID.randomUUID();
        UUID discrepancyId = UUID.randomUUID();
        AnalysisOwner owner = new RegisteredUserOwner(UUID.randomUUID());
        DiscrepancyResolutionUpdate command = new DiscrepancyResolutionUpdate(
                2, DiscrepancyResolutionStatus.RESOLVED,
                DiscrepancyResolutionReason.SUPPLIER_CORRECTION_EXPECTED, null);
        Instant now = Instant.parse("2026-10-11T12:00:00Z");
        Discrepancy persisted = new Discrepancy(
                discrepancyId, analysisId, null, DiscrepancyType.TOTAL_MISMATCH,
                "100", "110", BigDecimal.TEN, null, "The invoice total differs.",
                DiscrepancyResolutionStatus.RESOLVED, DiscrepancyResolutionReason.SUPPLIER_CORRECTION_EXPECTED,
                null, 3, now, now.minusSeconds(10), now);
        when(transaction.update(analysisId, owner, discrepancyId, command)).thenReturn(persisted);

        assertSame(persisted, service.update(analysisId, owner, discrepancyId, command));

        verify(transaction).update(analysisId, owner, discrepancyId, command);
    }
}
