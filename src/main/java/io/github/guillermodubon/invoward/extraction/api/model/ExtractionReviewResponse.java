package io.github.guillermodubon.invoward.extraction.api.model;

import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReview;

public record ExtractionReviewResponse(
        ExtractedDocumentResponse reference,
        ExtractedDocumentResponse invoice) {

    public static ExtractionReviewResponse from(ExtractionReview review) {
        return new ExtractionReviewResponse(
                ExtractedDocumentResponse.from(review.reference()),
                ExtractedDocumentResponse.from(review.invoice()));
    }
}
