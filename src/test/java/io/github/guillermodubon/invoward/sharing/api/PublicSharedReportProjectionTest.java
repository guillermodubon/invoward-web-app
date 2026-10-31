package io.github.guillermodubon.invoward.sharing.api;

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
import io.github.guillermodubon.invoward.sharing.api.model.PublicSharedReportResponse;
import io.github.guillermodubon.invoward.sharing.application.model.PublicSharedReport;
import io.github.guillermodubon.invoward.sharing.application.service.PublicSharedReportAssembler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicSharedReportProjectionTest {

    private static final UUID ANALYSIS_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID LINE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID MATCH_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID DISCREPANCY_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID DUPLICATE_ANALYSIS_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant REVIEWED_AT = Instant.parse("2026-01-02T00:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-01-03T00:00:00Z");
    private static final String PRIVATE_SUPPLIER_KEY = "PRIVATE_SUPPLIER_KEY_SENTINEL";
    private static final String PRIVATE_RESOLUTION_NOTE = "PRIVATE_RESOLUTION_NOTE_SENTINEL";
    private static final String PRIVATE_DUPLICATE_INVOICE = "PRIVATE_DUPLICATE_INVOICE_SENTINEL";

    private final PublicSharedReportAssembler assembler = new PublicSharedReportAssembler();
    private final PublicSharedReportResponseMapper responseMapper = new PublicSharedReportResponseMapper();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsPersistedValuesToAnExplicitPublicWhitelist() throws Exception {
        Analysis analysis = analysis(ANALYSIS_ID);
        ReconciliationResult reconciliation = reconciliation(ANALYSIS_ID);

        PublicSharedReport snapshot = assembler.assemble(analysis, reconciliation, COMPLETED_AT);
        PublicSharedReportResponse response = responseMapper.map(snapshot);
        String json = objectMapper.writeValueAsString(response);
        JsonNode root = objectMapper.readTree(json);

        assertEquals(Set.of(
                "reportGeneratedAt", "supplierName", "referenceType", "referenceNumber", "invoiceNumber",
                "currency", "reconciliationStatus", "referenceTotal", "invoiceTotal", "difference",
                "tolerance", "summary", "lines", "discrepancies"), propertyNames(root));
        assertEquals(Set.of("percentage", "absolute"), propertyNames(root.get("tolerance")));
        assertEquals(Set.of("lineCount", "matchedCount", "differentCount", "missingCount", "unexpectedCount",
                "discrepancyCount"), propertyNames(root.get("summary")));
        assertEquals(Set.of(
                "status", "referenceItemCode", "invoiceItemCode", "referenceDescription", "invoiceDescription",
                "referenceQuantity", "invoiceQuantity", "referenceUnitPrice", "invoiceUnitPrice",
                "referenceLineTotal", "invoiceLineTotal", "quantityDifference", "unitPriceDifference",
                "lineTotalDifference"), propertyNames(root.get("lines").get(0)));
        assertEquals(Set.of("type", "explanation", "referenceValue", "invoiceValue", "absoluteDifference",
                "percentageDifference", "resolutionStatus"), propertyNames(root.get("discrepancies").get(0)));

        assertEquals("REVIEW_REQUIRED", root.get("reconciliationStatus").asString());
        assertEquals("RESOLVED", root.get("discrepancies").get(0).get("resolutionStatus").asString());
        assertEquals(1, root.get("summary").get("discrepancyCount").asInt());
        assertTrue(json.contains("Public Supplier"));
        assertTrue(json.contains("Public reference item"));
        assertTrue(json.contains("UNIT_PRICE_MISMATCH"));
        assertTrue(json.contains("20.000000"));

        for (String privateSentinel : List.of(
                ANALYSIS_ID.toString(), OWNER_ID.toString(), LINE_ID.toString(), MATCH_ID.toString(),
                DISCREPANCY_ID.toString(), DUPLICATE_ANALYSIS_ID.toString(), PRIVATE_SUPPLIER_KEY,
                PRIVATE_RESOLUTION_NOTE, PRIVATE_DUPLICATE_INVOICE)) {
            assertFalse(json.contains(privateSentinel), "public JSON must not contain " + privateSentinel);
        }
        for (String forbiddenField : List.of(
                "analysisId", "owner", "guestSessionId", "supplierKey", "reviewedAt", "resolutionReason",
                "resolutionNote", "version", "resolvedAt", "duplicateInvoice", "evidence", "sourceText",
                "storageKey", "sha256", "publicUrl")) {
            assertFalse(json.contains("\"" + forbiddenField + "\""),
                    "public JSON must not contain field " + forbiddenField);
        }
    }

    @Test
    void rejectsAnalysisAndReconciliationSnapshotsForDifferentAnalyses() {
        assertThrows(IllegalArgumentException.class,
                () -> assembler.assemble(analysis(ANALYSIS_ID), reconciliation(UUID.randomUUID()), COMPLETED_AT));
    }

    @Test
    void publicSnapshotCopiesCollectionsAndPreservesNullableBusinessValues() {
        PublicSharedReport snapshot = new PublicSharedReport(
                COMPLETED_AT, null, null, null, null, null, AnalysisReconciliationStatus.MATCHED,
                null, null, null,
                new PublicSharedReport.Tolerance(BigDecimal.ZERO, null),
                new PublicSharedReport.Summary(0, 0, 0, 0, 0, 0),
                List.of(), List.of());

        PublicSharedReportResponse response = responseMapper.map(snapshot);

        assertTrue(response.lines().isEmpty());
        assertTrue(response.discrepancies().isEmpty());
        assertEquals(null, response.supplierName());
        assertEquals(null, response.referenceTotal());
    }

    private static Analysis analysis(UUID analysisId) {
        return new Analysis(
                analysisId,
                new RegisteredUserOwner(OWNER_ID),
                AnalysisStatus.COMPLETED,
                AnalysisReviewStatus.REVIEWED,
                REVIEWED_AT,
                AnalysisReconciliationStatus.REVIEW_REQUIRED,
                "Public Supplier",
                PRIVATE_SUPPLIER_KEY,
                "QUOTE",
                "REF-001",
                "INV-001",
                "USD",
                new BigDecimal("100.0000"),
                new BigDecimal("120.0000"),
                new BigDecimal("20.0000"),
                new PriceTolerance(new BigDecimal("3"), new BigDecimal("5.0000")),
                false,
                null,
                null,
                2,
                COMPLETED_AT,
                null,
                CREATED_AT,
                COMPLETED_AT);
    }

    private static ReconciliationResult reconciliation(UUID analysisId) {
        ReconciliationLine line = new ReconciliationLine(
                LINE_ID,
                analysisId,
                MATCH_ID,
                ReconciliationLineStatus.DIFFERENT,
                "REF-CODE",
                "INV-CODE",
                "Public reference item",
                "Public invoice item",
                new BigDecimal("2.0000"),
                new BigDecimal("2.0000"),
                new BigDecimal("10.0000"),
                new BigDecimal("12.0000"),
                new BigDecimal("20.0000"),
                new BigDecimal("24.0000"),
                BigDecimal.ZERO,
                new BigDecimal("2.0000"),
                new BigDecimal("4.0000"),
                CREATED_AT);
        Discrepancy discrepancy = new Discrepancy(
                DISCREPANCY_ID,
                analysisId,
                LINE_ID,
                DiscrepancyType.UNIT_PRICE_MISMATCH,
                "10",
                "12",
                new BigDecimal("2.0000"),
                new BigDecimal("20.000000"),
                "The invoiced unit price differs from the reference price beyond the configured tolerance.",
                DiscrepancyResolutionStatus.RESOLVED,
                DiscrepancyResolutionReason.OTHER,
                PRIVATE_RESOLUTION_NOTE,
                7,
                COMPLETED_AT,
                CREATED_AT,
                COMPLETED_AT);
        DuplicateInvoiceWarning warning = new DuplicateInvoiceWarning(true, List.of(
                new DuplicateInvoiceCandidate(
                        DUPLICATE_ANALYSIS_ID,
                        CREATED_AT,
                        PRIVATE_DUPLICATE_INVOICE,
                        List.of(DuplicateInvoiceSignal.EXACT_INVOICE_FILE_HASH))));
        return new ReconciliationResult(
                analysisId,
                AnalysisStatus.COMPLETED,
                AnalysisReconciliationStatus.REVIEW_REQUIRED,
                new PriceTolerance(new BigDecimal("3"), new BigDecimal("5.0000")),
                new BigDecimal("100.0000"),
                new BigDecimal("120.0000"),
                new BigDecimal("20.0000"),
                warning,
                new ReconciliationSummary(1, 0, 1, 0, 0, 0, 0, 1),
                List.of(line),
                List.of(discrepancy));
    }

    private static Set<String> propertyNames(JsonNode node) {
        HashSet<String> names = new HashSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }
}
