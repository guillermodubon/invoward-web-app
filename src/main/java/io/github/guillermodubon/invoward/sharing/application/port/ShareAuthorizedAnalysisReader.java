package io.github.guillermodubon.invoward.sharing.application.port;

import io.github.guillermodubon.invoward.analysis.domain.Analysis;

import java.util.Optional;
import java.util.UUID;

/** Reads an Analysis after the caller has validated its public share-link capability. */
public interface ShareAuthorizedAnalysisReader {

    Optional<Analysis> findById(UUID analysisId);
}
