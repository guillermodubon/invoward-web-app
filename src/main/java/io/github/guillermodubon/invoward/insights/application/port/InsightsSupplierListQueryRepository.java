package io.github.guillermodubon.invoward.insights.application.port;

import io.github.guillermodubon.invoward.insights.application.model.InsightsPage;
import io.github.guillermodubon.invoward.insights.application.model.SupplierKeyAggregate;
import io.github.guillermodubon.invoward.insights.application.model.SupplierListQuery;

import java.util.UUID;

/** Owner-scoped grouped supplier listing query. */
public interface InsightsSupplierListQueryRepository {

    InsightsPage<SupplierKeyAggregate> pageSupplierKeys(UUID registeredUserId, SupplierListQuery query);
}
