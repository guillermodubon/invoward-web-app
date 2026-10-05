package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.extraction.application.ExtractionVersion;
import io.github.guillermodubon.invoward.extraction.application.exception.InvalidExtractionOutputException;
import io.github.guillermodubon.invoward.extraction.application.model.CachedExtractionPayload;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionCacheKey;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDraft;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractionCacheRepository;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractionCacheServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final String SHA_256 = "a".repeat(64);
    private static final String MODEL = "gemini-test-model";

    private final InMemoryExtractionCacheRepository repository = new InMemoryExtractionCacheRepository();
    private final ExtractionCacheService service = new ExtractionCacheService(
            repository,
            new ProviderOutputValidator(500, 4000),
            Duration.ofHours(24),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void activeEntryIsRevalidatedAndReturnedWithCacheSourceWithoutWriting() {
        ExtractionCacheKey key = currentKey(SHA_256, MODEL);
        repository.seed(key, validPayload(" Cached Vendor ", " usd "), NOW.plusSeconds(60));

        Optional<ExtractedDocument> cached = service.findValid(SHA_256, MODEL, 2);

        assertTrue(cached.isPresent());
        assertEquals(ExtractionSource.CACHE, cached.orElseThrow().extractionSource());
        assertEquals("Cached Vendor", cached.orElseThrow().vendorName());
        assertEquals("USD", cached.orElseThrow().currency());
        assertEquals(0, cached.orElseThrow().lines().getFirst().position());
        assertEquals(0, repository.writeCount);
    }

    @Test
    void absentExpiredAndVersionOrModelMismatchesAreCacheMisses() {
        assertTrue(service.findValid(SHA_256, MODEL, 1).isEmpty());

        repository.seed(currentKey(SHA_256, MODEL), validPayload("Expired", "USD"), NOW);
        assertTrue(service.findValid(SHA_256, MODEL, 1).isEmpty());

        repository.seed(new ExtractionCacheKey(SHA_256, "document-extraction-v0", MODEL, 1),
                validPayload("Old extractor", "USD"), NOW.plusSeconds(60));
        repository.seed(new ExtractionCacheKey(SHA_256, ExtractionVersion.EXTRACTOR_VERSION, MODEL, 2),
                validPayload("Old schema", "USD"), NOW.plusSeconds(60));
        repository.seed(currentKey(SHA_256, "different-model"), validPayload("Other model", "USD"),
                NOW.plusSeconds(60));

        assertTrue(service.findValid(SHA_256, MODEL, 1).isEmpty());
    }

    @Test
    void invalidCachedPayloadIsAMissAndIsNotReturned() {
        ExtractionDraft invalid = new ExtractionDraft(
                "Vendor", "INV-1", "not-a-date", "USD", null, null, null, null, null);
        repository.seed(currentKey(SHA_256, MODEL), new CachedExtractionPayload(invalid), NOW.plusSeconds(60));

        assertTrue(service.findValid(SHA_256, MODEL, 1).isEmpty());
    }

    @Test
    void providerOutputIsValidatedNormalizedAndCachedForConfiguredTtl() {
        ExtractedDocument result = service.cacheProviderOutput(SHA_256, MODEL, validDraft(), 2);

        assertEquals(ExtractionSource.AI, result.extractionSource());
        assertEquals("Acme Supplies", result.vendorName());
        assertEquals("USD", result.currency());
        assertEquals(1, repository.writeCount);

        StoredEntry stored = repository.entries.get(currentKey(SHA_256, MODEL));
        assertEquals(NOW, stored.createdAt());
        assertEquals(NOW.plus(Duration.ofHours(24)), stored.expiresAt());
        assertEquals("Acme Supplies", stored.payload().extraction().vendorName());
        assertEquals("USD", stored.payload().extraction().currency());
        assertEquals("Service fee", stored.payload().extraction().lines().getFirst().description());
    }

    @Test
    void expiredKeyCanBeRefreshedAndBecomesAnActiveCacheEntry() {
        ExtractionCacheKey key = currentKey(SHA_256, MODEL);
        repository.seed(key, validPayload("Old", "USD"), NOW);

        ExtractedDocument result = service.cacheProviderOutput(SHA_256, MODEL, validDraft(), 2);

        assertEquals(ExtractionSource.AI, result.extractionSource());
        assertEquals(1, repository.writeCount);
        assertEquals("Acme Supplies", service.findValid(SHA_256, MODEL, 2).orElseThrow().vendorName());
    }

    @Test
    void concurrentValidWinnerIsRevalidatedAndReturnedAsCacheSource() {
        ExtractionCacheKey key = currentKey(SHA_256, MODEL);
        repository.seed(key, validPayload("Concurrent winner", "USD"), NOW.plusSeconds(60));

        ExtractedDocument result = service.cacheProviderOutput(SHA_256, MODEL, validDraft(), 2);

        assertEquals(ExtractionSource.CACHE, result.extractionSource());
        assertEquals("Concurrent winner", result.vendorName());
        assertEquals(0, repository.writeCount);
    }

    @Test
    void invalidProviderOutputIsRejectedWithoutWritingCache() {
        ExtractionDraft invalid = new ExtractionDraft(
                "Vendor", null, "2026-10-04", "US$", null, null, null, null, null);

        assertFalse(repository.entries.containsKey(currentKey(SHA_256, MODEL)));
        assertThrows(
                InvalidExtractionOutputException.class,
                () -> service.cacheProviderOutput(SHA_256, MODEL, invalid, 1));
        assertEquals(0, repository.writeCount);
    }

    @Test
    void cachePayloadContainsProviderDataOnlyAndNoOwnerOrDocumentIdentifiers() throws Exception {
        String json = new ObjectMapper().writeValueAsString(validPayload("Vendor", "USD"));

        assertTrue(json.contains("vendorName"));
        assertFalse(json.contains("userId"));
        assertFalse(json.contains("guestSessionId"));
        assertFalse(json.contains("analysisId"));
        assertFalse(json.contains("documentId"));
        assertFalse(json.contains("storageKey"));
        assertFalse(json.contains("originalFilename"));
        assertFalse(json.contains("presignedUrl"));
        assertFalse(json.contains("session"));
    }

    private static ExtractionCacheKey currentKey(String sha256, String modelId) {
        return new ExtractionCacheKey(
                sha256, ExtractionVersion.EXTRACTOR_VERSION, modelId, ExtractionVersion.SCHEMA_VERSION);
    }

    private static CachedExtractionPayload validPayload(String vendor, String currency) {
        return new CachedExtractionPayload(new ExtractionDraft(
                vendor, "INV-42", "2026-10-03", currency, null, null, null, null,
                java.util.List.of(new ExtractionDraft.Line(
                        "SVC-1", "Monthly service", null, null, null, null, null, null,
                        1, "Monthly service", null))));
    }

    private static ExtractionDraft validDraft() {
        return new ExtractionDraft(
                " Acme Supplies ", " INV-42 ", "2026-10-03", " usd ", null, null, null, null,
                java.util.List.of(new ExtractionDraft.Line(
                        "SVC-1", " Service fee ", null, null, null, null, null, null,
                        1, "Printed: Service fee", null)));
    }

    private record StoredEntry(CachedExtractionPayload payload, Instant createdAt, Instant expiresAt) {
    }

    private static final class InMemoryExtractionCacheRepository implements ExtractionCacheRepository {
        private final Map<ExtractionCacheKey, StoredEntry> entries = new HashMap<>();
        private int writeCount;

        @Override
        public Optional<CachedExtractionPayload> findActive(ExtractionCacheKey key, Instant now) {
            StoredEntry entry = entries.get(key);
            if (entry == null || !entry.expiresAt().isAfter(now)) {
                return Optional.empty();
            }
            return Optional.of(entry.payload());
        }

        @Override
        public boolean putIfAbsent(
                ExtractionCacheKey key,
                CachedExtractionPayload payload,
                Instant createdAt,
                Instant expiresAt) {
            StoredEntry current = entries.get(key);
            if (current != null && current.expiresAt().isAfter(createdAt)) {
                return false;
            }
            entries.put(key, new StoredEntry(payload, createdAt, expiresAt));
            writeCount++;
            return true;
        }

        private void seed(ExtractionCacheKey key, CachedExtractionPayload payload, Instant expiresAt) {
            entries.put(key, new StoredEntry(payload, NOW.minusSeconds(60), expiresAt));
        }
    }
}
