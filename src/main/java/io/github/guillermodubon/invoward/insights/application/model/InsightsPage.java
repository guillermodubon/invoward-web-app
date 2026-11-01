package io.github.guillermodubon.invoward.insights.application.model;

import java.util.List;
import java.util.Objects;

/** Immutable application-level page metadata, independent of Spring Data. */
public record InsightsPage<T>(List<T> content, int page, int size, long totalElements) {

    public static final int MAX_PAGE = 1_000;
    public static final int MAX_SIZE = 100;

    public InsightsPage {
        Objects.requireNonNull(content, "content is required");
        if (page < 0 || page > MAX_PAGE) {
            throw new IllegalArgumentException("Page is outside the supported range.");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("Page size is outside the supported range.");
        }
        if (totalElements < 0) {
            throw new IllegalArgumentException("Total elements cannot be negative.");
        }
        if (content.size() > size || content.size() > totalElements) {
            throw new IllegalArgumentException("Page content is inconsistent with its metadata.");
        }
        long offset = (long) page * size;
        if (!content.isEmpty() && offset >= totalElements) {
            throw new IllegalArgumentException("Page content is inconsistent with its metadata.");
        }
        content = List.copyOf(content);
    }

    public long totalPages() {
        return totalElements == 0 ? 0 : 1 + (totalElements - 1) / size;
    }

    public boolean hasNext() {
        return (long) page + 1 < totalPages();
    }

    public boolean hasPrevious() {
        return page > 0;
    }
}
