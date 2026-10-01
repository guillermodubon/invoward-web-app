package io.github.guillermodubon.invoward.analysis.application.service;

import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Creates a new Analysis and its initial job through the atomic persistence boundary. */
@Service
public class CreateAnalysisService {

    private final CreateAnalysisTransaction transaction;
    private final Clock clock;

    public CreateAnalysisService(CreateAnalysisTransaction transaction, Clock clock) {
        this.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public Analysis create(AnalysisOwner owner, PriceTolerance priceTolerance) {
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(priceTolerance, "priceTolerance must not be null");

        Instant now = clock.instant();
        UUID analysisId = UUID.randomUUID();
        Analysis analysis = Analysis.create(analysisId, owner, priceTolerance, now);
        AnalysisJob initialJob = AnalysisJob.waitingForUser(UUID.randomUUID(), analysisId, now);

        return transaction.create(analysis, initialJob);
    }
}
