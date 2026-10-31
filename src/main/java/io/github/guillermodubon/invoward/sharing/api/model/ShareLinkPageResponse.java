package io.github.guillermodubon.invoward.sharing.api.model;

import io.github.guillermodubon.invoward.sharing.application.model.ShareLinkListing;

import java.util.List;
import java.util.Objects;

/** Paginated response for the owner's share-link metadata. */
public record ShareLinkPageResponse(
        List<ShareLinkSummaryResponse> links,
        int page,
        int size,
        long totalElements,
        long totalPages,
        boolean hasNext) {

    public ShareLinkPageResponse {
        Objects.requireNonNull(links, "links must not be null");
        links = List.copyOf(links);
    }

    public static ShareLinkPageResponse from(ShareLinkListing listing) {
        Objects.requireNonNull(listing, "listing must not be null");
        return new ShareLinkPageResponse(
                listing.links().stream().map(ShareLinkSummaryResponse::from).toList(),
                listing.page(), listing.size(), listing.totalElements(), listing.totalPages(), listing.hasNext());
    }
}
