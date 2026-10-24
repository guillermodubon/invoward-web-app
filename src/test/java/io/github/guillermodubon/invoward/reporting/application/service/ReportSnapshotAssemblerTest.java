package io.github.guillermodubon.invoward.reporting.application.service;

import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceCandidate;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceSignal;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceWarning;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationSummary;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionReason;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLine;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLineStatus;
import io.github.guillermodubon.invoward.reporting.application.model.ReportSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportSnapshotAssemblerTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final Instant CREATED = NOW.minusSeconds(60);
    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final UUID PRIVATE_USER_ID = UUID.randomUUID();
    private static final UUID PRIVATE_CANDIDATE_ID = UUID.randomUUID();
    private static final PriceTolerance TOLERANCE = new PriceTolerance(
            new BigDecimal("3"), new BigDecimal("5.0000"));

    @Test
    void assemblesPersistedFieldsAndPreservesTheReconciliationProjectionOrdering() {
        Analysis analysis = analysis(ANALYSIS_ID, "private-supplier-key-sentinel");
        ReconciliationLine different = differentLine(UUID.randomUUID(), "Reference description");
        ReconciliationLine missing = missingLine(UUID.randomUUID());
        ReconciliationLine unexpected = unexpectedLine(UUID.randomUUID());
        Discrepancy open = discrepancy(
                UUID.randomUUID(), different.id(), DiscrepancyType.UNIT_PRICE_MISMATCH,
                DiscrepancyResolutionStatus.OPEN, null, null, "unit price explanation");
        Discrepancy accepted = discrepancy(
                UUID.randomUUID(), null, DiscrepancyType.TOTAL_MISMATCH,
                DiscrepancyResolutionStatus.ACCEPTED, DiscrepancyResolutionReason.APPROVED_CHANGE,
                "current resolution note", "document total explanation");
        ReconciliationResult result = result(
                analysis,
                new DuplicateInvoiceWarning(true, List.of(new DuplicateInvoiceCandidate(
                        PRIVATE_CANDIDATE_ID, CREATED, "private-invoice-number", List.of(
                                DuplicateInvoiceSignal.SAME_INVOICE_NUMBER)))),
                new ReconciliationSummary(3, 0, 1, 1, 1, 1, 1, 0),
                List.of(different, missing, unexpected), List.of(open, accepted));

        ReportSnapshot snapshot = ReportSnapshotAssembler.assemble(
                analysis, result, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-09-28"), NOW);

        assertEquals("Supplier Example", snapshot.supplierName());
        assertEquals("INV-200", snapshot.invoiceNumber());
        assertEquals("REF-100", snapshot.referenceNumber());
        assertEquals("INVOICE", snapshot.referenceType());
        assertEquals(LocalDate.parse("2026-10-01"), snapshot.invoiceDate());
        assertEquals(LocalDate.parse("2026-09-28"), snapshot.referenceDate());
        assertEquals("USD", snapshot.currency());
        assertEquals(AnalysisReconciliationStatus.REVIEW_REQUIRED, snapshot.reconciliationStatus());
        assertEquals(new BigDecimal("100.0000"), snapshot.referenceTotal());
        assertEquals(new BigDecimal("97.5000"), snapshot.invoiceTotal());
        assertEquals(new BigDecimal("-2.5000"), snapshot.difference());
        assertEquals(new BigDecimal("3"), snapshot.priceTolerancePercent());
        assertEquals(new BigDecimal("5.0000"), snapshot.priceToleranceAbsolute());
        assertTrue(snapshot.duplicateWarning());
        assertEquals(new ReportSnapshot.Summary(3, 0, 1, 1, 1, 1, 1, 0), snapshot.summary());
        assertEquals(List.of(ReconciliationLineStatus.DIFFERENT, ReconciliationLineStatus.MISSING,
                ReconciliationLineStatus.UNEXPECTED), snapshot.lines().stream()
                .map(line -> line.status()).toList());
        assertEquals(List.of(DiscrepancyType.UNIT_PRICE_MISMATCH, DiscrepancyType.TOTAL_MISMATCH),
                snapshot.discrepancies().stream().map(item -> item.type()).toList());
        assertEquals(DiscrepancyResolutionStatus.ACCEPTED, snapshot.discrepancies().get(1).resolutionStatus());
        assertEquals(DiscrepancyResolutionReason.APPROVED_CHANGE,
                snapshot.discrepancies().get(1).resolutionReason());
        assertEquals("current resolution note", snapshot.discrepancies().get(1).resolutionNote());
        assertEquals(NOW, snapshot.generatedAt());

        assertEquals(new BigDecimal("2.2500"), snapshot.lines().get(0).unitPriceDifference());
        assertEquals(new BigDecimal("-2.5000"), snapshot.lines().get(0).lineTotalDifference());
        assertEquals("R-1", snapshot.lines().get(0).referenceCode());
        assertEquals("I-1", snapshot.lines().get(0).invoiceCode());
        assertEquals("Reference description", snapshot.lines().get(0).referenceDescription());
        assertEquals("Invoice description", snapshot.lines().get(0).invoiceDescription());
    }

    @Test
    void keepsOptionalValuesNullAndCopiesCollectionsImmutably() {
        Analysis analysis = analysis(ANALYSIS_ID, "private-supplier-key-sentinel");
        ReconciliationLine missing = missingLine(UUID.randomUUID());
        ReconciliationResult result = result(
                analysis, DuplicateInvoiceWarning.none(),
                new ReconciliationSummary(1, 0, 0, 1, 0, 0, 0, 0), List.of(missing), List.of());

        ReportSnapshot snapshot = ReportSnapshotAssembler.assemble(analysis, result, null, null, NOW);

        assertEquals(null, snapshot.invoiceDate());
        assertEquals(null, snapshot.referenceDate());
        assertEquals(null, snapshot.lines().get(0).invoiceCode());
        assertEquals(null, snapshot.lines().get(0).invoiceDescription());
        assertEquals(null, snapshot.lines().get(0).invoiceQuantity());
        assertEquals(null, snapshot.lines().get(0).lineTotalDifference());
        assertFalse(snapshot.duplicateWarning());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.lines().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.discrepancies().clear());
    }

    @Test
    void doesNotRetainOwnerDuplicateCandidateOrRawIdentifierData() {
        Analysis analysis = analysis(ANALYSIS_ID, "private-supplier-key-sentinel");
        ReconciliationLine line = differentLine(UUID.randomUUID(), "private-description-sentinel");
        Discrepancy discrepancy = discrepancy(
                UUID.randomUUID(), line.id(), DiscrepancyType.UNIT_PRICE_MISMATCH,
                DiscrepancyResolutionStatus.OPEN, null, null, "private-resolution-note-sentinel");
        ReconciliationResult result = result(
                analysis,
                new DuplicateInvoiceWarning(true, List.of(new DuplicateInvoiceCandidate(
                        PRIVATE_CANDIDATE_ID, CREATED, "private-invoice-number", List.of(
                                DuplicateInvoiceSignal.EXACT_INVOICE_FILE_HASH)))),
                new ReconciliationSummary(1, 0, 1, 0, 0, 1, 0, 0), List.of(line), List.of(discrepancy));

        ReportSnapshot snapshot = ReportSnapshotAssembler.assemble(analysis, result, null, null, NOW);
        String safeSummary = snapshot.toString() + snapshot.lines().get(0).toString()
                + snapshot.discrepancies().get(0).toString();

        assertFalse(safeSummary.contains(ANALYSIS_ID.toString()));
        assertFalse(safeSummary.contains(PRIVATE_USER_ID.toString()));
        assertFalse(safeSummary.contains(PRIVATE_CANDIDATE_ID.toString()));
        assertFalse(safeSummary.contains("private-supplier-key-sentinel"));
        assertFalse(safeSummary.contains("private-description-sentinel"));
        assertFalse(safeSummary.contains("private-resolution-note-sentinel"));
        assertFalse(safeSummary.contains("private-invoice-number"));
        assertTrue(snapshot.duplicateWarning());
    }

    @Test
    void rejectsAnalysisAndReconciliationProjectionFromDifferentStates() {
        Analysis analysis = analysis(ANALYSIS_ID, "supplier-key");
        Analysis otherAnalysis = analysis(UUID.randomUUID(), "other-key");
        ReconciliationResult mismatched = result(
                otherAnalysis, DuplicateInvoiceWarning.none(),
                new ReconciliationSummary(0, 0, 0, 0, 0, 0, 0, 0), List.of(), List.of());

        assertThrows(IllegalArgumentException.class,
                () -> ReportSnapshotAssembler.assemble(analysis, mismatched, null, null, NOW));
    }

    private static Analysis analysis(UUID analysisId, String supplierKey) {
        return new Analysis(
                analysisId,
                new RegisteredUserOwner(PRIVATE_USER_ID),
                AnalysisStatus.GENERATING_REPORT,
                AnalysisReviewStatus.REVIEWED,
                null,
                AnalysisReconciliationStatus.REVIEW_REQUIRED,
                "Supplier Example",
                supplierKey,
                "INVOICE",
                "REF-100",
                "INV-200",
                "USD",
                new BigDecimal("100.0000"),
                new BigDecimal("97.5000"),
                new BigDecimal("-2.5000"),
                TOLERANCE,
                false,
                null,
                null,
                0,
                null,
                null,
                CREATED,
                NOW);
    }

    private static ReconciliationResult result(
            Analysis analysis,
            DuplicateInvoiceWarning warning,
            ReconciliationSummary summary,
            List<ReconciliationLine> lines,
            List<Discrepancy> discrepancies) {
        return new ReconciliationResult(
                analysis.id(), analysis.status(), analysis.reconciliationStatus(), analysis.priceTolerance(),
                analysis.referenceTotal(), analysis.invoicedTotal(), analysis.difference(), warning,
                summary, lines, discrepancies);
    }

    private static ReconciliationLine differentLine(UUID lineId, String descriptionSentinel) {
        return new ReconciliationLine(
                lineId, ANALYSIS_ID, UUID.randomUUID(), ReconciliationLineStatus.DIFFERENT,
                "R-1", "I-1", descriptionSentinel,
                "Invoice description", new BigDecimal("2.0000"), new BigDecimal("2.0000"),
                new BigDecimal("10.0000"), new BigDecimal("12.2500"),
                new BigDecimal("20.0000"), new BigDecimal("17.5000"),
                BigDecimal.ZERO, new BigDecimal("2.2500"), new BigDecimal("-2.5000"), NOW);
    }

    private static ReconciliationLine missingLine(UUID lineId) {
        return new ReconciliationLine(
                lineId, ANALYSIS_ID, UUID.randomUUID(), ReconciliationLineStatus.MISSING,
                "R-MISSING", null, "Reference-only item", null,
                BigDecimal.ONE, null, new BigDecimal("4.0000"), null,
                new BigDecimal("4.0000"), null, null, null, null, NOW);
    }

    private static ReconciliationLine unexpectedLine() {
        return unexpectedLine(UUID.randomUUID());
    }

    private static ReconciliationLine unexpectedLine(UUID lineId) {
        return new ReconciliationLine(
                lineId, ANALYSIS_ID, UUID.randomUUID(), ReconciliationLineStatus.UNEXPECTED,
                null, "I-UNEXPECTED", null, "Invoice-only item",
                null, BigDecimal.ONE, null, new BigDecimal("8.0000"),
                null, new BigDecimal("8.0000"), null, null, null, NOW);
    }

    private static Discrepancy discrepancy(
            UUID id,
            UUID lineId,
            DiscrepancyType type,
            DiscrepancyResolutionStatus status,
            DiscrepancyResolutionReason reason,
            String note,
            String explanation) {
        boolean resolved = status != DiscrepancyResolutionStatus.OPEN;
        return new Discrepancy(
                id, ANALYSIS_ID, lineId, type, "100.0000", "97.5000",
                new BigDecimal("2.5000"), new BigDecimal("2.500000"), explanation,
                status, reason, note, resolved ? 1 : 0, resolved ? NOW : null, CREATED, NOW);
    }
}
