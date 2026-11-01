package io.github.guillermodubon.invoward.insights.application.model;

/** Framework-neutral page request shared by Insights query boundaries. */
public record InsightsPageRequest(int page, int size) {

    public InsightsPageRequest {
        if (page < 0 || page > InsightsPage.MAX_PAGE) {
            throw new IllegalArgumentException("Page is outside the supported range.");
        }
        if (size < 1 || size > InsightsPage.MAX_SIZE) {
            throw new IllegalArgumentException("Page size is outside the supported range.");
        }
    }

    public static InsightsPageRequest of(Integer page, Integer size, int defaultSize) {
        int resolvedPage = page == null ? 0 : page;
        int resolvedSize = size == null ? defaultSize : size;
        return new InsightsPageRequest(resolvedPage, resolvedSize);
    }
}
