package io.github.guillermodubon.invoward.extraction.api.model;

import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReviewUpdate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Full replacement request for the two draft documents under human review. */
public record UpdateExtractionRequest(
        @NotNull @Size(min = 2, max = 2) List<@NotNull @Valid UpdateExtractedDocumentRequest> documents) {

    public UpdateExtractionRequest {
        if (documents != null) {
            documents = List.copyOf(documents);
            if (documents.size() == 2
                    && documents.stream().allMatch(Objects::nonNull)
                    && documents.stream().allMatch(document -> document.documentId() != null)
                    && new HashSet<>(documents.stream().map(UpdateExtractedDocumentRequest::documentId).toList())
                            .size() != 2) {
                throw new IllegalArgumentException("documents must identify two different documents");
            }
        }
    }

    public ExtractionReviewUpdate toApplicationUpdate() {
        return new ExtractionReviewUpdate(documents.stream()
                .map(UpdateExtractedDocumentRequest::toApplicationUpdate)
                .toList());
    }
}
