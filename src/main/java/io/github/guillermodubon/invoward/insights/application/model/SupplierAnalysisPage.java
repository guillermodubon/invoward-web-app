package io.github.guillermodubon.invoward.insights.application.model;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryItem;

import java.util.Objects;

public record SupplierAnalysisPage(SupplierKey supplierKey, InsightsPage<AnalysisHistoryItem> page) {

    public SupplierAnalysisPage {
        Objects.requireNonNull(supplierKey, "supplierKey is required");
        Objects.requireNonNull(page, "page is required");
    }
}
