package io.github.guillermodubon.invoward.analysis.domain;

/** Exactly one ownership strategy for an Analysis. */
public sealed interface AnalysisOwner permits RegisteredUserOwner, GuestSessionOwner {
}
