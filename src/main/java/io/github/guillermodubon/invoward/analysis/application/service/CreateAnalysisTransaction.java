package io.github.guillermodubon.invoward.analysis.application.service;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Keeps Analysis and its initial AnalysisJob in one database transaction. */
@Service
public class CreateAnalysisTransaction {

    private final AnalysisRepository analysisRepository;
    private final AnalysisJobRepository analysisJobRepository;

    public CreateAnalysisTransaction(
            AnalysisRepository analysisRepository,
            AnalysisJobRepository analysisJobRepository) {
        this.analysisRepository = Objects.requireNonNull(
                analysisRepository, "analysisRepository must not be null");
        this.analysisJobRepository = Objects.requireNonNull(
                analysisJobRepository, "analysisJobRepository must not be null");
    }

    @Transactional
    public Analysis create(Analysis analysis, AnalysisJob initialJob) {
        Objects.requireNonNull(analysis, "analysis must not be null");
        Objects.requireNonNull(initialJob, "initialJob must not be null");
        if (!analysis.id().equals(initialJob.analysisId())) {
            throw new IllegalArgumentException("initial job must belong to the created Analysis");
        }

        Analysis persistedAnalysis = analysisRepository.create(analysis);
        analysisJobRepository.create(initialJob);
        return persistedAnalysis;
    }
}
