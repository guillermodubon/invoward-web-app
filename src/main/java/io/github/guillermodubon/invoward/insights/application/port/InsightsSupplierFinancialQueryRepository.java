package io.github.guillermodubon.invoward.insights.application.port;

import io.github.guillermodubon.invoward.insights.application.model.SupplierCurrencyAmounts;
import io.github.guillermodubon.invoward.insights.application.model.SupplierFinancialDataQualityCounts;
import io.github.guillermodubon.invoward.insights.application.model.SupplierKey;

import java.util.List;
import java.util.UUID;

/** Bounded, owner-scoped supplier financial aggregates grouped by persisted supplier key. */
public interface InsightsSupplierFinancialQueryRepository {

    List<SupplierCurrencyAmounts> fetchSupplierCurrencyAmounts(
            UUID registeredUserId,
            List<SupplierKey> supplierKeys);

    List<SupplierCurrencyAmounts> fetchSupplierPotentialDifferencesByCurrency(
            UUID registeredUserId,
            List<SupplierKey> supplierKeys);

    List<SupplierFinancialDataQualityCounts> fetchSupplierMissingFinancialDataCounts(
            UUID registeredUserId,
            List<SupplierKey> supplierKeys);
}
