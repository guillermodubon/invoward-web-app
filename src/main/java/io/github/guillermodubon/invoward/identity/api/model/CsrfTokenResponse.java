package io.github.guillermodubon.invoward.identity.api.model;

/** CSRF data required by clients for subsequent protected requests. */
public record CsrfTokenResponse(String token, String headerName) {
}
