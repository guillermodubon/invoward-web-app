package io.github.guillermodubon.invoward.identity.api.model;

import jakarta.validation.constraints.NotNull;

/** Credentials are deliberately excluded from this request's string representation. */
public record ChangePasswordRequest(
        @NotNull(message = "Current password is required") String currentPassword,
        @NotNull(message = "New password is required") String newPassword) {

    @Override
    public String toString() {
        return "ChangePasswordRequest[currentPassword=[REDACTED], newPassword=[REDACTED]]";
    }
}
