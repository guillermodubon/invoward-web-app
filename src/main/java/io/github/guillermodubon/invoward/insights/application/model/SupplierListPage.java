package io.github.guillermodubon.invoward.insights.application.model;

import java.util.Objects;

public record SupplierListPage(InsightsPage<SupplierListItem> page) {

    public SupplierListPage {
        Objects.requireNonNull(page, "page is required");
    }
}
