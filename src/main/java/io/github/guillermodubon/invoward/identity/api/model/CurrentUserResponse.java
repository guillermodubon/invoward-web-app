package io.github.guillermodubon.invoward.identity.api.model;

import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;

import java.util.UUID;

/** Safe user projection shared by successful login and the current-user endpoint. */
public record CurrentUserResponse(
        UUID id,
        String displayName,
        String email,
        boolean emailVerified,
        String status) {

    public static CurrentUserResponse from(AuthenticatedIdentity identity) {
        return new CurrentUserResponse(
                identity.userId(),
                identity.displayName(),
                identity.email(),
                identity.emailVerified(),
                identity.status().name());
    }
}
