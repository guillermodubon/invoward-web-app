package io.github.guillermodubon.invoward.analysis.application.model;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Safe, immutable history projection; deliberately excludes ownership and storage metadata. */
public record AnalysisHistoryItem(
        UUID id,
        AnalysisStatus status,
        AnalysisReconciliationStatus reconciliationStatus,
        AnalysisReviewStatus reviewStatus,
        Instant reviewedAt,
        String supplierName,
        String referenceType,
        String referenceNumber,
        String invoiceNumber,
        String currency,
        BigDecimal referenceTotal,
        BigDecimal invoicedTotal,
        BigDecimal difference,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public AnalysisHistoryItem {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(reviewStatus, "reviewStatus must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)
                || (completedAt != null && (completedAt.isBefore(createdAt) || completedAt.isAfter(updatedAt)))
                || (reviewedAt != null && (reviewedAt.isBefore(createdAt) || reviewedAt.isAfter(updatedAt)))
                || (reviewStatus == AnalysisReviewStatus.PENDING && reviewedAt != null)) {
            throw new IllegalArgumentException("Analysis history item timestamps are inconsistent.");
        }
        requireNonnegative(referenceTotal, "referenceTotal");
        requireNonnegative(invoicedTotal, "invoicedTotal");
    }

    private static void requireNonnegative(BigDecimal value, String field) {
        if (value != null && value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
