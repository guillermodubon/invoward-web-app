package io.github.guillermodubon.invoward.document.application.service;

import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisService;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Lists only metadata after owner-scoped authorization of the parent Analysis. */
public final class ListDocumentsService {

    private static final Comparator<Document> ROLE_ORDER = Comparator.comparingInt(
            document -> document.role() == DocumentRole.REFERENCE ? 0 : 1);

    private final GetAnalysisService analysisService;
    private final DocumentRepository documentRepository;

    public ListDocumentsService(
            GetAnalysisService analysisService,
            DocumentRepository documentRepository) {
        this.analysisService = Objects.requireNonNull(analysisService, "analysisService must not be null");
        this.documentRepository = Objects.requireNonNull(
                documentRepository, "documentRepository must not be null");
    }

    public List<Document> list(UUID analysisId, AnalysisOwner owner) {
        Objects.requireNonNull(analysisId, "analysisId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        analysisService.get(analysisId, owner);
        return documentRepository.findByAnalysisId(analysisId).stream()
                .sorted(ROLE_ORDER)
                .toList();
    }
}
