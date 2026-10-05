package io.github.guillermodubon.invoward.extraction.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.extraction.application.model.CachedExtractionPayload;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionCacheKey;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractionCacheRepository;
import io.github.guillermodubon.invoward.extraction.infrastructure.persistence.mapper.ExtractionCachePersistenceMapper;
import io.github.guillermodubon.invoward.extraction.infrastructure.persistence.repository.SpringDataExtractionCacheJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional
public class JpaExtractionCacheRepository implements ExtractionCacheRepository {

    private static final Duration MAXIMUM_CACHE_TTL = Duration.ofHours(24);

    private final SpringDataExtractionCacheJpaRepository repository;
    private final ExtractionCachePersistenceMapper mapper;

    public JpaExtractionCacheRepository(
            SpringDataExtractionCacheJpaRepository repository,
            ExtractionCachePersistenceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CachedExtractionPayload> findActive(ExtractionCacheKey key, Instant now) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return repository.findBySha256AndExtractorVersionAndModelIdAndSchemaVersionAndExpiresAtAfter(
                        key.sha256(), key.extractorVersion(), key.modelId(), key.schemaVersion(), now)
                .flatMap(entity -> {
                    try {
                        return Optional.of(mapper.toPayload(entity));
                    } catch (IllegalArgumentException invalidPayload) {
                        return Optional.empty();
                    }
                });
    }

    @Override
    public boolean putIfAbsent(
            ExtractionCacheKey key,
            CachedExtractionPayload payload,
            Instant createdAt,
            Instant expiresAt) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Duration ttl = Duration.between(createdAt, expiresAt);
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAXIMUM_CACHE_TTL) > 0) {
            throw new IllegalArgumentException("cache expiry must be after creation and no more than 24 hours later");
        }
        int inserted = repository.insertOrRefreshExpired(
                UUID.randomUUID(), key.sha256(), key.extractorVersion(), key.modelId(),
                key.schemaVersion(), mapper.toJson(payload), expiresAt, createdAt);
        return inserted == 1;
    }
}
