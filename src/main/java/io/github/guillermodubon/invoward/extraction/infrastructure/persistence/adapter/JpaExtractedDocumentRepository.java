package io.github.guillermodubon.invoward.extraction.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedDocumentRepository;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedLineItemRepository;
import io.github.guillermodubon.invoward.extraction.infrastructure.persistence.entity.ExtractedDocumentJpaEntity;
import io.github.guillermodubon.invoward.extraction.infrastructure.persistence.mapper.ExtractedDocumentPersistenceMapper;
import io.github.guillermodubon.invoward.extraction.infrastructure.persistence.repository.SpringDataExtractedDocumentJpaRepository;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional
public class JpaExtractedDocumentRepository implements ExtractedDocumentRepository {

    private final SpringDataExtractedDocumentJpaRepository repository;
    private final ExtractedLineItemRepository lineItemRepository;
    private final ExtractedDocumentPersistenceMapper mapper;

    public JpaExtractedDocumentRepository(
            SpringDataExtractedDocumentJpaRepository repository,
            ExtractedLineItemRepository lineItemRepository,
            ExtractedDocumentPersistenceMapper mapper) {
        this.repository = repository;
        this.lineItemRepository = lineItemRepository;
        this.mapper = mapper;
    }

    @Override
    public PersistedExtraction create(
            UUID documentId,
            io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument extraction,
            String extractorVersion,
            String modelId,
            int schemaVersion,
            Instant now) {
        Objects.requireNonNull(extraction, "extraction must not be null");
        ExtractedDocumentJpaEntity entity = mapper.toNewEntity(
                documentId, extraction, extractorVersion, modelId, schemaVersion, now);
        ExtractedDocumentJpaEntity saved = repository.saveAndFlush(entity);
        return mapper.toModel(saved, lineItemRepository.replaceAll(saved.getId(), extraction.lines(), now));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PersistedExtraction> findByDocumentId(UUID documentId) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        return repository.findByDocumentId(documentId).map(this::toModelWithLines);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PersistedExtraction> findByIdAndDocumentId(UUID extractionId, UUID documentId) {
        Objects.requireNonNull(extractionId, "extractionId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        return repository.findByIdAndDocumentId(extractionId, documentId).map(this::toModelWithLines);
    }

    @Override
    public Optional<PersistedExtraction> update(PersistedExtraction extraction, Instant now) {
        Objects.requireNonNull(extraction, "extraction must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Optional<ExtractedDocumentJpaEntity> found = repository.findByIdAndDocumentId(
                extraction.id(), extraction.documentId());
        if (found.isEmpty() || found.orElseThrow().getVersion() != extraction.version()) {
            return Optional.empty();
        }
        ExtractedDocumentJpaEntity entity = found.orElseThrow();
        mapper.update(extraction.extraction(), now, entity);
        ExtractedDocumentJpaEntity saved = repository.saveAndFlush(entity);
        return Optional.of(mapper.toModel(saved,
                lineItemRepository.replaceAll(saved.getId(), extraction.extraction().lines(), now)));
    }

    @Override
    public Optional<PersistedExtraction> confirm(PersistedExtraction extraction, Instant confirmedAt) {
        Objects.requireNonNull(extraction, "extraction must not be null");
        Objects.requireNonNull(confirmedAt, "confirmedAt must not be null");
        if (extraction.extraction().status() != ExtractionStatus.CONFIRMED
                || !confirmedAt.equals(extraction.extraction().confirmedAt())) {
            throw new IllegalArgumentException("the extraction must be confirmed at the supplied instant");
        }
        Optional<ExtractedDocumentJpaEntity> found = repository.findByIdAndDocumentId(
                extraction.id(), extraction.documentId());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ExtractedDocumentJpaEntity entity = found.orElseThrow();
        if (entity.getVersion() != extraction.version() || entity.getStatus() != ExtractionStatus.DRAFT) {
            return Optional.empty();
        }
        mapper.update(extraction.extraction(), confirmedAt, entity);
        ExtractedDocumentJpaEntity saved = repository.saveAndFlush(entity);
        return Optional.of(mapper.toModel(
                saved, lineItemRepository.findByExtractedDocumentId(saved.getId())));
    }

    private PersistedExtraction toModelWithLines(ExtractedDocumentJpaEntity entity) {
        return mapper.toModel(entity, lineItemRepository.findByExtractedDocumentId(entity.getId()));
    }
}
