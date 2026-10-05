package io.github.guillermodubon.invoward.extraction.application.model;

import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;

import java.util.Objects;

/** Provider-ready result; persistence of newly extracted drafts belongs to the next workflow step. */
public sealed interface ExtractionPreparation
        permits ExtractionPreparation.Ready, ExtractionPreparation.ExistingDraft {

    record Ready(
            ExtractionDocumentPair documents,
            ExtractedDocument referenceExtraction,
            ExtractedDocument invoiceExtraction,
            String modelId) implements ExtractionPreparation {

        public Ready {
            Objects.requireNonNull(documents, "documents must not be null");
            Objects.requireNonNull(referenceExtraction, "referenceExtraction must not be null");
            Objects.requireNonNull(invoiceExtraction, "invoiceExtraction must not be null");
            Objects.requireNonNull(modelId, "modelId must not be null");
        }

        @Override
        public String toString() {
            return "Ready[referenceRole=REFERENCE, invoiceRole=INVOICE]";
        }
    }

    record ExistingDraft(ExtractionReview review) implements ExtractionPreparation {

        public ExistingDraft {
            Objects.requireNonNull(review, "review must not be null");
        }

        @Override
        public String toString() {
            return "ExistingDraft[present=true]";
        }
    }
}
