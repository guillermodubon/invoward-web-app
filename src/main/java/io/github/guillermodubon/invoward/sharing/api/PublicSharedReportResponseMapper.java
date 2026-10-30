package io.github.guillermodubon.invoward.sharing.api;

import io.github.guillermodubon.invoward.sharing.api.model.PublicSharedReportResponse;
import io.github.guillermodubon.invoward.sharing.application.model.PublicSharedReport;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Maps the public application snapshot to its explicit HTTP whitelist. */
@Component
public final class PublicSharedReportResponseMapper {

    public PublicSharedReportResponse map(PublicSharedReport report) {
        Objects.requireNonNull(report, "report must not be null");
        PublicSharedReport.Summary summary = report.summary();
        return new PublicSharedReportResponse(
                report.reportGeneratedAt(),
                report.supplierName(),
                report.referenceType(),
                report.referenceNumber(),
                report.invoiceNumber(),
                report.currency(),
                report.reconciliationStatus(),
                report.referenceTotal(),
                report.invoiceTotal(),
                report.difference(),
                new PublicSharedReportResponse.ToleranceResponse(
                        report.tolerance().percentage(), report.tolerance().absolute()),
                new PublicSharedReportResponse.SummaryResponse(
                        summary.lineCount(),
                        summary.matchedCount(),
                        summary.differentCount(),
                        summary.missingCount(),
                        summary.unexpectedCount(),
                        summary.discrepancyCount()),
                report.lines().stream().map(PublicSharedReportResponseMapper::line).toList(),
                report.discrepancies().stream()
                        .map(PublicSharedReportResponseMapper::discrepancy)
                        .toList());
    }

    private static PublicSharedReportResponse.LineResponse line(PublicSharedReport.Line line) {
        return new PublicSharedReportResponse.LineResponse(
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

    private static PublicSharedReportResponse.DiscrepancyResponse discrepancy(
            PublicSharedReport.PublicDiscrepancy discrepancy) {
        return new PublicSharedReportResponse.DiscrepancyResponse(
                discrepancy.type(),
                discrepancy.explanation(),
                discrepancy.referenceValue(),
                discrepancy.invoiceValue(),
                discrepancy.absoluteDifference(),
                discrepancy.percentageDifference(),
                discrepancy.resolutionStatus());
    }
}
