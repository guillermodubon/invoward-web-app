package io.github.guillermodubon.invoward.extraction.infrastructure.persistence.repository;

import io.github.guillermodubon.invoward.extraction.infrastructure.persistence.entity.ExtractionCacheEntryJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataExtractionCacheJpaRepository
        extends JpaRepository<ExtractionCacheEntryJpaEntity, UUID> {

    Optional<ExtractionCacheEntryJpaEntity> findBySha256AndExtractorVersionAndModelIdAndSchemaVersionAndExpiresAtAfter(
            String sha256, String extractorVersion, String modelId, int schemaVersion, Instant now);

    @Modifying
    @Query(value = """
            INSERT INTO invoward.extraction_cache_entries
                (id, sha256, extractor_version, model_id, schema_version, payload, expires_at, created_at)
            VALUES (:id, :sha256, :extractorVersion, :modelId, :schemaVersion,
                    CAST(:payload AS jsonb), :expiresAt, :createdAt)
            ON CONFLICT (sha256, extractor_version, model_id, schema_version) DO UPDATE
                SET payload = EXCLUDED.payload,
                    expires_at = EXCLUDED.expires_at,
                    created_at = EXCLUDED.created_at
                WHERE invoward.extraction_cache_entries.expires_at <= EXCLUDED.created_at
            """, nativeQuery = true)
    int insertOrRefreshExpired(
            @Param("id") UUID id,
            @Param("sha256") String sha256,
            @Param("extractorVersion") String extractorVersion,
            @Param("modelId") String modelId,
            @Param("schemaVersion") int schemaVersion,
            @Param("payload") String payload,
            @Param("expiresAt") Instant expiresAt,
            @Param("createdAt") Instant createdAt);
}
