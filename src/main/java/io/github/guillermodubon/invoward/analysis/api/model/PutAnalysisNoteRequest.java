package io.github.guillermodubon.invoward.analysis.api.model;

/** Full-replacement request for a private Analysis note. */
public record PutAnalysisNoteRequest(String text, Long expectedVersion) {
}
