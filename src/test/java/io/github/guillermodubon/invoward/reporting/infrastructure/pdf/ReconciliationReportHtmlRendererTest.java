package io.github.guillermodubon.invoward.reporting.infrastructure.pdf;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionReason;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLineStatus;
import io.github.guillermodubon.invoward.reporting.application.model.ReportDiscrepancySnapshot;
import io.github.guillermodubon.invoward.reporting.application.model.ReportLineSnapshot;
import io.github.guillermodubon.invoward.reporting.application.model.ReportSnapshot;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationReportHtmlRendererTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-10-14T12:00:00Z");
    private final ReconciliationReportHtmlRenderer renderer = new ReconciliationReportHtmlRenderer();

    @Test
    void rendersEveryRequiredSectionAndCompletePersistedLineAndDiscrepancyData() {
        String html = renderer.render(snapshot(
                "Northwind Supplies", "ACME-INV-44", "RFQ-13", "QUOTE", LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-09-28"), "USD", AnalysisReconciliationStatus.REVIEW_REQUIRED,
                true, List.of(completeLine(), missingLine(), unexpectedLine()), List.of(completeDiscrepancy())));

        assertTrue(html.contains("InvoWard Reconciliation Report"));
        assertTrue(html.contains("<h2>Documents</h2>"));
        assertTrue(html.contains("Northwind Supplies"));
        assertTrue(html.contains("QUOTE"));
        assertTrue(html.contains("RFQ-13"));
        assertTrue(html.contains("2026-09-28"));
        assertTrue(html.contains("ACME-INV-44"));
        assertTrue(html.contains("2026-10-01"));
        assertTrue(html.contains("USD"));
        assertTrue(html.contains("<h2>Result summary</h2>"));
        assertTrue(html.contains("REVIEW REQUIRED"));
        assertTrue(html.contains("Matched lines"));
        assertTrue(html.contains("Different lines"));
        assertTrue(html.contains("Missing lines"));
        assertTrue(html.contains("Unexpected lines"));
        assertTrue(html.contains("Open discrepancies"));
        assertTrue(html.contains("Accepted discrepancies"));
        assertTrue(html.contains("Resolved discrepancies"));
        assertTrue(html.contains("<h2>Price tolerance</h2>"));
        assertTrue(html.contains("3%"));
        assertTrue(html.contains("5.0000"));
        assertTrue(html.contains("<h2>Financial comparison</h2>"));
        assertTrue(html.contains("100.0000"));
        assertTrue(html.contains("97.5000"));
        assertTrue(html.contains("-2.5000"));
        assertTrue(html.contains("A possible duplicate invoice was detected."));
        assertTrue(html.contains("<h2>Discrepancies</h2>"));
        assertTrue(html.contains("UNIT_PRICE_MISMATCH"));
        assertTrue(html.contains("The invoiced unit price differs beyond tolerance."));
        assertTrue(html.contains("RESOLVED"));
        assertTrue(html.contains("OTHER"));
        assertTrue(html.contains("Confirmed after review."));
        assertTrue(html.contains("<h2>Line comparison</h2>"));
        assertTrue(html.contains("REF-1"));
        assertTrue(html.contains("INV-1"));
        assertTrue(html.contains("Reference item description"));
        assertTrue(html.contains("Invoice item description"));
        assertTrue(html.contains("REF-MISSING"));
        assertTrue(html.contains("Reference-only item"));
        assertTrue(html.contains("UNEXPECTED"));
        assertTrue(html.contains("INV-UNEXPECTED"));
        assertTrue(html.contains("Invoice-only item"));
        assertTrue(html.contains("<th>Quantity difference</th>"));
        assertTrue(html.contains("<th>Unit price difference</th>"));
        assertTrue(html.contains("<th>Line total difference</th>"));
        assertTrue(html.contains("2026-10-14 12:00 UTC"));

        for (String lineColumn : List.of(
                "Status", "Reference code", "Reference description", "Invoice code", "Invoice description",
                "Reference quantity", "Invoice quantity", "Quantity difference", "Reference unit price",
                "Invoice unit price", "Unit price difference", "Reference line total", "Invoice line total",
                "Line total difference")) {
            assertTrue(html.contains("<th>" + lineColumn + "</th>"), lineColumn);
        }
        for (String discrepancyColumn : List.of(
                "Type", "Reference value", "Invoice value", "Absolute difference", "Percentage difference",
                "Explanation", "Resolution status", "Resolution reason", "Resolution note")) {
            assertTrue(html.contains("<th>" + discrepancyColumn + "</th>"), discrepancyColumn);
        }
    }

    @Test
    void rendersMissingOptionalValuesAsANeutralDash() {
        ReportDiscrepancySnapshot discrepancy = new ReportDiscrepancySnapshot(
                DiscrepancyType.CURRENCY_MISMATCH, null, null, null, null, "Currencies differ.",
                DiscrepancyResolutionStatus.OPEN, null, null);
        ReportSnapshot snapshot = snapshot(
                null, null, null, null, null, null, null, AnalysisReconciliationStatus.MATCHED,
                false, List.of(), List.of(discrepancy));

        String html = renderer.render(snapshot);

        assertTrue(html.contains("<td>—</td>"));
        assertTrue(html.contains("<p>None</p>"));
        assertFalse(html.contains("A possible duplicate invoice was detected."));
    }

    @Test
    void encodesUntrustedMarkupAndKeepsUrlsAsTextWithoutResourceReferences() throws Exception {
        String payload = "<script>alert(1)</script><img src=\"https://attacker.example/x\" onerror=\"run()\">"
                + " & < > \" ' https://attacker.example/x file:///etc/passwd";
        ReportSnapshot snapshot = snapshot(
                payload, "INV-<script>bad</script>", "REF-1", "QUOTE", null, null, "USD",
                AnalysisReconciliationStatus.REVIEW_REQUIRED, false,
                List.of(lineWithDescription(payload)),
                List.of(new ReportDiscrepancySnapshot(
                        DiscrepancyType.TOTAL_MISMATCH, null, null, null, null, payload,
                        DiscrepancyResolutionStatus.RESOLVED, DiscrepancyResolutionReason.OTHER, payload)));

        String html = renderer.render(snapshot);

        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertTrue(html.contains("&lt;img src=&quot;https://attacker.example/x&quot;"));
        assertTrue(html.contains("&amp; &lt; &gt; &quot; &#39;"));
        assertFalse(html.contains("<script"));
        assertFalse(html.contains("<img"));
        assertFalse(html.contains("<iframe"));
        assertFalse(html.contains("<form"));
        assertFalse(html.contains("<a "));
        assertFalse(html.contains("<link"));
        assertFalse(Pattern.compile("<[^>]*\\b(?:src|href)\\s*=", Pattern.CASE_INSENSITIVE)
                .matcher(html).find());
        assertFalse(Pattern.compile("url\\s*\\(|@import", Pattern.CASE_INSENSITIVE).matcher(html).find());
        assertTrue(html.contains("https://attacker.example/x"));
        assertTrue(html.contains("file:///etc/passwd"));

        Document parsed = parseSecurely(html);
        assertEquals("html", parsed.getDocumentElement().getTagName());
        assertEquals(0, parsed.getElementsByTagName("script").getLength());
        assertEquals(0, parsed.getElementsByTagName("img").getLength());
    }

    @Test
    void outputContainsNoPrivateIdentifiersStorageMetadataOrDuplicateCandidateDetails() {
        String html = renderer.render(snapshot(
                "Supplier", "Invoice", "Reference", "INVOICE", null, null, "USD",
                AnalysisReconciliationStatus.REVIEW_REQUIRED, true, List.of(completeLine()), List.of()));

        assertFalse(html.contains("owner-private-id"));
        assertFalse(html.contains("guest-session-private-id"));
        assertFalse(html.contains("duplicate-analysis-private-id"));
        assertFalse(html.contains("supplierKey"));
        assertFalse(html.contains("storage-key"));
        assertFalse(html.contains("sha256"));
        assertFalse(html.contains("signed-url"));
        assertTrue(html.contains("A possible duplicate invoice was detected."));
    }

    private static Document parseSecurely(String html) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(html)));
    }

    private static ReportSnapshot snapshot(
            String supplierName,
            String invoiceNumber,
            String referenceNumber,
            String referenceType,
            LocalDate invoiceDate,
            LocalDate referenceDate,
            String currency,
            AnalysisReconciliationStatus result,
            boolean duplicate,
            List<ReportLineSnapshot> lines,
            List<ReportDiscrepancySnapshot> discrepancies) {
        long matched = lines.stream().filter(line -> line.status() == ReconciliationLineStatus.MATCHED).count();
        long different = lines.stream().filter(line -> line.status() == ReconciliationLineStatus.DIFFERENT).count();
        long missing = lines.stream().filter(line -> line.status() == ReconciliationLineStatus.MISSING).count();
        long unexpected = lines.stream().filter(line -> line.status() == ReconciliationLineStatus.UNEXPECTED).count();
        long open = discrepancies.stream()
                .filter(discrepancy -> discrepancy.resolutionStatus() == DiscrepancyResolutionStatus.OPEN).count();
        long accepted = discrepancies.stream()
                .filter(discrepancy -> discrepancy.resolutionStatus() == DiscrepancyResolutionStatus.ACCEPTED).count();
        long resolved = discrepancies.stream()
                .filter(discrepancy -> discrepancy.resolutionStatus() == DiscrepancyResolutionStatus.RESOLVED).count();
        return new ReportSnapshot(
                supplierName, invoiceNumber, referenceNumber, referenceType, invoiceDate, referenceDate, currency,
                result, new BigDecimal("100.0000"), new BigDecimal("97.5000"), new BigDecimal("-2.5000"),
                new BigDecimal("3"), new BigDecimal("5.0000"), duplicate,
                new ReportSnapshot.Summary(lines.size(), matched, different, missing, unexpected,
                        open, accepted, resolved), lines, discrepancies, GENERATED_AT);
    }

    private static ReportLineSnapshot completeLine() {
        return lineWithDescription("Reference item description");
    }

    private static ReportLineSnapshot lineWithDescription(String description) {
        return new ReportLineSnapshot(
                ReconciliationLineStatus.DIFFERENT,
                "REF-1", description, "INV-1", "Invoice item description",
                new BigDecimal("2.0000"), new BigDecimal("1.0000"), new BigDecimal("-1.0000"),
                new BigDecimal("10.0000"), new BigDecimal("12.0000"), new BigDecimal("2.0000"),
                new BigDecimal("20.0000"), new BigDecimal("17.5000"), new BigDecimal("-2.5000"));
    }

    private static ReportLineSnapshot missingLine() {
        return new ReportLineSnapshot(
                ReconciliationLineStatus.MISSING,
                "REF-MISSING", "Reference-only item", null, null,
                BigDecimal.ONE, null, null, new BigDecimal("4.0000"), null, null,
                new BigDecimal("4.0000"), null, null);
    }

    private static ReportLineSnapshot unexpectedLine() {
        return new ReportLineSnapshot(
                ReconciliationLineStatus.UNEXPECTED,
                null, null, "INV-UNEXPECTED", "Invoice-only item",
                null, BigDecimal.ONE, null, null, new BigDecimal("8.0000"), null,
                null, new BigDecimal("8.0000"), null);
    }

    private static ReportDiscrepancySnapshot completeDiscrepancy() {
        return new ReportDiscrepancySnapshot(
                DiscrepancyType.UNIT_PRICE_MISMATCH, "10.0000", "12.0000",
                new BigDecimal("2.0000"), new BigDecimal("20.000000"),
                "The invoiced unit price differs beyond tolerance.", DiscrepancyResolutionStatus.RESOLVED,
                DiscrepancyResolutionReason.OTHER, "Confirmed after review.");
    }
}
