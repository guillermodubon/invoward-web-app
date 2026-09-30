package io.github.guillermodubon.invoward.identity.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Reset input that deliberately excludes token and password from its string form. */
public record ResetPasswordRequest(
        @NotBlank(message = "Token is required") String token,
        @NotNull(message = "Password is required") String newPassword) {

    @Override
    public String toString() {
        return "ResetPasswordRequest[token=[REDACTED], newPassword=[REDACTED]]";
    }
}
