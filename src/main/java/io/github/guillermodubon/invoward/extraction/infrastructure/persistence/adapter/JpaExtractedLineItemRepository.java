package io.github.guillermodubon.invoward.extraction.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.extraction.application.port.ExtractedLineItemRepository;
import io.github.guillermodubon.invoward.extraction.application.model.PersistedLineItem;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;
import io.github.guillermodubon.invoward.extraction.infrastructure.persistence.entity.ExtractedLineItemJpaEntity;
import io.github.guillermodubon.invoward.extraction.infrastructure.persistence.mapper.ExtractedLineItemPersistenceMapper;
import io.github.guillermodubon.invoward.extraction.infrastructure.persistence.repository.SpringDataExtractedLineItemJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;

@Repository
@Transactional
public class JpaExtractedLineItemRepository implements ExtractedLineItemRepository {

    private static final int MAX_LINES = 500;

    private final SpringDataExtractedLineItemJpaRepository repository;
    private final ExtractedLineItemPersistenceMapper mapper;

    public JpaExtractedLineItemRepository(
            SpringDataExtractedLineItemJpaRepository repository,
            ExtractedLineItemPersistenceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PersistedLineItem> findByExtractedDocumentId(UUID extractedDocumentId) {
        Objects.requireNonNull(extractedDocumentId, "extractedDocumentId must not be null");
        return repository.findByExtractedDocumentIdOrderByLinePosition(extractedDocumentId)
                .stream().map(entity -> new PersistedLineItem(entity.getId(), mapper.toDomain(entity))).toList();
    }

    @Override
    public List<PersistedLineItem> replaceAll(
            UUID extractedDocumentId, List<ExtractedLineItem> lineItems, Instant now) {
        Objects.requireNonNull(extractedDocumentId, "extractedDocumentId must not be null");
        Objects.requireNonNull(lineItems, "lineItems must not be null");
        Objects.requireNonNull(now, "now must not be null");
        List<ExtractedLineItem> snapshot = List.copyOf(lineItems);
        if (snapshot.size() > MAX_LINES
                || IntStream.range(0, snapshot.size()).anyMatch(index -> snapshot.get(index).position() != index)) {
            throw new IllegalArgumentException("line positions must be contiguous and contain at most 500 items");
        }

        repository.deleteByExtractedDocumentId(extractedDocumentId);
        repository.flush();
        List<ExtractedLineItemJpaEntity> entities = snapshot.stream()
                .map(line -> mapper.toEntity(extractedDocumentId, line, now))
                .toList();
        return repository.saveAllAndFlush(entities).stream()
                .map(entity -> new PersistedLineItem(entity.getId(), mapper.toDomain(entity)))
                .toList();
    }
}
