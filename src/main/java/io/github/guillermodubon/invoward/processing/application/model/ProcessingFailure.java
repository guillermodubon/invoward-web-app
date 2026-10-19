package io.github.guillermodubon.invoward.processing.application.model;

import java.util.Objects;

/** Preclassified, safe failure data; raw provider/exception details are never accepted here. */
public record ProcessingFailure(
        String code,
        String internalMessage,
        String userMessage,
        boolean retryableCandidate) {

    public ProcessingFailure {
        requireSafeCode(code);
        requireSafeMessage(internalMessage, "internalMessage");
        requireSafeMessage(userMessage, "userMessage");
    }

    private static void requireSafeCode(String value) {
        Objects.requireNonNull(value, "code must not be null");
        if (!value.matches("[A-Z][A-Z0-9_]{0,79}")) {
            throw new IllegalArgumentException("code must be a safe uppercase code");
        }
    }

    private static void requireSafeMessage(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank() || value.length() > 500
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(fieldName + " must be a safe non-empty message");
        }
    }

    @Override
    public String toString() {
        return "ProcessingFailure[code=" + code + ", retryableCandidate=" + retryableCandidate + "]";
    }
}
