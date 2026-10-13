package io.github.guillermodubon.invoward.reconciliation.domain;

import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.extraction.domain.BoundingBox;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscrepancyEvidenceBuilderTest {

    private static final Instant CREATED = Instant.parse("2026-10-01T12:00:00Z");
    private static final Instant CONFIRMED = Instant.parse("2026-10-01T11:00:00Z");
    private static final UUID ANALYSIS_ID = UUID.randomUUID();

    private final DiscrepancyEvidenceBuilder builder = new DiscrepancyEvidenceBuilder();

    @Test
    void createsExactReferenceAndInvoiceEvidenceWithoutChangingSourceMetadata() {
        BoundingBox referenceBox = new BoundingBox(0.1, 0.2, 0.4, 0.5);
        BoundingBox invoiceBox = new BoundingBox(0.5, 0.4, 0.9, 0.8);
        Side reference = side(DocumentRole.REFERENCE, 3, List.of(line(0, 2, "Reference source", referenceBox)));
        Side invoice = side(DocumentRole.INVOICE, 4, List.of(line(0, 3, "Invoice source", invoiceBox)));
        LineItemMatch match = match(reference.lineIds().getFirst(), invoice.lineIds().getFirst(), LineMatchStatus.MATCHED);
        ReconciliationLine line = reconciliationLine(match.id(), ReconciliationLineStatus.DIFFERENT);
        Discrepancy discrepancy = discrepancy(DiscrepancyType.QUANTITY_MISMATCH, line.id());

        List<DiscrepancyEvidence> evidence = build(
                reference, invoice, List.of(match), List.of(line), List.of(discrepancy), List.of());

        assertEquals(2, evidence.size());
        assertEquals(EvidenceSide.REFERENCE, evidence.get(0).side());
        assertEquals(reference.source().document().id(), evidence.get(0).documentId());
        assertEquals(2, evidence.get(0).pageNumber());
        assertEquals("Reference source", evidence.get(0).sourceText());
        assertEquals(referenceBox, evidence.get(0).boundingBox());
        assertEquals(EvidenceSide.INVOICE, evidence.get(1).side());
        assertEquals(invoice.source().document().id(), evidence.get(1).documentId());
        assertEquals(3, evidence.get(1).pageNumber());
        assertEquals("Invoice source", evidence.get(1).sourceText());
        assertEquals(invoiceBox, evidence.get(1).boundingBox());
        assertTrue(evidence.stream().allMatch(item -> item.discrepancyId().equals(discrepancy.id())));
    }

    @Test
    void missingAndUnexpectedDiscrepanciesOnlyUseTheirExistingSide() {
        Side reference = side(DocumentRole.REFERENCE, 2, List.of(line(0, 1, "Reference only", null)));
        Side noInvoiceLines = side(DocumentRole.INVOICE, 2, List.of());
        LineItemMatch missingMatch = match(reference.lineIds().getFirst(), null, LineMatchStatus.UNMATCHED_REFERENCE);
        ReconciliationLine missingLine = reconciliationLine(missingMatch.id(), ReconciliationLineStatus.MISSING);
        Discrepancy missing = discrepancy(DiscrepancyType.MISSING_ITEM, missingLine.id());

        List<DiscrepancyEvidence> missingEvidence = build(
                reference, noInvoiceLines, List.of(missingMatch), List.of(missingLine), List.of(missing), List.of());

        assertEquals(1, missingEvidence.size());
        assertEquals(EvidenceSide.REFERENCE, missingEvidence.getFirst().side());
        assertEquals("Reference only", missingEvidence.getFirst().sourceText());

        Side noReferenceLines = side(DocumentRole.REFERENCE, 2, List.of());
        Side invoice = side(DocumentRole.INVOICE, 2, List.of(line(0, 2, "Invoice only", null)));
        LineItemMatch unexpectedMatch = match(null, invoice.lineIds().getFirst(), LineMatchStatus.UNMATCHED_INVOICE);
        ReconciliationLine unexpectedLine = reconciliationLine(unexpectedMatch.id(), ReconciliationLineStatus.UNEXPECTED);
        Discrepancy unexpected = discrepancy(DiscrepancyType.UNEXPECTED_ITEM, unexpectedLine.id());

        List<DiscrepancyEvidence> unexpectedEvidence = build(
                noReferenceLines, invoice, List.of(unexpectedMatch), List.of(unexpectedLine),
                List.of(unexpected), List.of());

        assertEquals(1, unexpectedEvidence.size());
        assertEquals(EvidenceSide.INVOICE, unexpectedEvidence.getFirst().side());
        assertEquals("Invoice only", unexpectedEvidence.getFirst().sourceText());
    }

    @Test
    void omitsLineEvidenceWhenTheConfirmedExtractionHasNoPageLocator() {
        Side reference = side(DocumentRole.REFERENCE, 2, List.of(line(0, null, "unlocated reference", null)));
        Side invoice = side(DocumentRole.INVOICE, 2, List.of(line(0, null, "unlocated invoice", null)));
        LineItemMatch match = match(reference.lineIds().getFirst(), invoice.lineIds().getFirst(), LineMatchStatus.MATCHED);
        ReconciliationLine line = reconciliationLine(match.id(), ReconciliationLineStatus.DIFFERENT);
        Discrepancy discrepancy = discrepancy(DiscrepancyType.QUANTITY_MISMATCH, line.id());

        assertTrue(build(reference, invoice, List.of(match), List.of(line), List.of(discrepancy), List.of()).isEmpty());
    }

    @Test
    void includesHeaderEvidenceOnlyForSinglePageDocuments() {
        Side referenceSinglePage = side(DocumentRole.REFERENCE, 1, List.of());
        Side invoiceSinglePage = side(DocumentRole.INVOICE, 1, List.of());
        Discrepancy header = discrepancy(DiscrepancyType.TOTAL_MISMATCH, null);
        DocumentReconciliationResult headerResult = documentResult(List.of(header));

        List<DiscrepancyEvidence> singlePageEvidence = build(
                referenceSinglePage, invoiceSinglePage, List.of(), List.of(), List.of(), headerResult.discrepancies());

        assertEquals(2, singlePageEvidence.size());
        assertEquals(1, singlePageEvidence.get(0).pageNumber());
        assertEquals(1, singlePageEvidence.get(1).pageNumber());
        assertNull(singlePageEvidence.get(0).sourceText());
        assertNull(singlePageEvidence.get(0).boundingBox());

        Side referenceMultiPage = side(DocumentRole.REFERENCE, 2, List.of());
        Side invoiceMultiPage = side(DocumentRole.INVOICE, 5, List.of());
        assertTrue(build(referenceMultiPage, invoiceMultiPage, List.of(), List.of(), List.of(),
                headerResult.discrepancies()).isEmpty());
    }

    @Test
    void rejectsEvidencePageOutsideSourceDocumentBounds() {
        Side reference = side(DocumentRole.REFERENCE, 1, List.of(line(0, 2, "invalid page", null)));
        Side invoice = side(DocumentRole.INVOICE, 1, List.of(line(0, 1, "invoice", null)));
        LineItemMatch match = match(reference.lineIds().getFirst(), invoice.lineIds().getFirst(), LineMatchStatus.MATCHED);
        ReconciliationLine line = reconciliationLine(match.id(), ReconciliationLineStatus.DIFFERENT);
        Discrepancy discrepancy = discrepancy(DiscrepancyType.QUANTITY_MISMATCH, line.id());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> build(reference, invoice, List.of(match), List.of(line), List.of(discrepancy), List.of()));
        assertFalse(error.getMessage().contains("invalid page"));
    }

    @Test
    void rejectsWrongAnalysisOrWrongSourceRole() {
        Side reference = side(DocumentRole.REFERENCE, 1, List.of());
        Side invoice = side(DocumentRole.INVOICE, 1, List.of());
        ConfirmedEvidenceSource otherAnalysis = source(
                document(DocumentRole.REFERENCE, UUID.randomUUID(), 1), List.of());

        assertThrows(IllegalArgumentException.class,
                () -> builder.build(emptyLineResult(), emptyDocumentResult(), List.of(),
                        otherAnalysis, invoice.source(), CREATED));

        Side wrongRoleInvoice = side(DocumentRole.REFERENCE, 1, List.of());
        assertThrows(IllegalArgumentException.class,
                () -> builder.build(emptyLineResult(), emptyDocumentResult(), List.of(),
                        reference.source(), wrongRoleInvoice.source(), CREATED));
    }

    @Test
    void evidenceSourceRequiresConfirmedExtractionAndMatchingDocumentIdentity() {
        Side side = side(DocumentRole.REFERENCE, 1, List.of(line(0, 1, "source", null)));
        assertThrows(IllegalArgumentException.class,
                () -> new ConfirmedEvidenceSource(side.source().document(), UUID.randomUUID(),
                        side.source().extraction(), side.lineIds()));

        ExtractedDocument draft = extraction(List.of(line(0, 1, "source", null)), false);
        assertThrows(IllegalArgumentException.class,
                () -> new ConfirmedEvidenceSource(side.source().document(), side.source().document().id(),
                        draft, side.lineIds()));
    }

    @Test
    void rejectsLineMatchWhoseReferenceIdentityBelongsToInvoiceExtraction() {
        Side reference = side(DocumentRole.REFERENCE, 2, List.of(line(0, 1, "reference", null)));
        Side invoice = side(DocumentRole.INVOICE, 2, List.of(line(0, 1, "invoice", null)));
        LineItemMatch wrongSide = match(invoice.lineIds().getFirst(), reference.lineIds().getFirst(),
                LineMatchStatus.MATCHED);
        ReconciliationLine line = reconciliationLine(wrongSide.id(), ReconciliationLineStatus.DIFFERENT);

        assertThrows(IllegalArgumentException.class,
                () -> build(reference, invoice, List.of(wrongSide), List.of(line),
                        List.of(discrepancy(DiscrepancyType.QUANTITY_MISMATCH, line.id())), List.of()));
    }

    @Test
    void returnsNoRowsWhenThereAreNoDiscrepancies() {
        Side reference = side(DocumentRole.REFERENCE, 1, List.of());
        Side invoice = side(DocumentRole.INVOICE, 1, List.of());
        assertTrue(build(reference, invoice, List.of(), List.of(), List.of(), List.of()).isEmpty());
    }

    private static List<DiscrepancyEvidence> build(
            Side reference,
            Side invoice,
            List<LineItemMatch> matches,
            List<ReconciliationLine> lines,
            List<Discrepancy> lineDiscrepancies,
            List<Discrepancy> documentDiscrepancies) {
        return new DiscrepancyEvidenceBuilder().build(
                new LineReconciliationResult(ANALYSIS_ID, lines, lineDiscrepancies),
                documentResult(documentDiscrepancies), matches,
                reference.source(), invoice.source(), CREATED);
    }

    private static LineReconciliationResult emptyLineResult() {
        return new LineReconciliationResult(ANALYSIS_ID, List.of(), List.of());
    }

    private static DocumentReconciliationResult emptyDocumentResult() {
        return documentResult(List.of());
    }

    private static DocumentReconciliationResult documentResult(List<Discrepancy> discrepancies) {
        return new DocumentReconciliationResult(ANALYSIS_ID, true, null, discrepancies);
    }

    private static Side side(DocumentRole role, int pageCount, List<ExtractedLineItem> lines) {
        Document document = document(role, ANALYSIS_ID, pageCount);
        List<UUID> ids = lines.stream().map(ignored -> UUID.randomUUID()).toList();
        return new Side(source(document, ids, lines), ids);
    }

    private static ConfirmedEvidenceSource source(Document document, List<ExtractedLineItem> lines) {
        List<UUID> ids = lines.stream().map(ignored -> UUID.randomUUID()).toList();
        return source(document, ids, lines);
    }

    private static ConfirmedEvidenceSource source(
            Document document,
            List<UUID> ids,
            List<ExtractedLineItem> lines) {
        return new ConfirmedEvidenceSource(document, document.id(), extraction(lines, true), ids);
    }

    private static Document document(DocumentRole role, UUID analysisId, int pageCount) {
        UUID id = UUID.randomUUID();
        return Document.createUploaded(id, analysisId, role, "source.pdf", "application/pdf",
                100, pageCount, "a".repeat(64), "analysis/" + id, null, CONFIRMED);
    }

    private static ExtractedDocument extraction(List<ExtractedLineItem> lines, boolean confirmed) {
        ExtractedDocument draft = ExtractedDocument.draft(
                ExtractionSource.AI, null, null, null, "USD", null, null, null, null, lines);
        return confirmed ? draft.confirm(CONFIRMED) : draft;
    }

    private static ExtractedLineItem line(int position, Integer page, String sourceText, BoundingBox box) {
        return new ExtractedLineItem(position, "SKU-" + position, "Widget " + position,
                BigDecimal.ONE, "each", BigDecimal.TEN, null, null, BigDecimal.TEN,
                page, sourceText, box);
    }

    private static LineItemMatch match(UUID referenceId, UUID invoiceId, LineMatchStatus status) {
        return new LineItemMatch(UUID.randomUUID(), ANALYSIS_ID, referenceId, invoiceId, status,
                status == LineMatchStatus.UNMATCHED_REFERENCE || status == LineMatchStatus.UNMATCHED_INVOICE
                        ? LineMatchMethod.NONE : LineMatchMethod.MANUAL,
                null, 0, null, CREATED, CREATED);
    }

    private static ReconciliationLine reconciliationLine(UUID matchId, ReconciliationLineStatus status) {
        return switch (status) {
            case MATCHED, DIFFERENT -> new ReconciliationLine(
                    UUID.randomUUID(), ANALYSIS_ID, matchId, status,
                    null, null, "Reference", "Invoice", null, null, null, null, null, null,
                    null, null, null, CREATED);
            case MISSING -> new ReconciliationLine(
                    UUID.randomUUID(), ANALYSIS_ID, matchId, status,
                    null, null, "Reference", null, null, null, null, null, null, null,
                    null, null, null, CREATED);
            case UNEXPECTED -> new ReconciliationLine(
                    UUID.randomUUID(), ANALYSIS_ID, matchId, status,
                    null, null, null, "Invoice", null, null, null, null, null, null,
                    null, null, null, CREATED);
        };
    }

    private static Discrepancy discrepancy(DiscrepancyType type, UUID lineId) {
        return new Discrepancy(UUID.randomUUID(), ANALYSIS_ID, lineId, type,
                "reference", "invoice", null, null, "A neutral discrepancy explanation.",
                DiscrepancyResolutionStatus.OPEN, null, null, 0, null, CREATED, CREATED);
    }

    private record Side(ConfirmedEvidenceSource source, List<UUID> lineIds) {
    }
}
