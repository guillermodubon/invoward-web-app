package io.github.guillermodubon.invoward.extraction.application.port;

import io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence operations for extraction headers and their review version. */
public interface ExtractedDocumentRepository {

    PersistedExtraction create(
            UUID documentId,
            ExtractedDocument extraction,
            String extractorVersion,
            String modelId,
            int schemaVersion,
            Instant now);

    Optional<PersistedExtraction> findByDocumentId(UUID documentId);

    Optional<PersistedExtraction> findByIdAndDocumentId(UUID extractionId, UUID documentId);

    /** Returns empty when the row is missing or its optimistic version is stale. */
    Optional<PersistedExtraction> update(PersistedExtraction extraction, Instant now);

    /** Confirms an extraction header without replacing its persisted line identities or evidence. */
    Optional<PersistedExtraction> confirm(PersistedExtraction extraction, Instant confirmedAt);
}
