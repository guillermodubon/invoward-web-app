package io.github.guillermodubon.invoward.analysis.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisReviewNotAllowedException;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisReviewStatusResult;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChangeAnalysisReviewStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final Instant CREATED_AT = NOW.minusSeconds(60);
    private static final Instant COMPLETED_AT = NOW.minusSeconds(10);
    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final RegisteredUserOwner OWNER = new RegisteredUserOwner(USER_ID);

    private final AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
    private final ChangeAnalysisReviewStatusService service = new ChangeAnalysisReviewStatusService(
            analysisRepository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void marksCompletedAnalysisReviewedAndReturnsOnlyReviewProjection() {
        Analysis current = completed(AnalysisReviewStatus.PENDING, null);
        Analysis changed = current.changeReviewStatus(AnalysisReviewStatus.REVIEWED, NOW);
        Analysis persisted = copyWithVersion(changed, 1);
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW)).thenReturn(Optional.of(current));
        when(analysisRepository.update(changed)).thenReturn(persisted);

        AnalysisReviewStatusResult result = service.change(ANALYSIS_ID, USER_ID, AnalysisReviewStatus.REVIEWED);

        assertEquals(new AnalysisReviewStatusResult(ANALYSIS_ID, AnalysisReviewStatus.REVIEWED, NOW), result);
        assertEquals(AnalysisStatus.COMPLETED, persisted.status());
        assertEquals(AnalysisReconciliationStatus.REVIEW_REQUIRED, persisted.reconciliationStatus());
        assertEquals(current.supplierName(), persisted.supplierName());
        assertEquals(current.difference(), persisted.difference());
        assertEquals(current.referenceTotal(), persisted.referenceTotal());
        assertEquals(current.invoicedTotal(), persisted.invoicedTotal());
        assertEquals(current.completedAt(), persisted.completedAt());
        assertEquals(1, persisted.version());

        var order = inOrder(analysisRepository);
        order.verify(analysisRepository).findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW);
        order.verify(analysisRepository).update(changed);
        order.verifyNoMoreInteractions();
    }

    @Test
    void revertsReviewedAnalysisToPendingAndClearsReviewedAt() {
        Instant previousReview = NOW.minusSeconds(20);
        Analysis current = completed(AnalysisReviewStatus.REVIEWED, previousReview);
        Analysis changed = current.changeReviewStatus(AnalysisReviewStatus.PENDING, NOW);
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW)).thenReturn(Optional.of(current));
        when(analysisRepository.update(changed)).thenReturn(changed);

        AnalysisReviewStatusResult result = service.change(ANALYSIS_ID, USER_ID, AnalysisReviewStatus.PENDING);

        assertEquals(new AnalysisReviewStatusResult(ANALYSIS_ID, AnalysisReviewStatus.PENDING, null), result);
        assertNull(changed.reviewedAt());
        assertEquals(NOW, changed.updatedAt());
        verify(analysisRepository).update(changed);
    }

    @Test
    void sameStatusIsNoOpAndPreservesLegacyUnknownReviewTime() {
        Analysis legacyReviewed = completed(AnalysisReviewStatus.REVIEWED, null);
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW))
                .thenReturn(Optional.of(legacyReviewed));

        AnalysisReviewStatusResult result = service.change(ANALYSIS_ID, USER_ID, AnalysisReviewStatus.REVIEWED);

        assertEquals(AnalysisReviewStatusResult.from(legacyReviewed), result);
        verify(analysisRepository, never()).update(any(Analysis.class));
    }

    @Test
    void samePendingStatusDoesNotWriteOrAdvanceAggregateTimestamps() {
        Analysis pending = completed(AnalysisReviewStatus.PENDING, null);
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW)).thenReturn(Optional.of(pending));

        assertEquals(AnalysisReviewStatusResult.from(pending),
                service.change(ANALYSIS_ID, USER_ID, AnalysisReviewStatus.PENDING));

        verify(analysisRepository, never()).update(any(Analysis.class));
    }

    @Test
    void foreignOrGuestOwnerIsNotFoundBeforeAnyMutation() {
        Analysis guestAnalysis = new Analysis(
                ANALYSIS_ID,
                new GuestSessionOwner(UUID.randomUUID(), NOW.plusSeconds(3600)),
                AnalysisStatus.COMPLETED,
                AnalysisReviewStatus.PENDING,
                null,
                AnalysisReconciliationStatus.MATCHED,
                null, null, null, null, null, null,
                null, null, null,
                PriceTolerance.exactMatch(),
                false, null, null, 0,
                COMPLETED_AT,
                NOW.plusSeconds(3600),
                CREATED_AT,
                COMPLETED_AT);
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW))
                .thenReturn(Optional.of(guestAnalysis));

        assertThrows(AnalysisNotFoundException.class,
                () -> service.change(ANALYSIS_ID, USER_ID, AnalysisReviewStatus.REVIEWED));

        verify(analysisRepository, never()).update(any(Analysis.class));
    }

    @Test
    void nonCompletedAnalysisIsNotAllowedAndIsNotUpdated() {
        Analysis incomplete = Analysis.create(ANALYSIS_ID, OWNER, PriceTolerance.exactMatch(), CREATED_AT);
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW)).thenReturn(Optional.of(incomplete));

        assertThrows(AnalysisReviewNotAllowedException.class,
                () -> service.change(ANALYSIS_ID, USER_ID, AnalysisReviewStatus.REVIEWED));

        verify(analysisRepository, never()).update(any(Analysis.class));
    }

    @Test
    void failedAnalysisIsNotAllowed() {
        Analysis failed = new Analysis(
                ANALYSIS_ID, OWNER, AnalysisStatus.FAILED, AnalysisReviewStatus.PENDING, null,
                null, null, null, null, null, null, null, null, null, null,
                PriceTolerance.exactMatch(), false, "PROCESSING_FAILURE", "Processing failed safely.", 0,
                null, null, CREATED_AT, CREATED_AT);
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW)).thenReturn(Optional.of(failed));

        assertThrows(AnalysisReviewNotAllowedException.class,
                () -> service.change(ANALYSIS_ID, USER_ID, AnalysisReviewStatus.REVIEWED));

        verify(analysisRepository, never()).update(any(Analysis.class));
    }

    @Test
    void missingOrForeignAnalysisIsNotFound() {
        when(analysisRepository.findOwnedByIdForUpdate(ANALYSIS_ID, OWNER, NOW)).thenReturn(Optional.empty());

        assertThrows(AnalysisNotFoundException.class,
                () -> service.change(ANALYSIS_ID, USER_ID, AnalysisReviewStatus.REVIEWED));

        verify(analysisRepository, never()).update(any(Analysis.class));
    }

    @Test
    void serviceOwnsAnAtomicWriteTransaction() throws NoSuchMethodException {
        Transactional transaction = ChangeAnalysisReviewStatusService.class
                .getMethod("change", UUID.class, UUID.class, AnalysisReviewStatus.class)
                .getAnnotation(Transactional.class);

        assertTrue(transaction != null);
        assertTrue(!transaction.readOnly());
    }

    private static Analysis completed(AnalysisReviewStatus reviewStatus, Instant reviewedAt) {
        return new Analysis(
                ANALYSIS_ID, OWNER, AnalysisStatus.COMPLETED, reviewStatus, reviewedAt,
                AnalysisReconciliationStatus.REVIEW_REQUIRED,
                "Northwind Supplies", "northwind supplies", "QUOTE", "Q-42", "INV-42", "USD",
                new BigDecimal("100.0000"), new BigDecimal("115.0000"), new BigDecimal("15.0000"),
                PriceTolerance.exactMatch(), false, null, null, 0,
                COMPLETED_AT, null, CREATED_AT, COMPLETED_AT);
    }

    private static Analysis copyWithVersion(Analysis analysis, long version) {
        return new Analysis(
                analysis.id(), analysis.owner(), analysis.status(), analysis.reviewStatus(), analysis.reviewedAt(),
                analysis.reconciliationStatus(), analysis.supplierName(), analysis.supplierKey(),
                analysis.referenceType(), analysis.referenceNumber(), analysis.invoiceNumber(), analysis.currency(),
                analysis.referenceTotal(), analysis.invoicedTotal(), analysis.difference(), analysis.priceTolerance(),
                analysis.retryable(), analysis.failureCode(), analysis.failureUserMessage(), version,
                analysis.completedAt(), analysis.expiresAt(), analysis.createdAt(), analysis.updatedAt());
    }
}
