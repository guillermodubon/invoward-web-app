package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.reconciliation.application.exception.DiscrepancyConflictException;
import io.github.guillermodubon.invoward.reconciliation.application.exception.DiscrepancyNotFoundException;
import io.github.guillermodubon.invoward.reconciliation.application.model.DiscrepancyResolutionUpdate;
import io.github.guillermodubon.invoward.reconciliation.application.port.DiscrepancyRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Applies a human resolution transition atomically after owner-scoped Analysis authorization. */
@Service
public class UpdateDiscrepancyTransaction {

    private final AnalysisRepository analysisRepository;
    private final DiscrepancyRepository discrepancyRepository;
    private final Clock clock;

    public UpdateDiscrepancyTransaction(
            AnalysisRepository analysisRepository,
            DiscrepancyRepository discrepancyRepository,
            Clock clock) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository);
        this.discrepancyRepository = Objects.requireNonNull(discrepancyRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public Discrepancy update(
            UUID analysisId,
            AnalysisOwner owner,
            UUID discrepancyId,
            DiscrepancyResolutionUpdate update) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(discrepancyId, "discrepancyId must not be null");
        Objects.requireNonNull(update, "update must not be null");

        Instant now = clock.instant();
        var analysis = analysisRepository.findOwnedByIdForUpdate(analysisId, owner, now)
                .orElseThrow(AnalysisNotFoundException::new);
        Discrepancy current = discrepancyRepository
                .findByIdAndAnalysisIdForUpdate(discrepancyId, analysis.id())
                .orElseThrow(DiscrepancyNotFoundException::new);
        if (current.version() != update.expectedVersion()) {
            throw new DiscrepancyConflictException();
        }

        Discrepancy transitioned;
        try {
            transitioned = current.transitionResolution(
                    update.targetStatus(), update.reason(), update.note(), now);
        } catch (IllegalStateException invalidTransition) {
            throw new DiscrepancyConflictException();
        }
        return discrepancyRepository.update(transitioned).orElseThrow(DiscrepancyConflictException::new);
    }
}
