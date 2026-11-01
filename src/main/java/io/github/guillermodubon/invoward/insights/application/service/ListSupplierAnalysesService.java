package io.github.guillermodubon.invoward.insights.application.service;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryItem;
import io.github.guillermodubon.invoward.insights.application.exception.SupplierNotFoundException;
import io.github.guillermodubon.invoward.insights.application.model.InsightsPage;
import io.github.guillermodubon.invoward.insights.application.model.SupplierAnalysisPage;
import io.github.guillermodubon.invoward.insights.application.model.SupplierAnalysisQuery;
import io.github.guillermodubon.invoward.insights.application.model.SupplierKey;
import io.github.guillermodubon.invoward.insights.application.port.InsightsSupplierQueryRepository;

import java.util.Objects;
import java.util.UUID;

/** Reads an existing registered-owner supplier history page without triggering processing. */
public final class ListSupplierAnalysesService {

    private final InsightsSupplierQueryRepository queryRepository;

    public ListSupplierAnalysesService(InsightsSupplierQueryRepository queryRepository) {
        this.queryRepository = Objects.requireNonNull(queryRepository, "queryRepository is required");
    }

    /**
     * Resolves the supplier under the registered owner before querying its history. The page
     * query performs its own owner/key predicates and returns a real database page and count.
     */
    public SupplierAnalysisPage list(
            UUID registeredUserId,
            SupplierKey supplierKey,
            SupplierAnalysisQuery query) {
        Objects.requireNonNull(registeredUserId, "registeredUserId is required");
        Objects.requireNonNull(supplierKey, "supplierKey is required");
        Objects.requireNonNull(query, "query is required");

        Objects.requireNonNull(
                        queryRepository.findOwnedCompletedSupplierDisplayName(registeredUserId, supplierKey),
                        "supplier existence query must return a result")
                .orElseThrow(SupplierNotFoundException::new);

        InsightsPage<AnalysisHistoryItem> page = Objects.requireNonNull(
                queryRepository.pageSupplierAnalyses(registeredUserId, supplierKey, query),
                "supplier history query must return a page");
        return new SupplierAnalysisPage(supplierKey, page);
    }
}
