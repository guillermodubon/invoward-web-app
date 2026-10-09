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
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchSetConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.MatchesNotFoundException;
import io.github.guillermodubon.invoward.reconciliation.application.model.LineItemMatchSetView;
import io.github.guillermodubon.invoward.reconciliation.application.port.LineItemMatchRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchMethod;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GetLineItemMatchesServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T18:00:00Z");
    private static final UUID ANALYSIS_ID = uuid(1);
    private static final UUID OWNER_ID = uuid(2);

    private final AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final ExtractedDocumentRepository extractedDocumentRepository =
            mock(ExtractedDocumentRepository.class);
    private final LineItemMatchRepository lineItemMatchRepository = mock(LineItemMatchRepository.class);

    private GetLineItemMatchesService service;

    @BeforeEach
    void setUp() {
        service = new GetLineItemMatchesService(
                analysisRepository,
                documentRepository,
                extractedDocumentRepository,
                lineItemMatchRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void authorizesAnalysisBeforeReadingMatchRowsOrExtractionData() {
        AnalysisOwner owner = owner();
        when(analysisRepository.findOwnedById(ANALYSIS_ID, owner, NOW)).thenReturn(Optional.empty());

        assertThrows(AnalysisNotFoundException.class, () -> service.get(ANALYSIS_ID, owner));

        verifyNoInteractions(lineItemMatchRepository, documentRepository, extractedDocumentRepository);
    }

    @Test
    void reportsMissingMatchSetWithoutLoadingDocumentsOrExtractions() {
        AnalysisOwner owner = owner();
        when(analysisRepository.findOwnedById(ANALYSIS_ID, owner, NOW))
                .thenReturn(Optional.of(analysis(owner, AnalysisStatus.MATCHING)));
        when(lineItemMatchRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(List.of());

        assertThrows(MatchesNotFoundException.class, () -> service.get(ANALYSIS_ID, owner));

        verify(lineItemMatchRepository).findByAnalysisId(ANALYSIS_ID);
        verifyNoInteractions(documentRepository, extractedDocumentRepository);
    }

    @Test
    void projectsPersistedMatchesInReferenceThenInvoiceOnlyPositionOrder() {
        Fixture fixture = fixture(AnalysisStatus.AWAITING_MATCH_REVIEW);
        LineItemMatch referencePositionOne = match(
                uuid(20), fixture.referenceExtraction().lineItemIds().get(1),
                fixture.invoiceExtraction().lineItemIds().get(2), LineMatchStatus.NEEDS_REVIEW,
                LineMatchMethod.FUZZY, new BigDecimal("0.8123"));
        LineItemMatch unmatchedReference = match(
                uuid(10), fixture.referenceExtraction().lineItemIds().get(0), null,
                LineMatchStatus.UNMATCHED_REFERENCE, LineMatchMethod.NONE, null);
        LineItemMatch referencePositionTwo = match(
                uuid(30), fixture.referenceExtraction().lineItemIds().get(2),
                fixture.invoiceExtraction().lineItemIds().get(0), LineMatchStatus.MATCHED,
                LineMatchMethod.SKU, BigDecimal.ONE);
        LineItemMatch invoiceOnly = match(
                uuid(40), null, fixture.invoiceExtraction().lineItemIds().get(1),
                LineMatchStatus.UNMATCHED_INVOICE, LineMatchMethod.NONE, null);
        stub(fixture, List.of(invoiceOnly, referencePositionTwo, referencePositionOne, unmatchedReference));

        LineItemMatchSetView result = service.get(ANALYSIS_ID, fixture.owner());

        assertEquals(ANALYSIS_ID, result.analysisId());
        assertEquals(AnalysisStatus.AWAITING_MATCH_REVIEW, result.analysisStatus());
        assertTrue(result.reviewRequired());
        assertEquals(List.of(uuid(10), uuid(20), uuid(30), uuid(40)),
                result.matches().stream().map(LineItemMatchSetView.MatchEntry::id).toList());
        assertEquals(0, result.matches().get(0).reference().position());
        assertNull(result.matches().get(0).invoice());
        assertEquals(2, result.matches().get(1).invoice().position());
        assertEquals("Reference item 1", result.matches().get(1).reference().description());
        assertEquals(new BigDecimal("12.5000"), result.matches().get(1).reference().unitPrice());
        assertEquals(new BigDecimal("25.0000"), result.matches().get(1).reference().lineTotal());
        assertNull(result.matches().get(3).reference());
        assertEquals(1, result.matches().get(3).invoice().position());
        assertEquals(new BigDecimal("0.8123"), result.matches().get(1).confidence());

        verify(analysisRepository).findOwnedById(ANALYSIS_ID, fixture.owner(), NOW);
        verify(lineItemMatchRepository).findByAnalysisId(ANALYSIS_ID);
    }

    @Test
    void keepsMatchSetReadableDuringReconciliationAndDerivesReviewFlagFromAnalysisState() {
        Fixture fixture = fixture(AnalysisStatus.RECONCILING);
        LineItemMatch persisted = match(
                uuid(50), fixture.referenceExtraction().lineItemIds().get(0),
                fixture.invoiceExtraction().lineItemIds().get(0), LineMatchStatus.MATCHED,
                LineMatchMethod.NORMALIZED_NAME, BigDecimal.ONE);
        stub(fixture, List.of(persisted));

        LineItemMatchSetView result = service.get(ANALYSIS_ID, fixture.owner());

        assertEquals(AnalysisStatus.RECONCILING, result.analysisStatus());
        assertFalse(result.reviewRequired());
        assertEquals(1, result.matches().size());
        assertEquals("Reference item 0", result.matches().getFirst().reference().description());
        assertEquals("Invoice item 0", result.matches().getFirst().invoice().description());
    }

    @Test
    void rejectsPersistedRowsThatDoNotBelongToTheConfirmedExtractionPair() {
        Fixture fixture = fixture(AnalysisStatus.AWAITING_MATCH_REVIEW);
        LineItemMatch invalid = match(
                uuid(60), uuid(999), fixture.invoiceExtraction().lineItemIds().get(0),
                LineMatchStatus.MATCHED, LineMatchMethod.MANUAL, null);
        stub(fixture, List.of(invalid));

        assertThrows(MatchSetConflictException.class, () -> service.get(ANALYSIS_ID, fixture.owner()));
    }

    @Test
    void projectionContainsOnlyApprovedDisplayFields() {
        assertEquals(List.of("analysisId", "analysisStatus", "reviewRequired", "matches"),
                recordFields(LineItemMatchSetView.class));
        assertEquals(List.of("id", "status", "method", "confidence", "version", "reviewedAt",
                        "reference", "invoice"),
                recordFields(LineItemMatchSetView.MatchEntry.class));
        assertEquals(List.of("lineItemId", "position", "itemCode", "description", "quantity", "unit",
                        "unitPrice", "lineTotal"),
                recordFields(LineItemMatchSetView.LineItem.class));
    }

    private void stub(Fixture fixture, List<LineItemMatch> matches) {
        when(analysisRepository.findOwnedById(ANALYSIS_ID, fixture.owner(), NOW))
                .thenReturn(Optional.of(fixture.analysis()));
        when(lineItemMatchRepository.findByAnalysisId(ANALYSIS_ID)).thenReturn(matches);
        // Return documents in non-semantic order to verify role-based resolution.
        when(documentRepository.findByAnalysisId(ANALYSIS_ID))
                .thenReturn(List.of(fixture.invoice(), fixture.reference()));
        when(extractedDocumentRepository.findByDocumentId(fixture.reference().id()))
                .thenReturn(Optional.of(fixture.referenceExtraction()));
        when(extractedDocumentRepository.findByDocumentId(fixture.invoice().id()))
                .thenReturn(Optional.of(fixture.invoiceExtraction()));
    }

    private static Fixture fixture(AnalysisStatus status) {
        AnalysisOwner owner = owner();
        Analysis analysis = analysis(owner, status);
        Document reference = document(DocumentRole.REFERENCE, DocumentType.QUOTE);
        Document invoice = document(DocumentRole.INVOICE, DocumentType.INVOICE);
        return new Fixture(owner, analysis, reference, invoice,
                extraction(reference, "Reference item "), extraction(invoice, "Invoice item "));
    }

    private static Analysis analysis(AnalysisOwner owner, AnalysisStatus status) {
        Instant created = NOW.minusSeconds(20);
        Analysis matching = Analysis.create(ANALYSIS_ID, owner, PriceTolerance.exactMatch(), created)
                .transitionToUploading(created.plusSeconds(1))
                .markClassifying(created.plusSeconds(2))
                .awaitExtractionConfirmation(created.plusSeconds(3))
                .confirmExtraction("QUOTE", "Q-1", "I-1", "Test vendor", "USD",
                        BigDecimal.TEN, BigDecimal.TEN, created.plusSeconds(4));
        return switch (status) {
            case MATCHING -> matching;
            case AWAITING_MATCH_REVIEW -> matching.awaitMatchReview(created.plusSeconds(5));
            case RECONCILING -> matching.beginReconciliation(created.plusSeconds(5));
            default -> throw new IllegalArgumentException("test status must follow confirmed extraction");
        };
    }

    private static Document document(DocumentRole role, DocumentType type) {
        return Document.createUploaded(
                        UUID.randomUUID(), ANALYSIS_ID, role, role + ".pdf", "application/pdf", 1024,
                        1, "a".repeat(64), "match-read-test/" + UUID.randomUUID(), null, NOW.minusSeconds(10))
                .withDetectedType(type)
                .withConfirmedType(type);
    }

    private static PersistedExtraction extraction(Document document, String descriptionPrefix) {
        List<ExtractedLineItem> lines = java.util.stream.IntStream.range(0, 3)
                .mapToObj(position -> new ExtractedLineItem(
                        position,
                        "SKU-" + position,
                        descriptionPrefix + position,
                        BigDecimal.valueOf(position + 1),
                        "unit",
                        new BigDecimal("12.50"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("25.00"),
                        position + 1,
                        "private source evidence",
                        null))
                .toList();
        Instant confirmedAt = NOW.minusSeconds(3);
        ExtractedDocument confirmed = new ExtractedDocument(
                io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus.CONFIRMED,
                ExtractionSource.AI,
                confirmedAt,
                "Test vendor",
                "DOC-1",
                null,
                "USD",
                BigDecimal.TEN,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.TEN,
                lines);
        List<UUID> lineIds = java.util.stream.IntStream.range(0, 3)
                .mapToObj(position -> uuid((document.role() == DocumentRole.REFERENCE ? 100 : 200) + position))
                .toList();
        return new PersistedExtraction(
                UUID.randomUUID(), document.id(), confirmed, lineIds,
                "document-extraction-v1", "private-model-id", 1, 0,
                confirmedAt, confirmedAt, confirmedAt);
    }

    private static LineItemMatch match(
            UUID id,
            UUID referenceLineId,
            UUID invoiceLineId,
            LineMatchStatus status,
            LineMatchMethod method,
            BigDecimal confidence) {
        return new LineItemMatch(
                id, ANALYSIS_ID, referenceLineId, invoiceLineId, status, method, confidence,
                0, null, NOW.minusSeconds(5), NOW.minusSeconds(5));
    }

    private static List<String> recordFields(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(component -> component.getName()).toList();
    }

    private static AnalysisOwner owner() {
        return new RegisteredUserOwner(OWNER_ID);
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
