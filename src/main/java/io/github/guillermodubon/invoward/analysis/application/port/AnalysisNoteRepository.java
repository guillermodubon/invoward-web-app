package io.github.guillermodubon.invoward.analysis.application.port;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisNote;

import java.util.Optional;
import java.util.UUID;

/** Persistence port for the one private note associated with an Analysis. */
public interface AnalysisNoteRepository {

    Optional<AnalysisNote> findByAnalysisId(UUID analysisId);

    /** Inserts a new note; the database's unique analysis constraint remains authoritative. */
    AnalysisNote create(AnalysisNote note);

    /** Replaces note text if the supplied persisted version is still current. */
    AnalysisNote update(AnalysisNote note);

    /** Deletes only the expected note version. */
    void delete(UUID analysisId, long expectedVersion);
}
