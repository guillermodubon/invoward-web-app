package io.github.guillermodubon.invoward.document.application.port;

import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence operations required by document use cases. */
public interface DocumentRepository {

    Document create(Document document);

    List<Document> findByAnalysisId(UUID analysisId);

    Optional<Document> findByIdAndAnalysisId(UUID documentId, UUID analysisId);

    boolean existsByAnalysisIdAndRole(UUID analysisId, DocumentRole role);

    long sumSizeBytesByAnalysisId(UUID analysisId);
}
