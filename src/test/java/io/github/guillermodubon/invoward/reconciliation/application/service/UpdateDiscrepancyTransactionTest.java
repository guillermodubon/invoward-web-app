package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.reconciliation.application.exception.DiscrepancyConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.DiscrepancyNotFoundException;
import io.github.guillermodubon.invoward.reconciliation.application.model.DiscrepancyResolutionUpdate;
import io.github.guillermodubon.invoward.reconciliation.application.port.DiscrepancyRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionReason;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class UpdateDiscrepancyTransactionTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final UUID DISCREPANCY_ID = UUID.randomUUID();
    private static final AnalysisOwner OWNER = new RegisteredUserOwner(UUID.randomUUID());

    private final AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
    private final DiscrepancyRepository discrepancyRepository = mock(DiscrepancyRepository.class);
    private final UpdateDiscrepancyTransaction transaction = new UpdateDiscrepancyTransaction(
            analysisRepository,
            discrepancyRepository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void appliesEveryApprovedTransitionAndPersistsOnlyResolutionFields() {
        assertTransition(DiscrepancyResolutionStatus.OPEN, DiscrepancyResolutionStatus.ACCEPTED);
        assertTransition(DiscrepancyResolutionStatus.OPEN, DiscrepancyResolutionStatus.RESOLVED);
        assertTransition(DiscrepancyResolutionStatus.ACCEPTED, DiscrepancyResolutionStatus.RESOLVED);
        assertTransition(DiscrepancyResolutionStatus.RESOLVED, DiscrepancyResolutionStatus.ACCEPTED);
        assertTransition(DiscrepancyResolutionStatus.ACCEPTED, DiscrepancyResolutionStatus.OPEN);
        assertTransition(DiscrepancyResolutionStatus.RESOLVED, DiscrepancyResolutionStatus.OPEN);
    }

    @Test
    void authorizesAnalysisBeforeLookingUpDiscrepancy() {
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW)).thenReturn(Optional.empty());

        assertThrows(AnalysisNotFoundException.class, () -> transaction.update(
                ANALYSIS_ID, OWNER, DISCREPANCY_ID, update(0, DiscrepancyResolutionStatus.ACCEPTED,
                        DiscrepancyResolutionReason.APPROVED_CHANGE, null)));

        verify(analysisRepository).findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW);
        verifyNoMoreInteractions(analysisRepository);
        verifyNoInteractions(discrepancyRepository);
    }

    @Test
    void reportsDiscrepancyNotFoundWhenItIsAbsentOrBelongsToAnotherAnalysis() {
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW))
                .thenReturn(Optional.of(reconciledAnalysis()));
        when(discrepancyRepository.findByIdAndAnalysisIdForUpdate(DISCREPANCY_ID, ANALYSIS_ID))
                .thenReturn(Optional.empty());

        assertThrows(DiscrepancyNotFoundException.class, () -> transaction.update(
                ANALYSIS_ID, OWNER, DISCREPANCY_ID, update(0, DiscrepancyResolutionStatus.ACCEPTED,
                        DiscrepancyResolutionReason.APPROVED_CHANGE, null)));

        InOrder order = inOrder(analysisRepository, discrepancyRepository);
        order.verify(analysisRepository).findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW);
        order.verify(discrepancyRepository).findByIdAndAnalysisIdForUpdate(DISCREPANCY_ID, ANALYSIS_ID);
        verifyNoMoreInteractions(analysisRepository, discrepancyRepository);
    }

    @Test
    void rejectsStaleVersionsAndInvalidTransitionsWithoutWriting() {
        Discrepancy current = discrepancy(DiscrepancyResolutionStatus.OPEN, 4);
        arrange(current);

        assertThrows(DiscrepancyConflictException.class, () -> transaction.update(
                ANALYSIS_ID, OWNER, DISCREPANCY_ID,
                update(3, DiscrepancyResolutionStatus.ACCEPTED, DiscrepancyResolutionReason.APPROVED_CHANGE, null)));
        assertThrows(DiscrepancyConflictException.class, () -> transaction.update(
                ANALYSIS_ID, OWNER, DISCREPANCY_ID,
                update(4, DiscrepancyResolutionStatus.OPEN, null, null)));

        verify(discrepancyRepository, never()).update(any());
    }

    @Test
    void requiresAnOtherNoteAndDoesNotTouchAnalysisState() {
        Discrepancy current = discrepancy(DiscrepancyResolutionStatus.OPEN, 0);
        arrange(current);

        assertThrows(IllegalArgumentException.class, () -> transaction.update(
                ANALYSIS_ID, OWNER, DISCREPANCY_ID,
                update(0, DiscrepancyResolutionStatus.RESOLVED, DiscrepancyResolutionReason.OTHER, "  ")));

        verify(analysisRepository).findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW);
        verifyNoMoreInteractions(analysisRepository);
        verify(discrepancyRepository).findByIdAndAnalysisIdForUpdate(DISCREPANCY_ID, ANALYSIS_ID);
        verify(discrepancyRepository, never()).update(any());
    }

    private void assertTransition(
            DiscrepancyResolutionStatus currentStatus,
            DiscrepancyResolutionStatus targetStatus) {
        AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
        DiscrepancyRepository discrepancyRepository = mock(DiscrepancyRepository.class);
        UpdateDiscrepancyTransaction transaction = new UpdateDiscrepancyTransaction(
                analysisRepository, discrepancyRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        Discrepancy current = discrepancy(currentStatus, 7);
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW))
                .thenReturn(Optional.of(reconciledAnalysis()));
        when(discrepancyRepository.findByIdAndAnalysisIdForUpdate(DISCREPANCY_ID, ANALYSIS_ID))
                .thenReturn(Optional.of(current));
        when(discrepancyRepository.update(any(Discrepancy.class))).thenAnswer(invocation -> {
            Discrepancy transitioned = invocation.getArgument(0);
            return Optional.of(withVersion(transitioned, current.version() + 1));
        });
        DiscrepancyResolutionReason targetReason = DiscrepancyResolutionReason.OTHER;
        String targetNote = targetStatus == DiscrepancyResolutionStatus.OPEN ? "ignored on reopen" : "reviewed";

        Discrepancy updated = transaction.update(ANALYSIS_ID, OWNER, DISCREPANCY_ID,
                update(current.version(), targetStatus, targetReason, targetNote));

        assertEquals(8, updated.version());
        assertEquals(targetStatus, updated.resolutionStatus());
        assertEquals("100.00", updated.referenceValue());
        assertEquals("110.00", updated.invoiceValue());
        assertEquals(new BigDecimal("10.0000"), updated.absoluteDifference());
        assertEquals("The invoice total differs.", updated.explanation());
        assertEquals(NOW, updated.updatedAt());
        if (targetStatus == DiscrepancyResolutionStatus.OPEN) {
            assertNull(updated.resolutionReason());
            assertNull(updated.resolutionNote());
            assertNull(updated.resolvedAt());
        } else {
            assertEquals(targetReason, updated.resolutionReason());
            assertEquals("reviewed", updated.resolutionNote());
            assertEquals(NOW, updated.resolvedAt());
        }

        InOrder order = inOrder(analysisRepository, discrepancyRepository);
        order.verify(analysisRepository).findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW);
        order.verify(discrepancyRepository).findByIdAndAnalysisIdForUpdate(DISCREPANCY_ID, ANALYSIS_ID);
        order.verify(discrepancyRepository).update(any(Discrepancy.class));
        verifyNoMoreInteractions(analysisRepository, discrepancyRepository);
    }

    private void arrange(Discrepancy current) {
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW))
                .thenReturn(Optional.of(reconciledAnalysis()));
        when(discrepancyRepository.findByIdAndAnalysisIdForUpdate(DISCREPANCY_ID, ANALYSIS_ID))
                .thenReturn(Optional.of(current));
        when(discrepancyRepository.update(any(Discrepancy.class))).thenAnswer(invocation -> {
            Discrepancy transitioned = invocation.getArgument(0);
            return Optional.of(withVersion(transitioned, current.version() + 1));
        });
    }

    private static Discrepancy withVersion(Discrepancy value, long version) {
        return new Discrepancy(value.id(), value.analysisId(), value.reconciliationLineId(), value.type(),
                value.referenceValue(), value.invoiceValue(), value.absoluteDifference(), value.percentageDifference(),
                value.explanation(), value.resolutionStatus(), value.resolutionReason(), value.resolutionNote(), version,
                value.resolvedAt(), value.createdAt(), value.updatedAt());
    }

    private static Discrepancy discrepancy(DiscrepancyResolutionStatus status, long version) {
        Instant createdAt = NOW.minusSeconds(100);
        Instant updatedAt = NOW.minusSeconds(10);
        boolean open = status == DiscrepancyResolutionStatus.OPEN;
        return new Discrepancy(DISCREPANCY_ID, ANALYSIS_ID, null, DiscrepancyType.TOTAL_MISMATCH,
                "100.00", "110.00", new BigDecimal("10.0000"), null, "The invoice total differs.",
                status, open ? null : DiscrepancyResolutionReason.APPROVED_CHANGE,
                open ? null : "previous review", version, open ? null : updatedAt, createdAt, updatedAt);
    }

    private static Analysis reconciledAnalysis() {
        Instant createdAt = NOW.minusSeconds(30);
        Analysis analysis = Analysis.create(ANALYSIS_ID, OWNER, PriceTolerance.exactMatch(), createdAt);
        analysis = analysis.transitionToUploading(createdAt.plusSeconds(1));
        analysis = analysis.markClassifying(createdAt.plusSeconds(2));
        analysis = analysis.awaitExtractionConfirmation(createdAt.plusSeconds(3));
        analysis = analysis.confirmExtraction("QUOTE", "Q-1", "I-1", "Example Supplier", "USD",
                BigDecimal.TEN, BigDecimal.TEN, createdAt.plusSeconds(4));
        analysis = analysis.beginReconciliation(createdAt.plusSeconds(5));
        return analysis.completeReconciliation(
                AnalysisReconciliationStatus.REVIEW_REQUIRED, BigDecimal.ZERO, createdAt.plusSeconds(6));
    }

    private static DiscrepancyResolutionUpdate update(
            long version,
            DiscrepancyResolutionStatus status,
            DiscrepancyResolutionReason reason,
            String note) {
        return new DiscrepancyResolutionUpdate(version, status, reason, note);
    }
}
