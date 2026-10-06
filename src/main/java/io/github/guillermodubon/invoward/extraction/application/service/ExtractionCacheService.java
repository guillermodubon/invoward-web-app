package io.github.guillermodubon.invoward.extraction.application.service;

import io.github.guillermodubon.invoward.extraction.application.ExtractionVersion;
import io.github.guillermodubon.invoward.extraction.application.exception.InvalidExtractionOutputException;
import io.github.guillermodubon.invoward.extraction.application.model.CachedExtractionPayload;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionCacheKey;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDraft;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractionCacheRepository;
import io.github.guillermodubon.invoward.extraction.domain.BoundingBox;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Resolves validated provider output from the cross-analysis extraction cache. */
public final class ExtractionCacheService {

    private final ExtractionCacheRepository repository;
    private final ProviderOutputValidator validator;
    private final Duration cacheTtl;
    private final Clock clock;

    public ExtractionCacheService(
            ExtractionCacheRepository repository,
            ProviderOutputValidator validator,
            Duration cacheTtl,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.cacheTtl = requireCacheTtl(cacheTtl);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Returns a revalidated cache hit with source CACHE, or empty for every cache miss. */
    public Optional<ExtractedDocument> findValid(
            String sha256,
            String modelId,
            int sourcePageCount) {
        Instant now = clock.instant();
        return findValid(key(sha256, modelId), sourcePageCount, now);
    }

    /** Validates provider output, stores only provider data, and resolves a concurrent cache winner. */
    public ExtractedDocument cacheProviderOutput(
            String sha256,
            String modelId,
            ExtractionDraft providerOutput,
            int sourcePageCount) {
        ExtractionCacheKey key = key(sha256, modelId);
        ExtractedDocument validatedOutput = validator.validate(providerOutput, sourcePageCount, ExtractionSource.AI);
        CachedExtractionPayload payload = new CachedExtractionPayload(normalizedDraft(validatedOutput));
        Instant now = clock.instant();

        boolean stored = repository.putIfAbsent(key, payload, now, now.plus(cacheTtl));
        if (stored) {
            return validatedOutput;
        }
        return findValid(key, sourcePageCount, now).orElse(validatedOutput);
    }

    private Optional<ExtractedDocument> findValid(
            ExtractionCacheKey key,
            int sourcePageCount,
            Instant now) {
        return repository.findActive(key, now)
                .flatMap(payload -> validateCachePayload(payload, sourcePageCount));
    }

    private Optional<ExtractedDocument> validateCachePayload(CachedExtractionPayload payload, int sourcePageCount) {
        try {
            return Optional.of(validator.validate(payload.extraction(), sourcePageCount, ExtractionSource.CACHE));
        } catch (InvalidExtractionOutputException invalidPayload) {
            return Optional.empty();
        }
    }

    private static ExtractionCacheKey key(String sha256, String modelId) {
        return new ExtractionCacheKey(
                sha256,
                ExtractionVersion.EXTRACTOR_VERSION,
                modelId,
                ExtractionVersion.SCHEMA_VERSION);
    }

    private static ExtractionDraft normalizedDraft(ExtractedDocument extraction) {
        List<ExtractionDraft.Line> lines = extraction.lines().stream()
                .map(ExtractionCacheService::normalizedLine)
                .toList();
        return new ExtractionDraft(
                extraction.vendorName(),
                extraction.documentNumber(),
                extraction.documentDate() == null ? null : extraction.documentDate().toString(),
                extraction.currency(),
                extraction.subtotal(),
                extraction.discountTotal(),
                extraction.taxTotal(),
                extraction.total(),
                lines);
    }

    private static ExtractionDraft.Line normalizedLine(ExtractedLineItem line) {
        BoundingBox box = line.boundingBox();
        ExtractionDraft.RawBoundingBox rawBox = box == null
                ? null
                : new ExtractionDraft.RawBoundingBox(box.xMin(), box.yMin(), box.xMax(), box.yMax());
        return new ExtractionDraft.Line(
                line.itemCode(), line.description(), line.quantity(), line.unit(), line.unitPrice(),
                line.discountAmount(), line.taxAmount(), line.lineTotal(), line.pageNumber(),
                line.sourceText(), rawBox);
    }

    private static Duration requireCacheTtl(Duration cacheTtl) {
        Objects.requireNonNull(cacheTtl, "cacheTtl must not be null");
        if (cacheTtl.isZero() || cacheTtl.isNegative() || cacheTtl.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("cacheTtl must be greater than 0 and at most 24 hours");
        }
        return cacheTtl;
    }
}
