package io.github.guillermodubon.invoward.sharing.application.model;

import io.github.guillermodubon.invoward.sharing.domain.ShareLink;

import java.util.List;
import java.util.Objects;

/** Immutable bounded page of persisted share-link metadata. */
public record ShareLinkPage(List<ShareLink> content, int page, int size, long totalElements) {

    public static final int MAX_PAGE = 1000;
    public static final int MAX_SIZE = 100;

    public ShareLinkPage {
        Objects.requireNonNull(content, "content must not be null");
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_SIZE
                || totalElements < 0 || content.size() > size || totalElements < content.size()) {
            throw new IllegalArgumentException("Share-link page is inconsistent");
        }
        content = List.copyOf(content);
    }

    public long totalPages() {
        return totalElements == 0 ? 0 : 1 + (totalElements - 1) / size;
    }

    public boolean hasNext() {
        return (long) page + 1 < totalPages();
    }
}
