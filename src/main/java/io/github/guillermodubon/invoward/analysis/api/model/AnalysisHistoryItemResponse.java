package io.github.guillermodubon.invoward.analysis.api.model;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryItem;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Safe HTTP projection of a registered user's Analysis history item. */
public record AnalysisHistoryItemResponse(
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

    public static AnalysisHistoryItemResponse from(AnalysisHistoryItem item) {
        return new AnalysisHistoryItemResponse(
                item.id(),
                item.status(),
                item.reconciliationStatus(),
                item.reviewStatus(),
                item.reviewedAt(),
                item.supplierName(),
                item.referenceType(),
                item.referenceNumber(),
                item.invoiceNumber(),
                item.currency(),
                item.referenceTotal(),
                item.invoicedTotal(),
                item.difference(),
                item.createdAt(),
                item.updatedAt(),
                item.completedAt());
    }
}
