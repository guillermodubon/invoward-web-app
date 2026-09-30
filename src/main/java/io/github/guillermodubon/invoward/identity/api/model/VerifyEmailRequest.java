package io.github.guillermodubon.invoward.identity.api.model;

import jakarta.validation.constraints.NotBlank;

/** Verification token input; its value must never appear in diagnostic output. */
public record VerifyEmailRequest(@NotBlank String token) {

    @Override
    public String toString() {
        return "VerifyEmailRequest[token=[REDACTED]]";
    }
}
