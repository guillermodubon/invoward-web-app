package io.github.guillermodubon.invoward.sharing.application.exception;

/** Safe, uniform failure for every unavailable public share capability. */
public final class PublicSharedReportNotFoundException extends RuntimeException {

    public PublicSharedReportNotFoundException() {
        super("Shared report was not found.");
    }
}
