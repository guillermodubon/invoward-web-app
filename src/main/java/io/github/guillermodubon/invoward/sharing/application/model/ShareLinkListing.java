package io.github.guillermodubon.invoward.sharing.application.model;

import java.util.List;
import java.util.Objects;

/** Immutable bounded page of safe share-link metadata. */
public record ShareLinkListing(List<ShareLinkListItem> links, int page, int size, long totalElements) {

    public ShareLinkListing {
        Objects.requireNonNull(links, "links must not be null");
        if (page < 0 || page > ShareLinkPage.MAX_PAGE || size < 1 || size > ShareLinkPage.MAX_SIZE
                || totalElements < 0 || links.size() > size || totalElements < links.size()) {
            throw new IllegalArgumentException("Share-link listing is inconsistent.");
        }
        links = List.copyOf(links);
    }

    public long totalPages() {
        return totalElements == 0 ? 0 : 1 + (totalElements - 1) / size;
    }

    public boolean hasNext() {
        return (long) page + 1 < totalPages();
    }
}
