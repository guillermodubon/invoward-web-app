package io.github.guillermodubon.invoward.analysis.application.model;

import java.util.List;
import java.util.Objects;

/** Immutable bounded page of safe Analysis history projections. */
public record AnalysisHistoryPage(List<AnalysisHistoryItem> content, int page, int size, long totalElements) {

    public AnalysisHistoryPage {
        Objects.requireNonNull(content, "content must not be null");
        if (page < 0 || page > AnalysisHistoryQuery.MAX_PAGE
                || size < 1 || size > AnalysisHistoryQuery.MAX_SIZE
                || totalElements < 0 || content.size() > size || totalElements < content.size()) {
            throw new IllegalArgumentException("Analysis history page is inconsistent.");
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
