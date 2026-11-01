package io.github.guillermodubon.invoward.insights.application.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Batched currency projection for one supplier key; not a supplier entity. */
public record SupplierCurrencyAmounts(SupplierKey supplierKey, List<CurrencyAmount> amounts) {

    public SupplierCurrencyAmounts {
        Objects.requireNonNull(supplierKey, "supplierKey is required");
        Objects.requireNonNull(amounts, "amounts is required");
        amounts = List.copyOf(amounts);
        Set<String> currencies = new HashSet<>();
        if (amounts.stream().anyMatch(amount -> !currencies.add(amount.currency()))) {
            throw new IllegalArgumentException("Currency breakdown contains duplicate currencies.");
        }
    }
}
