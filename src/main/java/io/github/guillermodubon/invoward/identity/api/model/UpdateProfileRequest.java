package io.github.guillermodubon.invoward.identity.api.model;

import jakarta.validation.constraints.NotBlank;

/** Profile update input; display name is the only mutable public profile field in V1. */
public record UpdateProfileRequest(@NotBlank(message = "Display name is required") String displayName) {

    @Override
    public String toString() {
        return "UpdateProfileRequest[displayName=[REDACTED]]";
    }
}
