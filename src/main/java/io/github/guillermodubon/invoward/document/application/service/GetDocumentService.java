package io.github.guillermodubon.invoward.document.application.service;

import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisService;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.document.application.exception.DocumentNotFoundException;
import io.github.guillermodubon.invoward.document.application.model.DocumentDownload;
import io.github.guillermodubon.invoward.document.application.model.PresignedDownload;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.application.port.DocumentStorage;
import io.github.guillermodubon.invoward.document.domain.Document;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Retrieves one owner-authorized document and creates its short-lived signed download. */
public final class GetDocumentService {

    private static final Duration SIGNATURE_EXPIRY_SAFETY_MARGIN = Duration.ofSeconds(1);

    private final GetAnalysisService analysisService;
    private final DocumentRepository documentRepository;
    private final DocumentStorage documentStorage;
    private final Clock clock;
    private final Duration configuredDownloadTtl;

    public GetDocumentService(
            GetAnalysisService analysisService,
            DocumentRepository documentRepository,
            DocumentStorage documentStorage,
            Clock clock,
            Duration configuredDownloadTtl) {
        this.analysisService = Objects.requireNonNull(analysisService, "analysisService must not be null");
        this.documentRepository = Objects.requireNonNull(
                documentRepository, "documentRepository must not be null");
        this.documentStorage = Objects.requireNonNull(documentStorage, "documentStorage must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.configuredDownloadTtl = Objects.requireNonNull(
                configuredDownloadTtl, "configuredDownloadTtl must not be null");
        if (configuredDownloadTtl.isZero() || configuredDownloadTtl.isNegative()) {
            throw new IllegalArgumentException("configuredDownloadTtl must be greater than 0");
        }
    }

    public DocumentDownload get(UUID analysisId, UUID documentId, AnalysisOwner owner) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        analysisService.get(analysisId, owner);
        Document document = documentRepository.findByIdAndAnalysisId(documentId, analysisId)
                .orElseThrow(DocumentNotFoundException::new);

        Instant now = clock.instant();
        Duration ttl = effectiveTtl(document.expiresAt(), now);
        PresignedDownload download = documentStorage.createPresignedDownload(
                document.storageKey(), document.contentType(), document.originalFilename(), ttl);
        if (!download.expiresAt().isAfter(now)
                || (document.expiresAt() != null && download.expiresAt().isAfter(document.expiresAt()))) {
            throw new IllegalStateException("Document storage returned an invalid download expiration");
        }
        return new DocumentDownload(document, download);
    }

    private Duration effectiveTtl(Instant documentExpiresAt, Instant now) {
        if (documentExpiresAt == null) {
            return configuredDownloadTtl;
        }

        Duration remaining = Duration.between(now, documentExpiresAt);
        if (remaining.isZero() || remaining.isNegative()) {
            throw new DocumentNotFoundException();
        }
        if (remaining.compareTo(configuredDownloadTtl) >= 0) {
            return configuredDownloadTtl;
        }

        Duration safeRemaining = remaining.minus(SIGNATURE_EXPIRY_SAFETY_MARGIN);
        if (safeRemaining.isZero() || safeRemaining.isNegative()) {
            throw new DocumentNotFoundException();
        }
        return safeRemaining;
    }
}
