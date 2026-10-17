package io.github.guillermodubon.invoward.reporting.infrastructure.pdf;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.reporting.application.model.ReportDiscrepancySnapshot;
import io.github.guillermodubon.invoward.reporting.application.model.ReportLineSnapshot;
import io.github.guillermodubon.invoward.reporting.application.model.ReportSnapshot;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Renders a self-contained XHTML presentation from an immutable report snapshot. */
@Component
public final class ReconciliationReportHtmlRenderer {

    private static final String EMPTY_VALUE = "—";
    private static final DateTimeFormatter GENERATED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private static final String INLINE_STYLES = """
            @page { size: A4; margin: 16mm 12mm; }
            body { color: #202124; font-family: sans-serif; font-size: 9pt; }
            h1 { font-size: 20pt; margin: 0 0 14pt; }
            h2 { border-bottom: 1px solid #9aa0a6; font-size: 13pt; margin: 18pt 0 7pt; padding-bottom: 4pt; }
            table { border-collapse: collapse; table-layout: fixed; width: 100%; }
            th, td { border: 1px solid #9aa0a6; padding: 4pt; text-align: left; vertical-align: top; word-wrap: break-word; }
            th { background-color: #f1f3f4; }
            thead { display: table-header-group; }
            tr { page-break-inside: avoid; }
            .metadata th { width: 24%; }
            .numeric { text-align: right; }
            .generated-at { margin-top: 18pt; }
            """;

    public String render(ReportSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        StringBuilder html = new StringBuilder(8192);
        html.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<html><head><meta charset=\"UTF-8\"/><title>InvoWard Reconciliation Report</title>")
                .append("<style type=\"text/css\">").append(INLINE_STYLES).append("</style></head><body>")
                .append("<h1>InvoWard Reconciliation Report</h1>");

        appendDocuments(html, snapshot);
        appendSummary(html, snapshot);
        appendTolerance(html, snapshot);
        appendFinancialComparison(html, snapshot);
        if (snapshot.duplicateWarning()) {
            html.append("<p class=\"duplicate-warning\">A possible duplicate invoice was detected.</p>");
        }
        appendDiscrepancies(html, snapshot);
        appendLineComparison(html, snapshot);
        html.append("<p class=\"generated-at\"><strong>Generated at:</strong> ")
                .append(escape(GENERATED_AT_FORMAT.format(snapshot.generatedAt())))
                .append("</p></body></html>");
        return html.toString();
    }

    private static void appendDocuments(StringBuilder html, ReportSnapshot snapshot) {
        html.append("<section><h2>Documents</h2><table class=\"metadata\"><tbody>");
        appendPair(html, "Supplier", snapshot.supplierName());
        appendPair(html, "Reference type", snapshot.referenceType());
        appendPair(html, "Reference number", snapshot.referenceNumber());
        appendPair(html, "Reference date", date(snapshot.referenceDate()));
        appendPair(html, "Invoice number", snapshot.invoiceNumber());
        appendPair(html, "Invoice date", date(snapshot.invoiceDate()));
        appendPair(html, "Currency", snapshot.currency());
        html.append("</tbody></table></section>");
    }

    private static void appendSummary(StringBuilder html, ReportSnapshot snapshot) {
        ReportSnapshot.Summary summary = snapshot.summary();
        html.append("<section><h2>Result summary</h2><table><tbody>");
        appendPair(html, "Result", status(snapshot.reconciliationStatus()));
        appendPair(html, "Line count", Long.toString(summary.lineCount()));
        appendPair(html, "Matched lines", Long.toString(summary.matchedCount()));
        appendPair(html, "Different lines", Long.toString(summary.differentCount()));
        appendPair(html, "Missing lines", Long.toString(summary.missingCount()));
        appendPair(html, "Unexpected lines", Long.toString(summary.unexpectedCount()));
        appendPair(html, "Open discrepancies", Long.toString(summary.openDiscrepancyCount()));
        appendPair(html, "Accepted discrepancies", Long.toString(summary.acceptedDiscrepancyCount()));
        appendPair(html, "Resolved discrepancies", Long.toString(summary.resolvedDiscrepancyCount()));
        html.append("</tbody></table></section>");
    }

    private static void appendTolerance(StringBuilder html, ReportSnapshot snapshot) {
        html.append("<section><h2>Price tolerance</h2><table><tbody>");
        appendPair(html, "Percentage tolerance", withSuffix(amount(snapshot.priceTolerancePercent()), "%"));
        appendPair(html, "Absolute tolerance", amount(snapshot.priceToleranceAbsolute()));
        html.append("</tbody></table></section>");
    }

    private static void appendFinancialComparison(StringBuilder html, ReportSnapshot snapshot) {
        html.append("<section><h2>Financial comparison</h2><table><thead><tr>")
                .append("<th>Reference total</th><th>Invoice total</th><th>Signed difference</th>")
                .append("</tr></thead><tbody><tr>");
        appendCell(html, amount(snapshot.referenceTotal()));
        appendCell(html, amount(snapshot.invoiceTotal()));
        appendCell(html, amount(snapshot.difference()));
        html.append("</tr></tbody></table></section>");
    }

    private static void appendDiscrepancies(StringBuilder html, ReportSnapshot snapshot) {
        html.append("<section><h2>Discrepancies</h2>");
        if (snapshot.discrepancies().isEmpty()) {
            html.append("<p>None</p></section>");
            return;
        }
        html.append("<table><thead><tr><th>Type</th><th>Reference value</th><th>Invoice value</th>")
                .append("<th>Absolute difference</th><th>Percentage difference</th><th>Explanation</th>")
                .append("<th>Resolution status</th><th>Resolution reason</th><th>Resolution note</th>")
                .append("</tr></thead><tbody>");
        for (ReportDiscrepancySnapshot discrepancy : snapshot.discrepancies()) {
            html.append("<tr>");
            appendCell(html, discrepancy.type().name());
            appendCell(html, display(discrepancy.referenceValue()));
            appendCell(html, display(discrepancy.invoiceValue()));
            appendCell(html, amount(discrepancy.absoluteDifference()));
            appendCell(html, withSuffix(amount(discrepancy.percentageDifference()), "%"));
            appendCell(html, display(discrepancy.explanation()));
            appendCell(html, discrepancy.resolutionStatus().name());
            appendCell(html, discrepancy.resolutionReason() == null
                    ? EMPTY_VALUE : discrepancy.resolutionReason().name());
            appendCell(html, display(discrepancy.resolutionNote()));
            html.append("</tr>");
        }
        html.append("</tbody></table></section>");
    }

    private static void appendLineComparison(StringBuilder html, ReportSnapshot snapshot) {
        html.append("<section><h2>Line comparison</h2>");
        if (snapshot.lines().isEmpty()) {
            html.append("<p>None</p></section>");
            return;
        }
        html.append("<table><thead><tr><th>Status</th><th>Reference code</th><th>Reference description</th>")
                .append("<th>Invoice code</th><th>Invoice description</th><th>Reference quantity</th>")
                .append("<th>Invoice quantity</th><th>Quantity difference</th><th>Reference unit price</th>")
                .append("<th>Invoice unit price</th><th>Unit price difference</th><th>Reference line total</th>")
                .append("<th>Invoice line total</th><th>Line total difference</th></tr></thead><tbody>");
        for (ReportLineSnapshot line : snapshot.lines()) {
            html.append("<tr>");
            appendCell(html, line.status().name());
            appendCell(html, display(line.referenceCode()));
            appendCell(html, display(line.referenceDescription()));
            appendCell(html, display(line.invoiceCode()));
            appendCell(html, display(line.invoiceDescription()));
            appendCell(html, amount(line.referenceQuantity()));
            appendCell(html, amount(line.invoiceQuantity()));
            appendCell(html, amount(line.quantityDifference()));
            appendCell(html, amount(line.referenceUnitPrice()));
            appendCell(html, amount(line.invoiceUnitPrice()));
            appendCell(html, amount(line.unitPriceDifference()));
            appendCell(html, amount(line.referenceLineTotal()));
            appendCell(html, amount(line.invoiceLineTotal()));
            appendCell(html, amount(line.lineTotalDifference()));
            html.append("</tr>");
        }
        html.append("</tbody></table></section>");
    }

    private static void appendPair(StringBuilder html, String label, String value) {
        html.append("<tr><th>");
        appendText(html, label);
        html.append("</th><td>");
        appendText(html, display(value));
        html.append("</td></tr>");
    }

    private static void appendCell(StringBuilder html, String value) {
        html.append("<td>");
        appendText(html, display(value));
        html.append("</td>");
    }

    private static void appendText(StringBuilder html, String value) {
        html.append(escape(value));
    }

    private static String escape(String value) {
        return HtmlEscaper.escape(value);
    }

    private static String display(String value) {
        return value == null || value.isBlank() ? EMPTY_VALUE : value;
    }

    private static String amount(BigDecimal value) {
        return value == null ? EMPTY_VALUE : value.toPlainString();
    }

    private static String date(LocalDate value) {
        return value == null ? EMPTY_VALUE : value.toString();
    }

    private static String withSuffix(String value, String suffix) {
        return EMPTY_VALUE.equals(value) ? EMPTY_VALUE : value + suffix;
    }

    private static String status(AnalysisReconciliationStatus status) {
        return status == AnalysisReconciliationStatus.MATCHED ? "MATCHED" : "REVIEW REQUIRED";
    }
}
