package io.github.guillermodubon.invoward.extraction.infrastructure.persistence.adapter;

import io.github.guillermodubon.invoward.extraction.application.ExtractionVersion;
import io.github.guillermodubon.invoward.extraction.application.model.CachedExtractionPayload;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionCacheKey;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDraft;
import io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractionCacheRepository;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedDocumentRepository;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedLineItemRepository;
import io.github.guillermodubon.invoward.extraction.domain.BoundingBox;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionSource;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class ExtractionPersistenceIT {

    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");

    @Autowired
    private ExtractedDocumentRepository extractedDocumentRepository;

    @Autowired
    private ExtractedLineItemRepository extractedLineItemRepository;

    @Autowired
    private ExtractionCacheRepository extractionCacheRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void persistsDraftAndConfirmedExtractionValuesAndOptimisticallyIncrementsVersion() {
        UUID draftDocumentId = insertDocument();
        PersistedExtraction draft = extractedDocumentRepository.create(
                draftDocumentId, newExtraction(ExtractionSource.AI, List.of()),
                ExtractionVersion.EXTRACTOR_VERSION, "test-model", ExtractionVersion.SCHEMA_VERSION, NOW);

        assertEquals(ExtractionStatus.DRAFT, draft.extraction().status());
        assertNull(draft.extraction().confirmedAt());
        assertEquals(0, draft.version());
        assertEquals("Acme Supplies", draft.extraction().vendorName());
        assertTrue(extractedDocumentRepository.findByDocumentId(draftDocumentId).isPresent());

        PersistedExtraction updated = extractedDocumentRepository.update(
                draft.withExtraction(draft.extraction().confirm(NOW.plusSeconds(1))), NOW.plusSeconds(1))
                .orElseThrow();

        assertEquals(ExtractionStatus.CONFIRMED, updated.extraction().status());
        assertEquals(NOW.plusSeconds(1), updated.extraction().confirmedAt());
        assertEquals(1, updated.version());
        assertTrue(extractedDocumentRepository.update(
                draft.withExtraction(draft.extraction().confirm(NOW.plusSeconds(2))), NOW.plusSeconds(2)).isEmpty());

        UUID cachedDocumentId = insertDocument();
        PersistedExtraction cached = extractedDocumentRepository.create(
                cachedDocumentId, newExtraction(ExtractionSource.CACHE, List.of()),
                ExtractionVersion.EXTRACTOR_VERSION, "test-model", ExtractionVersion.SCHEMA_VERSION, NOW);
        assertEquals(ExtractionSource.CACHE, cached.extraction().extractionSource());
    }

    @Test
    void persistsOrderedLinesAndTypedBoundingBoxEvidence() {
        UUID documentId = insertDocument();
        List<ExtractedLineItem> expectedLines = List.of(
                line(0, "First", new BoundingBox(0.1, 0.2, 0.3, 0.4)),
                line(1, "Second", null));

        PersistedExtraction saved = extractedDocumentRepository.create(
                documentId, newExtraction(ExtractionSource.AI, expectedLines),
                ExtractionVersion.EXTRACTOR_VERSION, "test-model", ExtractionVersion.SCHEMA_VERSION, NOW);

        List<io.github.guillermodubon.invoward.extraction.application.model.PersistedLineItem> persisted = extractedLineItemRepository
                .findByExtractedDocumentId(saved.id());
        assertEquals(expectedLines, persisted.stream()
                .map(io.github.guillermodubon.invoward.extraction.application.model.PersistedLineItem::line)
                .toList());
        assertEquals(saved.lineItemIds(), persisted.stream()
                .map(io.github.guillermodubon.invoward.extraction.application.model.PersistedLineItem::id)
                .toList());
        assertEquals("first", persisted.getFirst().line().normalizedDescription());
        assertEquals(new BoundingBox(0.1, 0.2, 0.3, 0.4), persisted.getFirst().line().boundingBox());
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.extracted_line_items WHERE extracted_document_id = ?",
                Integer.class, saved.id()));
    }

    @Test
    void enforcesOneExtractionPerDocument() {
        UUID documentId = insertDocument();
        extractedDocumentRepository.create(
                documentId, newExtraction(ExtractionSource.AI, List.of()),
                ExtractionVersion.EXTRACTOR_VERSION, "test-model", ExtractionVersion.SCHEMA_VERSION, NOW);

        assertThrows(DataIntegrityViolationException.class, () -> extractedDocumentRepository.create(
                documentId, newExtraction(ExtractionSource.AI, List.of()),
                ExtractionVersion.EXTRACTOR_VERSION, "test-model", ExtractionVersion.SCHEMA_VERSION,
                NOW.plusSeconds(1)));
    }

    @Test
    void findsOnlyActiveCacheEntriesAndIgnoresConcurrentDuplicateInsert() {
        CachedExtractionPayload payload = new CachedExtractionPayload(new ExtractionDraft(
                "Cache Vendor", "CACHE-1", "2026-09-30", "USD", new BigDecimal("12.5000"),
                null, null, new BigDecimal("12.5000"), List.of()));
        ExtractionCacheKey activeKey = new ExtractionCacheKey(
                "a".repeat(64), ExtractionVersion.EXTRACTOR_VERSION, "test-model", 1);
        Instant expiresAt = NOW.plusSeconds(3600);

        assertTrue(extractionCacheRepository.putIfAbsent(activeKey, payload, NOW, expiresAt));
        assertFalse(extractionCacheRepository.putIfAbsent(activeKey, payload, NOW, expiresAt));
        CachedExtractionPayload loaded = extractionCacheRepository
                .findActive(activeKey, NOW.plusSeconds(10)).orElseThrow();
        assertEquals(payload.extraction().vendorName(), loaded.extraction().vendorName());
        assertEquals(payload.extraction().documentNumber(), loaded.extraction().documentNumber());
        assertEquals(payload.extraction().documentDate(), loaded.extraction().documentDate());
        assertEquals(payload.extraction().currency(), loaded.extraction().currency());
        assertEquals(0, payload.extraction().subtotal().compareTo(loaded.extraction().subtotal()));
        assertEquals(0, payload.extraction().total().compareTo(loaded.extraction().total()));

        ExtractionCacheKey expiredKey = new ExtractionCacheKey(
                "b".repeat(64), ExtractionVersion.EXTRACTOR_VERSION, "test-model", 1);
        assertTrue(extractionCacheRepository.putIfAbsent(
                expiredKey, payload, NOW.minusSeconds(7200), NOW.minusSeconds(3600)));
        assertTrue(extractionCacheRepository.findActive(expiredKey, NOW).isEmpty());
        assertTrue(extractionCacheRepository.putIfAbsent(expiredKey, payload, NOW, NOW.plusSeconds(3600)));
        assertTrue(extractionCacheRepository.findActive(expiredKey, NOW.plusSeconds(1)).isPresent());

        String payloadJson = jdbcTemplate.queryForObject(
                "SELECT payload::text FROM invoward.extraction_cache_entries WHERE sha256 = ?",
                String.class, activeKey.sha256());
        assertTrue(payloadJson.contains("Cache Vendor"));
        assertFalse(payloadJson.contains("analysisId"));
        assertFalse(payloadJson.contains("documentId"));
        assertFalse(payloadJson.contains("storageKey"));
    }

    @Test
    void malformedJsonbCachePayloadIsTreatedAsAMiss() {
        CachedExtractionPayload payload = new CachedExtractionPayload(new ExtractionDraft(
                "Cache Vendor", "CACHE-2", "2026-09-30", "USD", null, null, null, null, List.of()));
        ExtractionCacheKey key = new ExtractionCacheKey(
                "c".repeat(64), ExtractionVersion.EXTRACTOR_VERSION, "test-model", 1);
        assertTrue(extractionCacheRepository.putIfAbsent(key, payload, NOW, NOW.plusSeconds(3600)));

        jdbcTemplate.update(
                "UPDATE invoward.extraction_cache_entries SET payload = CAST(? AS jsonb) WHERE sha256 = ?",
                "{\"unexpected\":true}", key.sha256());

        assertTrue(extractionCacheRepository.findActive(key, NOW.plusSeconds(1)).isEmpty());
    }

    @Test
    void deletingDocumentCascadesThroughExtractionAndItsLines() {
        UUID documentId = insertDocument();
        PersistedExtraction saved = extractedDocumentRepository.create(
                documentId, newExtraction(ExtractionSource.AI, List.of(line(0, "Cascade", null))),
                ExtractionVersion.EXTRACTOR_VERSION, "test-model", ExtractionVersion.SCHEMA_VERSION, NOW);

        assertEquals(1, jdbcTemplate.update("DELETE FROM invoward.documents WHERE id = ?", documentId));

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.extracted_documents WHERE id = ?", Integer.class, saved.id()));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.extracted_line_items WHERE extracted_document_id = ?",
                Integer.class, saved.id()));
    }

    private UUID insertDocument() {
        UUID analysisId = jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUserAnalysis);
        return jdbcTemplate.execute((ConnectionCallback<UUID>) connection -> DatabaseFixtures.insertDocument(
                connection, analysisId, "REFERENCE", "QUOTE", "extraction-test/" + UUID.randomUUID()));
    }

    private static ExtractedDocument newExtraction(ExtractionSource source, List<ExtractedLineItem> lines) {
        return ExtractedDocument.draft(source, "Acme Supplies", "INV-2026-01", LocalDate.parse("2026-09-30"),
                "usd", new BigDecimal("100.0000"), new BigDecimal("0.0000"),
                new BigDecimal("5.0000"), new BigDecimal("105.0000"), lines);
    }

    private static ExtractedLineItem line(int position, String description, BoundingBox boundingBox) {
        return new ExtractedLineItem(position, "ITEM-" + position, description,
                new BigDecimal("2.0000"), "each", new BigDecimal("10.0000"),
                BigDecimal.ZERO.setScale(4), BigDecimal.ZERO.setScale(4),
                new BigDecimal("20.0000"), 1, "Printed: " + description, boundingBox);
    }
}
