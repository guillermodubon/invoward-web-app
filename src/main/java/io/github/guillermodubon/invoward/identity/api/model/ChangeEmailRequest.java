package io.github.guillermodubon.invoward.identity.api.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Reauthentication and target email for an authenticated email-change request. */
public record ChangeEmailRequest(
        @NotNull(message = "Current password is required") String currentPassword,
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        String newEmail) {

    @Override
    public String toString() {
        return "ChangeEmailRequest[currentPassword=[REDACTED], newEmail=[REDACTED]]";
    }
}
