package io.github.guillermodubon.invoward.extraction.api.model;

import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReview;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

public record ExtractedDocumentResponse(
        UUID extractedDocumentId,
        UUID documentId,
        DocumentRole role,
        DocumentType confirmedType,
        ExtractionStatus status,
        long version,
        String vendorName,
        String documentNumber,
        LocalDate documentDate,
        String currency,
        BigDecimal subtotal,
        BigDecimal discountTotal,
        BigDecimal taxTotal,
        BigDecimal total,
        List<ExtractedLineItemResponse> lines) {

    public ExtractedDocumentResponse {
        lines = List.copyOf(lines);
    }

    public static ExtractedDocumentResponse from(ExtractionReview.ReviewedDocument review) {
        List<ExtractedLineItem> sourceLines = review.extraction().lines();
        List<ExtractedLineItemResponse> lines = IntStream.range(0, sourceLines.size())
                .mapToObj(index -> ExtractedLineItemResponse.from(review.lineItemIds().get(index), sourceLines.get(index)))
                .toList();
        return new ExtractedDocumentResponse(
                review.extractedDocumentId(), review.documentId(), review.role(), review.confirmedType(),
                review.extraction().status(), review.version(), review.extraction().vendorName(),
                review.extraction().documentNumber(), review.extraction().documentDate(),
                review.extraction().currency(), review.extraction().subtotal(),
                review.extraction().discountTotal(), review.extraction().taxTotal(),
                review.extraction().total(), lines);
    }
}
