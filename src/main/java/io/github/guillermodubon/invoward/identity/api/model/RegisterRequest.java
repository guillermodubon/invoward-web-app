package io.github.guillermodubon.invoward.identity.api.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Public registration input. Password is deliberately excluded from its string representation. */
public record RegisterRequest(
        @NotBlank(message = "Display name is required")
        String displayName,
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        String email,
        @NotBlank(message = "Password is required")
        String password) {

    @Override
    public String toString() {
        return "RegisterRequest[displayName=" + displayName + ", email=" + email + ", password=[REDACTED]]";
    }
}
