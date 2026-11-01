package io.github.guillermodubon.invoward.insights.application.port;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryItem;
import io.github.guillermodubon.invoward.insights.application.model.InsightsPage;
import io.github.guillermodubon.invoward.insights.application.model.SupplierAnalysisQuery;
import io.github.guillermodubon.invoward.insights.application.model.SupplierKey;

import java.util.UUID;

/** Supplier reads are always scoped to one registered account at the query boundary. */
public interface InsightsSupplierQueryRepository
        extends InsightsSupplierListQueryRepository,
                InsightsSupplierFinancialQueryRepository,
                InsightsSupplierSummaryQueryRepository {

    /** Owner/key/filter-scoped page and count; this never performs fuzzy supplier-name matching. */
    InsightsPage<AnalysisHistoryItem> pageSupplierAnalyses(
            UUID registeredUserId,
            SupplierKey supplierKey,
            SupplierAnalysisQuery query);
}
