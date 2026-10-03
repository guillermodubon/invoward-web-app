package io.github.guillermodubon.invoward.analysis.infrastructure.persistence.mapper;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.entity.AnalysisJobJpaEntity;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Maps the AnalysisJob domain model to and from its JPA representation. */
@Component
public class AnalysisJobPersistenceMapper {

    public AnalysisJobJpaEntity toEntity(AnalysisJob analysisJob) {
        Objects.requireNonNull(analysisJob, "analysisJob must not be null");
        AnalysisJobJpaEntity entity = AnalysisJobJpaEntity.create(analysisJob.id());
        updateEntity(analysisJob, entity);
        return entity;
    }

    public void updateEntity(AnalysisJob analysisJob, AnalysisJobJpaEntity entity) {
        Objects.requireNonNull(analysisJob, "analysisJob must not be null");
        Objects.requireNonNull(entity, "entity must not be null");
        if (!analysisJob.id().equals(entity.getId())) {
            throw new IllegalArgumentException("Analysis job id must match persistence entity id");
        }
        entity.setAnalysisId(analysisJob.analysisId());
        entity.setStatus(analysisJob.status());
        entity.setCurrentStage(analysisJob.currentStage());
        entity.setAttemptCount(analysisJob.attemptCount());
        entity.setRetryable(analysisJob.retryable());
        entity.setLastErrorCode(analysisJob.lastErrorCode());
        entity.setLastErrorMessage(analysisJob.lastErrorMessage());
        entity.setStartedAt(analysisJob.startedAt());
        entity.setCompletedAt(analysisJob.completedAt());
        entity.setCreatedAt(analysisJob.createdAt());
        entity.setUpdatedAt(analysisJob.updatedAt());
    }

    public AnalysisJob toDomain(AnalysisJobJpaEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return new AnalysisJob(
                entity.getId(),
                entity.getAnalysisId(),
                entity.getStatus(),
                entity.getCurrentStage(),
                entity.getAttemptCount(),
                entity.isRetryable(),
                entity.getLastErrorCode(),
                entity.getLastErrorMessage(),
                entity.getStartedAt(),
                entity.getCompletedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
