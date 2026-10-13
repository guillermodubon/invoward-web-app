package io.github.guillermodubon.invoward.reconciliation.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable historical snapshot for one persisted line-item match. */
public record ReconciliationLine(
        UUID id,
        UUID analysisId,
        UUID lineMatchId,
        ReconciliationLineStatus status,
        String referenceItemCode,
        String invoiceItemCode,
        String referenceDescription,
        String invoiceDescription,
        BigDecimal referenceQuantity,
        BigDecimal invoiceQuantity,
        BigDecimal referenceUnitPrice,
        BigDecimal invoiceUnitPrice,
        BigDecimal referenceLineTotal,
        BigDecimal invoiceLineTotal,
        BigDecimal quantityDifference,
        BigDecimal unitPriceDifference,
        BigDecimal lineTotalDifference,
        Instant createdAt) {

    public ReconciliationLine {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(lineMatchId, "lineMatchId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");

        validateItemCode(referenceItemCode, "referenceItemCode");
        validateItemCode(invoiceItemCode, "invoiceItemCode");
        validateSnapshotAmount(referenceQuantity, "referenceQuantity");
        validateSnapshotAmount(invoiceQuantity, "invoiceQuantity");
        validateSnapshotAmount(referenceUnitPrice, "referenceUnitPrice");
        validateSnapshotAmount(invoiceUnitPrice, "invoiceUnitPrice");
        validateSnapshotAmount(referenceLineTotal, "referenceLineTotal");
        validateSnapshotAmount(invoiceLineTotal, "invoiceLineTotal");
        validateDifference(quantityDifference, "quantityDifference");
        validateDifference(unitPriceDifference, "unitPriceDifference");
        validateDifference(lineTotalDifference, "lineTotalDifference");
        validateRowShape(
                status,
                referenceItemCode,
                invoiceItemCode,
                referenceDescription,
                invoiceDescription,
                referenceQuantity,
                invoiceQuantity,
                referenceUnitPrice,
                invoiceUnitPrice,
                referenceLineTotal,
                invoiceLineTotal,
                quantityDifference,
                unitPriceDifference,
                lineTotalDifference);
    }

    private static void validateRowShape(
            ReconciliationLineStatus status,
            String referenceItemCode,
            String invoiceItemCode,
            String referenceDescription,
            String invoiceDescription,
            BigDecimal referenceQuantity,
            BigDecimal invoiceQuantity,
            BigDecimal referenceUnitPrice,
            BigDecimal invoiceUnitPrice,
            BigDecimal referenceLineTotal,
            BigDecimal invoiceLineTotal,
            BigDecimal quantityDifference,
            BigDecimal unitPriceDifference,
            BigDecimal lineTotalDifference) {
        switch (status) {
            case MATCHED, DIFFERENT -> {
                requirePresentSide(referenceDescription, "referenceDescription");
                requirePresentSide(invoiceDescription, "invoiceDescription");
            }
            case MISSING -> {
                requirePresentSide(referenceDescription, "referenceDescription");
                requireAbsentSide(
                        invoiceItemCode, invoiceDescription, invoiceQuantity, invoiceUnitPrice, invoiceLineTotal,
                        "invoice");
                requireNoDifferences(quantityDifference, unitPriceDifference, lineTotalDifference);
            }
            case UNEXPECTED -> {
                requirePresentSide(invoiceDescription, "invoiceDescription");
                requireAbsentSide(
                        referenceItemCode, referenceDescription, referenceQuantity, referenceUnitPrice,
                        referenceLineTotal, "reference");
                requireNoDifferences(quantityDifference, unitPriceDifference, lineTotalDifference);
            }
        }
    }

    private static void requireNoDifferences(
            BigDecimal quantityDifference,
            BigDecimal unitPriceDifference,
            BigDecimal lineTotalDifference) {
        if (quantityDifference != null || unitPriceDifference != null || lineTotalDifference != null) {
            throw new IllegalArgumentException("unmatched lines must not contain calculated differences");
        }
    }

    private static void requirePresentSide(String description, String field) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException(field + " must be present for this line status");
        }
    }

    private static void requireAbsentSide(
            String itemCode,
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            String side) {
        if (itemCode != null || description != null || quantity != null || unitPrice != null || lineTotal != null) {
            throw new IllegalArgumentException(side + " values must be absent for this line status");
        }
    }

    private static void validateItemCode(String value, String field) {
        if (value != null && value.codePointCount(0, value.length()) > 120) {
            throw new IllegalArgumentException(field + " must not exceed 120 code points");
        }
    }

    private static void validateSnapshotAmount(BigDecimal value, String field) {
        if (value != null && value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        Numeric19_4.requireCompatible(value, field);
    }

    private static void validateDifference(BigDecimal value, String field) {
        Numeric19_4.requireCompatible(value, field);
    }

    @Override
    public String toString() {
        return "ReconciliationLine[id=" + id + ", analysisId=" + analysisId + ", lineMatchId=" + lineMatchId
                + ", status=" + status + "]";
    }
}
