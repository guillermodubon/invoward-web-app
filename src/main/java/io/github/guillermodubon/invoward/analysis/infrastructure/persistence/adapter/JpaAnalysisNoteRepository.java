package io.github.guillermodubon.invoward.analysis.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisNoteRepository;
import io.github.guillermodubon.invoward.analysis.application.exception.NoteVersionConflictException;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisNote;
import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.entity.AnalysisNoteJpaEntity;
import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.mapper.AnalysisNotePersistenceMapper;
import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.repository.SpringDataAnalysisNoteJpaRepository;
import org.hibernate.exception.ConstraintViolationException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL adapter for private Analysis notes, backed by the existing table. */
@Repository
@Transactional
public class JpaAnalysisNoteRepository implements AnalysisNoteRepository {

    private static final String ANALYSIS_NOTE_UNIQUE_CONSTRAINT = "analysis_notes_analysis_uq";

    private final ObjectProvider<SpringDataAnalysisNoteJpaRepository> repositoryProvider;
    private final AnalysisNotePersistenceMapper mapper;

    public JpaAnalysisNoteRepository(
            ObjectProvider<SpringDataAnalysisNoteJpaRepository> repositoryProvider,
            AnalysisNotePersistenceMapper mapper) {
        this.repositoryProvider = Objects.requireNonNull(repositoryProvider, "repositoryProvider must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AnalysisNote> findByAnalysisId(UUID analysisId) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        return repositoryProvider.getObject().findByAnalysisId(analysisId).map(mapper::toDomain);
    }

    @Override
    public AnalysisNote create(AnalysisNote note) {
        Objects.requireNonNull(note, "note must not be null");
        if (note.version() != 0) {
            throw new IllegalArgumentException("A new Analysis note must start at version zero");
        }
        try {
            return mapper.toDomain(repositoryProvider.getObject().saveAndFlush(mapper.toNewEntity(note)));
        } catch (DataIntegrityViolationException exception) {
            if (isNoteUniquenessViolation(exception)) {
                throw new NoteVersionConflictException();
            }
            throw exception;
        } catch (OptimisticLockingFailureException exception) {
            throw new NoteVersionConflictException();
        }
    }

    @Override
    public AnalysisNote update(AnalysisNote note) {
        Objects.requireNonNull(note, "note must not be null");
        try {
            SpringDataAnalysisNoteJpaRepository repository = repositoryProvider.getObject();
            AnalysisNoteJpaEntity entity = repository.findByAnalysisId(note.analysisId())
                    .orElseThrow(() -> new EntityNotFoundException("Analysis note does not exist"));
            requireCurrentVersion(note, entity);
            if (note.updatedAt().isBefore(entity.getUpdatedAt())) {
                throw new IllegalArgumentException("Analysis note updatedAt must be monotonic");
            }
            if (entity.getNoteText().equals(note.text())) {
                return mapper.toDomain(entity);
            }

            mapper.updateEntity(note, entity);
            repository.flush();
            return mapper.toDomain(entity);
        } catch (OptimisticLockException | OptimisticLockingFailureException | EntityNotFoundException exception) {
            throw new NoteVersionConflictException();
        }
    }

    @Override
    public void delete(UUID analysisId, long expectedVersion) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        try {
            SpringDataAnalysisNoteJpaRepository repository = repositoryProvider.getObject();
            AnalysisNoteJpaEntity entity = repository.findByAnalysisId(analysisId)
                    .orElseThrow(() -> new EntityNotFoundException("Analysis note does not exist"));
            if (entity.getVersion() != expectedVersion) {
                throw new OptimisticLockException("Analysis note was modified concurrently");
            }
            repository.delete(entity);
            repository.flush();
        } catch (OptimisticLockException | OptimisticLockingFailureException | EntityNotFoundException exception) {
            throw new NoteVersionConflictException();
        }
    }

    private void requireCurrentVersion(AnalysisNote note, AnalysisNoteJpaEntity entity) {
        if (entity.getVersion() != note.version()) {
            throw new OptimisticLockException("Analysis note was modified concurrently");
        }
    }

    private static boolean isNoteUniquenessViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraintViolation) {
                return ANALYSIS_NOTE_UNIQUE_CONSTRAINT.equals(constraintViolation.getConstraintName());
            }
        }
        return false;
    }
}
