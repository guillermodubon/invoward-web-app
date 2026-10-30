package io.github.guillermodubon.invoward.sharing.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.sharing.application.port.ShareAuthorizedAnalysisReader;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Adapts Analysis persistence for reads authorized by a validated share-link token. */
@Repository
@Transactional(readOnly = true)
public class JpaShareAuthorizedAnalysisReader implements ShareAuthorizedAnalysisReader {

    private final AnalysisRepository analysisRepository;

    public JpaShareAuthorizedAnalysisReader(AnalysisRepository analysisRepository) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository);
    }

    @Override
    public Optional<Analysis> findById(UUID analysisId) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        // The public bearer token is validated by GetPublicSharedReportService before this port is called.
        return analysisRepository.findByIdForProcessing(analysisId);
    }
}
