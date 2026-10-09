package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedDocumentRepository;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionSource;
import io.github.guillermodubon.invoward.reconciliation.application.exception.AnalysisMatchingNotAllowedException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchSetConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchingInputNotReadyException;
import io.github.guillermodubon.invoward.reconciliation.application.model.AmbiguousLineMatchingInput;
import io.github.guillermodubon.invoward.reconciliation.application.model.MatchableLineItem;
import io.github.guillermodubon.invoward.reconciliation.application.model.MatchingPlan;
import io.github.guillermodubon.invoward.reconciliation.application.port.LineItemMatchRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchMethod;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import io.github.guillermodubon.invoward.support.ai.FakeAmbiguousLineMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RunLineItemMatchingServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T18:00:00Z");
    private static final UUID ANALYSIS_ID = uuid(1);

    private final AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final ExtractedDocumentRepository extractedDocumentRepository = mock(ExtractedDocumentRepository.class);
    private final LineItemMatchRepository lineItemMatchRepository = mock(LineItemMatchRepository.class);
    private final PersistLineItemMatchesTransaction persistenceTransaction =
            mock(PersistLineItemMatchesTransaction.class);
    private final FakeAmbiguousLineMatcher matcher = new FakeAmbiguousLineMatcher();

    private RunLineItemMatchingService service;

    @BeforeEach
    void setUp() {
        LineItemMatchingEngine engine = new LineItemMatchingEngine(
                new io.github.guillermodubon.invoward.reconciliation.domain.LineItemCodeNormalizer(),
                new io.github.guillermodubon.invoward.reconciliation.domain.LineDescriptionSimilarity(),
                new BigDecimal("0.90"), new BigDecimal("0.65"), new BigDecimal("0.08"), 20);
        AmbiguousLineMatchingAssistant assistant = new AmbiguousLineMatchingAssistant(
                matcher, 5, RunLineItemMatchingServiceTest::randomId);
        service = new RunLineItemMatchingService(
                analysisRepository,
                documentRepository,
                extractedDocumentRepository,
                lineItemMatchRepository,
                engine,
                assistant,
                persistenceTransaction,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(persistenceTransaction.persist(any(), any(MatchingPlan.class)))
                .thenAnswer(invocation -> ((MatchingPlan) invocation.getArgument(1)).matches());
    }

    @Test
    void authorizesAnalysisBeforeReadingDocumentsOrInvokingAnyMatchingDependency() {
        AnalysisOwner owner = registeredOwner();
        when(analysisRepository.findOwnedById(ANALYSIS_ID, owner, NOW)).thenReturn(Optional.empty());

        assertThrows(AnalysisNotFoundException.class, () -> service.run(ANALYSIS_ID, owner));

        verifyNoInteractions(documentRepository, extractedDocumentRepository, lineItemMatchRepository,
                persistenceTransaction);
        assertEquals(0, matcher.callCount());
    }

    @Test
    void rejectsNonMatchingAnalysisBeforeLoadingDocuments() {
        AnalysisOwner owner = registeredOwner();
        Analysis analysis = Analysis.create(ANALYSIS_ID, owner, PriceTolerance.exactMatch(), NOW.minusSeconds(10));
        when(analysisRepository.findOwnedById(ANALYSIS_ID, owner, NOW)).thenReturn(Optional.of(analysis));

        assertThrows(AnalysisMatchingNotAllowedException.class, () -> service.run(ANALYSIS_ID, owner));

        verifyNoInteractions(documentRepository, extractedDocumentRepository, lineItemMatchRepository,
                persistenceTransaction);
        assertEquals(0, matcher.callCount());
    }

    @Test
    void rejectsMissingOrUnconfirmedExtractionWithoutCallingAiOrPersisting() {
        Fixture fixture = fixture(List.of(line("SKU-1", "accepted item")), List.of(line("SKU-1", "invoice item")));
        when(analysisRepository.findOwnedById(ANALYSIS_ID, fixture.owner(), NOW))
                .thenReturn(Optional.of(fixture.analysis()));
        when(documentRepository.findByAnalysisId(ANALYSIS_ID))
                .thenReturn(List.of(fixture.reference(), fixture.invoice()));
        when(extractedDocumentRepository.findByDocumentId(fixture.reference().id()))
                .thenReturn(Optional.of(fixture.referenceExtraction()));
        when(extractedDocumentRepository.findByDocumentId(fixture.invoice().id())).thenReturn(Optional.empty());

        assertThrows(MatchingInputNotReadyException.class,
                () -> service.run(ANALYSIS_ID, fixture.owner()));

        verify(lineItemMatchRepository, never()).findByAnalysisId(any());
        verifyNoInteractions(persistenceTransaction);
        assertEquals(0, matcher.callCount());
    }

    @Test
    void rejectsDraftExtractionWithoutCallingAiOrPersisting() {
        Fixture fixture = fixture(List.of(line("SKU-1", "accepted item")), List.of(line("SKU-1", "invoice item")));
        when(analysisRepository.findOwnedById(ANALYSIS_ID, fixture.owner(), NOW))
                .thenReturn(Optional.of(fixture.analysis()));
        when(documentRepository.findByAnalysisId(ANALYSIS_ID))
                .thenReturn(List.of(fixture.reference(), fixture.invoice()));
        when(extractedDocumentRepository.findByDocumentId(fixture.reference().id()))
                .thenReturn(Optional.of(fixture.referenceExtraction()));
        PersistedExtraction confirmedInvoice = fixture.invoiceExtraction();
        ExtractedDocument invoiceDraft = ExtractedDocument.draft(
                confirmedInvoice.extraction().extractionSource(),
                confirmedInvoice.extraction().vendorName(),
                confirmedInvoice.extraction().documentNumber(),
                confirmedInvoice.extraction().documentDate(),
                confirmedInvoice.extraction().currency(),
                confirmedInvoice.extraction().subtotal(),
                confirmedInvoice.extraction().discountTotal(),
                confirmedInvoice.extraction().taxTotal(),
                confirmedInvoice.extraction().total(),
                confirmedInvoice.extraction().lines());
        when(extractedDocumentRepository.findByDocumentId(fixture.invoice().id()))
                .thenReturn(Optional.of(confirmedInvoice.withExtraction(invoiceDraft)));

        assertThrows(MatchingInputNotReadyException.class,
                () -> service.run(ANALYSIS_ID, fixture.owner()));

        verify(lineItemMatchRepository, never()).findByAnalysisId(any());
        verifyNoInteractions(persistenceTransaction);
        assertEquals(0, matcher.callCount());
    }

    @Test
    void runsDeterministicMatchingAndPersistsACompleteCoverWithoutCallingAiForCertainMatches() {
        Fixture fixture = fixture(
                List.of(line("SKU-1", "reference widget"), line(null, "citrus oranges from orchard")),
                List.of(line(" sku-1 ", "invoice widget"), line(null, "annual cloud hosting subscription")));
        stubConfirmedInput(fixture);

        List<LineItemMatch> persisted = service.run(ANALYSIS_ID, fixture.owner());

        assertEquals(3, persisted.size());
        assertEquals(1, persisted.stream().filter(match -> match.status() == LineMatchStatus.MATCHED).count());
        assertEquals(1, persisted.stream()
                .filter(match -> match.status() == LineMatchStatus.UNMATCHED_REFERENCE).count());
        assertEquals(1, persisted.stream()
                .filter(match -> match.status() == LineMatchStatus.UNMATCHED_INVOICE).count());
        assertTrue(persisted.stream().anyMatch(match -> match.method() == LineMatchMethod.SKU));
        assertEquals(0, matcher.callCount());
        verify(persistenceTransaction).persist(eq(fixture.owner()), any(MatchingPlan.class));
    }

    @Test
    void consultsAiOnlyForAmbiguousFuzzyCandidatesAndKeepsSuggestionInHumanReview() {
        Fixture fixture = fixture(
                List.of(line(null, "blue paper archive storage box")),
                List.of(line(null, "BLUE paper archive storage box"),
                        line(null, "Blue Paper Archive Storage Box")));
        stubConfirmedInput(fixture);
        matcher.configureCandidate("C2");

        List<LineItemMatch> persisted = service.run(ANALYSIS_ID, fixture.owner());

        assertEquals(1, matcher.callCount());
        LineItemMatch suggested = persisted.stream()
                .filter(match -> match.status() == LineMatchStatus.NEEDS_REVIEW)
                .findFirst().orElseThrow();
        assertEquals(LineMatchMethod.AI, suggested.method());
        assertEquals(fixture.invoiceExtraction().lineItemIds().get(1), suggested.invoiceLineItemId());
        assertNull(suggested.reviewedAt());
        assertTrue(persisted.stream().anyMatch(match -> match.status() == LineMatchStatus.UNMATCHED_INVOICE
                && match.invoiceLineItemId().equals(fixture.invoiceExtraction().lineItemIds().getFirst())));
        assertEquals("REF", matcher.lastInput().orElseThrow().reference().label());
        assertEquals(List.of("C1", "C2"), matcher.lastInput().orElseThrow().invoiceCandidates().stream()
                .map(AmbiguousLineMatchingInput.Line::label).toList());
    }

    @Test
    void returnsAnExistingCompleteSetWithoutRecomputingOrOverwritingIt() {
        Fixture fixture = fixture(List.of(line("SKU-1", "reference item")), List.of(line("SKU-1", "invoice item")));
        stubConfirmedInput(fixture);
        LineItemMatch existing = match(
                fixture.referenceExtraction().lineItemIds().getFirst(),
                fixture.invoiceExtraction().lineItemIds().getFirst(),
                LineMatchStatus.MATCHED, LineMatchMethod.MANUAL, null);
        when(lineItemMatchRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(existing));

        List<LineItemMatch> result = service.run(ANALYSIS_ID, fixture.owner());

        assertEquals(List.of(existing), result);
        assertEquals(LineMatchMethod.MANUAL, result.getFirst().method());
        assertEquals(0, matcher.callCount());
        verifyNoInteractions(persistenceTransaction);
    }

    @Test
    void rejectsPartialExistingSetInsteadOfSilentlyRebuildingIt() {
        Fixture fixture = fixture(
                List.of(line("SKU-1", "reference one"), line("SKU-2", "reference two")),
                List.of(line("SKU-1", "invoice one"), line("SKU-2", "invoice two")));
        stubConfirmedInput(fixture);
        LineItemMatch partial = match(
                fixture.referenceExtraction().lineItemIds().getFirst(),
                fixture.invoiceExtraction().lineItemIds().getFirst(),
                LineMatchStatus.MATCHED, LineMatchMethod.SKU, new BigDecimal("1.0000"));
        when(lineItemMatchRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of(partial));

        assertThrows(MatchSetConflictException.class, () -> service.run(ANALYSIS_ID, fixture.owner()));

        assertEquals(0, matcher.callCount());
        verifyNoInteractions(persistenceTransaction);
    }

    private void stubConfirmedInput(Fixture fixture) {
        when(analysisRepository.findOwnedById(ANALYSIS_ID, fixture.owner(), NOW))
                .thenReturn(Optional.of(fixture.analysis()));
        // Deliberately reverse repository order; the use case resolves by domain role, not result position.
        when(documentRepository.findByAnalysisId(ANALYSIS_ID))
                .thenReturn(List.of(fixture.invoice(), fixture.reference()));
        when(extractedDocumentRepository.findByDocumentId(fixture.reference().id()))
                .thenReturn(Optional.of(fixture.referenceExtraction()));
        when(extractedDocumentRepository.findByDocumentId(fixture.invoice().id()))
                .thenReturn(Optional.of(fixture.invoiceExtraction()));
        when(lineItemMatchRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of());
    }

    private static Fixture fixture(List<ExtractedLineItem> referenceLines, List<ExtractedLineItem> invoiceLines) {
        AnalysisOwner owner = registeredOwner();
        Analysis analysis = matchingAnalysis(owner);
        Document reference = document(DocumentRole.REFERENCE, DocumentType.QUOTE);
        Document invoice = document(DocumentRole.INVOICE, DocumentType.INVOICE);
        return new Fixture(
                owner, analysis, reference, invoice,
                extraction(reference, referenceLines), extraction(invoice, invoiceLines));
    }

    private static Analysis matchingAnalysis(AnalysisOwner owner) {
        Instant created = NOW.minusSeconds(10);
        return Analysis.create(ANALYSIS_ID, owner, PriceTolerance.exactMatch(), created)
                .transitionToUploading(created.plusSeconds(1))
                .markClassifying(created.plusSeconds(2))
                .awaitExtractionConfirmation(created.plusSeconds(3))
                .confirmExtraction("QUOTE", "Q-1", "I-1", "Test vendor", "USD",
                        BigDecimal.TEN, BigDecimal.TEN, created.plusSeconds(4));
    }

    private static Document document(DocumentRole role, DocumentType type) {
        return Document.createUploaded(
                        UUID.randomUUID(), ANALYSIS_ID, role, role + ".pdf", "application/pdf", 1024,
                        1, "a".repeat(64), "matching-test/" + UUID.randomUUID(), null, NOW.minusSeconds(5))
                .withDetectedType(type)
                .withConfirmedType(type);
    }

    private static PersistedExtraction extraction(Document document, List<ExtractedLineItem> lines) {
        List<ExtractedLineItem> positionedLines = java.util.stream.IntStream.range(0, lines.size())
                .mapToObj(index -> {
                    ExtractedLineItem line = lines.get(index);
                    return new ExtractedLineItem(
                            index, line.itemCode(), line.description(), line.quantity(), line.unit(),
                            line.unitPrice(), line.discountAmount(), line.taxAmount(), line.lineTotal(),
                            line.pageNumber(), line.sourceText(), line.boundingBox());
                })
                .toList();
        ExtractedDocument confirmed = ExtractedDocument.draft(
                ExtractionSource.AI, "Test vendor", null, null, "USD",
                null, null, null, null, positionedLines).confirm(NOW.minusSeconds(3));
        List<UUID> lineIds = positionedLines.stream().map(ignored -> UUID.randomUUID()).toList();
        return new PersistedExtraction(
                UUID.randomUUID(), document.id(), confirmed, lineIds,
                "document-extraction-v1", "test-model", 1, 0,
                NOW.minusSeconds(3), NOW.minusSeconds(3), NOW.minusSeconds(3));
    }

    private static ExtractedLineItem line(String itemCode, String description) {
        return new ExtractedLineItem(
                0, itemCode, description, BigDecimal.ONE, "box", BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, 1, null, null);
    }

    private static LineItemMatch match(
            UUID referenceId,
            UUID invoiceId,
            LineMatchStatus status,
            LineMatchMethod method,
            BigDecimal confidence) {
        return new LineItemMatch(
                UUID.randomUUID(), ANALYSIS_ID, referenceId, invoiceId, status, method,
                confidence, 0, NOW.minusSeconds(1), NOW.minusSeconds(2), NOW.minusSeconds(1));
    }

    private static AnalysisOwner registeredOwner() {
        return new RegisteredUserOwner(uuid(2));
    }

    private static UUID randomId() {
        return UUID.randomUUID();
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }

    private record Fixture(
            AnalysisOwner owner,
            Analysis analysis,
            Document reference,
            Document invoice,
            PersistedExtraction referenceExtraction,
            PersistedExtraction invoiceExtraction) {
    }
}
