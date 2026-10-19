package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationNotFoundException;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceCandidate;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceSignal;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceWarning;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.application.port.DiscrepancyRepository;
import io.github.guillermodubon.invoward.reconciliation.application.port.LineItemMatchRepository;
import io.github.guillermodubon.invoward.reconciliation.application.port.ReconciliationLineRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionReason;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchMethod;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLine;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLineStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GetReconciliationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T15:00:00Z");
    private static final UUID ANALYSIS_ID = uuid(1);
    private static final AnalysisOwner OWNER = new RegisteredUserOwner(uuid(2));

    private final AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
    private final LineItemMatchRepository lineItemMatchRepository = mock(LineItemMatchRepository.class);
    private final ReconciliationLineRepository reconciliationLineRepository =
            mock(ReconciliationLineRepository.class);
    private final DiscrepancyRepository discrepancyRepository = mock(DiscrepancyRepository.class);
    private final CurrentDuplicateInvoiceWarningReader duplicateInvoiceWarningReader =
            mock(CurrentDuplicateInvoiceWarningReader.class);
    private final ReconciliationLinePositionReader linePositionReader =
            mock(ReconciliationLinePositionReader.class);

    private GetReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new GetReconciliationService(
                analysisRepository,
                lineItemMatchRepository,
                reconciliationLineRepository,
                discrepancyRepository,
                duplicateInvoiceWarningReader,
                linePositionReader,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void rejectsWrongOwnerBeforeReadingReconciliationOrDuplicateData() {
        when(analysisRepository.findOwnedById(ANALYSIS_ID, OWNER, NOW)).thenReturn(Optional.empty());

        assertThrows(AnalysisNotFoundException.class, () -> service.get(ANALYSIS_ID, OWNER));

        verifyNoInteractions(lineItemMatchRepository, reconciliationLineRepository,
                discrepancyRepository, duplicateInvoiceWarningReader, linePositionReader);
    }

    @Test
    void rejectsWrongGuestBeforeReadingReconciliationOrDuplicateData() {
        AnalysisOwner wrongGuest = new GuestSessionOwner(uuid(70), NOW.plusSeconds(3600));
        when(analysisRepository.findOwnedById(ANALYSIS_ID, wrongGuest, NOW)).thenReturn(Optional.empty());

        assertThrows(AnalysisNotFoundException.class, () -> service.get(ANALYSIS_ID, wrongGuest));

        verifyNoInteractions(lineItemMatchRepository, reconciliationLineRepository,
                discrepancyRepository, duplicateInvoiceWarningReader, linePositionReader);
    }

    @Test
    void rejectsExpiredGuestBeforeReadingReconciliationOrDuplicateData() {
        AnalysisOwner expiredGuest = new GuestSessionOwner(uuid(71), NOW.minusSeconds(1));
        when(analysisRepository.findOwnedById(ANALYSIS_ID, expiredGuest, NOW)).thenReturn(Optional.empty());

        assertThrows(AnalysisNotFoundException.class, () -> service.get(ANALYSIS_ID, expiredGuest));

        verifyNoInteractions(lineItemMatchRepository, reconciliationLineRepository,
                discrepancyRepository, duplicateInvoiceWarningReader, linePositionReader);
    }

    @Test
    void returnsNotFoundWhenOwnerVisibleAnalysisHasNoPersistedResult() {
        Analysis analysis = reconcilingAnalysis();
        when(analysisRepository.findOwnedById(ANALYSIS_ID, OWNER, NOW)).thenReturn(Optional.of(analysis));
        when(reconciliationLineRepository.countByAnalysisId(ANALYSIS_ID)).thenReturn(0L);
        when(discrepancyRepository.countByAnalysisId(ANALYSIS_ID)).thenReturn(0L);

        assertThrows(ReconciliationNotFoundException.class, () -> service.get(ANALYSIS_ID, OWNER));

        verifyNoInteractions(lineItemMatchRepository, duplicateInvoiceWarningReader, linePositionReader);
    }

    @Test
    void returnsCurrentResolutionSummaryStableRowsAndCurrentDuplicateWarningAfterReportCompletion() {
        Analysis analysis = withStatus(reconciledAnalysis(), AnalysisStatus.COMPLETED);
        ReconciliationLine referencePositionZero = line(
                uuid(10), uuid(21), ReconciliationLineStatus.MATCHED, NOW.minusSeconds(3));
        ReconciliationLine referencePositionOne = line(
                uuid(11), uuid(20), ReconciliationLineStatus.DIFFERENT, NOW.minusSeconds(4));
        ReconciliationLine referencePositionTwo = line(
                uuid(12), uuid(22), ReconciliationLineStatus.MISSING, NOW.minusSeconds(2));
        ReconciliationLine invoiceOnly = line(
                uuid(13), uuid(23), ReconciliationLineStatus.UNEXPECTED, NOW.minusSeconds(1));
        List<LineItemMatch> matches = List.of(
                match(uuid(20), uuid(31), uuid(40), LineMatchStatus.MATCHED),
                match(uuid(21), uuid(30), uuid(41), LineMatchStatus.MATCHED),
                match(uuid(22), uuid(32), null, LineMatchStatus.UNMATCHED_REFERENCE),
                match(uuid(23), null, uuid(42), LineMatchStatus.UNMATCHED_INVOICE));
        Discrepancy open = discrepancy(uuid(50), uuid(11), DiscrepancyType.UNIT_PRICE_MISMATCH,
                DiscrepancyResolutionStatus.OPEN, null, null, null, NOW.minusSeconds(3));
        Discrepancy accepted = discrepancy(uuid(51), uuid(10), DiscrepancyType.TOTAL_MISMATCH,
                DiscrepancyResolutionStatus.ACCEPTED, DiscrepancyResolutionReason.APPROVED_CHANGE,
                null, NOW.minusSeconds(2), NOW.minusSeconds(4));
        Discrepancy resolved = discrepancy(uuid(52), uuid(10), DiscrepancyType.QUANTITY_MISMATCH,
                DiscrepancyResolutionStatus.RESOLVED, DiscrepancyResolutionReason.OTHER,
                "Reviewed", NOW.minusSeconds(1), NOW.minusSeconds(5));
        Discrepancy documentLevel = discrepancy(uuid(53), null, DiscrepancyType.CURRENCY_MISMATCH,
                DiscrepancyResolutionStatus.OPEN, null, null, null, NOW.minusSeconds(6));
        DuplicateInvoiceWarning warning = new DuplicateInvoiceWarning(true, List.of(
                new DuplicateInvoiceCandidate(uuid(60), NOW.minusSeconds(60), "INV-100",
                        List.of(DuplicateInvoiceSignal.EXACT_INVOICE_FILE_HASH))));

        when(analysisRepository.findOwnedById(ANALYSIS_ID, OWNER, NOW)).thenReturn(Optional.of(analysis));
        when(reconciliationLineRepository.countByAnalysisId(ANALYSIS_ID)).thenReturn(4L);
        when(discrepancyRepository.countByAnalysisId(ANALYSIS_ID)).thenReturn(4L);
        when(lineItemMatchRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(matches);
        when(reconciliationLineRepository.findByAnalysisId(ANALYSIS_ID))
                .thenReturn(List.of(invoiceOnly, referencePositionTwo, referencePositionOne, referencePositionZero));
        when(discrepancyRepository.findByAnalysisId(ANALYSIS_ID))
                .thenReturn(List.of(documentLevel, resolved, open, accepted));
        when(linePositionReader.read(analysis)).thenReturn(Map.of(
                uuid(30), 0, uuid(31), 1, uuid(32), 2,
                uuid(40), 0, uuid(41), 1, uuid(42), 2));
        when(duplicateInvoiceWarningReader.read(analysis)).thenReturn(warning);

        ReconciliationResult result = service.get(ANALYSIS_ID, OWNER);

        assertEquals(AnalysisStatus.COMPLETED, result.analysisStatus());
        assertEquals(AnalysisReconciliationStatus.REVIEW_REQUIRED, result.reconciliationStatus());
        assertEquals(List.of(referencePositionZero, referencePositionOne, referencePositionTwo, invoiceOnly),
                result.lines());
        assertEquals(List.of(resolved, accepted, open, documentLevel), result.discrepancies());
        assertEquals(4, result.summary().lineCount());
        assertEquals(1, result.summary().matchedCount());
        assertEquals(1, result.summary().differentCount());
        assertEquals(1, result.summary().missingCount());
        assertEquals(1, result.summary().unexpectedCount());
        assertEquals(2, result.summary().openDiscrepancyCount());
        assertEquals(1, result.summary().acceptedDiscrepancyCount());
        assertEquals(1, result.summary().resolvedDiscrepancyCount());
        assertEquals(warning, result.duplicateInvoice());
        verify(duplicateInvoiceWarningReader).read(analysis);
        verify(linePositionReader).read(analysis);
        verify(analysisRepository).findOwnedById(ANALYSIS_ID, OWNER, NOW);
        verify(lineItemMatchRepository).findByAnalysisId(ANALYSIS_ID);
        verify(reconciliationLineRepository).countByAnalysisId(ANALYSIS_ID);
        verify(reconciliationLineRepository).findByAnalysisId(ANALYSIS_ID);
        verify(discrepancyRepository).countByAnalysisId(ANALYSIS_ID);
        verify(discrepancyRepository).findByAnalysisId(ANALYSIS_ID);
        verifyNoMoreInteractions(analysisRepository, lineItemMatchRepository,
                reconciliationLineRepository, discrepancyRepository,
                duplicateInvoiceWarningReader, linePositionReader);
    }

    @Test
    void readsPersistedSnapshotWhileReportIsQueuedRunningFailedOrCompleted() {
        Analysis generated = reconciledAnalysis();
        Analysis failed = withStatus(generated, AnalysisStatus.FAILED);
        Analysis completed = withStatus(generated, AnalysisStatus.COMPLETED);
        List<Analysis> lifecycleStates = List.of(generated, generated, failed, completed);
        List<AnalysisStatus> expectedStatuses = List.of(
                AnalysisStatus.GENERATING_REPORT,
                AnalysisStatus.GENERATING_REPORT,
                AnalysisStatus.FAILED,
                AnalysisStatus.COMPLETED);

        for (int index = 0; index < lifecycleStates.size(); index++) {
            reset(analysisRepository, lineItemMatchRepository, reconciliationLineRepository, discrepancyRepository,
                    duplicateInvoiceWarningReader, linePositionReader);
            Analysis analysis = lifecycleStates.get(index);
            LineItemMatch match = match(uuid(80), uuid(81), uuid(82), LineMatchStatus.MATCHED);
            ReconciliationLine line = line(uuid(83), match.id(), ReconciliationLineStatus.MATCHED, NOW);
            when(analysisRepository.findOwnedById(ANALYSIS_ID, OWNER, NOW)).thenReturn(Optional.of(analysis));
            when(reconciliationLineRepository.countByAnalysisId(ANALYSIS_ID)).thenReturn(1L);
            when(discrepancyRepository.countByAnalysisId(ANALYSIS_ID)).thenReturn(0L);
            when(lineItemMatchRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(match));
            when(reconciliationLineRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(line));
            when(discrepancyRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of());
            when(linePositionReader.read(analysis)).thenReturn(Map.of(uuid(81), 0, uuid(82), 0));
            when(duplicateInvoiceWarningReader.read(analysis)).thenReturn(DuplicateInvoiceWarning.none());

            ReconciliationResult result = service.get(ANALYSIS_ID, OWNER);

            assertEquals(expectedStatuses.get(index), result.analysisStatus());
            assertEquals(List.of(line), result.lines());
        }
    }

    @Test
    void rejectsIncompletePersistedSnapshotWithoutLookingUpDuplicateCandidates() {
        Analysis analysis = reconciledAnalysis();
        when(analysisRepository.findOwnedById(ANALYSIS_ID, OWNER, NOW)).thenReturn(Optional.of(analysis));
        when(reconciliationLineRepository.countByAnalysisId(ANALYSIS_ID)).thenReturn(1L);
        when(discrepancyRepository.countByAnalysisId(ANALYSIS_ID)).thenReturn(0L);
        when(lineItemMatchRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of());
        when(reconciliationLineRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of());
        when(discrepancyRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of());
        when(linePositionReader.read(analysis)).thenReturn(Map.of());

        assertThrows(ReconciliationConflictException.class, () -> service.get(ANALYSIS_ID, OWNER));

        verifyNoInteractions(duplicateInvoiceWarningReader);
    }

    @Test
    void rejectsMatchSetThatDoesNotCoverConfirmedSourceLines() {
        Analysis analysis = reconciledAnalysis();
        LineItemMatch match = match(uuid(20), uuid(30), uuid(40), LineMatchStatus.MATCHED);
        ReconciliationLine line = line(uuid(10), match.id(), ReconciliationLineStatus.MATCHED, NOW);
        when(analysisRepository.findOwnedById(ANALYSIS_ID, OWNER, NOW)).thenReturn(Optional.of(analysis));
        when(reconciliationLineRepository.countByAnalysisId(ANALYSIS_ID)).thenReturn(1L);
        when(discrepancyRepository.countByAnalysisId(ANALYSIS_ID)).thenReturn(0L);
        when(lineItemMatchRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(match));
        when(reconciliationLineRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(line));
        when(discrepancyRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of());
        when(linePositionReader.read(analysis)).thenReturn(Map.of(uuid(30), 0));

        assertThrows(ReconciliationConflictException.class, () -> service.get(ANALYSIS_ID, OWNER));

        verifyNoInteractions(duplicateInvoiceWarningReader);
    }

    private static Analysis reconcilingAnalysis() {
        return analysisBeforeReconciliation().beginReconciliation(NOW.minusSeconds(1));
    }

    private static Analysis reconciledAnalysis() {
        return reconcilingAnalysis().completeReconciliation(
                AnalysisReconciliationStatus.REVIEW_REQUIRED, BigDecimal.TEN, NOW);
    }

    private static Analysis withStatus(Analysis analysis, AnalysisStatus status) {
        return new Analysis(
                analysis.id(), analysis.owner(), status, analysis.reviewStatus(), analysis.reconciliationStatus(),
                analysis.supplierName(), analysis.supplierKey(), analysis.referenceType(), analysis.referenceNumber(),
                analysis.invoiceNumber(), analysis.currency(), analysis.referenceTotal(), analysis.invoicedTotal(),
                analysis.difference(), analysis.priceTolerance(), analysis.retryable(), analysis.failureCode(),
                analysis.failureUserMessage(), analysis.version(), analysis.completedAt(), analysis.expiresAt(),
                analysis.createdAt(), analysis.updatedAt());
    }

    private static Analysis analysisBeforeReconciliation() {
        Instant start = NOW.minusSeconds(20);
        return Analysis.create(ANALYSIS_ID, OWNER, PriceTolerance.exactMatch(), start)
                .transitionToUploading(start.plusSeconds(1))
                .markClassifying(start.plusSeconds(2))
                .awaitExtractionConfirmation(start.plusSeconds(3))
                .confirmExtraction("QUOTE", "Q-1", "INV-1", "Vendor", "USD",
                        new BigDecimal("100.0000"), new BigDecimal("110.0000"), start.plusSeconds(4))
                .awaitMatchReview(start.plusSeconds(5));
    }

    private static LineItemMatch match(UUID id, UUID referenceId, UUID invoiceId, LineMatchStatus status) {
        return new LineItemMatch(id, ANALYSIS_ID, referenceId, invoiceId, status,
                LineMatchMethod.SKU, BigDecimal.ONE, 0, NOW, NOW.minusSeconds(10), NOW.minusSeconds(10));
    }

    private static ReconciliationLine line(
            UUID id, UUID matchId, ReconciliationLineStatus status, Instant createdAt) {
        return new ReconciliationLine(id, ANALYSIS_ID, matchId, status,
                status == ReconciliationLineStatus.UNEXPECTED ? null : "SKU",
                status == ReconciliationLineStatus.MISSING ? null : "SKU",
                status == ReconciliationLineStatus.UNEXPECTED ? null : "Reference item",
                status == ReconciliationLineStatus.MISSING ? null : "Invoice item",
                status == ReconciliationLineStatus.UNEXPECTED ? null : BigDecimal.ONE,
                status == ReconciliationLineStatus.MISSING ? null : BigDecimal.ONE,
                status == ReconciliationLineStatus.UNEXPECTED ? null : BigDecimal.TEN,
                status == ReconciliationLineStatus.MISSING ? null : BigDecimal.TEN,
                status == ReconciliationLineStatus.UNEXPECTED ? null : BigDecimal.TEN,
                status == ReconciliationLineStatus.MISSING ? null : BigDecimal.TEN,
                status == ReconciliationLineStatus.MATCHED || status == ReconciliationLineStatus.DIFFERENT
                        ? BigDecimal.ZERO : null,
                status == ReconciliationLineStatus.MATCHED || status == ReconciliationLineStatus.DIFFERENT
                        ? BigDecimal.ZERO : null,
                status == ReconciliationLineStatus.MATCHED || status == ReconciliationLineStatus.DIFFERENT
                        ? BigDecimal.ZERO : null,
                createdAt);
    }

    private static Discrepancy discrepancy(
            UUID id,
            UUID lineId,
            DiscrepancyType type,
            DiscrepancyResolutionStatus status,
            DiscrepancyResolutionReason reason,
            String note,
            Instant resolvedAt,
            Instant createdAt) {
        Instant updatedAt = status == DiscrepancyResolutionStatus.OPEN ? createdAt : resolvedAt;
        return new Discrepancy(id, ANALYSIS_ID, lineId, type,
                "100", "110", BigDecimal.TEN, null, "The document totals differ.",
                status, reason, note, 0, resolvedAt, createdAt, updatedAt);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
