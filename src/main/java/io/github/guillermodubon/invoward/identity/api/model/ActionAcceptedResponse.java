package io.github.guillermodubon.invoward.identity.api.model;

/** Small endpoint-specific generic accepted responses without account-enumeration details. */
public record ActionAcceptedResponse(String message) {

    private static final String VERIFICATION_RESEND_MESSAGE =
            "If the account is eligible, a verification email will be sent.";
    private static final String PASSWORD_RESET_MESSAGE =
            "If an eligible account exists, password reset instructions will be sent.";
    private static final String EMAIL_CHANGE_MESSAGE =
            "If the new address can be used, a confirmation email will be sent.";

    public static ActionAcceptedResponse verificationResend() {
        return new ActionAcceptedResponse(VERIFICATION_RESEND_MESSAGE);
    }

    public static ActionAcceptedResponse passwordReset() {
        return new ActionAcceptedResponse(PASSWORD_RESET_MESSAGE);
    }

    public static ActionAcceptedResponse emailChangeRequest() {
        return new ActionAcceptedResponse(EMAIL_CHANGE_MESSAGE);
    }
}
