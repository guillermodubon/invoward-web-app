package io.github.guillermodubon.invoward.insights.application.model;

import java.util.Objects;

public record SupplierListQuery(String search, SupplierListSort sort, InsightsPageRequest pagination) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SEARCH_CODE_POINTS = 120;

    public SupplierListQuery {
        Objects.requireNonNull(sort, "sort is required");
        Objects.requireNonNull(pagination, "pagination is required");
        search = normalizeOptionalSearch(search);
    }

    public static SupplierListQuery of(Integer page, Integer size, String search, String sort) {
        return new SupplierListQuery(
                search,
                SupplierListSort.parse(sort),
                InsightsPageRequest.of(page, size, DEFAULT_SIZE));
    }

    private static String normalizeOptionalSearch(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.codePointCount(0, normalized.length()) > MAX_SEARCH_CODE_POINTS) {
            throw new IllegalArgumentException("Supplier search is outside the supported range.");
        }
        return normalized;
    }
}
