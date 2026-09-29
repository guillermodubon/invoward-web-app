package io.github.guillermodubon.invoward.identity.api.model;

/** Enumeration-resistant response shared by new and already-registered addresses. */
public record RegistrationAcceptedResponse(String message) {

    public static final String GENERIC_MESSAGE =
            "If the account can be created, check the email address for the next step.";

    public static RegistrationAcceptedResponse generic() {
        return new RegistrationAcceptedResponse(GENERIC_MESSAGE);
    }
}
