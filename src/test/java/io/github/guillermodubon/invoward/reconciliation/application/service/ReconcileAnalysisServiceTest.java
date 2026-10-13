package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionSource;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceCandidate;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceSignal;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceWarning;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationInput;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationPersistenceResult;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationPlan;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.application.port.DiscrepancyRepository;
import io.github.guillermodubon.invoward.reconciliation.application.port.LineItemMatchRepository;
import io.github.guillermodubon.invoward.reconciliation.application.port.ReconciliationLineRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchMethod;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLine;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLineStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReconcileAnalysisServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T15:00:00Z");
    private static final UUID ANALYSIS_ID = uuid(1);
    private static final UUID OWNER_ID = uuid(2);
    private static final AnalysisOwner OWNER = new RegisteredUserOwner(OWNER_ID);

    private final AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
    private final AnalysisJobRepository analysisJobRepository = mock(AnalysisJobRepository.class);
    private final LineItemMatchRepository lineItemMatchRepository = mock(LineItemMatchRepository.class);
    private final ReconciliationLineRepository reconciliationLineRepository = mock(ReconciliationLineRepository.class);
    private final DiscrepancyRepository discrepancyRepository = mock(DiscrepancyRepository.class);
    private final ReconciliationInputAssembler inputAssembler = mock(ReconciliationInputAssembler.class);
    private final DuplicateInvoiceDetector duplicateInvoiceDetector = mock(DuplicateInvoiceDetector.class);
    private final CurrentDuplicateInvoiceWarningReader duplicateInvoiceWarningReader =
            mock(CurrentDuplicateInvoiceWarningReader.class);
    private final ReconciliationLinePositionReader linePositionReader =
            mock(ReconciliationLinePositionReader.class);
    private final PersistReconciliationTransaction persistenceTransaction =
            mock(PersistReconciliationTransaction.class);

    private ReconcileAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new ReconcileAnalysisService(
                analysisRepository,
                analysisJobRepository,
                lineItemMatchRepository,
                reconciliationLineRepository,
                discrepancyRepository,
                inputAssembler,
                duplicateInvoiceDetector,
                duplicateInvoiceWarningReader,
                linePositionReader,
                persistenceTransaction,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void wrongOwnerIsRejectedBeforeReconciliationDataIsRead() {
        when(analysisRepository.findOwnedById(ANALYSIS_ID, OWNER, NOW)).thenReturn(Optional.empty());

        assertThrows(AnalysisNotFoundException.class, () -> service.reconcile(ANALYSIS_ID, OWNER));

        verifyNoInteractions(analysisJobRepository, lineItemMatchRepository, reconciliationLineRepository,
                discrepancyRepository, inputAssembler, duplicateInvoiceDetector,
                duplicateInvoiceWarningReader, linePositionReader, persistenceTransaction);
    }

    @Test
    void activeAnalysisRunsDeterministicEnginesAndPersistsOnePlan() {
        Fixture fixture = fixture();
        stubReady(fixture, DuplicateInvoiceWarning.none());
        stubPersistence();

        ReconciliationResult result = service.reconcile(ANALYSIS_ID, OWNER);

        assertEquals(AnalysisStatus.GENERATING_REPORT, result.analysisStatus());
        assertEquals(AnalysisReconciliationStatus.MATCHED, result.reconciliationStatus());
        assertEquals(BigDecimal.ZERO.setScale(4), result.difference());
        assertTrue(result.duplicateInvoice().candidates().isEmpty());
        assertEquals(1, result.summary().lineCount());
        assertEquals(1, result.summary().matchedCount());
        assertEquals(0, result.summary().openDiscrepancyCount());

        ArgumentCaptor<ReconciliationPlan> planCaptor = ArgumentCaptor.forClass(ReconciliationPlan.class);
        ArgumentCaptor<AnalysisReconciliationStatus> statusCaptor =
                ArgumentCaptor.forClass(AnalysisReconciliationStatus.class);
        verify(persistenceTransaction).persist(eq(OWNER), eq(fixture.input()), planCaptor.capture(),
                statusCaptor.capture());
        assertEquals(1, planCaptor.getValue().lines().size());
        assertTrue(planCaptor.getValue().discrepancies().isEmpty());
        assertTrue(planCaptor.getValue().evidence().isEmpty());
        assertEquals(AnalysisReconciliationStatus.MATCHED, statusCaptor.getValue());
    }

    @Test
    void duplicateWarningAloneRequiresReviewWithoutCreatingDiscrepancy() {
        Fixture fixture = fixture();
        DuplicateInvoiceWarning warning = new DuplicateInvoiceWarning(true, List.of(
                new DuplicateInvoiceCandidate(uuid(3), NOW.minusSeconds(60), "INV-1",
                        List.of(DuplicateInvoiceSignal.EXACT_INVOICE_FILE_HASH))));
        stubReady(fixture, warning);
        stubPersistence();

        ReconciliationResult result = service.reconcile(ANALYSIS_ID, OWNER);

        assertEquals(AnalysisReconciliationStatus.REVIEW_REQUIRED, result.reconciliationStatus());
        assertEquals(warning, result.duplicateInvoice());
        assertTrue(result.discrepancies().isEmpty());
        ArgumentCaptor<AnalysisReconciliationStatus> statusCaptor =
                ArgumentCaptor.forClass(AnalysisReconciliationStatus.class);
        verify(persistenceTransaction).persist(eq(OWNER), eq(fixture.input()), any(ReconciliationPlan.class),
                statusCaptor.capture());
        assertEquals(AnalysisReconciliationStatus.REVIEW_REQUIRED, statusCaptor.getValue());
    }

    @Test
    void persistedCompleteResultIsReturnedWithoutReassemblingOrRecomputing() {
        Fixture fixture = fixture();
        Analysis completed = fixture.input().analysis().completeReconciliation(
                AnalysisReconciliationStatus.MATCHED, BigDecimal.ZERO.setScale(4), NOW);
        AnalysisJob completedJob = fixture.job().awaitReportGeneration(NOW);
        ReconciliationLine line = reconciledLine(fixture.match());
        when(analysisRepository.findOwnedById(ANALYSIS_ID, OWNER, NOW)).thenReturn(Optional.of(completed));
        when(reconciliationLineRepository.countByAnalysisId(ANALYSIS_ID)).thenReturn(1L);
        when(discrepancyRepository.countByAnalysisId(ANALYSIS_ID)).thenReturn(0L);
        when(analysisJobRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(Optional.of(completedJob));
        when(lineItemMatchRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(fixture.match()));
        when(reconciliationLineRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(line));
        when(discrepancyRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of());
        when(duplicateInvoiceWarningReader.read(completed))
                .thenReturn(DuplicateInvoiceWarning.none());
        when(linePositionReader.read(completed)).thenReturn(Map.of(uuid(5), 0, uuid(6), 0));

        ReconciliationResult result = service.reconcile(ANALYSIS_ID, OWNER);

        assertEquals(AnalysisStatus.GENERATING_REPORT, result.analysisStatus());
        assertEquals(List.of(line), result.lines());
        assertEquals(AnalysisReconciliationStatus.MATCHED, result.reconciliationStatus());
        verifyNoInteractions(inputAssembler, persistenceTransaction);
    }

    @Test
    void partialPersistedRowsFailAsConflictInsteadOfBeingRebuilt() {
        Fixture fixture = fixture();
        when(analysisRepository.findOwnedById(ANALYSIS_ID, OWNER, NOW))
                .thenReturn(Optional.of(fixture.input().analysis()));
        when(reconciliationLineRepository.countByAnalysisId(ANALYSIS_ID)).thenReturn(1L);
        when(discrepancyRepository.countByAnalysisId(ANALYSIS_ID)).thenReturn(0L);

        assertThrows(ReconciliationConflictException.class, () -> service.reconcile(ANALYSIS_ID, OWNER));

        verifyNoInteractions(inputAssembler, duplicateInvoiceDetector, linePositionReader, persistenceTransaction);
    }

    private void stubReady(Fixture fixture, DuplicateInvoiceWarning warning) {
        when(analysisRepository.findOwnedById(ANALYSIS_ID, OWNER, NOW))
                .thenReturn(Optional.of(fixture.input().analysis()));
        when(reconciliationLineRepository.countByAnalysisId(ANALYSIS_ID)).thenReturn(0L);
        when(discrepancyRepository.countByAnalysisId(ANALYSIS_ID)).thenReturn(0L);
        when(analysisJobRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(Optional.of(fixture.job()));
        when(inputAssembler.assemble(ANALYSIS_ID, OWNER)).thenReturn(fixture.input());
        when(duplicateInvoiceDetector.detect(
                fixture.input().analysis(), fixture.input().invoiceDocument(),
                fixture.input().invoiceSource().extraction())).thenReturn(warning);
    }

    private void stubPersistence() {
        when(persistenceTransaction.persist(any(), any(), any(), any())).thenAnswer(invocation -> {
            ReconciliationInput input = invocation.getArgument(1);
            ReconciliationPlan plan = invocation.getArgument(2);
            AnalysisReconciliationStatus status = invocation.getArgument(3);
            Analysis completed = input.analysis().completeReconciliation(status, plan.analysisDifference(), NOW);
            return new ReconciliationPersistenceResult(true, completed, plan.lines(), plan.discrepancies());
        });
    }

    private static Fixture fixture() {
        Instant createdAt = NOW.minusSeconds(100);
        AnalysisOwner owner = OWNER;
        Analysis analysis = Analysis.create(ANALYSIS_ID, owner, PriceTolerance.exactMatch(), createdAt)
                .transitionToUploading(createdAt.plusSeconds(1))
                .markClassifying(createdAt.plusSeconds(2))
                .awaitExtractionConfirmation(createdAt.plusSeconds(3))
                .confirmExtraction("QUOTE", "Q-1", "INV-1", "Vendor", "USD",
                        BigDecimal.TEN, BigDecimal.TEN, createdAt.plusSeconds(4))
                .awaitMatchReview(createdAt.plusSeconds(5))
                .beginReconciliation(createdAt.plusSeconds(6));
        AnalysisJob job = AnalysisJob.waitingForUser(uuid(4), ANALYSIS_ID, createdAt)
                .awaitMoreUploads(createdAt.plusSeconds(1))
                .waitForUserAtClassification(createdAt.plusSeconds(2))
                .waitForExtractionConfirmation(createdAt.plusSeconds(3))
                .waitForMatching(createdAt.plusSeconds(4))
                .waitForMatchReview(createdAt.plusSeconds(5))
                .waitForReconciliation(createdAt.plusSeconds(6));

        Document reference = document(DocumentRole.REFERENCE, DocumentType.QUOTE, "b");
        Document invoice = document(DocumentRole.INVOICE, DocumentType.INVOICE, "a");
        ExtractedLineItem referenceLine = line();
        ExtractedLineItem invoiceLine = line();
        PersistedExtraction referenceExtraction = extraction(reference, referenceLine, uuid(5));
        PersistedExtraction invoiceExtraction = extraction(invoice, invoiceLine, uuid(6));
        LineItemMatch match = new LineItemMatch(
                uuid(7), ANALYSIS_ID, uuid(5), uuid(6), LineMatchStatus.MATCHED, LineMatchMethod.SKU,
                BigDecimal.ONE, 0, createdAt.plusSeconds(7), createdAt.plusSeconds(7), createdAt.plusSeconds(7));
        ReconciliationInput input = new ReconciliationInput(
                analysis, reference, invoice,
                Map.of(uuid(5), referenceLine), Map.of(uuid(6), invoiceLine), List.of(match),
                analysis.priceTolerance(), ReconciliationInput.snapshot(List.of(match)),
                new io.github.guillermodubon.invoward.reconciliation.domain.ConfirmedEvidenceSource(
                        reference, reference.id(), referenceExtraction.extraction(), referenceExtraction.lineItemIds()),
                new io.github.guillermodubon.invoward.reconciliation.domain.ConfirmedEvidenceSource(
                        invoice, invoice.id(), invoiceExtraction.extraction(), invoiceExtraction.lineItemIds()));
        return new Fixture(input, job, match, invoiceExtraction);
    }

    private static Document document(DocumentRole role, DocumentType type, String hashPrefix) {
        return Document.createUploaded(
                        UUID.randomUUID(), ANALYSIS_ID, role, role + ".pdf", "application/pdf", 1024,
                        1, hashPrefix.repeat(64), "reconciliation-test/" + UUID.randomUUID(), null,
                        NOW.minusSeconds(30))
                .withDetectedType(type)
                .withConfirmedType(type);
    }

    private static PersistedExtraction extraction(Document document, ExtractedLineItem line, UUID lineId) {
        ExtractedDocument confirmed = ExtractedDocument.draft(
                ExtractionSource.AI, "Vendor", document.role() == DocumentRole.INVOICE ? "INV-1" : "Q-1",
                LocalDate.of(2026, 10, 1), "USD", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.TEN, List.of(line)).confirm(NOW.minusSeconds(20));
        return new PersistedExtraction(
                UUID.randomUUID(), document.id(), confirmed, List.of(lineId), "document-extraction-v1",
                "test-model", 1, 0, NOW.minusSeconds(20), NOW.minusSeconds(20), NOW.minusSeconds(20));
    }

    private static ExtractedLineItem line() {
        return new ExtractedLineItem(0, "SKU-1", "Widget", BigDecimal.ONE, "each", BigDecimal.TEN,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, 1, "Widget, 1 each", null);
    }

    private static ReconciliationLine reconciledLine(LineItemMatch match) {
        return new ReconciliationLine(
                uuid(8), ANALYSIS_ID, match.id(), ReconciliationLineStatus.MATCHED,
                "SKU-1", "SKU-1", "Widget", "Widget", BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, NOW.minusSeconds(1));
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }

    private record Fixture(
            ReconciliationInput input,
            AnalysisJob job,
            LineItemMatch match,
            PersistedExtraction invoiceExtraction) {
    }
}
