package io.github.guillermodubon.invoward.analysis.api.model;

import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Public projection returned when an Analysis workspace is created. */
public record AnalysisResponse(
        UUID id,
        AnalysisStatus status,
        AnalysisReviewStatus reviewStatus,
        AnalysisReconciliationStatus reconciliationStatus,
        BigDecimal priceTolerancePercent,
        BigDecimal priceToleranceAbsolute,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt) {

    public static AnalysisResponse from(Analysis analysis) {
        Objects.requireNonNull(analysis, "analysis must not be null");
        return new AnalysisResponse(
                analysis.id(),
                analysis.status(),
                analysis.reviewStatus(),
                analysis.reconciliationStatus(),
                analysis.priceTolerance().priceTolerancePercent(),
                analysis.priceTolerance().priceToleranceAbsolute(),
                analysis.createdAt(),
                analysis.updatedAt(),
                analysis.expiresAt());
    }
}
