package io.github.guillermodubon.invoward.notification.application.model;

/** Immutable provider-neutral email content accepted by the notification application. */
public record TransactionalEmail(String recipient, String subject, String textBody, String htmlBody) {

    private static final int MAX_RECIPIENT_LENGTH = 320;
    private static final int MAX_SUBJECT_LENGTH = 200;
    private static final int MAX_BODY_LENGTH = 100_000;

    public TransactionalEmail {
        recipient = requireTrimmedHeader(recipient, "recipient", MAX_RECIPIENT_LENGTH);
        subject = requireTrimmedHeader(subject, "subject", MAX_SUBJECT_LENGTH);
        textBody = requireNonblankBody(textBody, "textBody");
        htmlBody = optionalNonblankBody(htmlBody, "htmlBody");
    }

    private static String requireTrimmedHeader(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (containsHeaderInjectionCharacter(value)) {
            throw new IllegalArgumentException(field + " must not contain CR, LF or NUL");
        }

        String trimmedValue = value.strip();
        if (trimmedValue.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds the maximum length of " + maxLength);
        }
        return trimmedValue;
    }

    private static boolean containsHeaderInjectionCharacter(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0;
    }

    private static String requireNonblankBody(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException(field + " exceeds the maximum length of " + MAX_BODY_LENGTH);
        }
        return value;
    }

    private static String optionalNonblankBody(String value, String field) {
        if (value == null) {
            return null;
        }
        return requireNonblankBody(value, field);
    }
}
