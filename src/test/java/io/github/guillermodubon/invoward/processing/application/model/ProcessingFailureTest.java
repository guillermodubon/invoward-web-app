package io.github.guillermodubon.invoward.processing.application.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingFailureTest {

    @Test
    void validatesSafeFailureFieldsAndDoesNotRenderMessagesInToString() {
        ProcessingFailure failure = new ProcessingFailure(
                "AI_PROVIDER_UNAVAILABLE", "Provider unavailable.", "Processing is temporarily unavailable.", true);

        assertTrue(failure.toString().contains("AI_PROVIDER_UNAVAILABLE"));
        assertFalse(failure.toString().contains("Provider unavailable."));
        assertFalse(failure.toString().contains("Processing is temporarily unavailable."));
        assertThrows(IllegalArgumentException.class, () -> new ProcessingFailure(
                "raw provider detail", "Safe internal category.", "Safe user message.", false));
        assertThrows(IllegalArgumentException.class, () -> new ProcessingFailure(
                "PROCESSING_FAILED", "unsafe\ninternal detail", "Safe user message.", false));
    }

    @Test
    void enforcesPersistedMessageLengthAndContentBoundaries() {
        String maximumLengthMessage = "a".repeat(500);
        ProcessingFailure maximumLengthFailure = new ProcessingFailure(
                "PROCESSING_FAILED", maximumLengthMessage, maximumLengthMessage, false);
        assertEquals(500, maximumLengthFailure.internalMessage().length());
        assertEquals(500, maximumLengthFailure.userMessage().length());

        assertThrows(IllegalArgumentException.class, () -> new ProcessingFailure(
                "PROCESSING_FAILED", "a".repeat(501), "Safe user message.", false));
        assertThrows(IllegalArgumentException.class, () -> new ProcessingFailure(
                "PROCESSING_FAILED", "Safe internal message.", "a".repeat(501), false));
        assertThrows(IllegalArgumentException.class, () -> new ProcessingFailure(
                "PROCESSING_FAILED", "unsafe\u0000internal detail", "Safe user message.", false));
        assertThrows(IllegalArgumentException.class, () -> new ProcessingFailure(
                "PROCESSING_FAILED", "Safe internal message.", "unsafe\ruser message", false));
    }
}
