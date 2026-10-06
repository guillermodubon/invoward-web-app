package io.github.guillermodubon.invoward.extraction.application.port;

import io.github.guillermodubon.invoward.extraction.application.model.CachedExtractionPayload;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionCacheKey;

import java.time.Instant;
import java.util.Optional;

/** PostgreSQL cache for provider-normalized output only. */
public interface ExtractionCacheRepository {

    Optional<CachedExtractionPayload> findActive(ExtractionCacheKey key, Instant now);

    /** Inserts or refreshes an expired key only; returns false when an active cache entry wins. */
    boolean putIfAbsent(
            ExtractionCacheKey key,
            CachedExtractionPayload payload,
            Instant createdAt,
            Instant expiresAt);
}
