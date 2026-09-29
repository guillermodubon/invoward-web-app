package io.github.guillermodubon.invoward.identity.application.model;

import io.github.guillermodubon.invoward.identity.domain.UserStatus;

import java.util.UUID;

/** Safe identity projection available to authenticated API use cases. */
public interface AuthenticatedIdentity {

    UUID userId();

    String displayName();

    String email();

    boolean emailVerified();

    UserStatus status();
}
