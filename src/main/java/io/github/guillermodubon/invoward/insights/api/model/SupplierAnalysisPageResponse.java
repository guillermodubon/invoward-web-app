package io.github.guillermodubon.invoward.insights.api.model;

import io.github.guillermodubon.invoward.insights.application.model.SupplierAnalysisPage;

import java.util.List;

/** Explicit HTTP projection for one supplier's safely paged analysis history. */
public record SupplierAnalysisPageResponse(
        String supplierKey,
        List<DashboardRecentAnalysisItem> content,
        int page,
        int size,
        long totalElements,
        long totalPages,
        boolean hasNext,
        boolean hasPrevious) {

    public SupplierAnalysisPageResponse {
        content = List.copyOf(content);
    }

    public static SupplierAnalysisPageResponse from(SupplierAnalysisPage supplierPage) {
        var page = supplierPage.page();
        return new SupplierAnalysisPageResponse(
                supplierPage.supplierKey().value(),
                page.content().stream().map(DashboardRecentAnalysisItem::from).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages(),
                page.hasNext(),
                page.hasPrevious());
    }
}
