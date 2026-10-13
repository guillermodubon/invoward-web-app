package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.reconciliation.application.exception.DiscrepancyNotFoundException;
import io.github.guillermodubon.invoward.reconciliation.application.port.DiscrepancyEvidenceRepository;
import io.github.guillermodubon.invoward.reconciliation.application.port.DiscrepancyRepository;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyEvidence;
import io.github.guillermodubon.invoward.reconciliation.domain.EvidenceSide;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Reads evidence only after the parent Analysis and discrepancy have been authorized. */
@Service
@Transactional(readOnly = true)
public class GetDiscrepancyEvidenceService {

    private static final Comparator<DiscrepancyEvidence> EVIDENCE_ORDER =
            Comparator.comparingInt((DiscrepancyEvidence evidence) -> evidence.side() == EvidenceSide.REFERENCE ? 0 : 1)
                    .thenComparingInt(DiscrepancyEvidence::pageNumber)
                    .thenComparing(DiscrepancyEvidence::id);

    private final AnalysisRepository analysisRepository;
    private final DiscrepancyRepository discrepancyRepository;
    private final DiscrepancyEvidenceRepository evidenceRepository;
    private final Clock clock;

    public GetDiscrepancyEvidenceService(
            AnalysisRepository analysisRepository,
            DiscrepancyRepository discrepancyRepository,
            DiscrepancyEvidenceRepository evidenceRepository,
            Clock clock) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository);
        this.discrepancyRepository = Objects.requireNonNull(discrepancyRepository);
        this.evidenceRepository = Objects.requireNonNull(evidenceRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    public List<DiscrepancyEvidence> get(UUID analysisId, AnalysisOwner owner, UUID discrepancyId) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(discrepancyId, "discrepancyId must not be null");

        var analysis = analysisRepository.findOwnedById(analysisId, owner, clock.instant())
                .orElseThrow(AnalysisNotFoundException::new);
        discrepancyRepository.findByIdAndAnalysisId(discrepancyId, analysis.id())
                .orElseThrow(DiscrepancyNotFoundException::new);

        return evidenceRepository.findByDiscrepancyId(discrepancyId).stream()
                .sorted(EVIDENCE_ORDER)
                .toList();
    }
}
