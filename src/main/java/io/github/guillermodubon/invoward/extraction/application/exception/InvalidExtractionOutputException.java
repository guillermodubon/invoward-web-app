package io.github.guillermodubon.invoward.extraction.application.exception;

public final class InvalidExtractionOutputException extends RuntimeException {

    public InvalidExtractionOutputException() {
        super("Extraction output failed validation");
    }
}
