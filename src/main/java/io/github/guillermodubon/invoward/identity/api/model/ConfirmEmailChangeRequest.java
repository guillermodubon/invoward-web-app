package io.github.guillermodubon.invoward.identity.api.model;

import jakarta.validation.constraints.NotBlank;

/** Raw confirmation token is deliberately excluded from its string representation. */
public record ConfirmEmailChangeRequest(@NotBlank(message = "Token is required") String token) {

    @Override
    public String toString() {
        return "ConfirmEmailChangeRequest[token=[REDACTED]]";
    }
}
