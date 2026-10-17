package io.github.guillermodubon.invoward.reporting.application.export;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceWarning;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationSummary;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionReason;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLine;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLineStatus;
import io.github.guillermodubon.invoward.reporting.application.exception.CsvExportTooLargeException;
import io.github.guillermodubon.invoward.reporting.application.model.CsvExport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvExportGeneratorTest {

    private static final UUID ANALYSIS_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID DIFFERENT_LINE_ID = UUID.fromString("50000000-0000-0000-0000-000000000010");
    private static final UUID MATCHED_LINE_ID = UUID.fromString("50000000-0000-0000-0000-000000000020");
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    @Test
    void generatesFixedHeaderCrLfRowsPlainNumbersAndDeterministicDiscrepancyRows() {
        CsvExport export = new CsvExportGenerator(20_000).generate(result(DiscrepancyResolutionStatus.OPEN));
        String csv = utf8(export);
        String[] rows = csv.split("\r\n", -1);

        assertEquals("Scope,Line Status,Reference Code,Reference Description,Invoice Code,Invoice Description,"
                + "Reference Qty,Invoice Qty,Quantity Difference,Reference Unit Price,Invoice Unit Price,"
                + "Unit Price Difference,Reference Line Total,Invoice Line Total,Line Total Difference,"
                + "Discrepancy Type,Reference Value,Invoice Value,Absolute Difference,Percentage Difference,"
                + "Resolution Status,Resolution Reason,Resolution Note", rows[0]);
        assertEquals(6, rows.length); // header, two discrepancies, one clean line, one document discrepancy, terminator
        assertTrue(rows[1].startsWith("LINE,DIFFERENT,R-1,\"'  =SUM(1,2)\",I-1,\"Invoice \"\"quoted\"\", item\""));
        assertTrue(rows[1].contains(",QUANTITY_MISMATCH,"));
        assertTrue(rows[2].contains(",UNIT_PRICE_MISMATCH,"));
        assertTrue(rows[2].contains(",ACCEPTED,NEEDS_INVESTIGATION,"));
        assertTrue(rows[2].contains("\"note, reviewed\""));
        assertTrue(rows[3].startsWith("LINE,MATCHED,R-2,Reference item,I-2,Invoice item,1,1,0,"));
        assertTrue(rows[1].contains(",-2.5000,QUANTITY_MISMATCH,")); // trusted signed BigDecimal remains numeric
        assertTrue(rows[4].startsWith("DOCUMENT,"));
        assertTrue(rows[4].contains(",SUBTOTAL_MISMATCH,'=100.0000,120.0000,20.0000,20.000000,OPEN,,"));
        assertTrue(csv.indexOf("QUANTITY_MISMATCH") < csv.indexOf("UNIT_PRICE_MISMATCH"));
        assertTrue(csv.indexOf("UNIT_PRICE_MISMATCH") < csv.indexOf("SUBTOTAL_MISMATCH"));
        assertTrue(csv.replace("\r\n", "").indexOf('\r') < 0);
        assertTrue(csv.replace("\r\n", "").indexOf('\n') < 0);
        assertEquals(export.sizeBytes(), export.content().length);
    }

    @Test
    void usesCurrentResolutionStateSuppliedByThePersistedProjection() {
        CsvExportGenerator generator = new CsvExportGenerator(20_000);
        String openCsv = utf8(generator.generate(result(DiscrepancyResolutionStatus.OPEN)));
        String acceptedCsv = utf8(generator.generate(result(DiscrepancyResolutionStatus.ACCEPTED)));

        assertTrue(openCsv.contains(",OPEN,,"));
        assertTrue(acceptedCsv.contains(",ACCEPTED,NEEDS_INVESTIGATION,"));
        assertEquals(DiscrepancyResolutionStatus.OPEN, result(DiscrepancyResolutionStatus.OPEN)
                .discrepancies().stream()
                .filter(item -> item.type() == DiscrepancyType.QUANTITY_MISMATCH)
                .findFirst().orElseThrow().resolutionStatus());
    }

    @Test
    void acceptsExactByteLimitAndRejectsAnExportBeyondItWithoutReturningPartialContent() {
        CsvExport reference = new CsvExportGenerator(20_000).generate(result(DiscrepancyResolutionStatus.OPEN));
        int exactSize = reference.content().length;

        assertEquals(exactSize, new CsvExportGenerator(exactSize)
                .generate(result(DiscrepancyResolutionStatus.OPEN)).sizeBytes());
        assertThrows(CsvExportTooLargeException.class, () -> new CsvExportGenerator(exactSize - 1)
                .generate(result(DiscrepancyResolutionStatus.OPEN)));
    }

    @Test
    void rejectsNonPositiveAndUnrepresentableByteLimits() {
        assertThrows(IllegalArgumentException.class, () -> new CsvExportGenerator(0));
        assertThrows(IllegalArgumentException.class, () -> new CsvExportGenerator((long) Integer.MAX_VALUE + 1));
    }

    private static ReconciliationResult result(DiscrepancyResolutionStatus firstStatus) {
        List<ReconciliationLine> lines = List.of(differentLine(), matchedLine());
        Discrepancy quantity = discrepancy(
                "50000000-0000-0000-0000-000000000101", DIFFERENT_LINE_ID,
                DiscrepancyType.QUANTITY_MISMATCH, firstStatus,
                firstStatus == DiscrepancyResolutionStatus.OPEN ? null : DiscrepancyResolutionReason.NEEDS_INVESTIGATION,
                firstStatus == DiscrepancyResolutionStatus.OPEN ? null : "note, reviewed");
        Discrepancy unitPrice = discrepancy(
                "50000000-0000-0000-0000-000000000102", DIFFERENT_LINE_ID,
                DiscrepancyType.UNIT_PRICE_MISMATCH, DiscrepancyResolutionStatus.ACCEPTED,
                DiscrepancyResolutionReason.NEEDS_INVESTIGATION, "note, reviewed");
        Discrepancy document = discrepancy(
                "50000000-0000-0000-0000-000000000103", null,
                DiscrepancyType.SUBTOTAL_MISMATCH, DiscrepancyResolutionStatus.OPEN, null, null);
        return new ReconciliationResult(
                ANALYSIS_ID, AnalysisStatus.COMPLETED, AnalysisReconciliationStatus.REVIEW_REQUIRED,
                PriceTolerance.exactMatch(), new BigDecimal("100.0000"), new BigDecimal("120.0000"),
                new BigDecimal("20.0000"), DuplicateInvoiceWarning.none(),
                new ReconciliationSummary(2, 1, 1, 0, 0, firstStatus == DiscrepancyResolutionStatus.OPEN ? 2 : 1,
                        firstStatus == DiscrepancyResolutionStatus.ACCEPTED ? 2 : 1, 0),
                lines, List.of(unitPrice, document, quantity));
    }

    private static ReconciliationLine differentLine() {
        return new ReconciliationLine(
                DIFFERENT_LINE_ID, ANALYSIS_ID, UUID.fromString("50000000-0000-0000-0000-000000000030"),
                ReconciliationLineStatus.DIFFERENT, "R-1", "I-1", "  =SUM(1,2)", "Invoice \"quoted\", item",
                new BigDecimal("2.0000"), new BigDecimal("3.0000"), new BigDecimal("5.0000"),
                new BigDecimal("7.0000"), new BigDecimal("10.0000"), new BigDecimal("7.5000"),
                new BigDecimal("1.0000"), new BigDecimal("2.0000"), new BigDecimal("-2.5000"), NOW);
    }

    private static ReconciliationLine matchedLine() {
        return new ReconciliationLine(
                MATCHED_LINE_ID, ANALYSIS_ID, UUID.fromString("50000000-0000-0000-0000-000000000040"),
                ReconciliationLineStatus.MATCHED, "R-2", "I-2", "Reference item", "Invoice item",
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("3.0000"), new BigDecimal("3.0000"),
                new BigDecimal("3.0000"), new BigDecimal("3.0000"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, NOW);
    }

    private static Discrepancy discrepancy(
            String id,
            UUID lineId,
            DiscrepancyType type,
            DiscrepancyResolutionStatus status,
            DiscrepancyResolutionReason reason,
            String note) {
        boolean resolved = status != DiscrepancyResolutionStatus.OPEN;
        return new Discrepancy(
                UUID.fromString(id), ANALYSIS_ID, lineId, type, "=100.0000", "120.0000",
                new BigDecimal("20.0000"), new BigDecimal("20.000000"), "A neutral explanation",
                status, reason, note, resolved ? 1 : 0, resolved ? NOW : null, NOW.minusSeconds(30), NOW);
    }

    private static String utf8(CsvExport export) {
        return new String(export.content(), StandardCharsets.UTF_8);
    }
}
