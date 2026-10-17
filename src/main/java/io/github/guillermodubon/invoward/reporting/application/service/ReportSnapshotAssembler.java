package io.github.guillermodubon.invoward.reporting.application.service;

import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationSummary;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLine;
import io.github.guillermodubon.invoward.reporting.application.model.ReportDiscrepancySnapshot;
import io.github.guillermodubon.invoward.reporting.application.model.ReportLineSnapshot;
import io.github.guillermodubon.invoward.reporting.application.model.ReportSnapshot;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** Maps authorized Analysis metadata and persisted reconciliation projection into a PDF snapshot. */
public final class ReportSnapshotAssembler {

    private ReportSnapshotAssembler() {
    }

    /**
     * Assembles report data without recalculating financial values or retaining infrastructure identifiers.
     * Dates are supplied from the confirmed reference and invoice extraction headers.
     */
    public static ReportSnapshot assemble(
            Analysis analysis,
            ReconciliationResult reconciliation,
            LocalDate invoiceDate,
            LocalDate referenceDate,
            Instant generatedAt) {
        Objects.requireNonNull(analysis, "analysis must not be null");
        Objects.requireNonNull(reconciliation, "reconciliation must not be null");
        if (!analysis.id().equals(reconciliation.analysisId())
                || analysis.status() != reconciliation.analysisStatus()
                || analysis.reconciliationStatus() != reconciliation.reconciliationStatus()
                || !analysis.priceTolerance().equals(reconciliation.priceTolerance())
                || !Objects.equals(analysis.referenceTotal(), reconciliation.referenceTotal())
                || !Objects.equals(analysis.invoicedTotal(), reconciliation.invoiceTotal())
                || !Objects.equals(analysis.difference(), reconciliation.difference())) {
            throw new IllegalArgumentException("Analysis and reconciliation projection must describe the same state");
        }

        return new ReportSnapshot(
                analysis.supplierName(),
                analysis.invoiceNumber(),
                analysis.referenceNumber(),
                analysis.referenceType(),
                invoiceDate,
                referenceDate,
                analysis.currency(),
                reconciliation.reconciliationStatus(),
                reconciliation.referenceTotal(),
                reconciliation.invoiceTotal(),
                reconciliation.difference(),
                reconciliation.priceTolerance().priceTolerancePercent(),
                reconciliation.priceTolerance().priceToleranceAbsolute(),
                reconciliation.duplicateInvoice().possibleDuplicate(),
                summary(reconciliation.summary()),
                reconciliation.lines().stream().map(ReportSnapshotAssembler::lineSnapshot).toList(),
                reconciliation.discrepancies().stream()
                        .map(ReportSnapshotAssembler::discrepancySnapshot).toList(),
                generatedAt);
    }

    private static ReportSnapshot.Summary summary(ReconciliationSummary summary) {
        return new ReportSnapshot.Summary(
                summary.lineCount(), summary.matchedCount(), summary.differentCount(), summary.missingCount(),
                summary.unexpectedCount(), summary.openDiscrepancyCount(), summary.acceptedDiscrepancyCount(),
                summary.resolvedDiscrepancyCount());
    }

    private static ReportLineSnapshot lineSnapshot(ReconciliationLine line) {
        return new ReportLineSnapshot(
                line.status(),
                line.referenceItemCode(),
                line.referenceDescription(),
                line.invoiceItemCode(),
                line.invoiceDescription(),
                line.referenceQuantity(),
                line.invoiceQuantity(),
                line.quantityDifference(),
                line.referenceUnitPrice(),
                line.invoiceUnitPrice(),
                line.unitPriceDifference(),
                line.referenceLineTotal(),
                line.invoiceLineTotal(),
                line.lineTotalDifference());
    }

    private static ReportDiscrepancySnapshot discrepancySnapshot(Discrepancy discrepancy) {
        return new ReportDiscrepancySnapshot(
                discrepancy.type(),
                discrepancy.referenceValue(),
                discrepancy.invoiceValue(),
                discrepancy.absoluteDifference(),
                discrepancy.percentageDifference(),
                discrepancy.explanation(),
                discrepancy.resolutionStatus(),
                discrepancy.resolutionReason(),
                discrepancy.resolutionNote());
    }
}
