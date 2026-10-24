package io.github.guillermodubon.invoward.analysis.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisReviewNotAllowedException;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisReviewStatusResult;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** Changes review status for a completed Analysis owned by the authenticated account. */
@Service
public class ChangeAnalysisReviewStatusService {

    private final AnalysisRepository analysisRepository;
    private final Clock clock;

    public ChangeAnalysisReviewStatusService(AnalysisRepository analysisRepository, Clock clock) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository, "analysisRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public AnalysisReviewStatusResult change(
            UUID analysisId,
            UUID registeredUserId,
            AnalysisReviewStatus requestedStatus) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        RegisteredUserOwner owner = new RegisteredUserOwner(
                Objects.requireNonNull(registeredUserId, "registeredUserId must not be null"));
        Objects.requireNonNull(requestedStatus, "requestedStatus must not be null");

        // PostgreSQL TIMESTAMPTZ stores microseconds; use the same precision in the response and aggregate.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Analysis current = analysisRepository.findOwnedByIdForUpdate(analysisId, owner, now)
                .filter(analysis -> owner.equals(analysis.owner()))
                .orElseThrow(AnalysisNotFoundException::new);

        if (current.status() != AnalysisStatus.COMPLETED) {
            throw new AnalysisReviewNotAllowedException();
        }

        Analysis changed = current.changeReviewStatus(requestedStatus, now);
        if (changed == current) {
            return AnalysisReviewStatusResult.from(current);
        }

        return AnalysisReviewStatusResult.from(analysisRepository.update(changed));
    }
}
