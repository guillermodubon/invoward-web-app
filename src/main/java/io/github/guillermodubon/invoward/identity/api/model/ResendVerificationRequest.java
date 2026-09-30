package io.github.guillermodubon.invoward.identity.api.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Public request to resend an account-verification message. */
public record ResendVerificationRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        String email) {

    @Override
    public String toString() {
        return "ResendVerificationRequest[email=[REDACTED]]";
    }
}
