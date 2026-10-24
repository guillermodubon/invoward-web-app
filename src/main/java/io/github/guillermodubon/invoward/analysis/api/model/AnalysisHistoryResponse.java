package io.github.guillermodubon.invoward.analysis.api.model;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryPage;

import java.util.List;

/** Stable, bounded HTTP page response for registered Analysis history. */
public record AnalysisHistoryResponse(
        List<AnalysisHistoryItemResponse> content,
        int page,
        int size,
        long totalElements,
        long totalPages,
        boolean hasNext,
        boolean hasPrevious) {

    public AnalysisHistoryResponse {
        content = List.copyOf(content);
    }

    public static AnalysisHistoryResponse from(AnalysisHistoryPage page) {
        return new AnalysisHistoryResponse(
                page.content().stream().map(AnalysisHistoryItemResponse::from).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages(),
                page.hasNext(),
                page.hasPrevious());
    }
}
