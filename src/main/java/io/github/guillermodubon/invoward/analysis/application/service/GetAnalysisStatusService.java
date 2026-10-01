package io.github.guillermodubon.invoward.analysis.application.service;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisStatusSnapshot;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** Retrieves the safe status projection after owner-scoped Analysis authorization. */
@Service
@Transactional(readOnly = true)
public class GetAnalysisStatusService {

    private final GetAnalysisService getAnalysisService;
    private final AnalysisJobRepository analysisJobRepository;

    public GetAnalysisStatusService(
            GetAnalysisService getAnalysisService,
            AnalysisJobRepository analysisJobRepository) {
        this.getAnalysisService = Objects.requireNonNull(
                getAnalysisService, "getAnalysisService must not be null");
        this.analysisJobRepository = Objects.requireNonNull(
                analysisJobRepository, "analysisJobRepository must not be null");
    }

    public AnalysisStatusSnapshot getStatus(UUID analysisId, AnalysisOwner owner) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        Analysis analysis = getAnalysisService.get(analysisId, owner);
        AnalysisJob job = analysisJobRepository.findByAnalysisId(analysis.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Persisted Analysis is missing its required AnalysisJob."));

        return new AnalysisStatusSnapshot(
                analysis.id(),
                analysis.status(),
                job.status(),
                job.currentStage(),
                analysis.retryable(),
                analysis.failureCode(),
                analysis.failureUserMessage(),
                analysis.updatedAt());
    }
}
