package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
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
import io.github.guillermodubon.invoward.reconciliation.application.exception.AnalysisReconciliationNotAllowedException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.ReconciliationInputNotReadyException;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationInput;
import io.github.guillermodubon.invoward.reconciliation.application.port.LineItemMatchRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchMethod;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.ConfirmedEvidenceSource;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReconciliationInputAssemblerTest {

    private static final Instant NOW = Instant.parse("2026-10-09T15:00:00Z");
    private static final UUID ANALYSIS_ID = uuid(1);

    private final AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final ExtractedDocumentRepository extractedDocumentRepository = mock(ExtractedDocumentRepository.class);
    private final LineItemMatchRepository lineItemMatchRepository = mock(LineItemMatchRepository.class);

    private ReconciliationInputAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new ReconciliationInputAssembler(
                analysisRepository,
                documentRepository,
                extractedDocumentRepository,
                lineItemMatchRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void assemblesCompleteConfirmedInputAndCapturesMatchConcurrencySnapshot() {
        Fixture fixture = fixture(List.of(line(0, "reference item")), List.of(line(0, "invoice item")));
        LineItemMatch match = match(
                fixture.referenceExtraction().lineItemIds().getFirst(),
                fixture.invoiceExtraction().lineItemIds().getFirst(),
                LineMatchStatus.MATCHED,
                4);
        stubInput(fixture, List.of(match));

        ReconciliationInput input = assembler.assemble(ANALYSIS_ID, fixture.owner());

        assertEquals(fixture.analysis(), input.analysis());
        assertEquals(fixture.reference(), input.referenceDocument());
        assertEquals(fixture.invoice(), input.invoiceDocument());
        assertEquals(new ConfirmedEvidenceSource(
                fixture.reference(), fixture.referenceExtraction().documentId(),
                fixture.referenceExtraction().extraction(), fixture.referenceExtraction().lineItemIds()),
                input.referenceSource());
        assertEquals(new ConfirmedEvidenceSource(
                fixture.invoice(), fixture.invoiceExtraction().documentId(),
                fixture.invoiceExtraction().extraction(), fixture.invoiceExtraction().lineItemIds()),
                input.invoiceSource());
        assertEquals(fixture.analysis().priceTolerance(), input.priceTolerance());
        assertEquals(List.of(fixture.referenceExtraction().lineItemIds().getFirst()),
                List.copyOf(input.referenceLines().keySet()));
        assertEquals(List.of(fixture.invoiceExtraction().lineItemIds().getFirst()),
                List.copyOf(input.invoiceLines().keySet()));
        assertEquals(List.of(match), input.matches());
        assertEquals(List.of(new ReconciliationInput.MatchSnapshot(
                match.id(), 4, LineMatchStatus.MATCHED,
                match.referenceLineItemId(), match.invoiceLineItemId())), input.matchSnapshots());
        assertTrue(input.referenceLines().get(match.referenceLineItemId()).description().startsWith("reference"));
    }

    @Test
    void rejectsMissingExtractionBeforeReadingMatches() {
        Fixture fixture = fixture(List.of(line(0, "reference item")), List.of(line(0, "invoice item")));
        stubAuthorizedDocuments(fixture);
        when(extractedDocumentRepository.findByDocumentId(fixture.reference().id())).thenReturn(Optional.empty());

        assertThrows(ReconciliationInputNotReadyException.class,
                () -> assembler.assemble(ANALYSIS_ID, fixture.owner()));

        verify(lineItemMatchRepository, org.mockito.Mockito.never()).findByAnalysisId(ANALYSIS_ID);
    }

    @Test
    void rejectsPartialMatchCover() {
        Fixture fixture = fixture(
                List.of(line(0, "reference one"), line(1, "reference two")),
                List.of(line(0, "invoice one")));
        LineItemMatch partial = match(
                fixture.referenceExtraction().lineItemIds().getFirst(),
                fixture.invoiceExtraction().lineItemIds().getFirst(),
                LineMatchStatus.MATCHED,
                0);
        stubInput(fixture, List.of(partial));

        assertThrows(ReconciliationInputNotReadyException.class,
                () -> assembler.assemble(ANALYSIS_ID, fixture.owner()));
    }

    @Test
    void rejectsMatchReferencingAnInvoiceLineOnTheReferenceSide() {
        Fixture fixture = fixture(List.of(line(0, "reference item")), List.of(line(0, "invoice item")));
        LineItemMatch wrongSide = match(
                fixture.invoiceExtraction().lineItemIds().getFirst(), null,
                LineMatchStatus.UNMATCHED_REFERENCE, 0);
        stubInput(fixture, List.of(wrongSide));

        assertThrows(ReconciliationInputNotReadyException.class,
                () -> assembler.assemble(ANALYSIS_ID, fixture.owner()));
    }

    @Test
    void rejectsMatchSetContainingNeedsReview() {
        Fixture fixture = fixture(List.of(line(0, "reference item")), List.of(line(0, "invoice item")));
        LineItemMatch needsReview = match(
                fixture.referenceExtraction().lineItemIds().getFirst(),
                fixture.invoiceExtraction().lineItemIds().getFirst(),
                LineMatchStatus.NEEDS_REVIEW,
                0);
        stubInput(fixture, List.of(needsReview));

        assertThrows(ReconciliationInputNotReadyException.class,
                () -> assembler.assemble(ANALYSIS_ID, fixture.owner()));
    }

    @Test
    void wrongOwnerIsHiddenBeforeAnyReconciliationSourceIsRead() {
        AnalysisOwner owner = owner();
        when(analysisRepository.findOwnedById(ANALYSIS_ID, owner, NOW)).thenReturn(Optional.empty());

        assertThrows(AnalysisNotFoundException.class, () -> assembler.assemble(ANALYSIS_ID, owner));

        verifyNoInteractions(documentRepository, extractedDocumentRepository, lineItemMatchRepository);
    }

    @Test
    void rejectsAnalysisOutsideReconcilingStateBeforeReadingSourceData() {
        Fixture fixture = fixture(List.of(line(0, "reference item")), List.of(line(0, "invoice item")));
        Analysis notReconciling = analysis(fixture.owner(), AnalysisStatus.MATCHING);
        when(analysisRepository.findOwnedById(ANALYSIS_ID, fixture.owner(), NOW))
                .thenReturn(Optional.of(notReconciling));

        assertThrows(AnalysisReconciliationNotAllowedException.class,
                () -> assembler.assemble(ANALYSIS_ID, fixture.owner()));

        verifyNoInteractions(documentRepository, extractedDocumentRepository, lineItemMatchRepository);
    }

    private void stubInput(Fixture fixture, List<LineItemMatch> matches) {
        stubAuthorizedDocuments(fixture);
        when(extractedDocumentRepository.findByDocumentId(fixture.reference().id()))
                .thenReturn(Optional.of(fixture.referenceExtraction()));
        when(extractedDocumentRepository.findByDocumentId(fixture.invoice().id()))
                .thenReturn(Optional.of(fixture.invoiceExtraction()));
        when(lineItemMatchRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(matches);
    }

    private void stubAuthorizedDocuments(Fixture fixture) {
        when(analysisRepository.findOwnedById(ANALYSIS_ID, fixture.owner(), NOW))
                .thenReturn(Optional.of(fixture.analysis()));
        when(documentRepository.findByAnalysisId(ANALYSIS_ID))
                .thenReturn(List.of(fixture.invoice(), fixture.reference()));
    }

    private static Fixture fixture(List<ExtractedLineItem> referenceLines, List<ExtractedLineItem> invoiceLines) {
        AnalysisOwner owner = owner();
        Analysis analysis = analysis(owner, AnalysisStatus.RECONCILING);
        Document reference = document(DocumentRole.REFERENCE, DocumentType.QUOTE);
        Document invoice = document(DocumentRole.INVOICE, DocumentType.INVOICE);
        return new Fixture(owner, analysis, reference, invoice,
                extraction(reference, referenceLines), extraction(invoice, invoiceLines));
    }

    private static Analysis analysis(AnalysisOwner owner, AnalysisStatus finalStatus) {
        Instant created = NOW.minusSeconds(30);
        Analysis analysis = Analysis.create(ANALYSIS_ID, owner, PriceTolerance.exactMatch(), created)
                .transitionToUploading(created.plusSeconds(1))
                .markClassifying(created.plusSeconds(2))
                .awaitExtractionConfirmation(created.plusSeconds(3))
                .confirmExtraction("QUOTE", "Q-1", "I-1", "Test vendor", "USD",
                        BigDecimal.TEN, BigDecimal.TEN, created.plusSeconds(4));
        if (finalStatus == AnalysisStatus.MATCHING) {
            return analysis;
        }
        return analysis.awaitMatchReview(created.plusSeconds(5)).beginReconciliation(created.plusSeconds(6));
    }

    private static Document document(DocumentRole role, DocumentType type) {
        return Document.createUploaded(
                        UUID.randomUUID(), ANALYSIS_ID, role, role + ".pdf", "application/pdf", 1024,
                        1, "a".repeat(64), "reconciliation-test/" + UUID.randomUUID(), null, NOW.minusSeconds(10))
                .withDetectedType(type)
                .withConfirmedType(type);
    }

    private static PersistedExtraction extraction(Document document, List<ExtractedLineItem> sourceLines) {
        List<ExtractedLineItem> positioned = java.util.stream.IntStream.range(0, sourceLines.size())
                .mapToObj(index -> withPosition(sourceLines.get(index), index))
                .toList();
        ExtractedDocument confirmed = ExtractedDocument.draft(
                ExtractionSource.AI, "Test vendor", null, null, "USD",
                BigDecimal.TEN, null, null, BigDecimal.TEN, positioned).confirm(NOW.minusSeconds(5));
        List<UUID> lineIds = positioned.stream().map(ignored -> UUID.randomUUID()).toList();
        return new PersistedExtraction(
                UUID.randomUUID(), document.id(), confirmed, lineIds,
                "document-extraction-v1", "test-model", 1, 0,
                NOW.minusSeconds(5), NOW.minusSeconds(5), NOW.minusSeconds(5));
    }

    private static ExtractedLineItem withPosition(ExtractedLineItem line, int position) {
        return new ExtractedLineItem(position, line.itemCode(), line.description(), line.quantity(), line.unit(),
                line.unitPrice(), line.discountAmount(), line.taxAmount(), line.lineTotal(), line.pageNumber(),
                line.sourceText(), line.boundingBox());
    }

    private static ExtractedLineItem line(int position, String description) {
        return new ExtractedLineItem(position, "SKU-" + position, description,
                BigDecimal.ONE, "each", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.TEN, 1, null, null);
    }

    private static LineItemMatch match(
            UUID referenceLineId, UUID invoiceLineId, LineMatchStatus status, long version) {
        return new LineItemMatch(UUID.randomUUID(), ANALYSIS_ID, referenceLineId, invoiceLineId,
                status, status == LineMatchStatus.UNMATCHED_REFERENCE ? LineMatchMethod.NONE : LineMatchMethod.MANUAL,
                null, version, NOW.minusSeconds(2), NOW.minusSeconds(3), NOW.minusSeconds(2));
    }

    private static AnalysisOwner owner() {
        return new RegisteredUserOwner(uuid(2));
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
