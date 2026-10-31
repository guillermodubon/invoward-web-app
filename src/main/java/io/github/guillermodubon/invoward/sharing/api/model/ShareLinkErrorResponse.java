package io.github.guillermodubon.invoward.sharing.api.model;

/** Safe and stable JSON error response for share-link endpoints. */
public record ShareLinkErrorResponse(String code, String message) {
}
