package io.github.guillermodubon.invoward.analysis.api.model;

import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Safe public projection of the Analysis detail contract. */
public record AnalysisDetailResponse(
        UUID id,
        AnalysisStatus status,
        AnalysisReviewStatus reviewStatus,
        AnalysisReconciliationStatus reconciliationStatus,
        String supplierName,
        String referenceType,
        String referenceNumber,
        String invoiceNumber,
        String currency,
        BigDecimal referenceTotal,
        BigDecimal invoicedTotal,
        BigDecimal difference,
        BigDecimal priceTolerancePercent,
        BigDecimal priceToleranceAbsolute,
        boolean retryable,
        String failureCode,
        String failureMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        Instant expiresAt) {

    public static AnalysisDetailResponse from(Analysis analysis) {
        Objects.requireNonNull(analysis, "analysis must not be null");
        return new AnalysisDetailResponse(
                analysis.id(),
                analysis.status(),
                analysis.reviewStatus(),
                analysis.reconciliationStatus(),
                analysis.supplierName(),
                analysis.referenceType(),
                analysis.referenceNumber(),
                analysis.invoiceNumber(),
                analysis.currency(),
                analysis.referenceTotal(),
                analysis.invoicedTotal(),
                analysis.difference(),
                analysis.priceTolerance().priceTolerancePercent(),
                analysis.priceTolerance().priceToleranceAbsolute(),
                analysis.retryable(),
                analysis.failureCode(),
                analysis.failureUserMessage(),
                analysis.createdAt(),
                analysis.updatedAt(),
                analysis.completedAt(),
                analysis.expiresAt());
    }
}
