package io.github.guillermodubon.invoward.analysis.application.service;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisNoteReadModel;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisNoteRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Reads a private note only after the registered account owns the parent Analysis. */
@Service
@Transactional(readOnly = true)
public class GetAnalysisNoteService {

    private final AnalysisRepository analysisRepository;
    private final AnalysisNoteRepository noteRepository;
    private final Clock clock;

    public GetAnalysisNoteService(
            AnalysisRepository analysisRepository,
            AnalysisNoteRepository noteRepository,
            Clock clock) {
        this.analysisRepository = Objects.requireNonNull(analysisRepository, "analysisRepository must not be null");
        this.noteRepository = Objects.requireNonNull(noteRepository, "noteRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public AnalysisNoteReadModel get(UUID analysisId, UUID registeredUserId) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        RegisteredUserOwner owner = new RegisteredUserOwner(
                Objects.requireNonNull(registeredUserId, "registeredUserId must not be null"));

        Analysis analysis = analysisRepository.findOwnedById(analysisId, owner, clock.instant())
                .filter(owned -> owner.equals(owned.owner()))
                .orElseThrow(AnalysisNotFoundException::new);

        return noteRepository.findByAnalysisId(analysis.id())
                .map(AnalysisNoteReadModel::from)
                .orElseGet(() -> AnalysisNoteReadModel.empty(analysis.id()));
    }
}
