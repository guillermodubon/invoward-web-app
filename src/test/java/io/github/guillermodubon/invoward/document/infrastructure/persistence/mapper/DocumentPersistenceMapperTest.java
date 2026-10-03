package io.github.guillermodubon.invoward.document.infrastructure.persistence.mapper;

import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.document.infrastructure.persistence.entity.DocumentJpaEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentPersistenceMapperTest {

    @Test
    void mapsEveryPersistedFieldInBothDirections() {
        Instant createdAt = Instant.parse("2026-09-30T12:00:00Z");
        Instant expiresAt = createdAt.plusSeconds(86_400);
        Document document = new Document(
                UUID.randomUUID(), UUID.randomUUID(), DocumentRole.REFERENCE,
                DocumentType.QUOTE, DocumentType.ESTIMATE,
                "accepted-quote.pdf", "application/pdf", 42_000, 3,
                "b".repeat(64), "guest/analysis/document.pdf", expiresAt, createdAt);
        DocumentPersistenceMapper mapper = new DocumentPersistenceMapper();

        DocumentJpaEntity entity = mapper.toEntity(document);

        assertEquals(document.id(), entity.getId());
        assertEquals(document, mapper.toDomain(entity));
    }
}
