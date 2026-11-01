package io.github.guillermodubon.invoward.insights.application.model;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;

import java.util.Objects;

public record SupplierAnalysisQuery(
        AnalysisStatus processingStatus,
        AnalysisReviewStatus reviewStatus,
        SupplierHistorySort sort,
        InsightsPageRequest pagination) {

    public static final int DEFAULT_SIZE = 20;

    public SupplierAnalysisQuery {
        Objects.requireNonNull(sort, "sort is required");
        Objects.requireNonNull(pagination, "pagination is required");
    }

    public static SupplierAnalysisQuery of(
            AnalysisStatus processingStatus,
            AnalysisReviewStatus reviewStatus,
            Integer page,
            Integer size,
            String sort) {
        return new SupplierAnalysisQuery(
                processingStatus,
                reviewStatus,
                SupplierHistorySort.parse(sort),
                InsightsPageRequest.of(page, size, DEFAULT_SIZE));
    }
}
