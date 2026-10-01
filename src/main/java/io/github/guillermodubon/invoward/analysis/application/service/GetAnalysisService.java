package io.github.guillermodubon.invoward.analysis.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Retrieves an Analysis only through an owner-scoped persistence query. */
@Service
@Transactional(readOnly = true)
public class GetAnalysisService {

    private final AnalysisRepository analysisRepository;
    private final Clock clock;

    public GetAnalysisService(AnalysisRepository analysisRepository, Clock clock) {
        this.analysisRepository = Objects.requireNonNull(
                analysisRepository, "analysisRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public Analysis get(UUID analysisId, AnalysisOwner owner) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        return analysisRepository.findOwnedById(analysisId, owner, clock.instant())
                .orElseThrow(AnalysisNotFoundException::new);
    }
}
