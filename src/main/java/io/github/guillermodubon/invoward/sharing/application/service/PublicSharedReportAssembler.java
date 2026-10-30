package io.github.guillermodubon.invoward.sharing.application.service;

import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationSummary;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLine;
import io.github.guillermodubon.invoward.sharing.application.model.PublicSharedReport;

import java.time.Instant;
import java.util.Objects;

/** Copies only approved persisted reconciliation data into the public application snapshot. */
public final class PublicSharedReportAssembler {

    public PublicSharedReport assemble(Analysis analysis, ReconciliationResult reconciliation, Instant generatedAt) {
        Objects.requireNonNull(analysis, "analysis must not be null");
        Objects.requireNonNull(reconciliation, "reconciliation must not be null");
        Objects.requireNonNull(generatedAt, "generatedAt must not be null");
        if (!analysis.id().equals(reconciliation.analysisId())
                || analysis.reconciliationStatus() == null
                || analysis.reconciliationStatus() != reconciliation.reconciliationStatus()) {
            throw new IllegalArgumentException("Analysis and persisted reconciliation do not match");
        }

        return new PublicSharedReport(
                generatedAt,
                analysis.supplierName(),
                analysis.referenceType(),
                analysis.referenceNumber(),
                analysis.invoiceNumber(),
                analysis.currency(),
                analysis.reconciliationStatus(),
                reconciliation.referenceTotal(),
                reconciliation.invoiceTotal(),
                reconciliation.difference(),
                new PublicSharedReport.Tolerance(
                        reconciliation.priceTolerance().priceTolerancePercent(),
                        reconciliation.priceTolerance().priceToleranceAbsolute()),
                summary(reconciliation.summary(), reconciliation.discrepancies().size()),
                reconciliation.lines().stream().map(PublicSharedReportAssembler::line).toList(),
                reconciliation.discrepancies().stream()
                        .map(PublicSharedReportAssembler::discrepancy)
                        .toList());
    }

    private static PublicSharedReport.Summary summary(ReconciliationSummary summary, int discrepancyCount) {
        return new PublicSharedReport.Summary(
                summary.lineCount(),
                summary.matchedCount(),
                summary.differentCount(),
                summary.missingCount(),
                summary.unexpectedCount(),
                discrepancyCount);
    }

    private static PublicSharedReport.Line line(ReconciliationLine line) {
        return new PublicSharedReport.Line(
                line.status(),
                line.referenceItemCode(),
                line.invoiceItemCode(),
                line.referenceDescription(),
                line.invoiceDescription(),
                line.referenceQuantity(),
                line.invoiceQuantity(),
                line.referenceUnitPrice(),
                line.invoiceUnitPrice(),
                line.referenceLineTotal(),
                line.invoiceLineTotal(),
                line.quantityDifference(),
                line.unitPriceDifference(),
                line.lineTotalDifference());
    }

    private static PublicSharedReport.PublicDiscrepancy discrepancy(Discrepancy discrepancy) {
        return new PublicSharedReport.PublicDiscrepancy(
                discrepancy.type(),
                discrepancy.explanation(),
                discrepancy.referenceValue(),
                discrepancy.invoiceValue(),
                discrepancy.absoluteDifference(),
                discrepancy.percentageDifference(),
                discrepancy.resolutionStatus());
    }
}
